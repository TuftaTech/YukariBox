package dev.yukaribox.vpn.proxy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Hostile-input cases for the share-link parser. Every link here is something a
 * subscription server (or a pasted string) can deliver, and each one used to weaken the
 * resulting node's security without any sign in the UI.
 */
class LinkSecurityTest {

    // ---- TLS downgrade ----

    @Test
    fun mangledTlsValueDoesNotSilentlyDisableTls() {
        // "tls " / "tls%20" comes from double-encoded subscriptions; it used to fall
        // through to "none", i.e. an outbound with no TLS block at all.
        val node = ProxyLinkParser.parse("vless://uuid-1@example.com:443?security=tls%20&type=tcp#n")
        assertTrue(node.tls.enabled)
        assertEquals("tls", node.tls.security)
    }

    @Test
    fun unknownSecurityValueFailsSecure() {
        val node = ProxyLinkParser.parse("vless://uuid-1@example.com:443?security=tls13#n")
        assertTrue(node.tls.enabled)
    }

    @Test
    fun explicitNoneIsStillHonouredForVless() {
        // A deliberate plaintext VLESS (behind a CDN, say) must keep working.
        val node = ProxyLinkParser.parse("vless://uuid-1@example.com:80?security=none&type=ws#n")
        assertFalse(node.tls.enabled)
        assertTrue(node.isPlaintext)
    }

    @Test
    fun trojanAlwaysGetsTlsEvenWhenTheLinkSaysNone() {
        // sing-box's trojan outbound without a tls block puts the password on the wire.
        val node = ProxyLinkParser.parse("trojan://secret@example.com:443?security=none#n")
        assertTrue(node.tls.enabled)
        assertFalse(node.isPlaintext)
    }

    @Test
    fun realityIsPreserved() {
        val node = ProxyLinkParser.parse(
            "vless://uuid-1@example.com:443?security=reality&pbk=key&sid=ab&fp=chrome#n",
        )
        assertTrue(node.tls.isReality)
        assertEquals("key", node.tls.realityPublicKey)
    }

    @Test
    fun vmessTreatsAnyNonEmptyTlsFlagAsTls() {
        // v2rayN writes "tls", older exporters write "1"; anything non-empty means TLS.
        for (flag in listOf("tls", "1", "TLS", "reality")) {
            val json = """{"add":"example.com","port":"443","id":"uuid-1","tls":"$flag","ps":"n"}"""
            val link = "vmess://" + java.util.Base64.getEncoder().encodeToString(json.toByteArray())
            assertTrue(flag, ProxyLinkParser.parse(link).tls.enabled)
        }
    }

    @Test
    fun vmessWithoutTlsFlagStaysPlaintext() {
        val json = """{"add":"example.com","port":"80","id":"uuid-1","ps":"n"}"""
        val link = "vmess://" + java.util.Base64.getEncoder().encodeToString(json.toByteArray())
        assertFalse(ProxyLinkParser.parse(link).tls.enabled)
    }

    @Test
    fun vmessCipherIsNormalizedToSomethingTheCoreAccepts() {
        val json = """{"add":"example.com","port":"443","id":"uuid-1","scy":"rc4-md5","tls":"tls","ps":"n"}"""
        val link = "vmess://" + java.util.Base64.getEncoder().encodeToString(json.toByteArray())
        assertEquals("auto", ProxyLinkParser.parse(link).encryption)
    }

    // ---- host / URL confusion ----

    @Test
    fun aSubscriptionUrlIsNotAProxyNode() {
        // Pasting a subscription URL into the clipboard importer used to yield an HTTP
        // proxy node whose server field carried the whole path.
        assertNull(ProxyLinkParser.parseOrNull("https://example.com/sub/vless"))
        assertNull(ProxyLinkParser.parseOrNull("http://example.com/api/v1/token"))
    }

    @Test
    fun hostsWithWhitespaceOrQuotesAreRejected() {
        assertNull(ProxyLinkParser.parseOrNull("vless://uuid-1@exa mple.com:443#n"))
        assertNull(ProxyLinkParser.parseOrNull("""vless://uuid-1@exa"mple.com:443#n"""))
    }

    @Test
    fun plainHttpProxyLinkStillParses() {
        val node = ProxyLinkParser.parse("http://user:pass@proxy.example.com:8080#n")
        assertEquals(ProxyType.HTTP, node.type)
        assertEquals("proxy.example.com", node.server)
        assertEquals(8080, node.port)
    }

    // ---- credential decoding ----

    @Test
    fun plusInABase64PasswordSurvivesDecoding() {
        // URLDecoder maps '+' to a space, which corrupts SS-2022 and WireGuard keys.
        val node = ProxyLinkParser.parse("trojan://ab%2Bcd+ef@example.com:443#n")
        assertEquals("ab+cd+ef", node.password)
    }

    @Test
    fun sip002PlainUserinfoIsPercentDecoded() {
        val node = ProxyLinkParser.parse("ss://aes-256-gcm:p%40ss%2Bword@example.com:8388#n")
        assertEquals("p@ss+word", node.password)
    }

    // ---- Shadowsocks cipher policy ----

    @Test
    fun shadowsocksNoneIsRejectedWithoutAPlugin() {
        assertNull(ProxyLinkParser.parseOrNull("ss://none:pass@example.com:8388#n"))
    }

    @Test
    fun shadowsocksNoneIsAllowedBehindAPlugin() {
        val node = ProxyLinkParser.parse("ss://none:pass@example.com:8388?plugin=v2ray-plugin%3Btls#n")
        assertEquals("none", node.encryption)
        assertEquals("v2ray-plugin", node.plugin)
        assertFalse(node.isPlaintext)
    }

    @Test
    fun legacyStreamCiphersRemainRejected() {
        assertNull(ProxyLinkParser.parseOrNull("ss://aes-256-cfb:pass@example.com:8388#n"))
    }

    // ---- plaintext badge ----

    @Test
    fun protocolsWhoseEncryptionIsNotOptionalAreNeverFlagged() {
        // The badge must not cry wolf: WireGuard encrypts at the protocol level and
        // Hysteria2/TUIC are QUIC-only, so none of them can be plaintext.
        assertFalse(
            ProxyLinkParser.parse(
                "wireguard://pk@wg.example.com:51820?publickey=peer&address=10.0.0.2/32#WG",
            ).isPlaintext,
        )
        assertFalse(ProxyLinkParser.parse("hysteria2://auth@hy.example.com:443?sni=hy.example.com#H").isPlaintext)
        assertFalse(ProxyLinkParser.parse("tuic://uuid-x:pw@t.example.com:443#T").isPlaintext)
    }

    @Test
    fun aTlsWrappedNodeIsNotFlagged() {
        assertFalse(ProxyLinkParser.parse("vless://u@example.com:443?security=tls&sni=x#n").isPlaintext)
        assertFalse(ProxyLinkParser.parse("ss://aes-256-gcm:pw@example.com:8388#n").isPlaintext)
    }

    // ---- dedup identity ----

    @Test
    fun nodesThatDifferOnlyInSecurityDoNotDedupe() {
        // A feed listing the same server twice, once plaintext, must not let "remove
        // duplicates" keep the weaker twin.
        val reality = ProxyLinkParser.parse("vless://u@example.com:443?security=reality&pbk=k#a")
        val plain = ProxyLinkParser.parse("vless://u@example.com:443?security=none#a")
        assertNotEquals(reality.dedupKey, plain.dedupKey)
    }

    @Test
    fun identicalNodesStillDedupe() {
        val a = ProxyLinkParser.parse("vless://u@example.com:443?security=tls&sni=x#one")
        val b = ProxyLinkParser.parse("vless://u@example.com:443?security=tls&sni=x#two")
        assertEquals(a.dedupKey, b.dedupKey)
    }
}
