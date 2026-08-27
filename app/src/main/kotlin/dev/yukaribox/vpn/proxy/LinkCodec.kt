package dev.yukaribox.vpn.proxy

import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/** Thrown when a share link / subscription entry cannot be parsed. */
class LinkParseException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Low-level decoding helpers shared by all protocol parsers: tolerant base64,
 * query-string parsing, and authority (userinfo@host:port) splitting with IPv6
 * support.
 */
internal object LinkCodec {

    /** Decode base64 that may be standard or URL-safe, with or without padding. */
    fun base64Decode(input: String): ByteArray {
        var s = input.trim().replace('-', '+').replace('_', '/').replace("\n", "").replace("\r", "")
        when (s.length % 4) {
            2 -> s += "=="
            3 -> s += "="
        }
        return java.util.Base64.getDecoder().decode(s)
    }

    fun base64DecodeToString(input: String): String =
        String(base64Decode(input), StandardCharsets.UTF_8)

    fun urlDecode(input: String): String =
        try {
            URLDecoder.decode(input, StandardCharsets.UTF_8.name())
        } catch (_: Exception) {
            input
        }

    /**
     * Percent-decode a *credential* (password, key, token).
     *
     * [urlDecode] goes through `URLDecoder`, which implements HTML form decoding and so
     * turns a literal `+` into a space. Shadowsocks-2022 keys and pre-shared keys are
     * base64, where `+` is a real character, so form decoding silently corrupts them
     * and the node then fails to authenticate for no visible reason. Percent escapes are
     * still decoded; `+` is preserved.
     */
    fun urlDecodeCredential(input: String): String =
        if (input.contains('+')) {
            urlDecode(input.replace("+", "%2B"))
        } else {
            urlDecode(input)
        }

    /** Parse a `k=v&k2=v2` query string into a map (first value wins, values URL-decoded). */
    fun parseQuery(query: String): Map<String, String> {
        if (query.isBlank()) return emptyMap()
        val out = LinkedHashMap<String, String>()
        for (pair in query.split('&')) {
            if (pair.isEmpty()) continue
            val idx = pair.indexOf('=')
            if (idx < 0) {
                out.putIfAbsent(urlDecode(pair), "")
            } else {
                val k = urlDecode(pair.substring(0, idx))
                val v = urlDecode(pair.substring(idx + 1))
                out.putIfAbsent(k, v)
            }
        }
        return out
    }

    /** Result of splitting `scheme://userinfo@host:port?query#fragment` (scheme already removed). */
    data class Parts(
        val userInfo: String,
        val host: String,
        val port: Int,
        val query: Map<String, String>,
        val fragment: String,
    )

    /**
     * Split a link body (everything after `scheme://`) into its components.
     * Handles IPv6 hosts in brackets and missing userinfo.
     */
    fun splitUri(body: String, defaultPort: Int = -1): Parts {
        var rest = body
        var fragment = ""
        val hashIdx = rest.indexOf('#')
        if (hashIdx >= 0) {
            fragment = urlDecode(rest.substring(hashIdx + 1))
            rest = rest.substring(0, hashIdx)
        }
        var query = emptyMap<String, String>()
        val qIdx = rest.indexOf('?')
        if (qIdx >= 0) {
            query = parseQuery(rest.substring(qIdx + 1))
            rest = rest.substring(0, qIdx)
        }
        var userInfo = ""
        val atIdx = rest.lastIndexOf('@')
        if (atIdx >= 0) {
            userInfo = rest.substring(0, atIdx)
            rest = rest.substring(atIdx + 1)
        }
        val (host, port) = splitHostPort(rest, defaultPort)
        return Parts(userInfo, host, port, query, fragment)
    }

    /** Split `host:port` honoring `[ipv6]:port` and bare-host (uses [defaultPort]). */
    fun splitHostPort(input: String, defaultPort: Int): Pair<String, Int> {
        val s = input.trim()
        if (s.startsWith("[")) {
            val close = s.indexOf(']')
            if (close < 0) throw LinkParseException("malformed IPv6 host: $s")
            val host = s.substring(1, close)
            val after = s.substring(close + 1)
            val port = if (after.startsWith(":")) after.substring(1).toIntOrThrow() else defaultPort
            return host.requireHost() to port
        }
        val colon = s.lastIndexOf(':')
        if (colon < 0) {
            if (defaultPort < 0) throw LinkParseException("missing port: $s")
            return s.requireHost() to defaultPort
        }
        val host = s.substring(0, colon)
        val port = s.substring(colon + 1).toIntOrThrow()
        return host.requireHost() to port
    }

    /**
     * Reject anything that is not a bare host. Share links never carry a path in the
     * authority (transport paths travel in the query as `path=`), so a `/` here means the
     * input was a URL rather than a link — most often a *subscription* URL pasted into
     * the clipboard importer, which used to be accepted as an HTTP-proxy node whose
     * server field contained the whole path. Whitespace and quotes are rejected for the
     * same reason: they only appear in mangled or hostile input, and a host is later
     * interpolated into log lines.
     */
    private fun String.requireHost(): String {
        if (isBlank()) throw LinkParseException("missing host")
        if (any { it in INVALID_HOST_CHARS || it.isWhitespace() }) {
            throw LinkParseException("invalid host: '$this'")
        }
        return this
    }

    /** Characters that can only appear in a host by mistake or by malice. */
    private val INVALID_HOST_CHARS = charArrayOf('/', '\\', '"', '\'')

    private fun String.toIntOrThrow(): Int =
        trim().toIntOrNull() ?: throw LinkParseException("invalid port: $this")
}
