package dev.yukaribox.vpn.core

import java.security.SecureRandom
import java.util.Base64

/**
 * Credentials for the local mixed (SOCKS/HTTP) inbound served in proxy-only mode.
 *
 * Without them the inbound on `127.0.0.1:2080` accepts anyone: every app on the
 * device can route through the user's tunnel, spending their quota and attributing
 * its traffic — and its choice of destinations — to them. The listener is bound to
 * loopback, so this is on-device only, not reachable from the LAN.
 *
 * The credentials are generated once and then persisted, rather than rotated per
 * session. Rotation would protect against a leaked password, but the password
 * lives in app-private storage: an attacker who can read it has already defeated
 * the sandbox, and at that point rotation buys nothing. What rotation *does* cost
 * is the mode itself — proxy-only exists so a browser or shell can be pointed at
 * the port once, and a password that changes on every connect means reconfiguring
 * the client every time, which pushes users straight to the no-auth escape hatch.
 * Generated-and-kept closes the hole the audit found; rotation would mostly
 * relocate it.
 */
object ProxyAuth {

    /** Fixed username: the password carries the entropy, a guessable name costs nothing. */
    const val USER = "yukaribox"

    /** 18 random bytes → 24 base64url chars. Well past guessing over a local socket. */
    private const val PASSWORD_BYTES = 18

    private val random = SecureRandom()

    /**
     * A fresh URL-safe, unpadded password. URL-safe because the usual way to hand
     * these to a client is `http://user:pass@127.0.0.1:2080`, where `+` and `/`
     * would need escaping.
     */
    fun newPassword(): String {
        val bytes = ByteArray(PASSWORD_BYTES)
        random.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
}
