package dev.yukaribox.vpn.core

/**
 * Sanity bounds for settings that reach the kernel or the core, applied where the
 * value is *consumed* rather than where it is typed (so the MTU text field stays
 * editable keystroke by keystroke) and once more when a settings file is read.
 *
 * This exists because an out-of-range MTU is not a cosmetic problem: Android's
 * `VpnService.Builder.setMtu` rejects a non-positive value with an
 * IllegalArgumentException, which fails `establishTun` — including the *blocking*
 * fail-closed TUN. A single bad number would therefore turn the kill switch into
 * a no-op while the notification still claimed traffic was blocked. Values reach
 * [SettingsData] from three places that do not all validate: the settings screen,
 * a restored backup file, and a hand-edited `settings.json`.
 *
 * Pure and Android-free on purpose so the bounds are unit-tested.
 */
object SettingsGuard {

    /** Smallest MTU worth offering: IPv6's minimum link MTU is 1280, IPv4's is 576. */
    const val MTU_MIN = 576

    /** Matches the upper bound the debug adb surface has always enforced. */
    const val MTU_MAX = 9000

    /** Clamp an MTU to a value `VpnService.Builder` and the core both accept. */
    fun mtu(value: Int): Int = value.coerceIn(MTU_MIN, MTU_MAX)

    /** Longest nickname worth storing. */
    const val NICKNAME_MAX = 32

    /**
     * Normalize a user nickname. Empty means "not set", and the built-in persona shows.
     *
     * Three rules, and each closes something a hand-edited `settings.json` or a foreign
     * backup could otherwise plant:
     *
     * - **Control characters go.** A newline is the sharp one: `SettingsStore` logs which
     *   keys changed, and `Logs.emit` collapses newlines in a message body precisely
     *   because a name carrying one can forge a line that looks like the app's own.
     * - **Bidi formatting goes.** U+202E reverses every glyph drawn after it, so a nickname
     *   ending in one would reverse the counts line beside it on both surfaces that draw
     *   the pair.
     * - **The length is capped by code point, not by char.** Cutting a UTF-16 string at 32
     *   `Char`s splits a surrogate pair whenever the 32nd is an emoji, and an unpaired
     *   surrogate draws as a replacement box.
     *
     * What is deliberately *not* stripped is the regional-indicator flag emoji. Node names
     * lose it (`NodeGeo.plainName`) because a subscription feed must not inject colour into
     * a monochrome interface; a nickname is the user's own content, which §1 of the design
     * system exempts, so it stays exactly as typed.
     */
    fun nickname(value: String): String {
        val cleaned = value.filterNot { it.isISOControl() || it.code in BIDI_CONTROLS }.trim()
        if (cleaned.isEmpty()) return ""
        val points = cleaned.codePointCount(0, cleaned.length)
        if (points <= NICKNAME_MAX) return cleaned
        return cleaned.substring(0, cleaned.offsetByCodePoints(0, NICKNAME_MAX))
    }

    /**
     * Normalize a whole settings snapshot. Applied when settings are loaded from
     * disk and when a backup is restored, so a hostile or corrupt file cannot
     * plant a value that breaks the tunnel or the kill switch.
     */
    fun sanitize(data: SettingsData): SettingsData = data.copy(
        nickname = nickname(data.nickname),
        mtu = mtu(data.mtu),
        autoUpdateInterval = data.autoUpdateInterval.coerceAtLeast(MIN_UPDATE_MINUTES),
        logLevel = data.logLevel.takeIf { it.lowercase() in Logs.ORDER } ?: DEFAULT_LOG_LEVEL,
        remoteDns = dnsAddress(data.remoteDns, DEFAULTS.remoteDns),
        directDns = dnsAddress(data.directDns, DEFAULTS.directDns),
    )

    /**
     * [sanitize] plus the fields that must not survive a transfer between devices.
     *
     * The proxy-only inbound password is dropped: a backup authored by someone else
     * carries a password *they* know, and importing it would reopen the hole the
     * credential exists to close — any app on the device could then use the tunnel
     * with a password from the file. A blank value regenerates on next use, so the
     * only cost is reconfiguring a local proxy client after a restore.
     */
    fun sanitizeRestored(data: SettingsData, current: SettingsData): SettingsData =
        sanitize(data).copy(
            proxyPassword = "",
            // The shape of the protection is this device's, never the file's.
            serviceMode = current.serviceMode,
            perAppProxyInclude = current.perAppProxyInclude,
            perAppPackages = current.perAppPackages,
            bypassLan = current.bypassLan,
            allowInsecure = current.allowInsecure,
            // Stricter than the load path: see [dnsAddressRestored].
            remoteDns = dnsAddressRestored(data.remoteDns, current.remoteDns),
            directDns = dnsAddressRestored(data.directDns, current.directDns),
        )

    /**
     * The restore-path DNS rule: an address arriving in a file has to name an *encrypted*
     * transport, or it is not accepted at all and [fallback] (this device's current value)
     * stands.
     *
     * [dnsAddress] rejects only `http://`, which leaves `udp://`, `tcp://` and a bare IP --
     * every one of them plaintext DNS to a host the file chose. It matters more here than
     * anywhere else because the unconditional `outbound: any -> dns-direct` rule sends the
     * *proxy server's own hostname* through the direct server, so a planted value watches the
     * one lookup that identifies which server the user is about to dial, and can answer it.
     * A value the user typed stays their own choice; a value from a file is not a choice they
     * made. Every preset this app can produce is `https://`, so nothing it writes is lost.
     */
    fun dnsAddressRestored(value: String, fallback: String): String {
        val address = value.trim()
        val scheme = address.substringBefore("://", "").lowercase()
        return if (scheme in ENCRYPTED_DNS_SCHEMES) address else fallback
    }

    /** DNS transports that carry no plaintext query. */
    private val ENCRYPTED_DNS_SCHEMES = setOf("https", "tls", "quic", "h3")

    /**
     * Reject a DNS address that would send queries in clear text.
     *
     * The DNS fields go straight into the sing-box `dns.servers` block, which the Android
     * network-security config does not govern — the core dials its own sockets. An
     * `http://` DoH URL is therefore plaintext DNS to an arbitrary host: exactly what a
     * tampered backup file would set to watch every domain the user resolves. Bare IPs
     * and the other schemes the core understands are left alone, since a plain resolver
     * is a legitimate (if less private) user choice.
     */
    fun dnsAddress(value: String, fallback: String): String {
        val address = value.trim()
        if (address.isEmpty()) return fallback
        return if (address.substringBefore("://", "").lowercase() == "http") fallback else address
    }

    /** Auto-update floor, mirroring the coercion in NodeRepository.maybeAutoUpdate. */
    private const val MIN_UPDATE_MINUTES = 15

    private const val DEFAULT_LOG_LEVEL = "info"

    // The level vocabulary lives in Logs.ORDER; see its KDoc for why there is one copy.

    /**
     * Unicode bidi formatting: LRM/RLM plus the embedding, override and isolate ranges.
     * `Char.isISOControl` does not cover these — they are printable-category characters
     * whose only effect is on the order everything after them is drawn.
     */
    private val BIDI_CONTROLS =
        (setOf(0x200E, 0x200F) + (0x202A..0x202E) + (0x2066..0x2069)).toSet()

    /** The declared defaults, used as the fallback for a rejected value. */
    private val DEFAULTS = SettingsData()
}
