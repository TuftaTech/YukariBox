package dev.yukaribox.vpn.proxy

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Parses individual proxy share links into [ProxyNode]s. Supports VLESS
 * (Reality/Vision), VMess, Trojan, Shadowsocks (SIP002 + legacy) and Hysteria2,
 * with ws/grpc/http/httpupgrade/xhttp/quic transports and TLS/Reality settings.
 */
object ProxyLinkParser {

    private val lenientJson = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Parse a single share link. Throws [LinkParseException] on malformed input. */
    fun parse(link: String): ProxyNode {
        val trimmed = link.trim()
        val scheme = trimmed.substringBefore("://", "").lowercase()
        return try {
            when (scheme) {
                "vless" -> parseVless(body(trimmed))
                "vmess" -> parseVmess(body(trimmed))
                "trojan" -> parseTrojan(body(trimmed))
                "ss" -> parseShadowsocks(body(trimmed))
                "hysteria2", "hy2" -> parseHysteria2(body(trimmed))
                "socks", "socks4", "socks4a", "socks5" -> parseSocks(body(trimmed))
                "http", "https" -> parseHttp(body(trimmed), tlsOn = scheme == "https")
                "tuic" -> parseTuic(body(trimmed))
                "wireguard", "wg" -> parseWireGuard(body(trimmed))
                else -> throw LinkParseException("unsupported scheme: '$scheme'")
            }
        } catch (e: LinkParseException) {
            throw e
        } catch (e: Exception) {
            throw LinkParseException("failed to parse $scheme link: ${e.message}", e)
        }
    }

    /** Parse a link, returning null instead of throwing. */
    fun parseOrNull(link: String): ProxyNode? = try {
        parse(link)
    } catch (_: Exception) {
        null
    }

    private fun body(link: String) = link.substringAfter("://")

    // ---- VLESS ----
    private fun parseVless(body: String): ProxyNode {
        val p = LinkCodec.splitUri(body)
        if (p.userInfo.isBlank()) throw LinkParseException("vless: missing uuid")
        val (tls, transport) = buildStream(p.query, defaultSecurity = "none")
        return ProxyNode(
            type = ProxyType.VLESS,
            name = p.fragment,
            server = p.host,
            port = p.port,
            uuid = p.userInfo,
            encryption = p.query["encryption"] ?: "none",
            flow = p.query["flow"] ?: "",
            tls = tls,
            transport = transport,
        )
    }

    // ---- Trojan ----
    private fun parseTrojan(body: String): ProxyNode {
        val p = LinkCodec.splitUri(body)
        if (p.userInfo.isBlank()) throw LinkParseException("trojan: missing password")
        val (tls, transport) = buildStream(p.query, defaultSecurity = "tls")
        // Trojan is TLS-only by design: the protocol's whole point is looking like
        // ordinary HTTPS, and sing-box's trojan outbound without a tls block sends the
        // password in the clear. A link that asks for `security=none` is broken, not a
        // valid plaintext configuration, so TLS is re-asserted here.
        val trojanTls = if (tls.enabled) tls else tls.copy(security = "tls")
        return ProxyNode(
            type = ProxyType.TROJAN,
            name = p.fragment,
            server = p.host,
            port = p.port,
            password = LinkCodec.urlDecodeCredential(p.userInfo),
            tls = trojanTls,
            transport = transport,
        )
    }

    // ---- VMess (base64 JSON, v2rayN format) ----
    private fun parseVmess(body: String): ProxyNode {
        val jsonText = LinkCodec.base64DecodeToString(body.substringBefore("#"))
        val obj = lenientJson.parseToJsonElement(jsonText) as? JsonObject
            ?: throw LinkParseException("vmess: not a JSON object")
        fun str(vararg keys: String): String {
            for (k in keys) obj[k]?.jsonPrimitive?.content?.let { if (it.isNotEmpty()) return it }
            return ""
        }
        val server = str("add")
        val portStr = str("port")
        val net = normalizeNetwork(str("net").ifBlank { "tcp" })
        val headerType = str("type").ifBlank { "none" }
        val host = str("host")
        val path = str("path")
        // Any non-empty `tls` value means the sender asked for TLS; only an absent or
        // empty field means plaintext (see normalizeSecurity).
        val security = if (str("tls").isEmpty()) "none" else normalizeSecurity(str("tls"), "tls")
        val alpn = splitAlpn(str("alpn"))
        val transport = TransportSettings(
            network = net,
            host = host,
            path = path,
            grpcServiceName = if (net == "grpc") path else "",
            headerType = headerType,
        )
        val tls = TlsSettings(
            security = security,
            sni = str("sni").ifBlank { host },
            alpn = alpn,
            fingerprint = str("fp"),
        )
        return ProxyNode(
            type = ProxyType.VMESS,
            name = str("ps"),
            server = server,
            port = portStr.toIntOrNull() ?: throw LinkParseException("vmess: invalid port '$portStr'"),
            uuid = str("id"),
            alterId = str("aid").toIntOrNull() ?: 0,
            encryption = normalizeVmessCipher(str("scy")),
            tls = tls,
            transport = transport,
        )
    }

    // ---- Shadowsocks (SIP002 and legacy base64) ----
    private fun parseShadowsocks(body: String): ProxyNode {
        var fragment = ""
        var work = body
        val hashIdx = work.indexOf('#')
        if (hashIdx >= 0) {
            fragment = LinkCodec.urlDecode(work.substring(hashIdx + 1))
            work = work.substring(0, hashIdx)
        }
        var query = emptyMap<String, String>()
        val qIdx = work.indexOf('?')
        if (qIdx >= 0) {
            query = LinkCodec.parseQuery(work.substring(qIdx + 1))
            work = work.substring(0, qIdx)
        }

        val method: String
        val password: String
        val host: String
        val port: Int

        val atIdx = work.lastIndexOf('@')
        if (atIdx >= 0) {
            // SIP002: ss://base64(method:password)@host:port  (userinfo may also be plain)
            val userInfo = work.substring(0, atIdx)
            val hostPort = work.substring(atIdx + 1)
            val plain = userInfo.contains(':')
            val decodedUser = if (plain) userInfo else LinkCodec.base64DecodeToString(userInfo)
            val mp = decodedUser.split(':', limit = 2)
            if (mp.size != 2) throw LinkParseException("ss: malformed method:password")
            method = mp[0]
            // Plain (non-base64) userinfo is percent-encoded per SIP002; base64 userinfo
            // is not, so only the former is decoded.
            password = if (plain) LinkCodec.urlDecodeCredential(mp[1]) else mp[1]
            val hp = LinkCodec.splitHostPort(hostPort, -1)
            host = hp.first
            port = hp.second
        } else {
            // Legacy: ss://base64(method:password@host:port)
            val decoded = LinkCodec.base64DecodeToString(work)
            val at = decoded.lastIndexOf('@')
            if (at < 0) throw LinkParseException("ss: malformed legacy link")
            val mp = decoded.substring(0, at).split(':', limit = 2)
            if (mp.size != 2) throw LinkParseException("ss: malformed method:password")
            method = mp[0]
            password = mp[1]
            val hp = LinkCodec.splitHostPort(decoded.substring(at + 1), -1)
            host = hp.first
            port = hp.second
        }

        if (!ShadowsocksMethods.isSupported(method)) {
            throw LinkParseException("ss: unsupported method '$method' (AEAD/AEAD-2022 required)")
        }

        var plugin = ""
        var pluginOpts = ""
        query["plugin"]?.let { raw ->
            val parts = raw.split(';', limit = 2)
            plugin = parts[0]
            pluginOpts = if (parts.size > 1) parts[1] else ""
        }
        // `none` means the Shadowsocks layer adds no encryption at all. That is only a
        // sane configuration when a transport plugin (v2ray-plugin, obfs over TLS…)
        // provides the encryption instead. Accepting a bare `ss://none:…` from a
        // subscription would tunnel the user's traffic in clear text with nothing in the
        // UI to say so, so it is rejected here. The manual editor still allows it: that
        // is the user's own explicit choice, not an untrusted feed's.
        if (ShadowsocksMethods.requiresPlugin(method) && plugin.isBlank()) {
            throw LinkParseException("ss: method 'none' needs a transport plugin to be secure")
        }
        return ProxyNode(
            type = ProxyType.SHADOWSOCKS,
            name = fragment,
            server = host,
            port = port,
            password = password,
            encryption = ShadowsocksMethods.normalize(method),
            plugin = plugin,
            pluginOpts = pluginOpts,
        )
    }

    // ---- Hysteria2 ----
    private fun parseHysteria2(body: String): ProxyNode {
        val p = LinkCodec.splitUri(body, defaultPort = 443)
        val insecure = p.query["insecure"] == "1" || p.query["insecure"].equals("true", true)
        return ProxyNode(
            type = ProxyType.HYSTERIA2,
            name = p.fragment,
            server = p.host,
            port = p.port,
            password = LinkCodec.urlDecodeCredential(p.userInfo),
            obfs = p.query["obfs"] ?: "",
            obfsPassword = p.query["obfs-password"] ?: "",
            tls = TlsSettings(
                security = "tls",
                sni = p.query["sni"] ?: p.query["peer"] ?: "",
                alpn = splitAlpn(p.query["alpn"] ?: "h3"),
                allowInsecure = insecure,
            ),
        )
    }

    // ---- SOCKS ----
    private fun parseSocks(body: String): ProxyNode {
        val p = LinkCodec.splitUri(body, defaultPort = 1080)
        var user = ""
        var pass = ""
        if (p.userInfo.isNotBlank()) {
            if (p.userInfo.contains(':')) {
                val parts = p.userInfo.split(':', limit = 2)
                user = LinkCodec.urlDecode(parts[0])
                pass = LinkCodec.urlDecodeCredential(parts[1])
            } else {
                // v2rayN encodes userinfo (or the whole authority) as base64.
                val decoded = try {
                    LinkCodec.base64DecodeToString(p.userInfo)
                } catch (_: Exception) {
                    ""
                }
                if (decoded.contains(':')) {
                    val parts = decoded.split(':', limit = 2)
                    user = parts[0]
                    pass = parts[1]
                }
            }
        }
        return ProxyNode(
            type = ProxyType.SOCKS,
            name = p.fragment,
            server = p.host,
            port = p.port,
            username = user,
            password = pass,
        )
    }

    // ---- HTTP(S) proxy ----
    private fun parseHttp(body: String, tlsOn: Boolean): ProxyNode {
        val p = LinkCodec.splitUri(body, defaultPort = if (tlsOn) 443 else 80)
        var user = ""
        var pass = ""
        if (p.userInfo.isNotBlank() && p.userInfo.contains(':')) {
            val parts = p.userInfo.split(':', limit = 2)
            user = LinkCodec.urlDecode(parts[0])
            pass = LinkCodec.urlDecodeCredential(parts[1])
        } else if (p.userInfo.isNotBlank()) {
            user = LinkCodec.urlDecode(p.userInfo)
        }
        return ProxyNode(
            type = ProxyType.HTTP,
            name = p.fragment,
            server = p.host,
            port = p.port,
            username = user,
            password = pass,
            tls = if (tlsOn) TlsSettings(security = "tls", sni = p.query["sni"] ?: "") else TlsSettings(),
        )
    }

    // ---- TUIC v5 ----
    private fun parseTuic(body: String): ProxyNode {
        val p = LinkCodec.splitUri(body, defaultPort = 443)
        if (p.userInfo.isBlank()) throw LinkParseException("tuic: missing uuid")
        val uuid: String
        val token: String
        if (p.userInfo.contains(':')) {
            val parts = p.userInfo.split(':', limit = 2)
            uuid = parts[0]
            token = LinkCodec.urlDecodeCredential(parts[1])
        } else {
            uuid = p.userInfo
            token = ""
        }
        val insecure = p.query["allow_insecure"] == "1" || p.query["insecure"] == "1" ||
            p.query["allow_insecure"].equals("true", true)
        return ProxyNode(
            type = ProxyType.TUIC,
            name = p.fragment,
            server = p.host,
            port = p.port,
            uuid = uuid,
            password = token,
            congestionControl = p.query["congestion_control"] ?: "bbr",
            udpRelayMode = p.query["udp_relay_mode"] ?: "native",
            tls = TlsSettings(
                security = "tls",
                sni = p.query["sni"] ?: "",
                alpn = splitAlpn(p.query["alpn"] ?: ""),
                allowInsecure = insecure,
                disableSni = p.query["disable_sni"] == "1",
            ),
        )
    }

    // ---- WireGuard (v2rayN-style share link) ----
    private fun parseWireGuard(body: String): ProxyNode {
        val p = LinkCodec.splitUri(body, defaultPort = 51820)
        if (p.userInfo.isBlank()) throw LinkParseException("wireguard: missing private key")
        val addresses = (p.query["address"] ?: p.query["ip"] ?: "")
            .split(',').map { it.trim() }.filter { it.isNotEmpty() }
        return ProxyNode(
            type = ProxyType.WIREGUARD,
            name = p.fragment,
            server = p.host,
            port = p.port,
            wgPrivateKey = LinkCodec.urlDecodeCredential(p.userInfo),
            wgPeerPublicKey = p.query["publickey"] ?: p.query["pubkey"] ?: "",
            wgPreSharedKey = p.query["presharedkey"] ?: p.query["psk"] ?: "",
            wgLocalAddress = addresses,
            wgMtu = p.query["mtu"]?.toIntOrNull() ?: 1420,
            wgReserved = p.query["reserved"] ?: "",
        )
    }

    // ---- shared v2ray-style stream (VLESS/Trojan) ----
    private fun buildStream(query: Map<String, String>, defaultSecurity: String): Pair<TlsSettings, TransportSettings> {
        val network = normalizeNetwork(query["type"] ?: "tcp")
        val security = normalizeSecurity(query["security"], defaultSecurity)
        val host = query["host"] ?: ""
        val path = query["path"] ?: ""
        val serviceName = query["serviceName"] ?: query["servicename"] ?: ""
        val transport = TransportSettings(
            network = network,
            host = host,
            path = path,
            grpcServiceName = if (network == "grpc") serviceName.ifBlank { path } else "",
            headerType = query["headerType"] ?: "none",
        )
        val allowInsecure = query["allowInsecure"] == "1" || query["insecure"] == "1" ||
            query["allowInsecure"].equals("true", true)
        val tls = TlsSettings(
            security = security,
            sni = query["sni"] ?: query["peer"] ?: "",
            alpn = splitAlpn(query["alpn"] ?: ""),
            fingerprint = query["fp"] ?: "",
            allowInsecure = allowInsecure,
            realityPublicKey = query["pbk"] ?: "",
            realityShortId = query["sid"] ?: "",
        )
        return tls to transport
    }

    /**
     * Map a link's `security=` value onto the three states the config builder knows.
     *
     * Unrecognised values used to fall through to "none", i.e. no TLS block at all: a
     * value as ordinary as `tls ` (trailing space, or `%20` from a double-encoded
     * subscription) silently produced a plaintext outbound, and for Trojan that puts the
     * password on the wire in the clear. An unknown value is never a deliberate request
     * for plaintext — that is spelled `none` — so it now resolves to TLS. If the guess is
     * wrong the handshake fails and the tunnel fails closed, which is the safe direction.
     */
    private fun normalizeSecurity(raw: String?, defaultSecurity: String): String {
        val value = raw?.trim()?.lowercase()
        return when {
            value.isNullOrEmpty() -> defaultSecurity
            value == "reality" -> "reality"
            value == "tls" || value == "xtls" -> "tls"
            value == "none" -> "none"
            else -> "tls"
        }
    }

    /**
     * VMess ciphers the core accepts. An unrecognised value used to be passed straight
     * through, where the core rejects the whole config and the tunnel never starts;
     * "auto" is the safe interpretation. `none`/`zero` stay available because they are a
     * legitimate choice when TLS carries the encryption.
     */
    private fun normalizeVmessCipher(raw: String): String {
        val value = raw.trim().lowercase()
        return if (value in VMESS_CIPHERS) value else "auto"
    }

    private val VMESS_CIPHERS = setOf(
        "auto", "none", "zero", "aes-128-gcm", "aes-128-cfb", "chacha20-poly1305",
    )

    private fun normalizeNetwork(raw: String): String = when (raw.lowercase()) {
        "", "tcp", "raw", "none" -> "tcp"
        "h2", "http" -> "http"
        "ws", "websocket" -> "ws"
        "grpc", "gun" -> "grpc"
        "httpupgrade" -> "httpupgrade"
        "xhttp", "splithttp" -> "xhttp"
        "quic" -> "quic"
        else -> raw.lowercase()
    }

    private fun splitAlpn(raw: String): List<String> =
        raw.split(',')
            // Some subscriptions double-encode alpn (e.g. "http%252f1.1"); decode
            // residual percent-escapes so we emit a valid "http/1.1". Idempotent
            // for already-decoded values.
            .map { if (it.contains('%')) LinkCodec.urlDecode(it.trim()) else it.trim() }
            .filter { it.isNotEmpty() }
}
