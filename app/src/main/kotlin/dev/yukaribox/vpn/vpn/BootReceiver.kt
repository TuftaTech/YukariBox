package dev.yukaribox.vpn.vpn

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import dev.yukaribox.vpn.core.Logs
import dev.yukaribox.vpn.core.SettingsStore
import dev.yukaribox.vpn.data.NodeRepository

/**
 * Starts the tunnel after boot when the user enabled auto-connect. VPN consent
 * cannot be requested from a receiver, so this only works once consent has been
 * granted (VpnService.prepare returns null) — the standard NekoBox behavior.
 * Application.onCreate has already initialized stores by the time this runs;
 * we only wait for the async subscription load before reading the selected node.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        if (!SettingsStore.data.autoConnectOnBoot) return
        if (VpnService.prepare(context) != null) {
            Logs.w("Boot", "auto-connect skipped: VPN consent not granted")
            return
        }
        val pending = goAsync()
        Thread {
            try {
                NodeRepository.awaitLoaded()
                Logs.i("Boot", "auto-connect ($action), node=${NodeRepository.selected()?.node?.displayName}")
                TunnelLauncher.start(context)
            } catch (e: Throwable) {
                Logs.e("Boot", "auto-connect failed", e)
            } finally {
                pending.finish()
            }
        }.start()
    }
}
