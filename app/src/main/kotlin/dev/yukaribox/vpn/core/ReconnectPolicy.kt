package dev.yukaribox.vpn.core

/**
 * Why a live tunnel session is considered down. The supervisor reacts to both
 * failure modes: a [HandshakeError] (the core failed to start / negotiate the
 * proxy) and a [TrafficTimeout] (the core came up but no traffic flows through
 * the tunnel within the probe window).
 */
enum class NodeFailure { HandshakeError, TrafficTimeout }

/**
 * Drives auto-reconnect after a node drops: a bounded number of attempts with
 * exponential backoff. Deliberately pure and free of Android / coroutine /
 * clock dependencies so the backoff timing and the attempt cap are exhaustively
 * unit-testable without real delays.
 *
 * With the defaults (max 3, base 1s, factor 2) the supervisor waits 1s, then 2s,
 * then 4s before the three retries; once they are spent [nextDelayMs] returns
 * null and the caller fails closed (no leak — see fail-closed kill switch).
 */
class ReconnectPolicy(
    val maxAttempts: Int = MAX_ATTEMPTS,
    private val baseDelayMs: Long = BASE_DELAY_MS,
    private val factor: Long = FACTOR,
) {
    init {
        require(maxAttempts >= 0) { "maxAttempts must be >= 0" }
        require(baseDelayMs >= 0L) { "baseDelayMs must be >= 0" }
        require(factor >= 1L) { "factor must be >= 1" }
    }

    /** Number of reconnect attempts handed out so far (0..[maxAttempts]). */
    var attempts: Int = 0
        private set

    /** Backoff before the [attempt]-th (1-based) try: base * factor^(attempt-1). */
    fun backoffMillis(attempt: Int): Long {
        require(attempt >= 1) { "attempt is 1-based" }
        var delay = baseDelayMs
        repeat(attempt - 1) { delay *= factor }
        return delay
    }

    /**
     * Reserve the next reconnect attempt and return how long to wait before it,
     * or null once the cap is reached (no attempts left — caller fails closed).
     */
    fun nextDelayMs(): Long? {
        if (attempts >= maxAttempts) return null
        attempts += 1
        return backoffMillis(attempts)
    }

    /** True once every attempt has been used up. */
    fun exhausted(): Boolean = attempts >= maxAttempts

    /** Forget previous attempts (call on a fresh, user-initiated connect). */
    fun reset() {
        attempts = 0
    }

    companion object {
        const val MAX_ATTEMPTS = 3
        const val BASE_DELAY_MS = 1000L
        const val FACTOR = 2L
    }
}
