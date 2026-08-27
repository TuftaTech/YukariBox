package dev.yukaribox.vpn.core

import java.net.HttpURLConnection
import java.net.URL

/** A named DNS-over-HTTPS server the user can pick instead of typing a URL. */
data class DohPreset(val name: String, val url: String)

/**
 * Curated DoH presets (Appendix C). All endpoints are DNS-over-HTTPS so they
 * carry no plaintext DNS. Remote presets resolve proxied traffic; direct presets
 * (China-friendly) resolve bypassed traffic. IP-literal URLs are used where the
 * server's certificate covers the IP, which avoids a bootstrap-DNS chicken-and-egg.
 */
object DohPresets {

    val remote: List<DohPreset> = listOf(
        DohPreset("Cloudflare", "https://1.1.1.1/dns-query"),
        DohPreset("Google", "https://8.8.8.8/dns-query"),
        DohPreset("Quad9", "https://9.9.9.9/dns-query"),
        DohPreset("AdGuard", "https://dns.adguard-dns.com/dns-query"),
        DohPreset("Mullvad", "https://dns.mullvad.net/dns-query"),
    )

    val direct: List<DohPreset> = listOf(
        DohPreset("AliDNS", "https://223.5.5.5/dns-query"),
        DohPreset("DNSPod", "https://doh.pub/dns-query"),
    )

    /**
     * Any HTTP status proves the DoH host answered, so it is reachable. A bare GET
     * to a `/dns-query` endpoint (no query packet) typically returns 400, and HEAD
     * may yield 405 — both still mean the server is up. A non-positive code means
     * no response (connection/timeout failure).
     */
    fun isReachable(httpStatus: Int): Boolean = httpStatus > 0
}

/**
 * Validates a DoH preset's reachability before it is offered for selection
 * (US-009 AC2). Runs a short, body-less HTTPS probe; the caller drives it off the
 * main thread. Restricted to https so a preset can never downgrade to cleartext.
 */
object DohReachability {

    private const val TIMEOUT_MS = 5000

    /** True if [urlStr] is an https endpoint that answers an HTTP request in time. */
    fun validate(urlStr: String): Boolean = try {
        val url = URL(urlStr)
        if (url.protocol.lowercase() != "https") {
            false
        } else {
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                instanceFollowRedirects = false
                requestMethod = "HEAD"
            }
            try {
                DohPresets.isReachable(conn.responseCode)
            } finally {
                conn.disconnect()
            }
        }
    } catch (_: Exception) {
        false
    }
}
