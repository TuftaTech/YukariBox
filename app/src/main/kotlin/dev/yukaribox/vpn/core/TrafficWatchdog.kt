package dev.yukaribox.vpn.core

/**
 * Decides when a *connected* tunnel looks dead.
 *
 * The service used to supervise the node only while connecting: once the probe
 * succeeded the loop exited and nothing watched the session again. A node blocked
 * mid-session (the common censorship case) therefore left the UI reporting
 * "Connected" indefinitely, with the stats line simply frozen at zero — no reconnect,
 * and the kill switch never armed, because nothing detected the failure.
 *
 * Sampling traffic is the cheap signal: a tunnel passing bytes is alive by definition,
 * so only a tunnel that has passed *nothing* for [idleTicksBeforeProbe] consecutive
 * samples is worth spending an active probe on. That keeps a busy tunnel completely
 * untouched and bounds probing to at most one per idle window.
 *
 * Pure and clock-free so the windowing is unit-tested without real delays.
 */
class TrafficWatchdog(private val idleTicksBeforeProbe: Int = DEFAULT_IDLE_TICKS) {

    init {
        require(idleTicksBeforeProbe >= 1) { "idleTicksBeforeProbe must be >= 1" }
    }

    private var idleTicks = 0

    /** Consecutive idle samples seen so far (exposed for logging/tests). */
    val idle: Int get() = idleTicks

    /**
     * Feed one traffic sample (bytes/second in each direction, one per second).
     *
     * Returns true when the idle window just closed and the caller should actively
     * probe the tunnel. The window resets on that signal, so a still-idle tunnel is
     * probed once per window rather than every second.
     */
    fun onSample(upRate: Long, downRate: Long): Boolean {
        if (upRate > 0L || downRate > 0L) {
            idleTicks = 0
            return false
        }
        idleTicks += 1
        if (idleTicks < idleTicksBeforeProbe) return false
        idleTicks = 0
        return true
    }

    /** Forget the current window (e.g. after a successful probe or a reconnect). */
    fun reset() {
        idleTicks = 0
    }

    companion object {
        /** 60 one-second samples: a minute of complete silence before probing. */
        const val DEFAULT_IDLE_TICKS = 60
    }
}
