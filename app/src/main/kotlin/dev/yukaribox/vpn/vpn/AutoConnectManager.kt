package dev.yukaribox.vpn.vpn

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import dev.yukaribox.vpn.core.Logs
import dev.yukaribox.vpn.core.SettingsStore
import dev.yukaribox.vpn.core.TunnelController
import dev.yukaribox.vpn.core.TunnelState
import dev.yukaribox.vpn.data.NodeRepository

/**
 * Optional auto-connect when an internet-capable network becomes available.
 * Complements [BootReceiver] (cold-start auto-connect): while the app process is
 * alive this brings the tunnel up as soon as the device gains connectivity — but
 * only when the user opted in ([SettingsStore]'s `autoConnectOnNetwork`, OFF by
 * default), the tunnel is idle, and VPN consent has already been granted (consent
 * needs an Activity and cannot be requested here). Manual connect stays the default.
 *
 * Registered once for the process lifetime from `YukariApp.onCreate`; the setting
 * is re-read on every callback so toggling it takes effect without re-registering.
 * The request requires NET_CAPABILITY_NOT_VPN, so our own TUN never re-triggers it.
 */
object AutoConnectManager {

    @Volatile
    private var registered = false

    fun register(context: Context) {
        if (registered) return
        val app = context.applicationContext
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        runCatching {
            cm.registerNetworkCallback(
                request,
                object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) = maybeConnect(app)
                },
            )
            registered = true
        }.onFailure { Logs.w("AutoConnect", "network callback registration failed") }
    }

    @Suppress("TooGenericExceptionCaught") // native core can throw Error; mirror BootReceiver
    private fun maybeConnect(context: Context) {
        if (!SettingsStore.data.autoConnectOnNetwork) return
        if (TunnelController.state != TunnelState.Disconnected) return
        if (VpnService.prepare(context) != null) {
            Logs.w("AutoConnect", "network available but VPN consent not granted; skipping")
            return
        }
        Thread {
            try {
                NodeRepository.awaitLoaded()
                // Re-check after the (possibly slow) load so we never race a manual connect.
                if (TunnelController.state != TunnelState.Disconnected) return@Thread
                val name = NodeRepository.selected()?.node?.displayName
                Logs.i("AutoConnect", "network available, connecting node=$name")
                TunnelLauncher.start(context)
            } catch (e: Throwable) {
                Logs.e("AutoConnect", "auto-connect on network failed", e)
            }
        }.also { it.isDaemon = true }.start()
    }
}
