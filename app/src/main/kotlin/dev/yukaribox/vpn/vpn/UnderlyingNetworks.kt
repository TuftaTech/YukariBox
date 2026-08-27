package dev.yukaribox.vpn.vpn

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import dev.yukaribox.vpn.core.Logs
import dev.yukaribox.vpn.core.NetTransport
import dev.yukaribox.vpn.core.TrackedNetwork
import dev.yukaribox.vpn.core.isHandover
import dev.yukaribox.vpn.core.preferredNetwork
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Watches the *physical* networks the tunnel can run over and reports which one it should use.
 *
 * This replaced `registerDefaultNetworkCallback`, which could not do the job: our own TUN
 * becomes this process's default network the instant `establish()` returns, so the callback
 * fired with the VPN itself. Three things followed from that, all of them observed on the
 * device — `LocalDns.underlyingNetwork` was set to the tunnel it exists to avoid resolving
 * through, `setUnderlyingNetworks` was handed the VPN as its own underlying network (visible in
 * `dumpsys connectivity` as `underlying{[<own netId>]}`), and since the process default then
 * stayed the VPN for the whole session, a real Wi-Fi↔LTE switch delivered no callback at all,
 * leaving `reconnectOnNetworkChange` dead exactly when it was supposed to work.
 *
 * The cost of watching every matching network instead of the default is that several are up at
 * once, so the choice becomes ours: [preferredNetwork] makes it, and it is pure and unit-tested
 * because "which interface is the tunnel on" is not a question to answer by inspection.
 *
 * Lives outside [YukariVpnService] for the same reason [PerAppRouting] and `attemptConnect` do:
 * the service sits at detekt's per-class function budget, and this needs nothing from it but
 * three things to call back into.
 *
 * @param onChosen the network to run over, or null when none is up. Always called on a change.
 * @param onHandover a genuine move between two physical networks, gap or no gap.
 * @param onCaptivePortal whether the network actually chosen advertises a sign-in page.
 */
internal class UnderlyingNetworks(
    private val connectivity: ConnectivityManager,
    private val onChosen: (Network?) -> Unit,
    private val onHandover: (from: Network, to: Network) -> Unit,
    private val onCaptivePortal: (Boolean) -> Unit,
) {

    /** Tracked networks and their last known capabilities. Guarded by itself. */
    private val tracked = LinkedHashMap<Network, TrackedNetwork>()

    private val order = AtomicLong(0)

    private val registered = AtomicBoolean(false)

    /** The value last reported through [onChosen]; null while no physical network is up. */
    private var chosen: Network? = null

    /** The last network actually used. Kept across a gap — see [isHandover]. */
    private var lastUsed: Network? = null

    private val callback = object : ConnectivityManager.NetworkCallback() {

        /**
         * Capabilities always follow, but the network is tracked here as well so one that is
         * slow to report them is not invisible in the meantime. An entry with no capabilities
         * yet is unvalidated, so it loses to anything that has them.
         */
        override fun onAvailable(network: Network) {
            put(network) { existing -> existing ?: blank(network) }
        }

        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            // Belt and braces: the request already asks for NET_CAPABILITY_NOT_VPN, and a
            // network that carries TRANSPORT_VPN is either our own TUN or another app's. Neither
            // is something this tunnel can run over, and adopting one is the loop above.
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                drop(network)
                return
            }
            put(network) { existing ->
                TrackedNetwork(
                    handle = network.networkHandle,
                    transport = transportOf(capabilities),
                    validated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
                    captivePortal = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL),
                    seq = existing?.seq ?: order.incrementAndGet(),
                )
            }
        }

        override fun onLost(network: Network) = drop(network)
    }

    /** Start watching. Idempotent; a failed registration leaves this ready to try again. */
    fun register() {
        if (!registered.compareAndSet(false, true)) return
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        runCatching { connectivity.registerNetworkCallback(request, callback) }
            .onFailure {
                registered.set(false)
                Logs.e("Net", "physical network callback registration failed", it)
            }
    }

    /**
     * Stop watching and report "no network".
     *
     * Idempotent, because both teardown paths call it: `cleanupCore` on the worker and
     * `onDestroy` on the main thread, in whichever order the platform runs them.
     * `unregisterNetworkCallback` throws for a callback that was never registered, so the CAS
     * is what makes the second call a no-op rather than a caught exception.
     */
    fun release() {
        if (!registered.compareAndSet(true, false)) return
        runCatching { connectivity.unregisterNetworkCallback(callback) }
        synchronized(tracked) {
            tracked.clear()
            chosen = null
            lastUsed = null
        }
        onChosen(null)
    }

    private fun blank(network: Network) =
        TrackedNetwork(handle = network.networkHandle, seq = order.incrementAndGet())

    private fun put(network: Network, entry: (TrackedNetwork?) -> TrackedNetwork) {
        synchronized(tracked) { tracked[network] = entry(tracked[network]) }
        publish()
    }

    private fun drop(network: Network) {
        val had = synchronized(tracked) { tracked.remove(network) != null }
        if (had) publish()
    }

    /**
     * Recompute the choice and report what changed.
     *
     * The captive-portal state is reported on every call, because it can change without the
     * chosen network changing (a portal that has just been signed into validates in place).
     */
    private fun publish() {
        var next: Network? = null
        var captive = false
        var previous: Network? = null
        var changed = false
        synchronized(tracked) {
            val best = preferredNetwork(tracked.values)
            next = best?.let { pick -> tracked.entries.firstOrNull { it.value === pick }?.key }
            captive = best?.captivePortal == true
            previous = lastUsed
            changed = next != chosen
            if (changed) {
                chosen = next
                if (next != null) lastUsed = next
            }
        }
        onCaptivePortal(captive)
        if (!changed) return
        val target = next
        val from = previous
        onChosen(target)
        if (target != null && from != null && isHandover(from.networkHandle, target.networkHandle)) {
            onHandover(from, target)
        }
    }

    private fun transportOf(capabilities: NetworkCapabilities): NetTransport = when {
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetTransport.Ethernet
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetTransport.Wifi
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetTransport.Cellular
        else -> NetTransport.Other
    }
}
