package dev.yukaribox.vpn.proxy

import kotlinx.serialization.Serializable

/** Supported proxy protocols. */
@Serializable
enum class ProxyType { VLESS, VMESS, TROJAN, SHADOWSOCKS, HYSTERIA2, SOCKS, HTTP, WIREGUARD, TUIC }

/** TLS / Reality layer settings parsed from a share link. */
@Serializable
data class TlsSettings(
    /** "none" | "tls" | "reality" */
    val security: String = "none",
    val sni: String = "",
    val alpn: List<String> = emptyList(),
    /** uTLS fingerprint, e.g. "chrome". */
    val fingerprint: String = "",
    val allowInsecure: Boolean = false,
    /** TUIC: don't send SNI in the TLS handshake. */
    val disableSni: Boolean = false,
    /** Reality public key (pbk). */
    val realityPublicKey: String = "",
    /** Reality short id (sid). */
    val realityShortId: String = "",
) {
    val enabled: Boolean get() = security == "tls" || security == "reality"
    val isReality: Boolean get() = security == "reality"
}

/** Stream transport settings parsed from a share link. */
@Serializable
data class TransportSettings(
    /** "tcp" | "ws" | "grpc" | "http" | "httpupgrade" | "quic" | "xhttp" */
    val network: String = "tcp",
    val host: String = "",
    val path: String = "",
    val grpcServiceName: String = "",
    /** header type for raw/tcp, e.g. "http" or "none". */
    val headerType: String = "none",
)

/**
 * A single proxy node. A protocol-agnostic, fully-parsed representation that the
 * config builder turns into a sing-box outbound. Only fields relevant to [type]
 * are populated.
 */
@Serializable
data class ProxyNode(
    val type: ProxyType,
    val name: String,
    val server: String,
    val port: Int,
    // credentials
    val uuid: String = "",
    val password: String = "",
    /** SOCKS / HTTP proxy auth user. */
    val username: String = "",
    val alterId: Int = 0,
    /** VMess cipher / VLESS encryption / Shadowsocks method. */
    val encryption: String = "",
    /** VLESS flow, e.g. "xtls-rprx-vision". */
    val flow: String = "",
    // shadowsocks plugin
    val plugin: String = "",
    val pluginOpts: String = "",
    // hysteria2 obfuscation
    val obfs: String = "",
    val obfsPassword: String = "",
    // wireguard
    /** Local interface addresses, e.g. ["10.0.0.2/32"]. */
    val wgLocalAddress: List<String> = emptyList(),
    val wgPrivateKey: String = "",
    val wgPeerPublicKey: String = "",
    val wgPreSharedKey: String = "",
    val wgMtu: Int = 1420,
    /** Raw reserved bytes, "1,2,3" or base64 — converted at config time. */
    val wgReserved: String = "",
    // tuic
    /** "cubic" | "new_reno" | "bbr" */
    val congestionControl: String = "bbr",
    /** "native" | "quic" */
    val udpRelayMode: String = "native",
    val zeroRttHandshake: Boolean = false,
    val tls: TlsSettings = TlsSettings(),
    val transport: TransportSettings = TransportSettings(),
) {
    init {
        require(server.isNotBlank()) { "server is blank" }
        require(port in 1..65535) { "port out of range: $port" }
    }

    /** A stable display name, falling back to server:port when unnamed. */
    val displayName: String get() = name.ifBlank { "$server:$port" }

    /**
     * Connection identity used to detect duplicate nodes (name-independent, like
     * NekoBox's deduplication): two nodes that dial the same endpoint with the
     * same credentials *and the same security posture* are the same node regardless
     * of label.
     *
     * The security fields are part of the identity on purpose. While the key covered
     * only the endpoint and credentials, a feed could list the same server twice —
     * once with `security=reality`, once plaintext — and "remove duplicates" would keep
     * whichever came first, silently downgrading the user's node.
     */
    val dedupKey: String
        get() = listOf(
            type.name, server, port.toString(), uuid, password, username,
            encryption, flow, wgPrivateKey, wgPeerPublicKey,
            tls.security, tls.allowInsecure.toString(), tls.sni,
            transport.network, transport.path, transport.host,
        ).joinToString("|")

    /**
     * True when the outbound carries no TLS/Reality layer — and, for Shadowsocks, no
     * cipher either. Drives the "NO TLS" warning badge on the node row: a subscription
     * can hand out a plaintext node among TLS ones, and nothing else in the row would
     * distinguish it. Protocols whose encryption is not optional report false.
     */
    val isPlaintext: Boolean
        get() = when (type) {
            ProxyType.SHADOWSOCKS -> encryption == "none" && plugin.isBlank()
            ProxyType.WIREGUARD -> false // WireGuard encrypts at the protocol level
            ProxyType.HYSTERIA2, ProxyType.TUIC -> false // QUIC/TLS only
            else -> !tls.enabled
        }
}
