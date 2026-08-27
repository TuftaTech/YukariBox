package dev.yukaribox.vpn.core

import dev.yukaribox.vpn.data.RouteRule
import dev.yukaribox.vpn.data.RuleOutbound
import dev.yukaribox.vpn.proxy.ProxyNode
import dev.yukaribox.vpn.proxy.ProxyType
import dev.yukaribox.vpn.proxy.TlsSettings
import dev.yukaribox.vpn.proxy.TransportSettings
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** Raised when a node uses a feature the sing-box core cannot express. */
class UnsupportedConfigException(message: String) : Exception(message)

/** Options controlling full-config assembly. */
data class ConfigOptions(
    /** Proxy-only: serve a local mixed (SOCKS/HTTP) inbound instead of the TUN. */
    val proxyOnly: Boolean = false,
    val mtu: Int = 9000,
    val tunStack: String = "gvisor",
    /**
     * Whether the core logs at all. False emits `log.disabled`, which is what keeps both
     * `neko.log` and `box.log` empty; the app's own lines are gated separately in [Logs].
     */
    /**
     * Default `false`, matching `SettingsData.logging`. Every production call site passes
     * explicit options from `SettingsStore.configOptions()`, so nothing depended on the old
     * `true` -- but a future one that omitted them would silently have opened `box.log`, the
     * file that can hold credentials at trace level.
     */
    val logging: Boolean = false,
    val logLevel: String = "info",
    val logOutput: String = "",
    val dnsRemote: String = "https://1.1.1.1/dns-query",
    val dnsDirect: String = "https://223.5.5.5/dns-query",
    val enableDnsRouting: Boolean = true,
    val ipv6: Boolean = false,
    /** Send private/local ranges direct. OFF by default = all traffic in tunnel. */
    val bypassLan: Boolean = false,
    val sniffing: Boolean = true,
    /** Global "allow insecure TLS" override, ORed into every node's TLS block. */
    val globalAllowInsecure: Boolean = false,
    /** User routing rules (priority order, already filtered to enabled). */
    val userRules: List<RouteRule> = emptyList(),
    /**
     * Credentials for the proxy-only mixed inbound. Blank on either field means an
     * open inbound — usable by every app on the device, so it only happens when the
     * user explicitly opts out of authentication (see [ProxyAuth]).
     */
    val proxyUser: String = "",
    val proxyPassword: String = "",
)

/**
 * Turns parsed proxy nodes into sing-box 1.12.x configuration JSON.
 *
 * Field names follow the form NekoBox emits against this exact core
 * (sing-box 1.12.19-neko): legacy keys such as TUN `inet4_address` and inbound
 * `sniff` are still accepted in 1.12 (removed only in 1.14).
 */
object ConfigBuilder {

    private const val TUN_ADDRESS = "172.19.0.1/30"
    private const val TUN_ADDRESS6 = "fdfe:dcba:9876::1/126"

    /** Local mixed (SOCKS/HTTP) inbound used in proxy-only mode. */
    private const val MIXED_LISTEN = "127.0.0.1"
    private const val MIXED_PORT = 2080

    /** Plain DNS port, hijacked to `dns-out` so resolution works with sniffing off. */
    private const val DNS_PORT = 53

    /** Private/local ranges kept off the proxy when bypass-LAN is on. */
    private val PRIVATE_RANGES = listOf(
        "10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "169.254.0.0/16", "127.0.0.0/8",
        "224.0.0.0/4", // IPv4 multicast (mDNS/SSDP — Chromecast, printers)
        "fc00::/7", // IPv6 ULA
        "fe80::/10", // IPv6 link-local
        "::1/128", // IPv6 loopback
    )

    /** Build a single sing-box outbound object for [rawNode] with the given [tag]. */
    fun buildOutbound(rawNode: ProxyNode, tag: String, globalAllowInsecure: Boolean = false): JsonObject {
        val node = if (globalAllowInsecure && !rawNode.tls.allowInsecure) {
            rawNode.copy(tls = rawNode.tls.copy(allowInsecure = true))
        } else {
            rawNode
        }
        return buildJsonObject {
        put("type", node.type.singBoxType)
        put("tag", tag)
        put("server", node.server)
        put("server_port", node.port)
        when (node.type) {
            ProxyType.VLESS -> {
                put("uuid", node.uuid)
                if (node.flow.isNotBlank()) put("flow", node.flow)
                put("packet_encoding", "xudp")
                tlsBlock(node.tls)?.let { put("tls", it) }
                transportBlock(node.transport)?.let { put("transport", it) }
            }
            ProxyType.VMESS -> {
                put("uuid", node.uuid)
                put("security", node.encryption.ifBlank { "auto" })
                put("alter_id", node.alterId)
                tlsBlock(node.tls)?.let { put("tls", it) }
                transportBlock(node.transport)?.let { put("transport", it) }
            }
            ProxyType.TROJAN -> {
                put("password", node.password)
                tlsBlock(node.tls)?.let { put("tls", it) }
                transportBlock(node.transport)?.let { put("transport", it) }
            }
            ProxyType.SHADOWSOCKS -> {
                put("method", node.encryption)
                put("password", node.password)
                if (node.plugin.isNotBlank()) {
                    put("plugin", node.plugin)
                    put("plugin_opts", node.pluginOpts)
                }
            }
            ProxyType.HYSTERIA2 -> {
                put("password", node.password)
                if (node.obfs.isNotBlank()) {
                    putJsonObject("obfs") {
                        put("type", node.obfs)
                        put("password", node.obfsPassword)
                    }
                }
                // Hysteria2 is QUIC/TLS only.
                put("tls", tlsBlock(node.tls, forceEnabled = true)!!)
            }
            ProxyType.SOCKS -> {
                put("version", "5")
                if (node.username.isNotBlank()) put("username", node.username)
                if (node.password.isNotBlank()) put("password", node.password)
            }
            ProxyType.HTTP -> {
                if (node.username.isNotBlank()) put("username", node.username)
                if (node.password.isNotBlank()) put("password", node.password)
                tlsBlock(node.tls)?.let { put("tls", it) }
            }
            ProxyType.WIREGUARD -> {
                if (node.wgPrivateKey.isBlank()) throw UnsupportedConfigException("wireguard: missing private key")
                if (node.wgPeerPublicKey.isBlank()) throw UnsupportedConfigException("wireguard: missing peer public key")
                putJsonArray("local_address") {
                    (node.wgLocalAddress.ifEmpty { listOf("10.0.0.2/32") }).forEach { add(it) }
                }
                put("private_key", node.wgPrivateKey)
                put("peer_public_key", node.wgPeerPublicKey)
                if (node.wgPreSharedKey.isNotBlank()) put("pre_shared_key", node.wgPreSharedKey)
                put("mtu", node.wgMtu)
                encodeWgReserved(node.wgReserved)?.let { put("reserved", it) }
            }
            ProxyType.TUIC -> {
                put("uuid", node.uuid)
                put("password", node.password)
                put("congestion_control", node.congestionControl.ifBlank { "bbr" })
                if (node.udpRelayMode == "quic") put("udp_relay_mode", "quic")
                if (node.zeroRttHandshake) put("zero_rtt_handshake", true)
                put("tls", tlsBlock(node.tls, forceEnabled = true)!!)
            }
        }
        }
    }

    /**
     * sing-box wants WireGuard reserved bytes as base64. Subscriptions write them
     * either as "1,2,3" decimal triplets or already-encoded base64 — convert the
     * former, pass the latter through, drop blanks.
     */
    private fun encodeWgReserved(raw: String): String? {
        if (raw.isBlank()) return null
        val parts = raw.replace("[", "").replace("]", "").split(',').map { it.trim() }
        val ints = parts.map { it.toIntOrNull() }
        // Reserved bytes must be 0..255 — out-of-range values would silently wrap
        // via toByte() and break the handshake; pass such input through verbatim.
        if (ints.size == 3 && ints.all { it != null && it in 0..255 }) {
            val bytes = ByteArray(3) { i -> ints[i]!!.toByte() }
            return java.util.Base64.getEncoder().encodeToString(bytes)
        }
        return raw
    }

    /** Assemble a complete sing-box config that routes all traffic through [node]. */
    fun buildConfig(node: ProxyNode, options: ConfigOptions = ConfigOptions()): String =
        assemble(buildOutbound(node, "proxy", options.globalAllowInsecure), options)

    /**
     * A config whose "proxy" outbound is plain `direct` — everything flows
     * through the TUN and sing-box straight to the network. Used to smoke-test
     * the full tunnel pipeline on-device without needing a remote server.
     */
    fun buildDirectConfig(options: ConfigOptions = ConfigOptions()): String =
        assemble(buildJsonObject { put("type", "direct"); put("tag", "proxy") }, options)

    /**
     * A lightweight config for latency testing a single [node]: no TUN inbound,
     * the node as the default outbound. Started transiently by the URL-test
     * engine, never as the main tunnel.
     */
    fun buildTestConfig(node: ProxyNode, options: ConfigOptions = ConfigOptions()): String {
        val root = buildJsonObject {
            putJsonObject("log") { put("level", "panic") }
            put("dns", dnsBlock(options))
            putJsonArray("outbounds") {
                add(buildOutbound(node, "proxy", options.globalAllowInsecure))
                add(buildJsonObject { put("type", "direct"); put("tag", "direct") })
                add(buildJsonObject { put("type", "dns"); put("tag", "dns-out") })
            }
            put("route", routeBlock())
        }
        return root.toString()
    }

    private fun assemble(proxyOutbound: JsonObject, options: ConfigOptions): String {
        val root = buildJsonObject {
            putJsonObject("log") {
                if (options.logging) {
                    put("level", options.logLevel)
                    if (options.logOutput.isNotBlank()) put("output", options.logOutput)
                } else {
                    // `disabled` is the field that silences the core; `panic` is belt and
                    // braces in case a core build ever ignores it, and both are real sing-box
                    // keys so neither risks a rejected config. No `output` at all, so
                    // `box.log` is never opened -- the file that can hold credentials at
                    // trace level simply does not come into existence.
                    put("disabled", true)
                    put("level", "panic")
                }
            }
            put("dns", dnsBlock(options))
            putJsonArray("inbounds") {
                add(if (options.proxyOnly) mixedInbound(options) else tunInbound(options))
            }
            putJsonArray("outbounds") {
                add(proxyOutbound)
                add(buildJsonObject { put("type", "direct"); put("tag", "direct") })
                add(buildJsonObject { put("type", "dns"); put("tag", "dns-out") })
                if (options.userRules.any { it.outbound == RuleOutbound.Block }) {
                    add(buildJsonObject { put("type", "block"); put("tag", "block") })
                }
            }
            put("route", routeBlock(options))
        }
        return root.toString()
    }

    private val ProxyType.singBoxType: String
        get() = when (this) {
            ProxyType.VLESS -> "vless"
            ProxyType.VMESS -> "vmess"
            ProxyType.TROJAN -> "trojan"
            ProxyType.SHADOWSOCKS -> "shadowsocks"
            ProxyType.HYSTERIA2 -> "hysteria2"
            ProxyType.SOCKS -> "socks"
            ProxyType.HTTP -> "http"
            ProxyType.WIREGUARD -> "wireguard"
            ProxyType.TUIC -> "tuic"
        }

    private fun tlsBlock(t: TlsSettings, forceEnabled: Boolean = false): JsonObject? {
        if (!t.enabled && !forceEnabled) return null
        return buildJsonObject {
            put("enabled", true)
            if (t.sni.isNotBlank()) put("server_name", t.sni)
            if (t.allowInsecure) put("insecure", true)
            if (t.disableSni) put("disable_sni", true)
            if (t.alpn.isNotEmpty()) putJsonArray("alpn") { t.alpn.forEach { add(it) } }
            // Reality mandates a uTLS fingerprint; default to chrome when unspecified.
            if (t.fingerprint.isNotBlank() || t.isReality) {
                putJsonObject("utls") {
                    put("enabled", true)
                    put("fingerprint", t.fingerprint.ifBlank { "chrome" })
                }
            }
            if (t.isReality) {
                putJsonObject("reality") {
                    put("enabled", true)
                    put("public_key", t.realityPublicKey)
                    if (t.realityShortId.isNotBlank()) put("short_id", t.realityShortId)
                }
            }
        }
    }

    private fun transportBlock(tr: TransportSettings): JsonObject? = when (tr.network) {
        "tcp" -> null
        "ws" -> buildJsonObject {
            put("type", "ws")
            if (tr.path.isNotBlank()) put("path", tr.path)
            if (tr.host.isNotBlank()) putJsonObject("headers") { put("Host", tr.host) }
        }
        "grpc" -> buildJsonObject {
            put("type", "grpc")
            put("service_name", tr.grpcServiceName.ifBlank { tr.path })
        }
        "http" -> buildJsonObject {
            put("type", "http")
            if (tr.host.isNotBlank()) putJsonArray("host") { add(tr.host) }
            if (tr.path.isNotBlank()) put("path", tr.path)
        }
        "httpupgrade" -> buildJsonObject {
            put("type", "httpupgrade")
            if (tr.host.isNotBlank()) put("host", tr.host)
            if (tr.path.isNotBlank()) put("path", tr.path)
        }
        "quic" -> buildJsonObject { put("type", "quic") }
        "xhttp" -> throw UnsupportedConfigException("xhttp transport is not supported by the sing-box core")
        else -> throw UnsupportedConfigException("unknown transport: ${tr.network}")
    }

    private fun tunInbound(o: ConfigOptions): JsonObject = buildJsonObject {
        put("type", "tun")
        put("tag", "tun-in")
        putJsonArray("inet4_address") { add(TUN_ADDRESS) }
        // Always give the TUN a v6 address so the core can process IPv6 packets the
        // VpnService.Builder captures with its ::/0 route. Capturing v6 unconditionally
        // is what prevents the IPv6 leak; when v6 is "disabled" the DNS strategy below
        // makes apps prefer IPv4, so little v6 actually flows (and what does is tunneled).
        putJsonArray("inet6_address") { add(TUN_ADDRESS6) }
        put("mtu", o.mtu)
        put("stack", o.tunStack)
        put("endpoint_independent_nat", true)
        put("sniff", o.sniffing)
        put("sniff_override_destination", o.sniffing)
    }

    private fun mixedInbound(o: ConfigOptions): JsonObject = buildJsonObject {
        put("type", "mixed")
        put("tag", "mixed-in")
        put("listen", MIXED_LISTEN)
        put("listen_port", MIXED_PORT)
        put("sniff", o.sniffing)
        put("sniff_override_destination", o.sniffing)
        // Authenticated by default. An open inbound on loopback is usable by every
        // app on the device: it spends the user's quota and attributes its traffic
        // to them. Emitted only when both fields are set, because sing-box treats a
        // `users` array containing a blank password as a valid empty credential.
        if (o.proxyUser.isNotBlank() && o.proxyPassword.isNotBlank()) {
            putJsonArray("users") {
                add(
                    buildJsonObject {
                        put("username", o.proxyUser)
                        put("password", o.proxyPassword)
                    },
                )
            }
        }
    }

    private fun dnsBlock(o: ConfigOptions): JsonObject = buildJsonObject {
        // A DoH server named by hostname cannot resolve itself: sing-box needs an
        // explicit address_resolver for it, or DNS dies completely the moment the user
        // picks one of the domain-named presets (AdGuard, Mullvad, doh.pub). Prefer
        // bootstrapping through the IP-literal direct server so the DoH hostname is
        // resolved over DoH too; only if that one is itself domain-named do we add a
        // platform ("local") resolver, which is the same physical-network path the
        // node's own hostname already takes.
        val directNeedsResolver = needsAddressResolver(o.dnsDirect)
        val remoteNeedsResolver = needsAddressResolver(o.dnsRemote)
        val bootstrap = if (directNeedsResolver) "dns-local" else "dns-direct"
        putJsonArray("servers") {
            add(
                buildJsonObject {
                    put("tag", "dns-remote"); put("address", o.dnsRemote); put("detour", "proxy")
                    if (remoteNeedsResolver) put("address_resolver", bootstrap)
                },
            )
            add(
                buildJsonObject {
                    put("tag", "dns-direct"); put("address", o.dnsDirect); put("detour", "direct")
                    if (directNeedsResolver) put("address_resolver", "dns-local")
                },
            )
            if (directNeedsResolver) {
                add(buildJsonObject { put("tag", "dns-local"); put("address", "local"); put("detour", "direct") })
            }
            add(buildJsonObject { put("tag", "dns-block"); put("address", "rcode://success") })
        }
        putJsonArray("rules") {
            // ALWAYS first, regardless of the "DNS routing" toggle: resolve outbound
            // server hostnames via direct DNS. Without it a domain-named node cannot be
            // dialled at all — `final` is dns-remote, whose detour is the very proxy we
            // are still trying to resolve (chicken-and-egg), so the tunnel never comes
            // up. This rule is plumbing, not a routing preference.
            add(buildJsonObject { putJsonArray("outbound") { add("any") }; put("server", "dns-direct") })
            // Mirror the routing decisions into DNS: a domain routed `direct`/`block`
            // resolves via the matching DNS server, so resolution follows the route.
            // This part *is* the "DNS routing" toggle.
            if (o.enableDnsRouting) {
                o.userRules.forEach { rule -> dnsRuleForUserRule(rule)?.let { add(it) } }
            }
        }
        put("final", "dns-remote")
        put("independent_cache", true)
        // When IPv6 is disabled, resolve A-only so apps prefer IPv4. Combined with the
        // unconditional ::/0 TUN capture this honours "Disable IPv6" without leaking:
        // apps stop asking for AAAA, and any residual v6 is tunneled rather than escaping.
        if (!o.ipv6) put("strategy", "ipv4_only")
    }

    /**
     * True when [address] names a DNS server by hostname, so sing-box has to be told how
     * to resolve that hostname. IP-literal and scheme-less special forms ("local",
     * "rcode://…", "dhcp://…") resolve nothing and need no bootstrap.
     */
    private fun needsAddressResolver(address: String): Boolean {
        val value = address.trim()
        if (value.isEmpty()) return false
        val scheme = value.substringBefore("://", "").lowercase()
        if (scheme in setOf("rcode", "dhcp")) return false
        if (value.equals("local", ignoreCase = true)) return false
        val authority = if (scheme.isEmpty()) value else value.substringAfter("://").substringBefore('/')
        val host = authority.substringAfterLast('@').substringBefore('/')
            .removeSurrounding("[", "]")
            .let { if (it.count { c -> c == ':' } == 1) it.substringBefore(':') else it }
        return !isValidCidr(host)
    }

    private fun routeBlock(o: ConfigOptions = ConfigOptions()): JsonObject = buildJsonObject {
        put("auto_detect_interface", true)
        putJsonArray("rules") {
            add(buildJsonObject { putJsonArray("protocol") { add("dns") }; put("outbound", "dns-out") })
            // Belt-and-braces DNS hijack. The `protocol` matcher above only fires when
            // the sniffer is on; with sniffing disabled every query to the TUN's DNS
            // address would fall through to `final` (the proxy), which cannot reach
            // 172.19.0.2 — all name resolution would die. Matching port 53 needs no
            // sniffer, so DNS keeps working with sniffing off.
            add(buildJsonObject { putJsonArray("port") { add(DNS_PORT) }; put("outbound", "dns-out") })
            // User rules outrank bypass-LAN so a custom rule can re-route LAN space.
            o.userRules.forEach { rule ->
                val block = userRuleBlock(rule)
                // A rule whose every matcher was dropped as invalid would emit an object
                // with no conditions, which sing-box treats as "match everything": a
                // broken `direct` rule would then route all traffic outside the proxy.
                if (block.keys.any { it != "outbound" }) add(block)
            }
            // Bypass LAN: send private/loopback ranges straight out, off the proxy.
            if (o.bypassLan) {
                add(buildJsonObject {
                    putJsonArray("ip_cidr") { PRIVATE_RANGES.forEach { add(it) } }
                    put("outbound", "direct")
                })
            }
        }
        put("final", "proxy")
    }

    /**
     * Translate one user rule. Domain entries accept NekoBox-style prefixes:
     * `full:` exact, `keyword:` substring, `regexp:` regex; bare values match as
     * suffixes. Ports accept "443" or "1000:2000" ranges.
     */
    private fun userRuleBlock(rule: RouteRule): JsonObject = buildJsonObject {
        putDomainMatchers(rule)
        if (rule.ipCidrs.isNotEmpty()) putJsonArray("ip_cidr") { rule.ipCidrs.forEach { add(it.trim()) } }
        val singlePorts = rule.ports.mapNotNull { it.trim().toIntOrNull() }
        val portRanges = rule.ports.map { it.trim() }.filter { it.contains(':') || it.contains('-') }
            .map { it.replace('-', ':') }
        if (singlePorts.isNotEmpty()) putJsonArray("port") { singlePorts.forEach { add(it) } }
        if (portRanges.isNotEmpty()) putJsonArray("port_range") { portRanges.forEach { add(it) } }
        if (rule.packages.isNotEmpty()) putJsonArray("package_name") { rule.packages.forEach { add(it.trim()) } }
        put(
            "outbound",
            when (rule.outbound) {
                RuleOutbound.Proxy -> "proxy"
                RuleOutbound.Direct -> "direct"
                RuleOutbound.Block -> "block"
            },
        )
    }

    /**
     * Write NekoBox-style domain matchers from [rule] into this object, returning
     * whether any were emitted. Shared by route rules and their DNS mirrors:
     * `full:` exact, `keyword:` substring, `regexp:` regex; bare values match as
     * suffixes (also `domain:`). Invalid regexes are dropped, not fatal.
     */
    private fun JsonObjectBuilder.putDomainMatchers(rule: RouteRule): Boolean {
        val exact = mutableListOf<String>()
        val suffix = mutableListOf<String>()
        val keyword = mutableListOf<String>()
        val regex = mutableListOf<String>()
        rule.domains.forEach { classifyDomain(it.trim(), exact, suffix, keyword, regex) }
        if (exact.isNotEmpty()) putJsonArray("domain") { exact.forEach { add(it) } }
        if (suffix.isNotEmpty()) putJsonArray("domain_suffix") { suffix.forEach { add(it) } }
        if (keyword.isNotEmpty()) putJsonArray("domain_keyword") { keyword.forEach { add(it) } }
        if (regex.isNotEmpty()) putJsonArray("domain_regex") { regex.forEach { add(it) } }
        return exact.isNotEmpty() || suffix.isNotEmpty() || keyword.isNotEmpty() || regex.isNotEmpty()
    }

    /** Sort one domain entry into its matcher bucket by NekoBox prefix. */
    private fun classifyDomain(
        d: String,
        exact: MutableList<String>,
        suffix: MutableList<String>,
        keyword: MutableList<String>,
        regex: MutableList<String>,
    ) {
        when {
            d.isEmpty() -> {}
            d.startsWith("full:") -> exact.add(d.removePrefix("full:"))
            d.startsWith("keyword:") -> keyword.add(d.removePrefix("keyword:"))
            d.startsWith("regexp:") -> {
                val pattern = d.removePrefix("regexp:")
                // An invalid pattern would abort the whole core start — drop it
                // instead of letting one bad rule kill the tunnel.
                if (runCatching { Regex(pattern) }.isSuccess) regex.add(pattern)
            }
            d.startsWith("domain:") -> suffix.add(d.removePrefix("domain:"))
            else -> suffix.add(d)
        }
    }

    /**
     * DNS counterpart of a route rule: a domain routed `direct` resolves via
     * `dns-direct`, `block` via `dns-block`, so resolution follows routing. Returns
     * null for proxy rules (proxy uses the `dns-remote` final) or rules with no
     * domain matchers (IP/port/package conditions can't apply to a DNS query).
     */
    private fun dnsRuleForUserRule(rule: RouteRule): JsonObject? {
        val server = when (rule.outbound) {
            RuleOutbound.Direct -> "dns-direct"
            RuleOutbound.Block -> "dns-block"
            RuleOutbound.Proxy -> return null
        }
        val matchers = buildJsonObject { putDomainMatchers(rule) }
        if (matchers.isEmpty()) return null
        return buildJsonObject {
            matchers.forEach { (key, value) -> put(key, value) }
            put("server", server)
        }
    }
}
