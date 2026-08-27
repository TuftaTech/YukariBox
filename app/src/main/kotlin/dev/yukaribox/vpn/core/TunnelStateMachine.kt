package dev.yukaribox.vpn.core

/**
 * Guards tunnel state transitions so the service can never wedge in an
 * inconsistent state. Only the legal edges of the lifecycle graph are allowed;
 * an abrupt [fail] collapses any active state back to Disconnected.
 *
 * This class is intentionally free of Android / coroutine dependencies so it can
 * be exhaustively unit-tested and driven from any thread (transitions are
 * synchronized).
 */
class TunnelStateMachine(
    initial: TunnelState = TunnelState.Disconnected,
    private val onChange: (TunnelState) -> Unit = {},
) {
    var state: TunnelState = initial
        private set

    private val allowed: Map<TunnelState, Set<TunnelState>> = mapOf(
        TunnelState.Disconnected to setOf(TunnelState.Connecting),
        TunnelState.Connecting to setOf(TunnelState.Connected, TunnelState.Disconnecting, TunnelState.Disconnected),
        TunnelState.Connected to setOf(TunnelState.Disconnecting, TunnelState.Disconnected),
        TunnelState.Disconnecting to setOf(TunnelState.Disconnected),
    )

    /** Whether moving from the current state to [target] is a legal edge. */
    fun canTransitionTo(target: TunnelState): Boolean =
        target == state || allowed[state]?.contains(target) == true

    /**
     * Attempt a transition. Returns true if it occurred (or was a redundant
     * no-op to the same state), false if the edge is illegal. Notifies [onChange]
     * only on an actual state change.
     */
    @Synchronized
    fun transitionTo(target: TunnelState): Boolean {
        if (target == state) return true
        if (allowed[state]?.contains(target) != true) return false
        state = target
        onChange(target)
        return true
    }

    // Convenience intents mirroring the service lifecycle.
    fun beginConnecting(): Boolean = transitionTo(TunnelState.Connecting)
    fun markConnected(): Boolean = transitionTo(TunnelState.Connected)
    fun beginDisconnecting(): Boolean = transitionTo(TunnelState.Disconnecting)
    fun markDisconnected(): Boolean = transitionTo(TunnelState.Disconnected)

    /** Force-collapse to Disconnected from any active state (error path). */
    @Synchronized
    fun fail(): Boolean {
        if (state == TunnelState.Disconnected) return false
        state = TunnelState.Disconnected
        onChange(state)
        return true
    }
}
