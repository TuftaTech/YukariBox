package dev.yukaribox.vpn.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pins the curated DoH preset catalogue (US-009 AC1) and the reachability classifier (AC2). */
class DohPresetsTest {

    @Test
    fun remotePresetsMatchAppendixC() {
        assertEquals(
            listOf("Cloudflare", "Google", "Quad9", "AdGuard", "Mullvad"),
            DohPresets.remote.map { it.name },
        )
    }

    @Test
    fun directPresetsMatchAppendixC() {
        assertEquals(listOf("AliDNS", "DNSPod"), DohPresets.direct.map { it.name })
    }

    @Test
    fun everyPresetUrlIsHttpsDoh() {
        (DohPresets.remote + DohPresets.direct).forEach { p ->
            assertTrue("${p.name} must be https", p.url.startsWith("https://"))
            assertTrue("${p.name} must be a DoH endpoint", p.url.endsWith("/dns-query"))
        }
    }

    @Test
    fun anyHttpResponseIsReachable() {
        // A bare GET to /dns-query yields 400, HEAD may yield 405 — both prove the host answered.
        assertTrue(DohPresets.isReachable(200))
        assertTrue(DohPresets.isReachable(400))
        assertTrue(DohPresets.isReachable(405))
    }

    @Test
    fun noResponseIsUnreachable() {
        // 0 / negative is the sentinel for connect/timeout failure: not reachable, never offered.
        assertFalse(DohPresets.isReachable(0))
        assertFalse(DohPresets.isReachable(-1))
    }
}
