package dev.yukaribox.vpn.vpn

/**
 * Sleep helper for the reconnect backoff, kept out of [YukariVpnService] so it is
 * unit-testable and so the service class stays under the detekt function-count
 * budget (the same reason [PerAppRouting] and `attemptConnect` live outside it).
 *
 * The service's connect loop runs on a single-thread executor, so a plain
 * `Thread.sleep` for the whole backoff makes a queued Stop wait for it: pressing
 * Stop during a 4 s backoff used to be deferred, and with all three retries the
 * user could wait ~20 s while the service kept trying to connect. Sleeping in
 * slices and re-checking a cancellation flag makes Stop take effect promptly
 * without adding a second thread to interrupt.
 */

/** Poll granularity for cancellation while sleeping. */
private const val DEFAULT_POLL_MS = 200L

/**
 * Sleep for [totalMs], re-checking [cancelled] every [pollMs].
 *
 * Returns true if the full delay elapsed, false if [cancelled] became true or the
 * thread was interrupted — in which case the caller must abandon the retry loop.
 */
fun sleepUnlessCancelled(
    totalMs: Long,
    pollMs: Long = DEFAULT_POLL_MS,
    sleeper: (Long) -> Unit = { Thread.sleep(it) },
    cancelled: () -> Boolean,
): Boolean {
    var remaining = totalMs
    while (remaining > 0) {
        if (cancelled()) return false
        val slice = minOf(remaining, pollMs)
        try {
            sleeper(slice)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            return false
        }
        remaining -= slice
    }
    return !cancelled()
}
