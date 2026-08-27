package dev.yukaribox.vpn.core

/**
 * Lifecycle states of the VPN tunnel. The canonical flow is
 * Disconnected → Connecting → Connected → Disconnecting → Disconnected.
 */
enum class TunnelState {
    Disconnected,
    Connecting,
    Connected,
    Disconnecting;

    /** True while the tunnel is either coming up or established. */
    val isActive: Boolean get() = this == Connecting || this == Connected
}
