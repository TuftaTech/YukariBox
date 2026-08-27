package dev.yukaribox.vpn.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The live-session supervisor's windowing. Before it existed, a node blocked
 * mid-session left the UI reporting "Connected" forever with the stats frozen at zero.
 */
class TrafficWatchdogTest {

    @Test
    fun idleWindowMustCloseBeforeProbing() {
        val watchdog = TrafficWatchdog(idleTicksBeforeProbe = 3)
        assertFalse(watchdog.onSample(0, 0))
        assertFalse(watchdog.onSample(0, 0))
        assertTrue(watchdog.onSample(0, 0))
    }

    @Test
    fun anyTrafficResetsTheWindow() {
        val watchdog = TrafficWatchdog(idleTicksBeforeProbe = 3)
        watchdog.onSample(0, 0)
        watchdog.onSample(0, 0)
        // A single byte in either direction proves the tunnel is alive.
        assertFalse(watchdog.onSample(0, 1))
        assertEquals(0, watchdog.idle)
        assertFalse(watchdog.onSample(0, 0))
        assertFalse(watchdog.onSample(0, 0))
        assertTrue(watchdog.onSample(0, 0))
    }

    @Test
    fun uplinkOnlyTrafficAlsoCountsAsAlive() {
        val watchdog = TrafficWatchdog(idleTicksBeforeProbe = 2)
        assertFalse(watchdog.onSample(0, 0))
        assertFalse(watchdog.onSample(512, 0))
        assertFalse(watchdog.onSample(0, 0))
        assertTrue(watchdog.onSample(0, 0))
    }

    @Test
    fun probingHappensOncePerWindowNotEverySecond() {
        val watchdog = TrafficWatchdog(idleTicksBeforeProbe = 2)
        assertFalse(watchdog.onSample(0, 0))
        assertTrue(watchdog.onSample(0, 0))
        // Window restarted: the next sample must not immediately probe again.
        assertFalse(watchdog.onSample(0, 0))
        assertTrue(watchdog.onSample(0, 0))
    }

    @Test
    fun resetClearsAPartialWindow() {
        val watchdog = TrafficWatchdog(idleTicksBeforeProbe = 3)
        watchdog.onSample(0, 0)
        watchdog.onSample(0, 0)
        watchdog.reset()
        assertEquals(0, watchdog.idle)
        assertFalse(watchdog.onSample(0, 0))
    }

    @Test
    fun defaultWindowIsAFullMinuteOfSilence() {
        assertEquals(60, TrafficWatchdog.DEFAULT_IDLE_TICKS)
        val watchdog = TrafficWatchdog()
        repeat(TrafficWatchdog.DEFAULT_IDLE_TICKS - 1) { assertFalse(watchdog.onSample(0, 0)) }
        assertTrue(watchdog.onSample(0, 0))
    }

    @Test(expected = IllegalArgumentException::class)
    fun aZeroWindowWouldProbeContinuouslyAndIsRejected() {
        TrafficWatchdog(idleTicksBeforeProbe = 0)
    }
}
