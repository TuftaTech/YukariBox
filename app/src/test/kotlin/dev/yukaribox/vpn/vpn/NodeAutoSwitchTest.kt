package dev.yukaribox.vpn.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Unit tests for the pure failover-pick logic used by auto-switch (US-004). */
class NodeAutoSwitchTest {

    @Test
    fun picksLowestPositiveLatency() {
        val best = NodeAutoSwitch.fastestId(listOf(1 to 180, 2 to 42, 3 to 95))
        assertEquals(2, best)
    }

    @Test
    fun ignoresNonPositiveLatencies() {
        // -1 untested, -2 failed, -3 testing must never be chosen over a real ping.
        val best = NodeAutoSwitch.fastestId(listOf(1 to -2, 2 to -1, 3 to 120, 4 to -3))
        assertEquals(3, best)
    }

    @Test
    fun returnsNullWhenNoneReachable() {
        assertNull(NodeAutoSwitch.fastestId(listOf(1 to -2, 2 to -1)))
        assertNull(NodeAutoSwitch.fastestId(emptyList()))
    }

    @Test
    fun firstWinsOnTie() {
        val best = NodeAutoSwitch.fastestId(listOf(5 to 60, 6 to 60))
        assertEquals(5, best)
    }
}
