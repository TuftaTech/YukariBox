package dev.yukaribox.vpn.vpn

import android.content.Context
import dev.yukaribox.vpn.core.ConfigBuilder
import dev.yukaribox.vpn.core.ConnectedProfile
import dev.yukaribox.vpn.core.Logs
import dev.yukaribox.vpn.core.SettingsStore
import dev.yukaribox.vpn.core.TunnelController
import dev.yukaribox.vpn.core.TunnelState
import dev.yukaribox.vpn.core.UnsupportedConfigException
import dev.yukaribox.vpn.data.NodeRepository

/**
 * Builds the sing-box config from the current node + settings and drives the
 * [YukariVpnService]. Shared by the in-app "Reconnect" action and the debug
 * adb-control receiver so both apply settings the same way. Connecting from a
 * cold state still needs VPN consent (an Activity) — that path stays in
 * `MainActivity`; [start]/[reconnect] here assume consent is already granted
 * (true whenever the tunnel is already active).
 */
object TunnelLauncher {

    /**
     * Build the full config for the selected node.
     *
     * Throws [UnsupportedConfigException] when nothing is selected. It used to fall
     * back to [ConfigBuilder.buildDirectConfig] — a dev smoke-test helper whose "proxy"
     * outbound is plain `direct` — which meant a fresh install with no nodes would
     * report CONNECTED while every packet went out unproxied. Refusing to start is the
     * only honest answer; `buildDirectConfig` stays for on-device pipeline testing.
     */
    fun buildConfig(context: Context): String {
        val selected = NodeRepository.selected()
            ?: throw UnsupportedConfigException("no node selected")
        val options = SettingsStore.configOptions(logOutput = "${context.filesDir.absolutePath}/box.log")
        return ConfigBuilder.buildConfig(selected.node, options)
    }

    /** Start the tunnel with a freshly built config. */
    fun start(context: Context) {
        // Refused up front when the native core never initialized: every path below ends in
        // `newSingBoxInstance`, which cannot work, and the failure would otherwise present
        // as three silent retries followed by an armed kill switch.
        if (!TunnelController.coreReady) {
            Logs.e("Launcher", "connect refused: native core failed to initialize")
            TunnelController.lastError = "core not initialized"
            return
        }
        try {
            val config = buildConfig(context)
            val selected = NodeRepository.selected()
            TunnelController.connectedProfile = ConnectedProfile(
                subId = NodeRepository.selectedSubId,
                nodeId = selected?.id,
                name = selected?.node?.displayName ?: "",
            )
            YukariVpnService.start(context, config)
        } catch (e: UnsupportedConfigException) {
            Logs.e("Launcher", "config build failed", e)
            TunnelController.lastError = e.message
        }
    }

    fun stop(context: Context) = YukariVpnService.stop(context)

    /**
     * Stop the running tunnel and start it again to apply config changes live. A no-op
     * when the tunnel is idle: "Reconnect now" used to *start* a tunnel the user never
     * asked for (and could land it in the fail-closed state). Use [start] to connect.
     */
    fun reconnect(context: Context) {
        Logs.i("Launcher", "reconnect requested")
        if (!TunnelController.state.isActive) {
            Logs.i("Launcher", "reconnect ignored: tunnel is idle")
            return
        }
        stop(context)
        // Wait for the service to actually reach Disconnected before re-arming, instead of a
        // fixed delay that could race a slow core shutdown and drop the restart (leaving the
        // tunnel down). Bounded so we never hang, and holding the *application* context: the
        // caller is a settings row, so `context` is an Activity that must not be pinned for
        // the length of the wait.
        val app = context.applicationContext
        Thread {
            val deadline = System.currentTimeMillis() + 5_000
            while (TunnelController.state != TunnelState.Disconnected &&
                System.currentTimeMillis() < deadline
            ) {
                runCatching { Thread.sleep(50) }
            }
            start(app)
        }.also { it.isDaemon = true }.start()
    }
}
