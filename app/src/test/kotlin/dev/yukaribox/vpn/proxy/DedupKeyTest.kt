package dev.yukaribox.vpn.proxy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class DedupKeyTest {

    private fun vless(server: String, port: Int, uuid: String, name: String) =
        ProxyNode(type = ProxyType.VLESS, name = name, server = server, port = port, uuid = uuid)

    @Test
    fun sameEndpointDifferentNameDedupesEqual() {
        val a = vless("h.net", 443, "uuid-1", "Tokyo #1")
        val b = vless("h.net", 443, "uuid-1", "東京 fast")
        assertEquals(a.dedupKey, b.dedupKey)
    }

    @Test
    fun differentCredentialsDoNotDedupe() {
        val a = vless("h.net", 443, "uuid-1", "x")
        val b = vless("h.net", 443, "uuid-2", "x")
        assertNotEquals(a.dedupKey, b.dedupKey)
    }

    @Test
    fun differentPortDoesNotDedupe() {
        val a = vless("h.net", 443, "uuid-1", "x")
        val b = vless("h.net", 8443, "uuid-1", "x")
        assertNotEquals(a.dedupKey, b.dedupKey)
    }

    @Test
    fun differentProtocolDoesNotDedupe() {
        val a = vless("h.net", 443, "uuid-1", "x")
        val b = ProxyNode(type = ProxyType.TROJAN, name = "x", server = "h.net", port = 443, password = "uuid-1")
        assertNotEquals(a.dedupKey, b.dedupKey)
    }
}
