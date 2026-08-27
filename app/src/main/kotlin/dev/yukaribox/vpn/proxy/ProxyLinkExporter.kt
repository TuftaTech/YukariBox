package dev.yukaribox.vpn.proxy

import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Base64

/**
 * Turns a [ProxyNode] back into a shareable link — the inverse of
 * [ProxyLinkParser]. Formats match what NekoBox/v2rayN emit so links round-trip
 * between clients.
 */
object ProxyLinkExporter {

    fun export(node: ProxyNode): String = when (node.type) {
        ProxyType.VLESS -> exportUriStyle("vless", node, userInfo = node.uuid) {
            if (node.encryption.isNotBlank() && node.encryption != "none") put("encryption", node.encryption)
            if (node.flow.isNotBlank()) put("flow", node.flow)
            putStream(node)
        }
        ProxyType.TROJAN -> exportUriStyle("trojan", node, userInfo = enc(node.password)) {
            putStream(node)
        }
        ProxyType.VMESS -> exportVmess(node)
        ProxyType.SHADOWSOCKS -> exportShadowsocks(node)
        ProxyType.HYSTERIA2 -> exportUriStyle("hysteria2", node, userInfo = enc(node.password)) {
            if (node.tls.sni.isNotBlank()) put("sni", node.tls.sni)
            if (node.tls.alpn.isNotEmpty()) put("alpn", node.tls.alpn.joinToString(","))
            if (node.tls.allowInsecure) put("insecure", "1")
            if (node.obfs.isNotBlank()) {
                put("obfs", node.obfs)
                put("obfs-password", node.obfsPassword)
            }
        }
        ProxyType.SOCKS -> exportUriStyle(
            "socks",
            node,
            userInfo = if (node.username.isNotBlank()) "${enc(node.username)}:${enc(node.password)}" else "",
        ) {}
        ProxyType.HTTP -> exportUriStyle(
            if (node.tls.enabled) "https" else "http",
            node,
            userInfo = if (node.username.isNotBlank()) "${enc(node.username)}:${enc(node.password)}" else "",
        ) {
            if (node.tls.sni.isNotBlank()) put("sni", node.tls.sni)
        }
        ProxyType.TUIC -> exportUriStyle("tuic", node, userInfo = "${node.uuid}:${enc(node.password)}") {
            put("congestion_control", node.congestionControl)
            put("udp_relay_mode", node.udpRelayMode)
            if (node.tls.sni.isNotBlank()) put("sni", node.tls.sni)
            if (node.tls.alpn.isNotEmpty()) put("alpn", node.tls.alpn.joinToString(","))
            if (node.tls.allowInsecure) put("allow_insecure", "1")
            if (node.tls.disableSni) put("disable_sni", "1")
        }
        ProxyType.WIREGUARD -> exportUriStyle("wireguard", node, userInfo = enc(node.wgPrivateKey)) {
            put("publickey", node.wgPeerPublicKey)
            if (node.wgPreSharedKey.isNotBlank()) put("presharedkey", node.wgPreSharedKey)
            if (node.wgLocalAddress.isNotEmpty()) put("address", node.wgLocalAddress.joinToString(","))
            put("mtu", node.wgMtu.toString())
            if (node.wgReserved.isNotBlank()) put("reserved", node.wgReserved)
        }
    }

    // ---- helpers ----

    private fun exportUriStyle(
        scheme: String,
        node: ProxyNode,
        userInfo: String,
        query: LinkedHashMap<String, String>.() -> Unit,
    ): String {
        val q = LinkedHashMap<String, String>().apply(query)
        return buildString {
            append(scheme).append("://")
            if (userInfo.isNotBlank()) append(userInfo).append('@')
            append(hostForUri(node.server)).append(':').append(node.port)
            if (q.isNotEmpty()) {
                append('?')
                append(q.entries.joinToString("&") { (k, v) -> "$k=${enc(v)}" })
            }
            if (node.name.isNotBlank()) append('#').append(enc(node.name))
        }
    }

    /** Shared v2ray-style stream params (VLESS/Trojan). */
    private fun LinkedHashMap<String, String>.putStream(node: ProxyNode) {
        val tr = node.transport
        put("type", tr.network)
        if (tr.host.isNotBlank()) put("host", tr.host)
        if (tr.path.isNotBlank()) put("path", tr.path)
        if (tr.network == "grpc" && tr.grpcServiceName.isNotBlank()) put("serviceName", tr.grpcServiceName)
        if (tr.headerType != "none" && tr.headerType.isNotBlank()) put("headerType", tr.headerType)
        val t = node.tls
        put("security", t.security)
        if (t.sni.isNotBlank()) put("sni", t.sni)
        if (t.alpn.isNotEmpty()) put("alpn", t.alpn.joinToString(","))
        if (t.fingerprint.isNotBlank()) put("fp", t.fingerprint)
        if (t.allowInsecure) put("allowInsecure", "1")
        if (t.isReality) {
            put("pbk", t.realityPublicKey)
            if (t.realityShortId.isNotBlank()) put("sid", t.realityShortId)
        }
    }

    private fun exportVmess(node: ProxyNode): String {
        // v2rayN base64-JSON format (the one parseVmess reads back).
        val tlsOn = node.tls.enabled
        val json = buildString {
            append('{')
            append("\"v\":\"2\",")
            append("\"ps\":${jsonStr(node.name)},")
            append("\"add\":${jsonStr(node.server)},")
            append("\"port\":\"${node.port}\",")
            append("\"id\":${jsonStr(node.uuid)},")
            append("\"aid\":\"${node.alterId}\",")
            append("\"scy\":${jsonStr(node.encryption.ifBlank { "auto" })},")
            append("\"net\":${jsonStr(node.transport.network)},")
            append("\"type\":${jsonStr(node.transport.headerType.ifBlank { "none" })},")
            append("\"host\":${jsonStr(node.transport.host)},")
            append("\"path\":${jsonStr(if (node.transport.network == "grpc") node.transport.grpcServiceName else node.transport.path)},")
            append("\"tls\":${jsonStr(if (tlsOn) "tls" else "")},")
            append("\"sni\":${jsonStr(node.tls.sni)},")
            append("\"alpn\":${jsonStr(node.tls.alpn.joinToString(","))},")
            append("\"fp\":${jsonStr(node.tls.fingerprint)}")
            append('}')
        }
        return "vmess://" + Base64.getEncoder().withoutPadding()
            .encodeToString(json.toByteArray(StandardCharsets.UTF_8))
    }

    private fun exportShadowsocks(node: ProxyNode): String {
        // SIP002: ss://base64(method:password)@host:port[?plugin=...]#name
        val userInfo = Base64.getUrlEncoder().withoutPadding()
            .encodeToString("${node.encryption}:${node.password}".toByteArray(StandardCharsets.UTF_8))
        return buildString {
            append("ss://").append(userInfo).append('@')
            append(hostForUri(node.server)).append(':').append(node.port)
            if (node.plugin.isNotBlank()) {
                val pluginValue = if (node.pluginOpts.isNotBlank()) "${node.plugin};${node.pluginOpts}" else node.plugin
                append("?plugin=").append(enc(pluginValue))
            }
            if (node.name.isNotBlank()) append('#').append(enc(node.name))
        }
    }

    private fun hostForUri(host: String): String =
        if (host.contains(':') && !host.startsWith("[")) "[$host]" else host

    private fun enc(s: String): String =
        URLEncoder.encode(s, StandardCharsets.UTF_8.name()).replace("+", "%20")

    private fun jsonStr(s: String): String = buildString {
        append('"')
        for (c in s) {
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                // Control chars and lone UTF-16 surrogates are invalid in raw JSON.
                else -> if (c.code < 0x20 || c.code in 0xD800..0xDFFF) {
                    append("\\u%04x".format(c.code))
                } else {
                    append(c)
                }
            }
        }
        append('"')
    }
}
