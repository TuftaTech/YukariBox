package dev.yukaribox.vpn.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TunnelStateMachineTest {

    @Test
    fun followsCanonicalLifecycle() {
        val seen = mutableListOf<TunnelState>()
        val sm = TunnelStateMachine(onChange = { seen.add(it) })

        assertEquals(TunnelState.Disconnected, sm.state)
        assertTrue(sm.beginConnecting())
        assertTrue(sm.markConnected())
        assertTrue(sm.beginDisconnecting())
        assertTrue(sm.markDisconnected())

        assertEquals(
            listOf(
                TunnelState.Connecting,
                TunnelState.Connected,
                TunnelState.Disconnecting,
                TunnelState.Disconnected,
            ),
            seen,
        )
    }

    @Test
    fun rejectsIllegalEdges() {
        val sm = TunnelStateMachine()
        // Disconnected -> Connected is illegal (must go through Connecting).
        assertFalse(sm.markConnected())
        assertEquals(TunnelState.Disconnected, sm.state)

        sm.beginConnecting()
        // Connected -> Connecting is illegal.
        sm.markConnected()
        assertFalse(sm.beginConnecting())
        assertEquals(TunnelState.Connected, sm.state)
    }

    @Test
    fun redundantTransitionIsNoOpButTrue() {
        val seen = mutableListOf<TunnelState>()
        val sm = TunnelStateMachine(onChange = { seen.add(it) })
        assertTrue(sm.markDisconnected()) // already Disconnected
        assertTrue(seen.isEmpty())
    }

    @Test
    fun connectingCanCancelDirectlyToDisconnected() {
        val sm = TunnelStateMachine()
        sm.beginConnecting()
        assertTrue(sm.transitionTo(TunnelState.Disconnected))
        assertEquals(TunnelState.Disconnected, sm.state)
    }

    @Test
    fun failCollapsesActiveStateToDisconnected() {
        val sm = TunnelStateMachine()
        sm.beginConnecting()
        sm.markConnected()
        assertTrue(sm.fail())
        assertEquals(TunnelState.Disconnected, sm.state)
        // fail() from Disconnected is a no-op.
        assertFalse(sm.fail())
    }

    @Test
    fun canTransitionToReflectsRules() {
        val sm = TunnelStateMachine()
        assertTrue(sm.canTransitionTo(TunnelState.Connecting))
        assertFalse(sm.canTransitionTo(TunnelState.Connected))
        assertFalse(sm.canTransitionTo(TunnelState.Disconnecting))
    }
}
