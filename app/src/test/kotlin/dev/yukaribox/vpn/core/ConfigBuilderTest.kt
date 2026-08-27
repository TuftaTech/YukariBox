package dev.yukaribox.vpn.core

import dev.yukaribox.vpn.data.RouteRule
import dev.yukaribox.vpn.data.RuleOutbound
import dev.yukaribox.vpn.proxy.ProxyLinkParser
import dev.yukaribox.vpn.proxy.ProxyNode
import dev.yukaribox.vpn.proxy.ProxyType
import dev.yukaribox.vpn.proxy.TlsSettings
import dev.yukaribox.vpn.proxy.TransportSettings
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigBuilderTest {

    private fun JsonObject.str(key: String) = this[key]!!.jsonPrimitive.content
    private fun JsonObject.obj(key: String) = this[key]!!.jsonObject

    // ---- outbound: VLESS Reality + Vision ----
    @Test
    fun vlessRealityOutbound() {
        val node = ProxyLinkParser.parse(
            "vless://uuid-1@e.com:443?encryption=none&security=reality&sni=www.ms.com" +
                "&fp=chrome&pbk=PUB&sid=abcd&flow=xtls-rprx-vision&type=tcp#R"
        )
        val ob = ConfigBuilder.buildOutbound(node, "proxy")

        assertEquals("vless", ob.str("type"))
        assertEquals("proxy", ob.str("tag"))
        assertEquals("e.com", ob.str("server"))
        assertEquals(443, ob["server_port"]!!.jsonPrimitive.int)
        assertEquals("uuid-1", ob.str("uuid"))
        assertEquals("xtls-rprx-vision", ob.str("flow"))

        val tls = ob.obj("tls")
        assertTrue(tls["enabled"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("www.ms.com", tls.str("server_name"))
        val reality = tls.obj("reality")
        assertTrue(reality["enabled"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("PUB", reality.str("public_key"))
        assertEquals("abcd", reality.str("short_id"))
        // Reality mandates a utls fingerprint.
        assertEquals("chrome", tls.obj("utls").str("fingerprint"))
        // tcp transport => no transport object
        assertNull(ob["transport"])
    }

    // ---- outbound: VLESS over WebSocket + TLS ----
    @Test
    fun vlessWebsocketTransport() {
        val node = ProxyLinkParser.parse(
            "vless://u@h.net:8443?encryption=none&security=tls&type=ws&path=%2Fp&host=cdn.h.net&sni=cdn.h.net#W"
        )
        val ob = ConfigBuilder.buildOutbound(node, "proxy")
        val tr = ob.obj("transport")
        assertEquals("ws", tr.str("type"))
        assertEquals("/p", tr.str("path"))
        assertEquals("cdn.h.net", tr.obj("headers").str("Host"))
        assertFalse(ob.obj("tls").containsKey("reality"))
    }

    // ---- outbound: VMess ----
    @Test
    fun vmessOutbound() {
        val node = ProxyNode(
            type = ProxyType.VMESS, name = "v", server = "1.2.3.4", port = 443,
            uuid = "id", alterId = 0, encryption = "auto",
            transport = TransportSettings(network = "tcp"),
        )
        val ob = ConfigBuilder.buildOutbound(node, "proxy")
        assertEquals("vmess", ob.str("type"))
        assertEquals("id", ob.str("uuid"))
        assertEquals("auto", ob.str("security"))
        assertEquals(0, ob["alter_id"]!!.jsonPrimitive.int)
    }

    // ---- outbound: Trojan gRPC ----
    @Test
    fun trojanGrpcOutbound() {
        val node = ProxyLinkParser.parse(
            "trojan://pw@t.host:443?security=tls&type=grpc&serviceName=svc&sni=t.host#T"
        )
        val ob = ConfigBuilder.buildOutbound(node, "proxy")
        assertEquals("trojan", ob.str("type"))
        assertEquals("pw", ob.str("password"))
        val tr = ob.obj("transport")
        assertEquals("grpc", tr.str("type"))
        assertEquals("svc", tr.str("service_name"))
    }

    // ---- outbound: Shadowsocks ----
    @Test
    fun shadowsocksOutbound() {
        val node = ProxyNode(
            type = ProxyType.SHADOWSOCKS, name = "s", server = "ss.h", port = 8388,
            encryption = "aes-256-gcm", password = "pw",
        )
        val ob = ConfigBuilder.buildOutbound(node, "proxy")
        assertEquals("shadowsocks", ob.str("type"))
        assertEquals("aes-256-gcm", ob.str("method"))
        assertEquals("pw", ob.str("password"))
        assertNull(ob["tls"])
        assertNull(ob["transport"])
    }

    // ---- outbound: Hysteria2 with obfs ----
    @Test
    fun hysteria2Outbound() {
        val node = ProxyLinkParser.parse(
            "hysteria2://auth@hy.h:443?sni=hy.h&insecure=1&obfs=salamander&obfs-password=op#H"
        )
        val ob = ConfigBuilder.buildOutbound(node, "proxy")
        assertEquals("hysteria2", ob.str("type"))
        assertEquals("auth", ob.str("password"))
        val obfs = ob.obj("obfs")
        assertEquals("salamander", obfs.str("type"))
        assertEquals("op", obfs.str("password"))
        assertTrue(ob.obj("tls")["insecure"]!!.jsonPrimitive.content.toBoolean())
        // bandwidth not provided => omit (sing-box uses congestion control)
        assertNull(ob["up_mbps"])
    }

    // ---- TLS: alpn list + insecure ----
    @Test
    fun tlsAlpnAndInsecure() {
        val node = ProxyNode(
            type = ProxyType.TROJAN, name = "t", server = "h", port = 443, password = "p",
            tls = TlsSettings(security = "tls", sni = "h", alpn = listOf("h2", "http/1.1"), allowInsecure = true),
        )
        val tls = ConfigBuilder.buildOutbound(node, "proxy").obj("tls")
        assertEquals(listOf("h2", "http/1.1"), tls["alpn"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertTrue(tls["insecure"]!!.jsonPrimitive.content.toBoolean())
    }

    // ---- xhttp is not a sing-box transport ----
    @Test
    fun xhttpTransportUnsupported() {
        val node = ProxyNode(
            type = ProxyType.VLESS, name = "x", server = "h", port = 443, uuid = "u",
            transport = TransportSettings(network = "xhttp", path = "/x"),
        )
        assertThrows(UnsupportedConfigException::class.java) {
            ConfigBuilder.buildOutbound(node, "proxy")
        }
    }

    // ---- log block ----
    @Test
    fun loggingOffDisablesTheCoreLogAndOpensNoFile() {
        val node = ProxyLinkParser.parse("vless://u@h.net:443?encryption=none&security=tls&sni=h.net#N")
        val json = ConfigBuilder.buildConfig(
            node,
            ConfigOptions(logging = false, logLevel = "debug", logOutput = "/data/box.log"),
        )
        val log = Json.parseToJsonElement(json).jsonObject.obj("log")

        assertTrue(log["disabled"]!!.jsonPrimitive.content.toBoolean())
        // Belt and braces: if a core build ever ignored `disabled`, `panic` still keeps it quiet.
        assertEquals("panic", log.str("level"))
        // No `output` at all, so `box.log` is never even created.
        assertNull(log["output"])
    }

    @Test
    fun loggingOnKeepsTheLevelAndTheOutput() {
        val node = ProxyLinkParser.parse("vless://u@h.net:443?encryption=none&security=tls&sni=h.net#N")
        val json = ConfigBuilder.buildConfig(
            node,
            ConfigOptions(logging = true, logLevel = "debug", logOutput = "/data/box.log"),
        )
        val log = Json.parseToJsonElement(json).jsonObject.obj("log")

        assertEquals("debug", log.str("level"))
        assertEquals("/data/box.log", log.str("output"))
        assertNull(log["disabled"])
    }

    // ---- full config assembly ----
    @Test
    fun fullConfigHasRequiredSections() {
        val node = ProxyLinkParser.parse("vless://u@h.net:443?encryption=none&security=tls&sni=h.net#N")
        val json = ConfigBuilder.buildConfig(node, ConfigOptions(mtu = 9000, tunStack = "mixed"))
        val root = Json.parseToJsonElement(json).jsonObject

        assertTrue(root.containsKey("log"))
        assertTrue(root.containsKey("dns"))
        assertTrue(root.containsKey("inbounds"))
        assertTrue(root.containsKey("outbounds"))
        assertTrue(root.containsKey("route"))

        val tun = root["inbounds"]!!.jsonArray.map { it.jsonObject }.first { it.str("type") == "tun" }
        assertEquals("tun-in", tun.str("tag"))
        assertEquals(9000, tun["mtu"]!!.jsonPrimitive.int)
        assertEquals("mixed", tun.str("stack"))
        // proven-on-1.12 legacy address key
        assertEquals("172.19.0.1/30", tun["inet4_address"]!!.jsonArray.first().jsonPrimitive.content)

        val outTags = root["outbounds"]!!.jsonArray.map { it.jsonObject.str("tag") }
        assertTrue(outTags.contains("proxy"))
        assertTrue(outTags.contains("direct"))
        assertTrue(outTags.contains("dns-out"))

        val route = root.obj("route")
        assertTrue(route["auto_detect_interface"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("proxy", route.str("final"))
    }

    @Test
    fun proxyOnlyConfigUsesMixedInboundNotTun() {
        val node = ProxyLinkParser.parse("vless://u@h.net:443?encryption=none&security=tls&sni=h.net#N")
        val json = ConfigBuilder.buildConfig(node, ConfigOptions(proxyOnly = true))
        val root = Json.parseToJsonElement(json).jsonObject
        val inbounds = root["inbounds"]!!.jsonArray.map { it.jsonObject }

        // Exactly one inbound, a mixed (SOCKS/HTTP) one — no TUN.
        assertEquals(1, inbounds.size)
        val mixed = inbounds.first()
        assertEquals("mixed", mixed.str("type"))
        assertEquals("mixed-in", mixed.str("tag"))
        assertEquals("127.0.0.1", mixed.str("listen"))
        assertEquals(2080, mixed["listen_port"]!!.jsonPrimitive.int)
        assertNull(inbounds.firstOrNull { it.str("type") == "tun" })
        // Still routes through the proxy outbound.
        assertEquals("proxy", root.obj("route").str("final"))
    }

    @Test
    fun vpnModeUsesTunInboundNotMixed() {
        val node = ProxyLinkParser.parse("vless://u@h.net:443?encryption=none&security=tls&sni=h.net#N")
        val json = ConfigBuilder.buildConfig(node, ConfigOptions(proxyOnly = false))
        val inbounds = Json.parseToJsonElement(json).jsonObject["inbounds"]!!.jsonArray.map { it.jsonObject }
        assertEquals("tun", inbounds.single().str("type"))
        assertNull(inbounds.firstOrNull { it.str("type") == "mixed" })
    }

    // ---- proxy-only inbound authentication ----
    // An open inbound on 127.0.0.1:2080 is usable by every app on the device: it
    // spends the user's quota and attributes its destinations to them.

    @Test
    fun proxyOnlyInboundCarriesCredentialsWhenSupplied() {
        val node = ProxyLinkParser.parse("vless://u@h.net:443?encryption=none&security=tls&sni=h.net#N")
        val json = ConfigBuilder.buildConfig(
            node,
            ConfigOptions(proxyOnly = true, proxyUser = "yukaribox", proxyPassword = "s3cret-token"),
        )
        val mixed = Json.parseToJsonElement(json).jsonObject["inbounds"]!!.jsonArray
            .map { it.jsonObject }.single()
        val users = mixed["users"]!!.jsonArray.map { it.jsonObject }
        assertEquals(1, users.size)
        assertEquals("yukaribox", users.single().str("username"))
        assertEquals("s3cret-token", users.single().str("password"))
    }

    @Test
    fun proxyOnlyInboundIsOpenOnlyWhenBothFieldsAreBlank() {
        val node = ProxyLinkParser.parse("vless://u@h.net:443?encryption=none&security=tls&sni=h.net#N")
        val json = ConfigBuilder.buildConfig(node, ConfigOptions(proxyOnly = true))
        val mixed = Json.parseToJsonElement(json).jsonObject["inbounds"]!!.jsonArray
            .map { it.jsonObject }.single()
        // The explicit opt-out: no users array at all, rather than an empty one.
        assertNull(mixed["users"])
    }

    @Test
    fun aBlankPasswordNeverBecomesAnEmptyCredential() {
        // sing-box would accept users:[{username, password:""}] as a valid login,
        // which is an open inbound wearing an auth block.
        val node = ProxyLinkParser.parse("vless://u@h.net:443?encryption=none&security=tls&sni=h.net#N")
        val json = ConfigBuilder.buildConfig(
            node,
            ConfigOptions(proxyOnly = true, proxyUser = "yukaribox", proxyPassword = "   "),
        )
        val mixed = Json.parseToJsonElement(json).jsonObject["inbounds"]!!.jsonArray
            .map { it.jsonObject }.single()
        assertNull(mixed["users"])
    }

    @Test
    fun credentialsAreIgnoredInVpnModeWhereThereIsNoLocalInbound() {
        val node = ProxyLinkParser.parse("vless://u@h.net:443?encryption=none&security=tls&sni=h.net#N")
        val json = ConfigBuilder.buildConfig(
            node,
            ConfigOptions(proxyOnly = false, proxyUser = "yukaribox", proxyPassword = "s3cret"),
        )
        val inbounds = Json.parseToJsonElement(json).jsonObject["inbounds"]!!.jsonArray.map { it.jsonObject }
        assertEquals("tun", inbounds.single().str("type"))
        assertFalse(json.contains("s3cret"))
    }

    // ---- DNS: remote/direct servers + rule-based routing (US-007) ----
    @Test
    fun dnsUsesRemoteAndDirectServers() {
        val node = ProxyLinkParser.parse("vless://u@h.net:443?encryption=none&security=tls&sni=h.net#N")
        val dns = Json.parseToJsonElement(ConfigBuilder.buildConfig(node)).jsonObject.obj("dns")
        val servers = dns["servers"]!!.jsonArray.map { it.jsonObject }
        val remote = servers.first { it.str("tag") == "dns-remote" }
        val direct = servers.first { it.str("tag") == "dns-direct" }
        assertEquals("https://1.1.1.1/dns-query", remote.str("address"))
        assertEquals("proxy", remote.str("detour"))
        assertEquals("https://223.5.5.5/dns-query", direct.str("address"))
        assertEquals("direct", direct.str("detour"))
        assertEquals("dns-remote", dns.str("final"))
    }

    @Test
    fun dnsRulesMirrorDirectAndBlockRoutes() {
        val node = ProxyLinkParser.parse("vless://u@h.net:443?encryption=none&security=tls&sni=h.net#N")
        val rules = listOf(
            RouteRule(id = "1", domains = listOf("direct.example"), outbound = RuleOutbound.Direct),
            RouteRule(id = "2", domains = listOf("ads.example"), outbound = RuleOutbound.Block),
            RouteRule(id = "3", domains = listOf("proxy.example"), outbound = RuleOutbound.Proxy),
            RouteRule(id = "4", ipCidrs = listOf("1.2.3.0/24"), outbound = RuleOutbound.Direct),
        )
        val dns = Json.parseToJsonElement(ConfigBuilder.buildConfig(node, ConfigOptions(userRules = rules)))
            .jsonObject.obj("dns")
        val dnsRules = dns["rules"]!!.jsonArray.map { it.jsonObject }

        fun suffixesFor(server: String): List<String> = dnsRules
            .filter { it["server"]?.jsonPrimitive?.content == server }
            .flatMap { it["domain_suffix"]?.jsonArray?.map { d -> d.jsonPrimitive.content } ?: emptyList() }

        // Direct domain resolves via direct DNS; blocked domain via the block server.
        assertTrue(suffixesFor("dns-direct").contains("direct.example"))
        assertTrue(suffixesFor("dns-block").contains("ads.example"))
        // Proxy domains use the dns-remote final, not a mirrored rule.
        assertFalse(dnsRules.any {
            it["domain_suffix"]?.jsonArray?.any { d -> d.jsonPrimitive.content == "proxy.example" } == true
        })
        // An IP-only direct rule has no DNS counterpart (can't match a DNS query).
        assertEquals(
            1,
            dnsRules.count {
                it["server"]?.jsonPrimitive?.content == "dns-direct" && it.containsKey("domain_suffix")
            },
        )
    }

    @Test
    fun dnsRoutingDisabledKeepsOnlyTheOutboundResolutionRule() {
        val node = ProxyLinkParser.parse("vless://u@h.net:443?encryption=none&security=tls&sni=h.net#N")
        val rules = listOf(
            RouteRule(id = "1", domains = listOf("direct.example"), outbound = RuleOutbound.Direct),
            RouteRule(id = "2", domains = listOf("ads.example"), outbound = RuleOutbound.Block),
        )
        val dns = Json.parseToJsonElement(
            ConfigBuilder.buildConfig(node, ConfigOptions(enableDnsRouting = false, userRules = rules)),
        ).jsonObject.obj("dns")
        val dnsRules = dns["rules"]!!.jsonArray.map { it.jsonObject }

        // The `outbound: any -> dns-direct` rule is plumbing, not a routing preference: without
        // it a domain-named node resolves through the very proxy being dialled. It stays even
        // with DNS routing off.
        assertEquals(1, dnsRules.size)
        assertEquals("dns-direct", dnsRules[0].str("server"))
        assertEquals("any", dnsRules[0]["outbound"]!!.jsonArray.single().jsonPrimitive.content)
        // What the toggle actually disables: the mirrors of the user's route rules.
        assertFalse(dnsRules.any { it.containsKey("domain_suffix") })
    }

    // ---- IPv6 leak prevention (US-008) ----
    // Default ConfigOptions has ipv6=false (SettingsData.ipv6Mode defaults to Disable), so
    // these defaults mirror the shipped "IPv6 OFF" state.
    @Test
    fun ipv6DisabledByDefaultUsesIpv4OnlyDnsStrategy() {
        val node = ProxyLinkParser.parse("vless://u@h.net:443?encryption=none&security=tls&sni=h.net#N")
        // Default options == IPv6 disabled.
        val dns = Json.parseToJsonElement(ConfigBuilder.buildConfig(node)).jsonObject.obj("dns")
        // A-only resolution => apps prefer IPv4 and no AAAA answers escape (no IPv6 DNS leak).
        assertEquals("ipv4_only", dns.str("strategy"))
    }

    @Test
    fun ipv6EnabledDropsIpv4OnlyDnsStrategy() {
        val node = ProxyLinkParser.parse("vless://u@h.net:443?encryption=none&security=tls&sni=h.net#N")
        val dns = Json.parseToJsonElement(ConfigBuilder.buildConfig(node, ConfigOptions(ipv6 = true)))
            .jsonObject.obj("dns")
        // With IPv6 on, AAAA resolution is allowed again (no ipv4_only clamp).
        assertNull(dns["strategy"])
    }

    @Test
    fun tunAlwaysCapturesIpv6RegardlessOfToggle() {
        val node = ProxyLinkParser.parse("vless://u@h.net:443?encryption=none&security=tls&sni=h.net#N")
        // Whether IPv6 is on or off, the TUN owns a v6 address so the core processes the
        // v6 packets the VpnService ::/0 route captures — v6 is tunneled, never leaked.
        for (ipv6 in listOf(false, true)) {
            val root = Json.parseToJsonElement(ConfigBuilder.buildConfig(node, ConfigOptions(ipv6 = ipv6))).jsonObject
            val tun = root["inbounds"]!!.jsonArray.map { it.jsonObject }.first { it.str("type") == "tun" }
            assertEquals("fdfe:dcba:9876::1/126", tun["inet6_address"]!!.jsonArray.first().jsonPrimitive.content)
        }
    }

    // ---- LAN-bypass: all traffic in tunnel by default + toggle (US-011) ----
    private fun routeRulesOf(json: String): List<JsonObject> =
        Json.parseToJsonElement(json).jsonObject.obj("route")["rules"]!!.jsonArray.map { it.jsonObject }

    @Test
    fun lanInTunnelByDefaultEmitsNoBypassRule() {
        val node = ProxyLinkParser.parse("vless://u@h.net:443?encryption=none&security=tls&sni=h.net#N")
        // Default options: bypassLan == false => all traffic (incl. LAN) stays in the tunnel.
        val rules = routeRulesOf(ConfigBuilder.buildConfig(node))
        // No private-range -> direct bypass rule is emitted.
        assertFalse(rules.any { it.containsKey("ip_cidr") && it["outbound"]?.jsonPrimitive?.content == "direct" })
        // Everything falls through to the proxy final.
        val route = Json.parseToJsonElement(ConfigBuilder.buildConfig(node)).jsonObject.obj("route")
        assertEquals("proxy", route.str("final"))
    }

    @Test
    fun bypassLanToggleEmitsPrivateRangeDirectRule() {
        val node = ProxyLinkParser.parse("vless://u@h.net:443?encryption=none&security=tls&sni=h.net#N")
        val rules = routeRulesOf(ConfigBuilder.buildConfig(node, ConfigOptions(bypassLan = true)))
        val bypass = rules.single { it.containsKey("ip_cidr") && it["outbound"]?.jsonPrimitive?.content == "direct" }
        val cidrs = bypass["ip_cidr"]!!.jsonArray.map { it.jsonPrimitive.content }
        // RFC1918 LAN ranges route direct when the toggle is on.
        assertTrue(cidrs.contains("10.0.0.0/8"))
        assertTrue(cidrs.contains("172.16.0.0/12"))
        assertTrue(cidrs.contains("192.168.0.0/16"))
    }

    @Test
    fun dnsIsHijackedByProtocolAndByPortSoSniffingOffStillResolves() {
        val node = ProxyLinkParser.parse("vless://u@h.net:443?encryption=none&security=tls&sni=h.net#N")
        val rules = routeRulesOf(ConfigBuilder.buildConfig(node, ConfigOptions(sniffing = false)))
        val toDnsOut = rules.filter { it["outbound"]?.jsonPrimitive?.content == "dns-out" }

        // The `protocol: dns` matcher only fires with the sniffer on, so a port-53 matcher must
        // back it up — otherwise queries to the TUN's DNS address fall through to the proxy
        // final, which cannot reach 172.19.0.2, and all name resolution dies.
        assertTrue(toDnsOut.any { it["protocol"]?.jsonArray?.any { p -> p.jsonPrimitive.content == "dns" } == true })
        assertTrue(toDnsOut.any { it["port"]?.jsonArray?.any { p -> p.jsonPrimitive.int == 53 } == true })
    }

    @Test
    fun directConfigUsesDirectProxyOutbound() {
        val json = ConfigBuilder.buildDirectConfig(ConfigOptions())
        val root = Json.parseToJsonElement(json).jsonObject
        val proxy = root["outbounds"]!!.jsonArray.map { it.jsonObject }.first { it.str("tag") == "proxy" }
        assertEquals("direct", proxy.str("type"))
        assertTrue(root.containsKey("inbounds"))
    }

    // ---- DNS bootstrap: a DoH server named by hostname cannot resolve itself ----

    private fun dnsServersOf(options: ConfigOptions): List<JsonObject> {
        val node = ProxyLinkParser.parse("vless://u@h.net:443?encryption=none&security=tls&sni=h.net#N")
        return Json.parseToJsonElement(ConfigBuilder.buildConfig(node, options))
            .jsonObject.obj("dns")["servers"]!!.jsonArray.map { it.jsonObject }
    }

    @Test
    fun ipLiteralDohServersNeedNoBootstrapResolver() {
        // Both defaults are IP-literal DoH, so nothing has to be resolved to reach them
        // and no platform resolver is introduced.
        val servers = dnsServersOf(ConfigOptions())
        assertFalse(servers.any { it.containsKey("address_resolver") })
        assertNull(servers.firstOrNull { it.str("tag") == "dns-local" })
    }

    @Test
    fun domainNamedRemoteDohBootstrapsThroughDirectDns() {
        // Picking a domain-named preset (AdGuard, Mullvad…) used to kill DNS outright:
        // sing-box could not resolve the DoH hostname itself.
        val servers = dnsServersOf(ConfigOptions(dnsRemote = "https://dns.adguard.com/dns-query"))
        assertEquals("dns-direct", servers.first { it.str("tag") == "dns-remote" }.str("address_resolver"))
        // The IP-literal direct server needs no bootstrap of its own, so no dns-local.
        assertFalse(servers.first { it.str("tag") == "dns-direct" }.containsKey("address_resolver"))
        assertNull(servers.firstOrNull { it.str("tag") == "dns-local" })
    }

    @Test
    fun domainNamedDirectDohBootstrapsThroughThePlatformResolver() {
        val servers = dnsServersOf(
            ConfigOptions(
                dnsRemote = "https://dns.adguard.com/dns-query",
                dnsDirect = "https://doh.pub/dns-query",
            ),
        )
        // With no IP-literal server left, both fall back to the platform resolver — the
        // same physical-network path the node's own hostname already takes.
        val local = servers.first { it.str("tag") == "dns-local" }
        assertEquals("local", local.str("address"))
        assertEquals("direct", local.str("detour"))
        assertEquals("dns-local", servers.first { it.str("tag") == "dns-direct" }.str("address_resolver"))
        assertEquals("dns-local", servers.first { it.str("tag") == "dns-remote" }.str("address_resolver"))
    }

    @Test
    fun bareIpAndBracketedIpv6DnsNeedNoBootstrap() {
        val servers = dnsServersOf(
            ConfigOptions(dnsRemote = "8.8.8.8", dnsDirect = "https://[2606:4700::1111]/dns-query"),
        )
        assertFalse(servers.any { it.containsKey("address_resolver") })
    }

    // ---- a rule with no conditions is "match everything" to sing-box ----

    @Test
    fun aUserRuleLeftWithoutConditionsIsNotEmitted() {
        val node = ProxyLinkParser.parse("vless://u@h.net:443?encryption=none&security=tls&sni=h.net#N")
        // What a rule looks like after RouteRuleValidation drops its only (invalid)
        // matcher. Emitted verbatim it would match every packet and route the user's
        // whole traffic to `direct` — outside the proxy.
        val empty = RouteRule(id = "1", outbound = RuleOutbound.Direct)
        val rules = routeRulesOf(ConfigBuilder.buildConfig(node, ConfigOptions(userRules = listOf(empty))))
        assertFalse(rules.any { it["outbound"]?.jsonPrimitive?.content == "direct" })
        assertFalse(rules.any { it.keys == setOf("outbound") })
    }

    @Test
    fun aUserRuleThatStillHasAConditionIsEmitted() {
        val node = ProxyLinkParser.parse("vless://u@h.net:443?encryption=none&security=tls&sni=h.net#N")
        val kept = RouteRule(id = "1", ipCidrs = listOf("10.0.0.0/8"), outbound = RuleOutbound.Direct)
        val rules = routeRulesOf(ConfigBuilder.buildConfig(node, ConfigOptions(userRules = listOf(kept))))
        val rule = rules.single { it["outbound"]?.jsonPrimitive?.content == "direct" }
        assertEquals("10.0.0.0/8", rule["ip_cidr"]!!.jsonArray.single().jsonPrimitive.content)
    }
}
