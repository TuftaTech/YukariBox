package dev.yukaribox.vpn.vpn

import android.content.Context
import dev.yukaribox.vpn.core.AppThreads
import dev.yukaribox.vpn.core.ConfigBuilder
import dev.yukaribox.vpn.core.ConnectedProfile
import dev.yukaribox.vpn.core.Logs
import dev.yukaribox.vpn.core.SettingsStore
import dev.yukaribox.vpn.core.TunnelController
import dev.yukaribox.vpn.core.UrlTestEngine
import dev.yukaribox.vpn.data.NodeEntry
import dev.yukaribox.vpn.data.NodeRepository
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Optional failover: when the active node drops and reconnect attempts are
 * exhausted, pick the lowest-ping reachable alternative and switch to it instead
 * of failing closed. Opt-in via [SettingsStore]'s `autoSwitchOnDrop` (OFF by
 * default — manual switch is the default).
 *
 * Candidate latency is measured by [UrlTestEngine.testOne], which probes through
 * a transient box that is never registered as the main instance — so the test
 * runs OUTSIDE the tunnel (its protected sockets bypass the TUN) and never
 * disturbs the live/blocking tunnel. Lives outside [YukariVpnService] so the
 * service class stays under the detekt function-count budget.
 */
object NodeAutoSwitch {

    /** Parallelism and overall wall-clock budget for the whole probe round. */
    private const val PROBE_THREADS = 4
    private const val PROBE_BUDGET_MS = 12_000

    /**
     * Upper bound on how many alternatives are probed. A large subscription used to be
     * probed one node at a time with a 5 s timeout each, which — before the kill switch
     * was armed first — postponed fail-closed by minutes and made Stop unresponsive for
     * just as long.
     *
     * The fixed 16 that replaced it could not finish either, so it is now **derived** from
     * the budget rather than chosen: [PROBE_THREADS] lanes times however many
     * [UrlTestEngine.TIMEOUT_MS] probes fit inside [PROBE_BUDGET_MS]. Sixteen unreachable
     * candidates over 4 lanes at 5 s each is about 22 s against a 12 s ceiling, so
     * `invokeAll` timed out as a matter of course and the fastest reachable node could sit
     * in the half that never started. Worse, that timeout and the `shutdownNow()` behind it
     * cancel by interrupt, which does not unblock a JNI call, so the lanes still running
     * carried whole sing-box instances into the reconnect this function's own result
     * triggers. Deriving the cap makes the number honest instead of nominal.
     */
    private const val MAX_CANDIDATES = PROBE_THREADS * (PROBE_BUDGET_MS / UrlTestEngine.TIMEOUT_MS)

    /**
     * If auto-switch is enabled, URL-test the alternative nodes in the selected
     * subscription (outside the tunnel), make the fastest reachable one the new
     * selection, and return its freshly built config — or null if the setting is
     * off, there are no alternatives, or none responded in time.
     *
     * The caller has already armed the fail-closed TUN, so this runs while traffic is
     * safely blocked rather than in front of the kill switch.
     */
    fun switchToFastest(context: Context): String? {
        if (!SettingsStore.data.autoSwitchOnDrop) return null
        val currentId = NodeRepository.selectedId
        val candidates = NodeRepository.selectedSubNodes()
            .filter { it.id != currentId }
            .take(MAX_CANDIDATES)
        if (candidates.isEmpty()) return null
        val bestId = fastestId(probeLatencies(candidates)) ?: return null
        val best = candidates.first { it.id == bestId }
        Logs.i("AutoSwitch", "node dropped; switching to fastest reachable '${best.node.displayName}'")
        NodeRepository.selectNode(NodeRepository.selectedSubId, best.id)
        TunnelController.connectedProfile = ConnectedProfile(
            subId = NodeRepository.selectedSubId,
            nodeId = best.id,
            name = best.node.displayName,
        )
        val options = SettingsStore.configOptions(logOutput = "${context.filesDir.absolutePath}/box.log")
        return runCatching { ConfigBuilder.buildConfig(best.node, options) }.getOrNull()
    }

    /**
     * Probe every candidate concurrently under one wall-clock budget. Candidates that
     * do not answer within the budget are reported as failed rather than waited for.
     */
    private fun probeLatencies(candidates: List<NodeEntry>): List<Pair<Int, Int>> {
        // Shared factory: daemon threads at background priority, like every other
        // executor here — these probes run while the user is looking at a failed session.
        val pool = Executors.newFixedThreadPool(PROBE_THREADS, AppThreads.factory("autoswitch-probe"))
        return try {
            val tasks = candidates.map { entry ->
                Callable { entry.id to UrlTestEngine.testOne(entry.node) }
            }
            pool.invokeAll(tasks, PROBE_BUDGET_MS.toLong(), TimeUnit.MILLISECONDS).map { future ->
                runCatching { future.get() }.getOrNull()
            }.filterNotNull()
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            emptyList()
        } finally {
            pool.shutdownNow()
        }
    }

    /**
     * Pure pick: the id of the candidate with the lowest positive latency, or null
     * when none responded (non-positive latency = failed / untested). Kept separate
     * from the native URL test so the choice logic is unit-testable.
     */
    internal fun fastestId(latencies: List<Pair<Int, Int>>): Int? =
        latencies.filter { it.second > 0 }.minByOrNull { it.second }?.first
}
