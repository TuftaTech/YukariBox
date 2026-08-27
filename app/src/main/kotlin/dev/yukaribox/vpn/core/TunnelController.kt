package dev.yukaribox.vpn.core

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * The node a tunnel session was started with. Selection inside the visible
 * group is free to change while browsing; this snapshot is what the status
 * card, notification and QS tile show while the tunnel is active.
 */
data class ConnectedProfile(val subId: String?, val nodeId: Int?, val name: String)

/**
 * Observable, process-wide tunnel status. The VPN service writes to it from a
 * background thread; Compose reads it and recomposes. Snapshot state is
 * thread-safe, so no extra synchronization is needed.
 */
object TunnelController {
    var state by mutableStateOf(TunnelState.Disconnected)
        private set

    /** Set when a session starts; null once fully disconnected. */
    var connectedProfile by mutableStateOf<ConnectedProfile?>(null)

    var uplinkBytes by mutableLongStateOf(0L)
        private set

    var downlinkBytes by mutableLongStateOf(0L)
        private set

    var uplinkRate by mutableLongStateOf(0L)
        private set

    var downlinkRate by mutableLongStateOf(0L)
        private set

    /**
     * Cumulative byte totals for the `direct` outbound (LAN / bypassed traffic
     * that never went through the proxy node). [uplinkBytes]/[downlinkBytes]
     * cover the `proxy` outbound — i.e. the connected node. Together they give
     * the per-node/per-route breakdown the Stats screen renders (US-005).
     */
    var directUplinkBytes by mutableLongStateOf(0L)
        private set

    var directDownlinkBytes by mutableLongStateOf(0L)
        private set

    var lastError by mutableStateOf<String?>(null)

    /**
     * False when `Libcore.initCore` failed at process start.
     *
     * Every core call then fails, so Connect refuses instead of letting the user watch
     * three retries and a kill switch arm for a reason nothing on screen explains. The
     * failure used to be swallowed into a single `Log.e` line that the in-app journal never
     * even saw. Written once from `YukariApp`, before any surface can read it.
     */
    var coreReady by mutableStateOf(true)

    /**
     * `System.currentTimeMillis()` at the moment the tunnel reached Connected, or 0
     * when no session is up. The connected banner and the Stats screen render a session
     * timer from it.
     *
     * Wall-clock rather than `elapsedRealtime` because it is only ever used as a
     * difference against another wall-clock read taken seconds later, and it is set
     * once per session — a clock change mid-session would skew a timer, which is a
     * cosmetic error, where using it for anything the tunnel decides would not be.
     * Assigned only on the *entry* into Connected, so a mid-session state re-report
     * cannot restart the clock.
     */
    var connectedSinceMs by mutableLongStateOf(0L)
        private set

    /**
     * True while a *blocking* fail-closed TUN is installed: the tunnel collapsed but
     * the TUN still captures 0.0.0.0/0 and ::/0 with no core behind it, so packets
     * are dropped rather than leaked. The state machine reads Disconnected in this
     * situation, so this flag is the only way any surface can tell "off" from
     * "blocked" — deliberately a separate flag rather than a new [TunnelState] so
     * every existing status surface keeps compiling unchanged.
     */
    var failClosed by mutableStateOf(false)
        private set

    /**
     * True when the tunnel collapsed and the blocking TUN could **not** be installed
     * (no VPN consent, another VPN took over, a Builder rejection). Traffic is NOT
     * protected in this state, so it must never be reported as "blocked".
     */
    var unprotected by mutableStateOf(false)
        private set

    /**
     * Report the outcome of the fail-closed transition. Call *after*
     * [TunnelStateMachine.fail], because the resulting [onState] Disconnected clears
     * both flags back to the clean-stop default.
     *
     * The tile and widget are re-synced here as well: they render from these flags,
     * and the Disconnected transition that precedes this call has already pushed its
     * own (still "off") update, so without a second sync both surfaces would claim
     * the tunnel is simply off while traffic is being dropped.
     */
    fun onFailClosed(blockingTunHeld: Boolean) {
        failClosed = blockingTunHeld
        unprotected = !blockingTunHeld
        syncStatusSurfaces()
    }

    /**
     * The blocking TUN has been released (the user pressed Stop, or the service is
     * being torn down), so neither "blocked" nor "unprotected" applies any more.
     * Needed because a stop from an already-Disconnected state performs no state
     * transition and therefore never reaches [onState].
     */
    fun clearFailClosed() {
        val wasFlagged = failClosed || unprotected
        failClosed = false
        unprotected = false
        if (wasFlagged) syncStatusSurfaces()
    }

    /** Called by the service's state machine on every transition. */
    fun onState(newState: TunnelState) {
        val previous = state
        state = newState
        if (newState == TunnelState.Connected && previous != TunnelState.Connected) {
            connectedSinceMs = System.currentTimeMillis()
        }
        if (newState == TunnelState.Disconnected) {
            connectedProfile = null
            failClosed = false
            unprotected = false
            connectedSinceMs = 0L
            uplinkBytes = 0L
            downlinkBytes = 0L
            uplinkRate = 0L
            downlinkRate = 0L
            directUplinkBytes = 0L
            directDownlinkBytes = 0L
        }
        syncStatusSurfaces()
    }

    /**
     * Pushes the current status to the two surfaces that live outside the Compose snapshot
     * system and therefore cannot observe it: the Quick Settings tile and any placed
     * home-screen widget. Set once from `YukariApp.onCreate`.
     *
     * A hook, because `core/` must not depend on `vpn/`. The previous version dodged that
     * edge by naming both classes in string literals, which meant renaming or moving either
     * one broke the sync at runtime with nothing to catch it at compile time, on a sync the
     * design makes load-bearing: the preceding `Disconnected` transition has already pushed
     * an "off" update, so without this re-sync tile and widget keep claiming it. It
     * also meant `TunnelControllerTest` passed only because two `runCatching` blocks were
     * swallowing an uninitialized `AppContext`; with a hook, a JVM test simply has none.
     */
    var surfaceSync: (() -> Unit)? = null

    /** @see surfaceSync */
    private fun syncStatusSurfaces() {
        surfaceSync?.invoke()
    }

    /** [up]/[down] are cumulative byte totals; [upRate]/[downRate] are bytes/sec. */
    fun onStats(up: Long, down: Long, upRate: Long, downRate: Long) {
        uplinkBytes = up
        downlinkBytes = down
        uplinkRate = upRate
        downlinkRate = downRate
    }

    /** Cumulative byte totals for the `direct` outbound (LAN / bypassed traffic). */
    fun onDirectStats(up: Long, down: Long) {
        directUplinkBytes = up
        directDownlinkBytes = down
    }
}
