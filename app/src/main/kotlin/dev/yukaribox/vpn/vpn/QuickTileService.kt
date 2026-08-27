package dev.yukaribox.vpn.vpn

import android.net.VpnService
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import dev.yukaribox.vpn.R
import dev.yukaribox.vpn.core.Logs
import dev.yukaribox.vpn.core.NodeGeo
import dev.yukaribox.vpn.core.TunnelController
import dev.yukaribox.vpn.core.TunnelState
import dev.yukaribox.vpn.data.NodeRepository

/**
 * Quick Settings tile: one-tap connect/disconnect with the selected node's name
 * on the tile. Connecting from the tile requires previously granted VPN consent
 * (no Activity here) — otherwise the tap opens the app.
 */
class QuickTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        refresh()
    }

    override fun onClick() {
        super.onClick()
        val state = TunnelController.state
        Logs.tap("tile:$state")
        when {
            state.isActive -> TunnelLauncher.stop(this)
            state == TunnelState.Disconnected -> {
                if (VpnService.prepare(this) != null) {
                    // Consent missing — bounce through the headless control activity,
                    // which shows the system consent dialog and connects.
                    val intent = android.content.Intent(this, dev.yukaribox.vpn.ControlActivity::class.java)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        .putExtra(dev.yukaribox.vpn.ControlActivity.EXTRA_AUTO_CONNECT, true)
                    startActivityAndCollapseCompat(intent)
                } else {
                    // onClick runs on the main thread; awaitLoaded blocks on disk IO. The
                    // application context and a daemon thread, so a tile service that is
                    // unbound while the load is still waiting is not held by it.
                    val context = applicationContext
                    Thread {
                        NodeRepository.awaitLoaded()
                        TunnelLauncher.start(context)
                    }.also { it.isDaemon = true }.start()
                }
            }
            else -> Unit // transition in progress; ignore taps
        }
        refresh()
    }

    private fun startActivityAndCollapseCompat(intent: android.content.Intent) {
        if (android.os.Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(
                android.app.PendingIntent.getActivity(
                    this, 0, intent, android.app.PendingIntent.FLAG_IMMUTABLE,
                ),
            )
        } else {
            @Suppress("DEPRECATION", "StartActivityAndCollapseDeprecated")
            startActivityAndCollapse(intent)
        }
    }

    private fun refresh() {
        val tile = qsTile ?: return
        val state = TunnelController.state
        tile.state = when (state) {
            TunnelState.Connected -> Tile.STATE_ACTIVE
            TunnelState.Disconnected -> Tile.STATE_INACTIVE
            else -> Tile.STATE_UNAVAILABLE
        }
        tile.label = getString(R.string.app_name)
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            tile.subtitle = subtitle(state)
        }
        tile.updateTile()
    }

    /**
     * Tile subtitle. The fail-closed flags come first: that session reads Disconnected
     * on the state machine while a blocking TUN is still dropping every packet, so
     * "off" would be the one word the tile must not show. The tile stays INACTIVE in
     * that state, so a tap reconnects; releasing the block is offered in the app and
     * in the notification, where there is room to explain what it means.
     */
    private fun subtitle(state: TunnelState): String = when {
        TunnelController.failClosed -> getString(R.string.tile_sub_blocked)
        TunnelController.unprotected -> getString(R.string.tile_sub_unprotected)
        else -> when (state) {
            TunnelState.Connected -> (
                TunnelController.connectedProfile?.name?.takeIf { it.isNotBlank() }
                    ?: NodeRepository.selected()?.node?.displayName
                )?.let { NodeGeo.plainName(it) }
                ?: getString(R.string.tile_sub_connected)
            TunnelState.Connecting -> getString(R.string.tile_sub_connecting)
            TunnelState.Disconnecting -> getString(R.string.tile_sub_disconnecting)
            TunnelState.Disconnected -> getString(R.string.tile_sub_off)
        }
    }
}
