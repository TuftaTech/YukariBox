package dev.yukaribox.vpn.data

import dev.yukaribox.vpn.proxy.ProxyLinkParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SubscriptionCodecTest {

    private fun sampleSub(id: String, vararg links: String): Subscription {
        val nodes = links.mapIndexed { i, link -> NodeEntry(i, ProxyLinkParser.parse(link), latencyMs = 100 + i) }
        return Subscription(
            id,
            "name-$id",
            "https://example/$id",
            updatedAt = 1700000000000L,
            nodes = nodes,
            selectedNodeId = 0,
        )
    }

    @Test
    fun roundTripsSubscriptions() {
        val subs = listOf(
            sampleSub(
                "a",
                "vless://u@h.net:443?encryption=none&security=reality&sni=h&pbk=K&flow=xtls-rprx-vision#A",
                "trojan://pw@t.host:443?security=tls&type=grpc&serviceName=svc#B",
            ),
            sampleSub("b", "ss://YWVzLTI1Ni1nY206cHc@ss.host:8388#C"),
        )
        val encoded = SubscriptionCodec.encode(subs)
        val decoded = SubscriptionCodec.decode(encoded)

        assertEquals(subs, decoded)
        assertEquals("h", decoded[0].nodes[0].node.tls.sni)
        assertEquals(101, decoded[0].nodes[1].latencyMs)
        assertEquals("grpc", decoded[0].nodes[1].node.transport.network)
    }

    @Test
    fun decodesEmptyAndBlank() {
        assertTrue(SubscriptionCodec.decode("").isEmpty())
        assertEquals("[]", SubscriptionCodec.encode(emptyList()))
    }
}
