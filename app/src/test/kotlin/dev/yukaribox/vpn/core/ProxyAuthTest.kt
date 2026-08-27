package dev.yukaribox.vpn.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Credentials for the proxy-only local inbound. */
class ProxyAuthTest {

    @Test
    fun passwordsAreUnique() {
        val generated = (1..200).map { ProxyAuth.newPassword() }.toSet()
        assertEquals(200, generated.size)
    }

    @Test
    fun passwordIsUrlSafeSoItCanGoInAProxyUrl() {
        // Clients are configured as http://user:pass@127.0.0.1:2080 — '+' and '/'
        // from standard base64 would have to be escaped, and '=' padding too.
        repeat(50) {
            val password = ProxyAuth.newPassword()
            assertTrue(password, password.all { it.isLetterOrDigit() || it == '-' || it == '_' })
            assertFalse(password, password.contains('='))
        }
    }

    @Test
    fun passwordHasEnoughEntropyToResistGuessing() {
        // 18 bytes -> 24 base64 chars, 144 bits.
        assertEquals(24, ProxyAuth.newPassword().length)
    }

    @Test
    fun userNameIsFixed() {
        assertEquals("yukaribox", ProxyAuth.USER)
    }
}
