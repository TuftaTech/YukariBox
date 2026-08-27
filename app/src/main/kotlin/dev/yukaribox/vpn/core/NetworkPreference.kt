package dev.yukaribox.vpn.core

/**
 * The transports a tunnel can run over, and how much this app prefers each.
 *
 * The order mirrors the platform's own: a wired link beats Wi-Fi, Wi-Fi beats cellular. It is
 * a *preference*, not a rule — [preferredNetwork] weighs validation first, because a network
 * that cannot reach the internet is no use however fast its transport is.
 */
enum class NetTransport(val preference: Int) {
    Ethernet(3),
    Wifi(2),
    Cellular(1),

    /** Bluetooth, USB tethering, a VPN belonging to another app, or capabilities not read yet. */
    Other(0),
}

/**
 * One physical network the service is tracking, reduced to what the choice depends on.
 *
 * Deliberately free of `android.net.Network`, so the choice below is a pure fold that a JVM
 * test can drive: the service keeps the real handles beside these and maps back by [handle].
 *
 * [captivePortal] takes no part in the choice — a captive network is never `validated`, so it
 * already loses to anything that is — and is carried here so the caller can tell the user
 * about the network it actually settled on rather than about any network it can see.
 */
data class TrackedNetwork(
    val handle: Long,
    val transport: NetTransport = NetTransport.Other,
    val validated: Boolean = false,
    val captivePortal: Boolean = false,
    /** Arrival order. Breaks a tie in favour of the network that appeared most recently. */
    val seq: Long = 0,
)

/**
 * The network a tunnel should run over, or null when no physical network is up.
 *
 * Needed because the service no longer watches the *default* network. It cannot: our own TUN
 * becomes this process's default the moment it is established, so the default-network callback
 * fired with the VPN itself — which meant `LocalDns` was handed the tunnel it exists to avoid
 * resolving through, `setUnderlyingNetworks` named the VPN as its own transport, and, because
 * the process default then never changed again for the life of the session, a real Wi-Fi↔LTE
 * handover produced no callback at all. Watching every INTERNET-capable non-VPN network instead
 * means several arrive at once, so the app has to make this choice itself.
 *
 * Validation comes first, then the transport preference, then recency. Ordering validation
 * above transport is what makes a captive-portal Wi-Fi lose to a working LTE connection.
 */
internal fun preferredNetwork(tracked: Collection<TrackedNetwork>): TrackedNetwork? =
    tracked.maxWithOrNull(
        compareBy(
            { if (it.validated) 1 else 0 },
            { it.transport.preference },
            { it.seq },
        ),
    )

/**
 * Whether moving from [previous] to [chosen] is a handover worth resetting connections for.
 *
 * [previous] is the last network actually used and **not** the immediately preceding value, so
 * it survives a gap: dropping Wi-Fi usually reports `onLost` before cellular's `onAvailable`,
 * and comparing against the momentary null in between would classify the most ordinary handover
 * on the device as two non-events. Neither end being null and the two differing is the whole
 * rule; a first attach (no previous) has no stale connection to reset.
 */
internal fun isHandover(previous: Long?, chosen: Long?): Boolean =
    previous != null && chosen != null && previous != chosen
