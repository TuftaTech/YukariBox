package dev.yukaribox.vpn.proxy

import dev.yukaribox.vpn.core.ConfigBuilder
import dev.yukaribox.vpn.core.ConfigOptions
import dev.yukaribox.vpn.data.RouteRule
import dev.yukaribox.vpn.data.RuleOutbound
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Parser, exporter and config coverage for socks/http/wireguard/tuic + route rules. */
class NewProtocolsTest {

    // ---- SOCKS ----

    @Test
    fun parsesSocksWithAuth() {
        val n = ProxyLinkParser.parse("socks5://user:p%40ss@proxy.example.com:1080#Office")
        assertEquals(ProxyType.SOCKS, n.type)
        assertEquals("user", n.username)
        assertEquals("p@ss", n.password)
        assertEquals(1080, n.port)
        assertEquals("Office", n.name)
    }

    @Test
    fun socksRoundTrip() {
        val n = ProxyLinkParser.parse("socks://alice:secret@10.1.2.3:9050#Tor")
        val exported = ProxyLinkExporter.export(n)
        val back = ProxyLinkParser.parse(exported)
        assertEquals(n.username, back.username)
        assertEquals(n.password, back.password)
        assertEquals(n.server, back.server)
        assertEquals(n.port, back.port)
        assertEquals(n.name, back.name)
    }

    @Test
    fun socksOutboundJson() {
        val n = ProxyLinkParser.parse("socks://u:p@host.example:1080")
        val json = ConfigBuilder.buildOutbound(n, "proxy").toString()
        assertTrue(json.contains("\"type\":\"socks\""))
        assertTrue(json.contains("\"version\":\"5\""))
        assertTrue(json.contains("\"username\":\"u\""))
    }

    // ---- HTTP ----

    @Test
    fun parsesHttpsProxyAsTls() {
        val n = ProxyLinkParser.parse("https://user:pass@proxy.example.com:8443#Corp")
        assertEquals(ProxyType.HTTP, n.type)
        assertTrue(n.tls.enabled)
        assertEquals(8443, n.port)
    }

    @Test
    fun httpOutboundJson() {
        val n = ProxyLinkParser.parse("http://h.example.com:8080")
        val json = ConfigBuilder.buildOutbound(n, "proxy").toString()
        assertTrue(json.contains("\"type\":\"http\""))
        assertFalse(json.contains("\"tls\""))
    }

    // ---- TUIC ----

    @Test
    fun parsesTuicLink() {
        val n = ProxyLinkParser.parse(
            "tuic://11111111-2222-3333-4444-555555555555:tok%21en@t.example.com:8443" +
                "?congestion_control=bbr&udp_relay_mode=quic&sni=cdn.example.com&alpn=h3&allow_insecure=1#TUIC",
        )
        assertEquals(ProxyType.TUIC, n.type)
        assertEquals("11111111-2222-3333-4444-555555555555", n.uuid)
        assertEquals("tok!en", n.password)
        assertEquals("quic", n.udpRelayMode)
        assertTrue(n.tls.allowInsecure)
        assertEquals(listOf("h3"), n.tls.alpn)
    }

    @Test
    fun tuicOutboundJson() {
        val n = ProxyLinkParser.parse("tuic://uuid-x:pw@t.example.com:443?congestion_control=cubic&sni=s.example")
        val json = ConfigBuilder.buildOutbound(n, "proxy").toString()
        assertTrue(json.contains("\"type\":\"tuic\""))
        assertTrue(json.contains("\"congestion_control\":\"cubic\""))
        assertTrue(json.contains("\"server_name\":\"s.example\""))
        assertTrue(json.contains("\"enabled\":true"))
    }

    @Test
    fun tuicRoundTrip() {
        val n = ProxyLinkParser.parse(
            "tuic://uid:pw@h.example:443?congestion_control=bbr&udp_relay_mode=quic&sni=x.y#N",
        )
        val back = ProxyLinkParser.parse(ProxyLinkExporter.export(n))
        assertEquals(n.uuid, back.uuid)
        assertEquals(n.password, back.password)
        assertEquals(n.udpRelayMode, back.udpRelayMode)
        assertEquals(n.tls.sni, back.tls.sni)
    }

    // ---- WireGuard ----

    @Test
    fun parsesWireGuardLink() {
        val n = ProxyLinkParser.parse(
            "wireguard://cHJpdmF0ZWtleQ%3D%3D@wg.example.com:51820" +
                "?publickey=cGVlcmtleQ%3D%3D&address=10.0.0.2/32,fd00::2/128&mtu=1380&reserved=1,2,3#WG",
        )
        assertEquals(ProxyType.WIREGUARD, n.type)
        assertEquals("cHJpdmF0ZWtleQ==", n.wgPrivateKey)
        assertEquals("cGVlcmtleQ==", n.wgPeerPublicKey)
        assertEquals(listOf("10.0.0.2/32", "fd00::2/128"), n.wgLocalAddress)
        assertEquals(1380, n.wgMtu)
    }

    @Test
    fun wireGuardOutboundJsonConvertsReserved() {
        val n = ProxyNode(
            type = ProxyType.WIREGUARD,
            name = "wg",
            server = "wg.example.com",
            port = 51820,
            wgPrivateKey = "priv",
            wgPeerPublicKey = "pub",
            wgLocalAddress = listOf("10.0.0.2/32"),
            wgReserved = "1,2,3",
        )
        val json = ConfigBuilder.buildOutbound(n, "proxy").toString()
        assertTrue(json.contains("\"type\":\"wireguard\""))
        assertTrue(json.contains("\"local_address\":[\"10.0.0.2/32\"]"))
        // 1,2,3 -> base64 of bytes [1,2,3]
        assertTrue(json.contains("\"reserved\":\"AQID\""))
    }

    @Test
    fun wireGuardRoundTrip() {
        val n = ProxyLinkParser.parse(
            "wireguard://pk@wg.example.com:51820?publickey=peer&address=10.0.0.2/32&mtu=1420#Home",
        )
        val back = ProxyLinkParser.parse(ProxyLinkExporter.export(n))
        assertEquals(n.wgPrivateKey, back.wgPrivateKey)
        assertEquals(n.wgPeerPublicKey, back.wgPeerPublicKey)
        assertEquals(n.wgLocalAddress, back.wgLocalAddress)
    }

    // ---- exporter round-trips for existing protocols ----

    @Test
    fun vlessRoundTrip() {
        val link = "vless://b831ebc3-2c4a-4a1f-8b0c-1234567890ab@example.com:443" +
            "?encryption=none&security=reality&sni=www.microsoft.com&fp=chrome" +
            "&pbk=PUBKEYxyz&sid=0123abcd&flow=xtls-rprx-vision&type=tcp#Reality%20Node"
        val n = ProxyLinkParser.parse(link)
        val back = ProxyLinkParser.parse(ProxyLinkExporter.export(n))
        assertEquals(n, back)
    }

    @Test
    fun vmessRoundTrip() {
        val n = ProxyNode(
            type = ProxyType.VMESS,
            name = "vm節点",
            server = "vm.example.com",
            port = 443,
            uuid = "u-u-i-d",
            encryption = "auto",
            tls = TlsSettings(security = "tls", sni = "vm.example.com"),
            transport = TransportSettings(network = "ws", host = "vm.example.com", path = "/ws"),
        )
        val back = ProxyLinkParser.parse(ProxyLinkExporter.export(n))
        assertEquals(n.uuid, back.uuid)
        assertEquals(n.name, back.name)
        assertEquals(n.transport.path, back.transport.path)
        assertEquals("tls", back.tls.security)
    }

    @Test
    fun shadowsocksRoundTrip() {
        val n = ProxyLinkParser.parse("ss://YWVzLTI1Ni1nY206cGFzc3dvcmQ@s.example.com:8388#SS")
        val back = ProxyLinkParser.parse(ProxyLinkExporter.export(n))
        assertEquals(n, back)
    }

    @Test
    fun hysteria2RoundTrip() {
        val n = ProxyLinkParser.parse(
            "hysteria2://pw@h.example.com:443?sni=h.example.com&obfs=salamander&obfs-password=op#HY",
        )
        val back = ProxyLinkParser.parse(ProxyLinkExporter.export(n))
        assertEquals(n.password, back.password)
        assertEquals(n.obfs, back.obfs)
        assertEquals(n.obfsPassword, back.obfsPassword)
    }

    // ---- user route rules ----

    @Test
    fun userRulesAppearInRouteBeforeBypassLan() {
        val node = ProxyLinkParser.parse("socks://u:p@h.example:1080")
        val rules = listOf(
            RouteRule(
                id = "r1",
                name = "block ads",
                domains = listOf("doubleclick.net", "keyword:adservice"),
                outbound = RuleOutbound.Block,
            ),
            RouteRule(
                id = "r2",
                name = "lan app",
                packages = listOf("com.example.app"),
                outbound = RuleOutbound.Direct,
            ),
        )
        val config = ConfigBuilder.buildConfig(node, ConfigOptions(userRules = rules, bypassLan = true))
        assertTrue(config.contains("\"domain_suffix\":[\"doubleclick.net\"]"))
        assertTrue(config.contains("\"domain_keyword\":[\"adservice\"]"))
        assertTrue(config.contains("\"package_name\":[\"com.example.app\"]"))
        // Block rule forces a block outbound to exist.
        assertTrue(config.contains("\"type\":\"block\""))
        // User rule must come before the bypass-LAN private-range rule.
        val userIdx = config.indexOf("doubleclick.net")
        val lanIdx = config.indexOf("192.168.0.0/16")
        assertTrue(userIdx in 1 until lanIdx)
    }

    // ---- security fixes ----

    @Test
    fun globalAllowInsecureIsOredIntoNodeTls() {
        val n = ProxyLinkParser.parse("trojan://pw@t.example.com:443?security=tls&sni=t.example.com")
        val without = ConfigBuilder.buildConfig(n, ConfigOptions())
        val with = ConfigBuilder.buildConfig(n, ConfigOptions(globalAllowInsecure = true))
        assertFalse(without.contains("\"insecure\":true"))
        assertTrue(with.contains("\"insecure\":true"))
    }

    @Test
    fun wgReservedOutOfRangePassesThroughVerbatim() {
        val n = ProxyNode(
            type = ProxyType.WIREGUARD, name = "", server = "wg.example", port = 51820,
            wgPrivateKey = "p", wgPeerPublicKey = "q", wgReserved = "256,0,0",
        )
        val json = ConfigBuilder.buildOutbound(n, "proxy").toString()
        // Not silently wrapped to bytes — emitted as-is for sing-box to reject loudly.
        assertTrue(json.contains("\"reserved\":\"256,0,0\""))
    }

    @Test
    fun invalidRegexRuleIsDroppedNotFatal() {
        val node = ProxyLinkParser.parse("socks://h.example:1080")
        val rules = listOf(
            RouteRule(
                id = "r",
                domains = listOf("regexp:([unclosed", "good.example.com"),
                outbound = RuleOutbound.Direct,
            ),
        )
        val config = ConfigBuilder.buildConfig(node, ConfigOptions(userRules = rules))
        assertFalse(config.contains("unclosed"))
        assertTrue(config.contains("good.example.com"))
    }

    @Test
    fun bypassLanIncludesMulticastAndIpv6Local() {
        val node = ProxyLinkParser.parse("socks://h.example:1080")
        val config = ConfigBuilder.buildConfig(node, ConfigOptions(bypassLan = true))
        assertTrue(config.contains("224.0.0.0/4"))
        assertTrue(config.contains("fc00::/7"))
        assertTrue(config.contains("fe80::/10"))
    }

    @Test
    fun jsonStrEscapesLoneSurrogates() {
        val n = ProxyNode(
            type = ProxyType.VMESS, name = "bad\uD800name", server = "v.example", port = 443,
            uuid = "u", encryption = "auto",
        )
        val link = ProxyLinkExporter.export(n)
        // Round-trip must not throw and must preserve a parseable payload.
        val back = ProxyLinkParser.parse(link)
        assertEquals("v.example", back.server)
    }

    @Test
    fun portRangesSplitCorrectly() {
        val node = ProxyLinkParser.parse("socks://h.example:1080")
        val rules = listOf(
            RouteRule(id = "r", ports = listOf("443", "1000:2000", "3000-4000"), outbound = RuleOutbound.Direct),
        )
        val config = ConfigBuilder.buildConfig(node, ConfigOptions(userRules = rules))
        assertTrue(config.contains("\"port\":[443]"))
        assertTrue(config.contains("\"port_range\":[\"1000:2000\",\"3000:4000\"]"))
    }
}
