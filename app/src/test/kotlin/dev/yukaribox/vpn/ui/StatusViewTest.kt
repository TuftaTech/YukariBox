package dev.yukaribox.vpn.ui

import dev.yukaribox.vpn.R
import dev.yukaribox.vpn.core.TunnelState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * The four readings a user can be in are "off", "connecting", "protected" and the two
 * fail-closed outcomes, and this file is the only place that decides which words, glyph and
 * screen-reader label each one gets. Two invariants rest on it — never render a fail-closed
 * session as a plain clean stop, and never render an unprotected one as "blocked" — which is
 * also why `StatusView` lives outside the shell rather than inside it, next to `NavState`,
 * where a test can reach it.
 */
class StatusViewTest {

    // ---- the two flags are what separate "off" from "blocked" and from "unprotected"

    @Test
    fun aBlockingTunReadsAsBlockedRatherThanDisconnected() {
        val view = statusView(TunnelState.Disconnected, failClosed = true, unprotected = false)
        assertEquals(StatusView.Blocked, view)
    }

    @Test
    fun aFailedKillSwitchReadsAsUnprotectedRatherThanDisconnected() {
        val view = statusView(TunnelState.Disconnected, failClosed = false, unprotected = true)
        assertEquals(StatusView.Unprotected, view)
    }

    @Test
    fun aCleanStopIsStillJustDisconnected() {
        val view = statusView(TunnelState.Disconnected, failClosed = false, unprotected = false)
        assertEquals(StatusView.Disconnected, view)
    }

    @Test
    fun theActiveStatesIgnoreTheFlagsEntirely() {
        // The flags are only ever set alongside Disconnected; a stale one must not be able to
        // relabel a live session.
        assertEquals(
            StatusView.Connected,
            statusView(TunnelState.Connected, failClosed = true, unprotected = true),
        )
        assertEquals(
            StatusView.Connecting,
            statusView(TunnelState.Connecting, failClosed = true, unprotected = true),
        )
    }

    // ---- the three readings must not collapse into one another on any surface

    @Test
    fun blockedOffAndConnectedEachGetTheirOwnSentence() {
        val blocked = securityRes(StatusView.Blocked, TunnelScope.Full)
        val off = securityRes(StatusView.Disconnected, TunnelScope.Full)
        val connected = securityRes(StatusView.Connected, TunnelScope.Full)
        val failed = securityRes(StatusView.Unprotected, TunnelScope.Full)
        assertEquals(4, setOf(blocked, off, connected, failed).size)
    }

    @Test
    fun blockedAndUnprotectedGetTheirOwnStateLabelAndCard() {
        assertNotEquals(stateLabelRes(StatusView.Blocked), stateLabelRes(StatusView.Unprotected))
        assertNotEquals(stateLabelRes(StatusView.Blocked), stateLabelRes(StatusView.Disconnected))
        // Both share Home's fail-closed card, which is deliberate: it is the card that
        // carries the explanation, and the sentence inside it is what differs.
        assertEquals(StatusSlot.FailClosed, statusSlot(StatusView.Blocked))
        assertEquals(StatusSlot.FailClosed, statusSlot(StatusView.Unprotected))
        assertEquals(StatusSlot.Location, statusSlot(StatusView.Disconnected))
    }

    @Test
    fun bothFailClosedViewsLabelTheControlAsReconnectNotAsStop() {
        // The state machine reads Disconnected in both, so the toggle takes the connect
        // branch. Labelling it "stop the session" told a screen-reader user the opposite of
        // what the control does and of what the visible caption says.
        val blocked = connectDescriptionRes(StatusView.Blocked)
        val failed = connectDescriptionRes(StatusView.Unprotected)
        assertEquals(R.string.cd_reconnect_blocked, blocked)
        assertEquals(R.string.cd_reconnect_unprotected, failed)
        assertNotEquals(R.string.cd_disconnect, blocked)
        assertNotEquals(R.string.cd_disconnect, failed)
        assertEquals(R.string.tap_to_reconnect, captionRes(StatusView.Blocked))
        assertEquals(R.string.tap_to_reconnect, captionRes(StatusView.Unprotected))
    }

    // ---- "protected" is a claim about coverage, not only about the tunnel being up

    @Test
    fun proxyOnlyIsNeverCalledProtected() {
        // Proxy-only owns no TUN and captures nothing: only a client the user pointed at the
        // local mixed inbound is proxied. Derived from tunnel state alone this said "your
        // connection is protected", which was false for every packet on the device.
        assertEquals(
            R.string.security_proxy_only,
            securityRes(StatusView.Connected, TunnelScope.ProxyOnly),
        )
    }

    @Test
    fun aSplitTunnelSaysSoInsteadOfClaimingEverythingIsCovered() {
        assertEquals(
            R.string.security_protected_partial,
            securityRes(StatusView.Connected, TunnelScope.SplitTunnel),
        )
    }

    @Test
    fun theUnqualifiedProtectedSentenceIsReservedForAFullTunnel() {
        assertEquals(
            R.string.security_protected,
            securityRes(StatusView.Connected, TunnelScope.Full),
        )
        assertEquals(
            3,
            setOf(
                securityRes(StatusView.Connected, TunnelScope.Full),
                securityRes(StatusView.Connected, TunnelScope.SplitTunnel),
                securityRes(StatusView.Connected, TunnelScope.ProxyOnly),
            ).size,
        )
    }

    @Test
    fun scopeOnlyQualifiesAConnectedSession() {
        // In every other view the tunnel is not carrying traffic, so how much of it *would*
        // be carried says nothing, and letting the scope leak into those sentences would put
        // "some apps bypass" on a screen where nothing is tunnelled at all.
        for (view in StatusView.entries.filter { it != StatusView.Connected }) {
            assertEquals(
                securityRes(view, TunnelScope.Full),
                securityRes(view, TunnelScope.ProxyOnly),
            )
        }
    }

    @Test
    fun proxyOnlyOutranksSplitTunnelBecauseItCapturesEvenLess() {
        assertEquals(TunnelScope.ProxyOnly, tunnelScope(proxyOnly = true, splitTunnel = true))
        assertEquals(TunnelScope.ProxyOnly, tunnelScope(proxyOnly = true, splitTunnel = false))
        assertEquals(TunnelScope.SplitTunnel, tunnelScope(proxyOnly = false, splitTunnel = true))
        assertEquals(TunnelScope.Full, tunnelScope(proxyOnly = false, splitTunnel = false))
    }
}
