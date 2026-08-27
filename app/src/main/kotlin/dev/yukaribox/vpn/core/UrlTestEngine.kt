package dev.yukaribox.vpn.core

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.yukaribox.vpn.R
import dev.yukaribox.vpn.data.LATENCY_FAILED
import dev.yukaribox.vpn.data.LATENCY_TESTING
import dev.yukaribox.vpn.data.NodeRepository
import dev.yukaribox.vpn.data.StatusMessage
import dev.yukaribox.vpn.proxy.ProxyNode
import dev.yukaribox.vpn.vpn.LocalDns
import libcore.Libcore
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Measures node latency with the sing-box core's own URL test
 * (`Libcore.urlTest`, an HTTP `generate_204` probe through a transient box that
 * is never registered as the main instance, so it never disturbs the tunnel).
 *
 * A batch run covers every node in the active group. Progress is exposed as
 * Compose state so the UI can draw a live progress bar; [cancel] stops the run
 * at any point (queued nodes are skipped, in-flight probes finish). Tests run
 * on a bounded background pool, so a batch keeps going while the app is
 * backgrounded or the progress card is hidden.
 */
object UrlTestEngine {

    internal const val TIMEOUT_MS = 5000

    /** How long a probe waits for an instance permit before reporting the node unreachable. */
    private const val PERMIT_WAIT_MS = 20_000L

    /**
     * Concurrent probes. Every one of them builds and starts a full sing-box instance
     * with its own DNS and routing tables, so this is the app's peak native footprint;
     * the work itself is network-bound (a `generate_204` fetch under [TIMEOUT_MS]), which
     * is why more threads buy less than they cost. Floored at 2 so a small device still
     * overlaps its waits.
     */
    private val PROBE_THREADS = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)

    // Daemon threads at background priority — see AppThreads. Six default-priority
    // workers running TLS handshakes used to compete with the servers list for CPU on
    // equal terms, which is most of what made a sweep feel like a hang.
    private val pool = Executors.newFixedThreadPool(PROBE_THREADS, AppThreads.factory("urltest"))

    /**
     * Single-node probes, deliberately **off** [pool].
     *
     * A tap on a ping badge queued behind whatever the batch had already submitted — up to
     * one task per node in the group. The badge was set to "TESTING" immediately and the
     * row's own re-probe is disabled while it says so, so on a large subscription the row
     * sat there unusable for as long as the sweep took, with nothing to cancel it.
     */
    private val single = Executors.newSingleThreadExecutor(AppThreads.factory("urltest-one"))

    /**
     * Process-wide ceiling on how many transient sing-box instances exist at once.
     *
     * Three independent pools reach [testOne] -- this object's batch [pool], its [single]
     * executor, and the failover pool in `NodeAutoSwitch` -- so each of them was bounded
     * while the *process* was not: a sweep continuing in the background while a fail-closed
     * auto-switch probed alternatives, with a ping badge tapped on top, could hold nine whole
     * cores at once, each with its own DNS router, outbound manager and crowd of goroutines.
     * That is not a failure mode Kotlin can catch: Go answers an exhausted heap or an
     * exhausted thread table with `fatal error:`, and the runtime aborts the process from
     * inside itself, where neither `recover` nor a `catch` can reach it.
     *
     * Sized [PROBE_THREADS] + 1 rather than [PROBE_THREADS], so the batch pool and the
     * single-node executor never queue behind each other -- that separation is the whole
     * point of [single] -- and only the failover pool ever waits.
     */
    private val instances = Semaphore(PROBE_THREADS + 1)

    /** Batch run in progress (drives the progress card). */
    var running by mutableStateOf(false)
        private set

    var testedCount by mutableIntStateOf(0)
        private set

    var totalCount by mutableIntStateOf(0)
        private set

    /** Bumped to invalidate everything queued by an older batch. */
    private val generation = AtomicInteger(0)

    /** Thread-safe completion counter (pool threads increment concurrently). */
    private val completed = AtomicInteger(0)

    private val testUrl: String get() = SettingsStore.data.connectionTestUrl

    /**
     * Test a single node synchronously; returns latency ms or [LATENCY_FAILED].
     *
     * Bounded by [instances]: the probe builds and starts a whole sing-box, so the permit is
     * held for the entire life of that instance and released only once it is closed.
     */
    fun testOne(node: ProxyNode): Int {
        if (!acquirePermit()) {
            Logs.w("UrlTest", "probe skipped: no instance permit within ${PERMIT_WAIT_MS}ms")
            return LATENCY_FAILED
        }
        return try {
            probeOne(node)
        } finally {
            instances.release()
        }
    }

    /**
     * Wait for an instance permit. A failed wait is reported as an unmeasured node rather
     * than as an error, which is what every caller already does with a probe that did not
     * answer.
     */
    private fun acquirePermit(): Boolean = try {
        instances.tryAcquire(PERMIT_WAIT_MS, TimeUnit.MILLISECONDS)
    } catch (_: InterruptedException) {
        // A cancelled round (NodeAutoSwitch's budget expiring calls shutdownNow). Restore the
        // flag so the pool can still observe it, and report the node as unmeasured.
        Thread.currentThread().interrupt()
        false
    }

    private fun probeOne(node: ProxyNode): Int = try {
        // Built with the user's real options (DNS servers, allow-insecure) so the measured
        // latency reflects the configuration the tunnel will actually use; the default
        // ConfigOptions used to silently test against different DNS than the session.
        val config = ConfigBuilder.buildTestConfig(node, SettingsStore.configOptions())
        val box = Libcore.newSingBoxInstance(config, LocalDns)
        try {
            box.start()
            val ms = Libcore.urlTest(box, testUrl, TIMEOUT_MS)
            if (ms <= 0) LATENCY_FAILED else ms
        } finally {
            // Logged rather than swallowed: a close that fails leaves that instance's
            // native sockets and threads alive, and a sweep runs several of them at once.
            // Silent failure here was invisible growth in the process.
            runCatching { box.close() }
                .onFailure { Logs.w("UrlTest", "probe instance did not close", it) }
        }
    } catch (_: Exception) {
        LATENCY_FAILED
    }

    /**
     * Re-test one node from the list (tap on its ping badge).
     *
     * Deliberately does **not** re-sort afterwards. The row the user just tapped is the
     * row under their finger, and in latency order a dead server's new number sends it
     * to the bottom of the list — `LazyListState` anchors on the first visible row's key
     * and follows it, so the whole screen rode down after it. The question this answers
     * is "is *this* one alive?", not "reorder the list": the number changes in place, and
     * the order is re-applied by the next full sweep, a group switch, or the user picking
     * a sort. It is the same rule [NodeRepository.resortAfterTest] already states for
     * per-result sorting inside a batch.
     */
    fun testSingle(id: Int, node: ProxyNode) {
        // Same group check as a batch: the probe takes seconds and the user may have
        // switched groups before it returns.
        val subId = NodeRepository.activeSubId
        NodeRepository.setLatency(id, LATENCY_TESTING, subId)
        single.execute {
            // Landed in a finally, so the badge always comes back. `testOne` swallows
            // Exception but not Error, and a lost result left the row reading "TESTING"
            // with its own re-probe disabled — stuck, with nothing to cancel it.
            var result = LATENCY_FAILED
            try {
                result = testOne(node)
            } finally {
                NodeRepository.setLatency(id, result, subId)
            }
        }
    }

    /** Test every node in the active group, updating progress as results arrive. */
    fun testAll() {
        val gen = generation.incrementAndGet()
        val entries = NodeRepository.nodes.toList()
        if (entries.isEmpty()) return
        // Remember which group this batch belongs to: results arrive seconds later,
        // by which time the user may be looking at another group whose nodes carry
        // the same (per-group) ids, and the timings would land on the wrong servers.
        val subId = NodeRepository.activeSubId
        running = true
        completed.set(0)
        testedCount = 0
        totalCount = entries.size
        NodeRepository.setTestStatus(StatusMessage.Text(R.string.status_testing, listOf(0, entries.size)))
        // One pass over the list rather than a setLatency per node: this runs on the UI
        // thread (the menu's tap) and each of those calls scanned the list for the id, so
        // opening a sweep over a large subscription was quadratic work inside the lock
        // before the frame that showed the progress bar could be drawn.
        NodeRepository.markAllTesting(subId)
        entries.forEach { entry ->
            pool.execute {
                // A superseded batch stops here without touching the counters: they
                // belong to the new batch now, and finishing this one's arithmetic
                // used to leave `running` stuck on (the count could never reach the
                // new total), which left the progress card up until an app restart.
                if (generation.get() != gen) return@execute
                try {
                    val ms = testOne(entry.node)
                    if (generation.get() != gen) return@execute
                    NodeRepository.setLatency(entry.id, ms, subId)
                } finally {
                    // Count the node even if the probe blew up in a way testOne does
                    // not catch (an Error, not an Exception). Skipping the increment
                    // meant the batch could never reach its total, leaving `running`
                    // — and the progress card — stuck on until the app restarted.
                    if (generation.get() == gen) finishOne(gen)
                }
            }
        }
    }

    /**
     * Mark one node of batch [gen] finished and update the progress text. The
     * generation is re-checked by the caller, so a superseded batch never moves the
     * current batch's counters.
     */
    private fun finishOne(gen: Int) {
        val done = completed.incrementAndGet()
        if (generation.get() != gen) return
        testedCount = done
        if (done >= totalCount) {
            running = false
            NodeRepository.setTestStatus(StatusMessage.Count(R.plurals.status_tested, done))
            // The whole point of the sweep: with latency order chosen, the list now shows
            // what was just measured instead of what the last sweep measured.
            NodeRepository.resortAfterTest()
        } else {
            NodeRepository.setTestStatus(StatusMessage.Text(R.string.status_testing, listOf(done, totalCount)))
        }
    }

    /** Stop the current batch: queued nodes are skipped, badges reset. */
    fun cancel() {
        if (!running) return
        generation.incrementAndGet()
        running = false
        NodeRepository.setTestStatus(
            StatusMessage.Text(R.string.status_test_stopped, listOf(testedCount, totalCount)),
        )
        // One pass, like the sweep's own opening: this is a UI-thread tap too.
        NodeRepository.clearTesting()
        // A stopped sweep still measured something; the rows it did reach take their place.
        NodeRepository.resortAfterTest()
    }
}
