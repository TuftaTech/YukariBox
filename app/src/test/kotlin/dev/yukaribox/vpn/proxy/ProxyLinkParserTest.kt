package dev.yukaribox.vpn.proxy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.util.Base64

class ProxyLinkParserTest {

    private fun b64(s: String): String =
        Base64.getEncoder().encodeToString(s.toByteArray(StandardCharsets.UTF_8))

    @Test
    fun parsesVlessRealityVision() {
        val link = "vless://b831ebc3-2c4a-4a1f-8b0c-1234567890ab@example.com:443" +
            "?encryption=none&security=reality&sni=www.microsoft.com&fp=chrome" +
            "&pbk=PUBKEYxyz&sid=0123abcd&flow=xtls-rprx-vision&type=tcp#Reality%20Node"
        val n = ProxyLinkParser.parse(link)

        assertEquals(ProxyType.VLESS, n.type)
        assertEquals("Reality Node", n.name)
        assertEquals("example.com", n.server)
        assertEquals(443, n.port)
        assertEquals("b831ebc3-2c4a-4a1f-8b0c-1234567890ab", n.uuid)
        assertEquals("xtls-rprx-vision", n.flow)
        assertEquals("reality", n.tls.security)
        assertTrue(n.tls.isReality)
        assertEquals("www.microsoft.com", n.tls.sni)
        assertEquals("chrome", n.tls.fingerprint)
        assertEquals("PUBKEYxyz", n.tls.realityPublicKey)
        assertEquals("0123abcd", n.tls.realityShortId)
        assertEquals("tcp", n.transport.network)
    }

    @Test
    fun parsesVlessWebsocketTls() {
        val link = "vless://uuid-1@host.net:8443?encryption=none&security=tls" +
            "&type=ws&path=%2Fwspath&host=cdn.host.net&sni=cdn.host.net#WS"
        val n = ProxyLinkParser.parse(link)

        assertEquals("ws", n.transport.network)
        assertEquals("/wspath", n.transport.path)
        assertEquals("cdn.host.net", n.transport.host)
        assertEquals("tls", n.tls.security)
        assertEquals("cdn.host.net", n.tls.sni)
        assertFalse(n.tls.isReality)
    }

    @Test
    fun parsesVmessBase64Json() {
        val json = """{"v":"2","ps":"VM Node","add":"1.2.3.4","port":"443","id":"uuid-vm",
            |"aid":"0","scy":"auto","net":"ws","type":"none","host":"vm.host","path":"/vmpath",
            |"tls":"tls","sni":"vm.host"}""".trimMargin().replace("\n", "")
        val n = ProxyLinkParser.parse("vmess://" + b64(json))

        assertEquals(ProxyType.VMESS, n.type)
        assertEquals("VM Node", n.name)
        assertEquals("1.2.3.4", n.server)
        assertEquals(443, n.port)
        assertEquals("uuid-vm", n.uuid)
        assertEquals(0, n.alterId)
        assertEquals("auto", n.encryption)
        assertEquals("ws", n.transport.network)
        assertEquals("/vmpath", n.transport.path)
        assertEquals("tls", n.tls.security)
        assertEquals("vm.host", n.tls.sni)
    }

    @Test
    fun parsesTrojanGrpc() {
        val link = "trojan://pass%40word@trojan.host:443" +
            "?security=tls&type=grpc&serviceName=grpcsvc&sni=trojan.host#Trojan"
        val n = ProxyLinkParser.parse(link)

        assertEquals(ProxyType.TROJAN, n.type)
        assertEquals("pass@word", n.password)
        assertEquals("grpc", n.transport.network)
        assertEquals("grpcsvc", n.transport.grpcServiceName)
        assertEquals("tls", n.tls.security)
    }

    @Test
    fun parsesShadowsocksSip002() {
        val userInfo = b64("aes-256-gcm:secretpass")
        val n = ProxyLinkParser.parse("ss://$userInfo@ss.host:8388#SS")

        assertEquals(ProxyType.SHADOWSOCKS, n.type)
        assertEquals("aes-256-gcm", n.encryption)
        assertEquals("secretpass", n.password)
        assertEquals("ss.host", n.server)
        assertEquals(8388, n.port)
    }

    @Test
    fun parsesShadowsocksLegacyBase64() {
        val whole = b64("chacha20-ietf-poly1305:pw123@ss.legacy:8389")
        val n = ProxyLinkParser.parse("ss://$whole#Legacy")

        assertEquals("chacha20-ietf-poly1305", n.encryption)
        assertEquals("pw123", n.password)
        assertEquals("ss.legacy", n.server)
        assertEquals(8389, n.port)
    }

    @Test
    fun parsesShadowsocks2022Method() {
        val userInfo = b64("2022-blake3-aes-256-gcm:psk")
        val n = ProxyLinkParser.parse("ss://$userInfo@ss.host:8388#SS2022")
        assertEquals("2022-blake3-aes-256-gcm", n.encryption)
    }

    @Test
    fun normalizesShadowsocksMethodCase() {
        val userInfo = b64("AES-256-GCM:pw")
        val n = ProxyLinkParser.parse("ss://$userInfo@ss.host:8388#SS")
        assertEquals("aes-256-gcm", n.encryption)
    }

    @Test
    fun rejectsShadowsocksStreamCipher() {
        for (method in listOf("rc4-md5", "aes-128-cfb", "aes-256-ctr", "chacha20-ietf")) {
            val userInfo = b64("$method:pw")
            assertThrows(LinkParseException::class.java) {
                ProxyLinkParser.parse("ss://$userInfo@ss.host:8388#Bad")
            }
            assertNull(ProxyLinkParser.parseOrNull("ss://$userInfo@ss.host:8388#Bad"))
        }
    }

    @Test
    fun parsesHysteria2WithObfs() {
        val link = "hysteria2://authpass@hy.host:443" +
            "?sni=hy.host&insecure=1&obfs=salamander&obfs-password=obfspw#Hy2"
        val n = ProxyLinkParser.parse(link)

        assertEquals(ProxyType.HYSTERIA2, n.type)
        assertEquals("authpass", n.password)
        assertEquals("salamander", n.obfs)
        assertEquals("obfspw", n.obfsPassword)
        assertEquals("hy.host", n.tls.sni)
        assertTrue(n.tls.allowInsecure)
    }

    @Test
    fun hy2AliasSchemeWorks() {
        val n = ProxyLinkParser.parse("hy2://pw@h.host:8443#A")
        assertEquals(ProxyType.HYSTERIA2, n.type)
        assertEquals(8443, n.port)
    }

    @Test
    fun decodesDoubleEncodedAlpn() {
        // Some subscriptions double-encode alpn: "http%252f1.1" -> "http/1.1".
        val node = ProxyLinkParser.parse(
            "vless://u@h.net:443?encryption=none&security=reality&sni=h.net" +
                "&pbk=K&alpn=http%252f1.1&flow=xtls-rprx-vision#A"
        )
        assertEquals(listOf("http/1.1"), node.tls.alpn)
    }

    @Test
    fun parsesPlainAlpnUnchanged() {
        val node = ProxyLinkParser.parse(
            "vless://u@h.net:443?encryption=none&security=tls&sni=h.net&alpn=h2,http/1.1#A"
        )
        assertEquals(listOf("h2", "http/1.1"), node.tls.alpn)
    }

    @Test
    fun rejectsUnsupportedScheme() {
        assertThrows(LinkParseException::class.java) {
            ProxyLinkParser.parse("ftp://whatever:21")
        }
    }

    @Test
    fun parseOrNullSwallowsErrors() {
        assertNull(ProxyLinkParser.parseOrNull("not a link at all"))
        assertNull(ProxyLinkParser.parseOrNull("vless://@:0#bad"))
    }
}
