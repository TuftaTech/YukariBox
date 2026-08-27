package dev.yukaribox.vpn.proxy

/**
 * Shadowsocks encryption methods accepted by the bundled sing-box core
 * (AEAD + AEAD-2022 + `none`). Legacy stream ciphers (`aes-*-cfb`, `rc4-md5`,
 * `chacha20`, …) are rejected by the core at runtime anyway; validating at the
 * parse/edit boundary keeps them out of stored profiles and gives the user an
 * immediate error instead of a silent connect failure.
 */
object ShadowsocksMethods {

    val SUPPORTED: List<String> = listOf(
        "2022-blake3-aes-128-gcm",
        "2022-blake3-aes-256-gcm",
        "2022-blake3-chacha20-poly1305",
        "aes-128-gcm",
        "aes-192-gcm",
        "aes-256-gcm",
        "chacha20-ietf-poly1305",
        "xchacha20-ietf-poly1305",
        "none",
    )

    const val DEFAULT = "aes-256-gcm"

    /** Method that provides no encryption of its own; only safe behind a plugin. */
    private const val NO_ENCRYPTION = "none"

    fun isSupported(method: String): Boolean = method.trim().lowercase() in SUPPORTED

    /**
     * True for methods that leave the payload unencrypted, so a transport plugin has to
     * supply the encryption. Enforced on the *parser* path (untrusted subscriptions and
     * share links) rather than in the manual editor, where selecting it is the user's own
     * deliberate choice.
     */
    fun requiresPlugin(method: String): Boolean = normalize(method) == NO_ENCRYPTION

    /** Canonical (lowercase, trimmed) form for storage/config. */
    fun normalize(method: String): String = method.trim().lowercase()
}
