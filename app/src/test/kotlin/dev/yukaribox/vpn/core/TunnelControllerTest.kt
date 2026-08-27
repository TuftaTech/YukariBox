package dev.yukaribox.vpn.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks the per-session traffic-totals contract (US-003): cumulative up/down
 * bytes accumulate while connected and reset to zero when the session ends.
 *
 * The state machine only allows Disconnected -> Connecting, so every new session
 * is necessarily preceded by a Disconnected transition; resetting there
 * guarantees a fresh session always starts the totals from zero.
 */
class TunnelControllerTest {

    @Test
    fun sessionTotalsAccumulateThenResetOnDisconnect() {
        // Start a session and report cumulative totals from the stats loop.
        TunnelController.onState(TunnelState.Connecting)
        TunnelController.onState(TunnelState.Connected)
        TunnelController.onStats(up = 1_500L, down = 4_200L, upRate = 150L, downRate = 420L)

        assertEquals(1_500L, TunnelController.uplinkBytes)
        assertEquals(4_200L, TunnelController.downlinkBytes)
        assertEquals(150L, TunnelController.uplinkRate)
        assertEquals(420L, TunnelController.downlinkRate)

        // Totals are cumulative within the session.
        TunnelController.onStats(up = 2_000L, down = 9_000L, upRate = 500L, downRate = 4_800L)
        assertEquals(2_000L, TunnelController.uplinkBytes)
        assertEquals(9_000L, TunnelController.downlinkBytes)

        // Ending the session zeroes every counter so the next session starts fresh.
        TunnelController.onState(TunnelState.Disconnecting)
        TunnelController.onState(TunnelState.Disconnected)
        assertEquals(0L, TunnelController.uplinkBytes)
        assertEquals(0L, TunnelController.downlinkBytes)
        assertEquals(0L, TunnelController.uplinkRate)
        assertEquals(0L, TunnelController.downlinkRate)
    }

    @Test
    fun newSessionStartsFromZero() {
        // Leave non-zero totals from a prior session...
        TunnelController.onState(TunnelState.Connecting)
        TunnelController.onState(TunnelState.Connected)
        TunnelController.onStats(up = 8_888L, down = 7_777L, upRate = 10L, downRate = 20L)
        TunnelController.onState(TunnelState.Disconnecting)
        TunnelController.onState(TunnelState.Disconnected)

        // ...then a brand-new session must begin at zero before any stats arrive.
        TunnelController.onState(TunnelState.Connecting)
        TunnelController.onState(TunnelState.Connected)
        assertEquals(0L, TunnelController.uplinkBytes)
        assertEquals(0L, TunnelController.downlinkBytes)
    }

    /**
     * Locks the live-rate contract (US-004): the displayed up/down speed is the
     * latest per-second sample, NOT a running sum. The stats loop pushes the
     * instantaneous delta each second, so a new sample must overwrite the rate —
     * the reading has to fall when throughput drops, not creep upward forever.
     */
    @Test
    fun liveRateReflectsLatestSampleNotCumulative() {
        TunnelController.onState(TunnelState.Connecting)
        TunnelController.onState(TunnelState.Connected)

        // A fast second: high instantaneous rate.
        TunnelController.onStats(up = 10_000L, down = 50_000L, upRate = 10_000L, downRate = 50_000L)
        assertEquals(10_000L, TunnelController.uplinkRate)
        assertEquals(50_000L, TunnelController.downlinkRate)

        // The next second is slower: totals still climb, but the rate must drop
        // to the new sample (overwrite), not accumulate.
        TunnelController.onStats(up = 10_100L, down = 50_200L, upRate = 100L, downRate = 200L)
        assertEquals(100L, TunnelController.uplinkRate)
        assertEquals(200L, TunnelController.downlinkRate)
        assertEquals(10_100L, TunnelController.uplinkBytes)
        assertEquals(50_200L, TunnelController.downlinkBytes)

        // An idle second: no new bytes -> rate reads zero even while connected.
        TunnelController.onStats(up = 10_100L, down = 50_200L, upRate = 0L, downRate = 0L)
        assertEquals(0L, TunnelController.uplinkRate)
        assertEquals(0L, TunnelController.downlinkRate)

        TunnelController.onState(TunnelState.Disconnecting)
        TunnelController.onState(TunnelState.Disconnected)
    }

    /**
     * Locks the per-route breakdown contract (US-005): the `proxy` outbound
     * (uplink/downlink) and the `direct` outbound (directUplink/directDownlink)
     * are tracked independently, and both reset to zero when the session ends so
     * the Stats screen never shows stale numbers from a prior session.
     */
    @Test
    fun directOutboundTotalsTrackedSeparatelyAndResetOnDisconnect() {
        TunnelController.onState(TunnelState.Connecting)
        TunnelController.onState(TunnelState.Connected)

        TunnelController.onStats(up = 6_000L, down = 12_000L, upRate = 0L, downRate = 0L)
        TunnelController.onDirectStats(up = 400L, down = 900L)

        // Proxy and direct counters are independent — direct doesn't touch proxy.
        assertEquals(6_000L, TunnelController.uplinkBytes)
        assertEquals(12_000L, TunnelController.downlinkBytes)
        assertEquals(400L, TunnelController.directUplinkBytes)
        assertEquals(900L, TunnelController.directDownlinkBytes)

        // Ending the session zeroes the direct counters too.
        TunnelController.onState(TunnelState.Disconnecting)
        TunnelController.onState(TunnelState.Disconnected)
        assertEquals(0L, TunnelController.directUplinkBytes)
        assertEquals(0L, TunnelController.directDownlinkBytes)
    }

    /**
     * The fail-closed flags are the only way a status surface can tell "off" from
     * "blocked": the state machine reads Disconnected in both cases, but in one of them
     * a blocking TUN is still dropping every packet. Reporting that as plain
     * "disconnected" is the lie this contract exists to prevent — and reporting it as
     * "blocked" when the TUN could not be installed is the opposite lie.
     */
    @Test
    fun failClosedIsDistinguishableFromACleanStop() {
        TunnelController.onState(TunnelState.Connecting)
        TunnelController.onState(TunnelState.Connected)

        // Retries spent: the service arms the kill switch and reports the outcome after
        // the (state-clearing) Disconnected transition.
        TunnelController.onState(TunnelState.Disconnected)
        TunnelController.onFailClosed(blockingTunHeld = true)
        assertTrue(TunnelController.failClosed)
        assertFalse(TunnelController.unprotected)

        // The user presses Stop: the blocking TUN is released, so neither applies.
        TunnelController.clearFailClosed()
        assertFalse(TunnelController.failClosed)
        assertFalse(TunnelController.unprotected)
    }

    @Test
    fun aFailedKillSwitchReportsUnprotectedNotBlocked() {
        TunnelController.onState(TunnelState.Connecting)
        TunnelController.onState(TunnelState.Disconnected)
        TunnelController.onFailClosed(blockingTunHeld = false)
        assertTrue(TunnelController.unprotected)
        assertFalse(TunnelController.failClosed)
        TunnelController.clearFailClosed()
    }

    @Test
    fun aNewSessionClearsTheFailClosedFlags() {
        TunnelController.onState(TunnelState.Connecting)
        TunnelController.onState(TunnelState.Disconnected)
        TunnelController.onFailClosed(blockingTunHeld = true)

        // Reconnecting (or auto-switching to another node) must not leave "BLOCKED" on
        // screen: the flags clear on the next Disconnected transition of the new session.
        TunnelController.onState(TunnelState.Connecting)
        TunnelController.onState(TunnelState.Connected)
        TunnelController.onState(TunnelState.Disconnected)
        assertFalse(TunnelController.failClosed)
        assertFalse(TunnelController.unprotected)
    }
}
