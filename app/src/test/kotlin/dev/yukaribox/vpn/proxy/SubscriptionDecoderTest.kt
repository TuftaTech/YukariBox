package dev.yukaribox.vpn.proxy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.util.Base64

class SubscriptionDecoderTest {

    private fun b64(s: String): String =
        Base64.getEncoder().encodeToString(s.toByteArray(StandardCharsets.UTF_8))

    private val links = listOf(
        "vless://uuid-1@host.net:8443?encryption=none&security=tls&type=ws&path=%2Fp#A",
        "trojan://pw@trojan.host:443?security=tls#B",
        "ss://${b64("aes-256-gcm:secret")}@ss.host:8388#C",
    )

    @Test
    fun decodesPlainNewlineList() {
        val nodes = SubscriptionDecoder.decode(links.joinToString("\n"))
        assertEquals(3, nodes.size)
        assertEquals(ProxyType.VLESS, nodes[0].type)
        assertEquals(ProxyType.TROJAN, nodes[1].type)
        assertEquals(ProxyType.SHADOWSOCKS, nodes[2].type)
    }

    @Test
    fun decodesBase64WrappedList() {
        val wrapped = b64(links.joinToString("\n"))
        val nodes = SubscriptionDecoder.decode(wrapped)
        assertEquals(3, nodes.size)
    }

    @Test
    fun skipsBlankAndCommentLines() {
        val content = "// comment\n\n${links[0]}\n   \n${links[1]}\n"
        val nodes = SubscriptionDecoder.decode(content)
        assertEquals(2, nodes.size)
    }

    @Test
    fun reportCountsFailures() {
        val content = "${links[0]}\nthis-is-garbage\nftp://nope:21\n${links[1]}"
        val report = SubscriptionDecoder.decodeReport(content)
        assertEquals(2, report.nodes.size)
        assertEquals(2, report.failedCount)
    }

    @Test
    fun emptyContentYieldsNothing() {
        assertTrue(SubscriptionDecoder.decode("   ").isEmpty())
    }

    // ---- an oversized feed is broken or hostile, not something to import whole ----

    private fun manyLinks(count: Int): String =
        (1..count).joinToString("\n") { "vless://uuid-$it@h$it.net:443?security=tls#N$it" }

    @Test
    fun nodeCountIsCappedSoAHugeFeedCannotWedgeTheApp() {
        val nodes = SubscriptionDecoder.decode(manyLinks(SubscriptionDecoder.MAX_NODES + 25))
        assertEquals(SubscriptionDecoder.MAX_NODES, nodes.size)
    }

    @Test
    fun theCapCountsTheExcessAsFailedRatherThanSilentlyDroppingIt() {
        val report = SubscriptionDecoder.decodeReport(manyLinks(SubscriptionDecoder.MAX_NODES + 25))
        assertEquals(SubscriptionDecoder.MAX_NODES, report.nodes.size)
        assertEquals(25, report.failedCount)
    }

    @Test
    fun aFeedUnderTheCapIsImportedWhole() {
        val nodes = SubscriptionDecoder.decode(manyLinks(SubscriptionDecoder.MAX_NODES))
        assertEquals(SubscriptionDecoder.MAX_NODES, nodes.size)
    }
}
