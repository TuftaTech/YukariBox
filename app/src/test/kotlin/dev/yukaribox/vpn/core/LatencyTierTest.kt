package dev.yukaribox.vpn.core

import dev.yukaribox.vpn.data.LATENCY_FAILED
import dev.yukaribox.vpn.data.LATENCY_TESTING
import dev.yukaribox.vpn.data.LATENCY_UNTESTED
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The latency tiers, extracted from the UI so the thresholds are pinned. The bar
 * count matters as much as the tier: with no latency colour left in the palette it
 * is the only thing the meter has to carry a measurement with.
 */
class LatencyTierTest {

    @Test
    fun tierBoundariesMatchTheDocumentedThresholds() {
        assertEquals(LatencyTier.Good, LatencyTier.of(1))
        assertEquals(LatencyTier.Good, LatencyTier.of(79))
        // 80 is the first mid value, not the last good one.
        assertEquals(LatencyTier.Mid, LatencyTier.of(80))
        assertEquals(LatencyTier.Mid, LatencyTier.of(150))
        assertEquals(LatencyTier.Bad, LatencyTier.of(151))
        assertEquals(LatencyTier.Bad, LatencyTier.of(10_000))
    }

    @Test
    fun sentinelsMapToTheirOwnTiers() {
        assertEquals(LatencyTier.Untested, LatencyTier.of(LATENCY_UNTESTED))
        assertEquals(LatencyTier.Testing, LatencyTier.of(LATENCY_TESTING))
        assertEquals(LatencyTier.Failed, LatencyTier.of(LATENCY_FAILED))
    }

    @Test
    fun zeroAndNegativeAreFailuresNotPerfectScores() {
        // The core's urlTest returns <= 0 for an unreachable node. Reading that as
        // "0 ms" would sort a dead node to the top of a latency sort.
        assertEquals(LatencyTier.Failed, LatencyTier.of(0))
        assertEquals(LatencyTier.Failed, LatencyTier.of(-7))
        assertEquals(LatencyTier.Failed, LatencyTier.of(Int.MIN_VALUE))
    }

    @Test
    fun barCountFallsMonotonicallyWithQuality() {
        // Four bars, all four filled at the fast end: measured off the reference's
        // status card, whose 35 ms node fills every bar.
        assertEquals(LatencyTier.TOTAL_BARS, LatencyTier.Good.filledBars)
        assertEquals(3, LatencyTier.Mid.filledBars)
        assertEquals(2, LatencyTier.Bad.filledBars)
        // A timeout is worse than merely slow, but it is still a measurement: one bar,
        // so it stays distinguishable from "never probed", which draws none.
        assertEquals(1, LatencyTier.Failed.filledBars)
        assertEquals(0, LatencyTier.Untested.filledBars)
        assertEquals(0, LatencyTier.Testing.filledBars)
    }

    @Test
    fun noTierClaimsMoreBarsThanTheMeterHas() {
        for (tier in LatencyTier.entries) {
            assertTrue(tier.name, tier.filledBars in 0..LatencyTier.TOTAL_BARS)
        }
    }

    @Test
    fun onlyMeasuredTiersDrawTheMeter() {
        assertTrue(LatencyTier.Good.hasMeasurement)
        assertTrue(LatencyTier.Mid.hasMeasurement)
        assertTrue(LatencyTier.Bad.hasMeasurement)
        assertFalse(LatencyTier.Untested.hasMeasurement)
        assertFalse(LatencyTier.Testing.hasMeasurement)
        assertFalse(LatencyTier.Failed.hasMeasurement)
    }
}
