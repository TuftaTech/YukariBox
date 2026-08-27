package dev.yukaribox.vpn.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReconnectPolicyTest {

    @Test
    fun backoffIsExponentialFromBase() {
        val policy = ReconnectPolicy()
        assertEquals(1000L, policy.backoffMillis(1))
        assertEquals(2000L, policy.backoffMillis(2))
        assertEquals(4000L, policy.backoffMillis(3))
        assertEquals(8000L, policy.backoffMillis(4))
    }

    @Test
    fun nextDelayYieldsOneTwoFourThenStops() {
        val policy = ReconnectPolicy()
        assertEquals(1000L, policy.nextDelayMs())
        assertEquals(2000L, policy.nextDelayMs())
        assertEquals(4000L, policy.nextDelayMs())
        // Cap reached: no fourth attempt, caller fails closed.
        assertNull(policy.nextDelayMs())
        assertEquals(3, policy.attempts)
        assertTrue(policy.exhausted())
    }

    @Test
    fun attemptsCountUpToCapOnly() {
        val policy = ReconnectPolicy()
        assertEquals(0, policy.attempts)
        assertFalse(policy.exhausted())
        policy.nextDelayMs()
        assertEquals(1, policy.attempts)
        // Hammering past the cap never advances the counter beyond maxAttempts.
        repeat(10) { policy.nextDelayMs() }
        assertEquals(policy.maxAttempts, policy.attempts)
    }

    @Test
    fun resetClearsAttempts() {
        val policy = ReconnectPolicy()
        policy.nextDelayMs()
        policy.nextDelayMs()
        policy.reset()
        assertEquals(0, policy.attempts)
        assertFalse(policy.exhausted())
        // After reset the sequence restarts from the base delay.
        assertEquals(1000L, policy.nextDelayMs())
    }

    @Test
    fun honoursCustomCapAndBackoff() {
        val policy = ReconnectPolicy(maxAttempts = 2, baseDelayMs = 500L, factor = 3L)
        assertEquals(500L, policy.nextDelayMs())
        assertEquals(1500L, policy.nextDelayMs())
        assertNull(policy.nextDelayMs())
        assertEquals(2, policy.attempts)
    }

    @Test
    fun zeroAttemptsFailsClosedImmediately() {
        val policy = ReconnectPolicy(maxAttempts = 0)
        assertTrue(policy.exhausted())
        assertNull(policy.nextDelayMs())
    }

    @Test
    fun bothFailureModesAreModelled() {
        // The supervisor must react to a handshake error AND a traffic timeout.
        assertEquals(
            setOf(NodeFailure.HandshakeError, NodeFailure.TrafficTimeout),
            NodeFailure.entries.toSet(),
        )
    }
}
