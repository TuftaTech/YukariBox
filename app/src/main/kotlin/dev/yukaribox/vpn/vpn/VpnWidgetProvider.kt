package dev.yukaribox.vpn.vpn

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import dev.yukaribox.vpn.ControlActivity
import dev.yukaribox.vpn.R
import dev.yukaribox.vpn.core.TunnelController
import dev.yukaribox.vpn.core.TunnelState

/**
 * Home-screen widget: one tap toggles the VPN, and the status line tracks the
 * tunnel state. Tapping fires [ControlActivity.ACTION_TOGGLE], which connects
 * (showing the consent dialog when needed) or disconnects depending on the
 * current state — the same path the Quick Settings tile uses.
 *
 * Live updates: [TunnelController.onState] broadcasts an APPWIDGET_UPDATE to
 * this provider on every transition, so every placed widget re-renders without
 * this class holding any state of its own.
 */
class VpnWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { render(context, manager, it) }
    }

    private fun render(context: Context, manager: AppWidgetManager, id: Int) {
        val views = RemoteViews(context.packageName, R.layout.widget_vpn)
        views.setTextViewText(R.id.widget_status, statusLabel(context))
        val toggle = Intent(context, ControlActivity::class.java)
            .setAction(ControlActivity.ACTION_TOGGLE)
        val pending = PendingIntent.getActivity(
            context,
            0,
            toggle,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        views.setOnClickPendingIntent(R.id.widget_root, pending)
        manager.updateAppWidget(id, views)
    }

    /**
     * The status line. A fail-closed session reads Disconnected on the state machine
     * while a blocking TUN is still dropping every packet, so "Disconnected" would be
     * the one label the widget must not show — the flags are checked first.
     */
    private fun statusLabel(context: Context): String =
        context.getString(
            when {
                TunnelController.failClosed -> R.string.widget_status_blocked
                TunnelController.unprotected -> R.string.widget_status_unprotected
                else -> when (TunnelController.state) {
                    TunnelState.Connected -> R.string.widget_status_connected
                    TunnelState.Connecting -> R.string.widget_status_connecting
                    TunnelState.Disconnecting -> R.string.widget_status_disconnecting
                    TunnelState.Disconnected -> R.string.widget_status_disconnected
                }
            },
        )
}
