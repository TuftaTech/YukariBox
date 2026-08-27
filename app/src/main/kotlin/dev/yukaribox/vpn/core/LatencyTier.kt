package dev.yukaribox.vpn.core

import dev.yukaribox.vpn.data.LATENCY_FAILED
import dev.yukaribox.vpn.data.LATENCY_TESTING
import dev.yukaribox.vpn.data.LATENCY_UNTESTED

/**
 * How a measured latency should be presented: which tier it falls in and how many
 * bars of the four-step meter are filled.
 *
 * Extracted from the UI and made pure so the thresholds are unit-tested rather
 * than buried in a `when` inside a composable. The tiers themselves are the app's
 * documented ones (<80 ms / 80–150 ms / >150 ms); [filledBars] is what carries the
 * tier by *shape*, which is the whole of it now — the interface has no latency colour
 * left to spend, so the meter and the weight of the digits beside it are the signal
 * (WCAG 1.4.1, satisfied by construction rather than as a fallback).
 *
 * Four bars, not three, and all four filled at the fast end: that is what the
 * reference's own status card draws for its 35 ms node. This enum is the single
 * tier→bars mapping in the tree; the connected banner used to carry a second one on a
 * different scale, and the two disagreed at every tier.
 */
enum class LatencyTier(val filledBars: Int) {
    /** Never probed. Renders as an em dash. */
    Untested(0),

    /** Probe in flight. */
    Testing(0),

    /** Under [GOOD_MAX_MS]. */
    Good(4),

    /** [GOOD_MAX_MS] up to and including [MID_MAX_MS]. */
    Mid(3),

    /** Above [MID_MAX_MS]. */
    Bad(2),

    /**
     * Probe timed out or errored. One bar rather than none: it is worse than merely
     * slow, and a *measured* failure is still different from having no measurement at
     * all, which is what an empty meter has to mean.
     */
    Failed(1),
    ;

    /** Whether the meter should be drawn at all (a probe produced a number). */
    val hasMeasurement: Boolean get() = this == Good || this == Mid || this == Bad

    companion object {
        /** Upper bound (exclusive) of [Good], in ms. */
        const val GOOD_MAX_MS = 80

        /** Upper bound (inclusive) of [Mid], in ms. */
        const val MID_MAX_MS = 150

        /** Bars in the meter — measured off the reference's status card. */
        const val TOTAL_BARS = 4

        /**
         * Classify a raw `NodeEntry.latencyMs`, including the negative sentinels
         * ([LATENCY_UNTESTED], [LATENCY_TESTING], [LATENCY_FAILED]).
         *
         * Any other non-positive value is treated as [Failed]: the core's urlTest
         * reports `<= 0` for an unreachable node, and calling that "0 ms, excellent"
         * would rank a dead node first in a latency sort.
         */
        fun of(latencyMs: Int): LatencyTier = when {
            latencyMs == LATENCY_UNTESTED -> Untested
            latencyMs == LATENCY_TESTING -> Testing
            latencyMs == LATENCY_FAILED -> Failed
            latencyMs <= 0 -> Failed
            latencyMs < GOOD_MAX_MS -> Good
            latencyMs <= MID_MAX_MS -> Mid
            else -> Bad
        }
    }
}
