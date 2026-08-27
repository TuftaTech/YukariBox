package dev.yukaribox.vpn.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The reconnect backoff must not swallow a Stop request. Verified with an injected
 * sleeper so the test costs no wall-clock time.
 */
class BackoffTest {

    @Test
    fun fullDelayElapsesWhenNothingCancels() {
        var slept = 0L
        val completed = sleepUnlessCancelled(1000, pollMs = 200, sleeper = { slept += it }) { false }
        assertTrue(completed)
        assertEquals(1000L, slept)
    }

    @Test
    fun cancellationStopsTheSleepEarly() {
        var slept = 0L
        // Cancelled after the third poll: a Stop arriving mid-backoff must cut it short
        // instead of leaving the user waiting out the whole delay.
        val completed = sleepUnlessCancelled(4000, pollMs = 200, sleeper = { slept += it }) { slept >= 600 }
        assertFalse(completed)
        assertEquals(600L, slept)
    }

    @Test
    fun alreadyCancelledSleepsNotAtAll() {
        var slept = 0L
        assertFalse(sleepUnlessCancelled(4000, pollMs = 200, sleeper = { slept += it }) { true })
        assertEquals(0L, slept)
    }

    @Test
    fun cancellationRaisedDuringTheFinalSliceIsStillReported() {
        var slept = 0L
        var cancel = false
        val completed = sleepUnlessCancelled(
            totalMs = 200,
            pollMs = 200,
            sleeper = { slept += it; cancel = true },
        ) { cancel }
        assertFalse(completed)
        assertEquals(200L, slept)
    }

    @Test
    fun interruptionAbandonsTheLoopAndKeepsTheFlag() {
        Thread.interrupted() // clear any stale flag
        val completed = sleepUnlessCancelled(1000, pollMs = 200, sleeper = { throw InterruptedException() }) { false }
        assertFalse(completed)
        assertTrue(Thread.interrupted())
    }

    @Test
    fun zeroDelayIsANoOp() {
        var slept = 0L
        assertTrue(sleepUnlessCancelled(0, pollMs = 200, sleeper = { slept += it }) { false })
        assertEquals(0L, slept)
    }
}
