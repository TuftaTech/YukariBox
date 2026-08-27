package dev.yukaribox.vpn.vpn

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.service.quicksettings.TileService
import dev.yukaribox.vpn.MainActivity
import dev.yukaribox.vpn.R
import dev.yukaribox.vpn.core.AppContext
import dev.yukaribox.vpn.core.NodeGeo
import dev.yukaribox.vpn.core.SettingsData
import dev.yukaribox.vpn.core.SettingsStore
import dev.yukaribox.vpn.core.TunnelController
import java.util.Locale

/**
 * Helpers lifted out of [YukariVpnService]'s class body. Same reason
 * [PerAppRouting], [sleepUnlessCancelled] and `attemptConnect` live outside it: the
 * service is at its detekt function-count budget, and anything that does not need
 * the service's own state is easier to read (and to test) as a plain function.
 *
 * [PerAppRouting] itself stays free of `VpnService.Builder` so the routing *decision*
 * remains unit-testable; this file is the thin Android-facing half that applies it.
 */

/**
 * Re-render the two status surfaces that cannot observe Compose state: the Quick Settings
 * tile and any placed home-screen widget. Wired into [TunnelController.surfaceSync] from
 * `YukariApp.onCreate`, which is what lets `core/` drive them without depending on `vpn/`.
 */
internal fun syncStatusSurfaces() {
    val context = AppContext.context
    // Keep the Quick Settings tile in sync while the QS panel is open.
    runCatching {
        TileService.requestListeningState(
            context,
            ComponentName(context, QuickTileService::class.java),
        )
    }
    // Re-render any placed home-screen widgets so they track the new state.
    runCatching {
        val manager = AppWidgetManager.getInstance(context)
        val provider = ComponentName(context, VpnWidgetProvider::class.java)
        val ids = manager.getAppWidgetIds(provider)
        if (ids.isNotEmpty()) {
            context.sendBroadcast(
                Intent(AppWidgetManager.ACTION_APPWIDGET_UPDATE)
                    .setComponent(provider)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids),
            )
        }
    }
}

/**
 * Post or cancel the captive-portal notice.
 *
 * The caller owns the "is it already showing" flag, because that is service state; this is only
 * the notification half, lifted out so the service class stays inside its function budget.
 */
internal fun Context.showCaptivePortalNotice(show: Boolean) {
    val manager = getSystemService(NotificationManager::class.java)
    runCatching {
        if (show) {
            manager.notify(
                YukariVpnService.CAPTIVE_NOTIFICATION_ID,
                buildTunnelNotification(
                    getString(R.string.notif_captive),
                    getString(R.string.notif_captive_body),
                    channelId = YukariVpnService.ALERT_CHANNEL_ID,
                    ongoing = false,
                ),
            )
        } else {
            manager.cancel(YukariVpnService.CAPTIVE_NOTIFICATION_ID)
        }
    }
}

/**
 * Apply the per-app split tunnel to this builder. Include mode = only listed apps go
 * through the VPN; exclude mode = listed apps bypass it. Our own package is never
 * routed through the tunnel (avoids a loop). Unknown packages are skipped, because
 * `addAllowedApplication`/`addDisallowedApplication` throw for a package that has
 * since been uninstalled and one stale entry must not fail the whole establish.
 */
internal fun VpnService.Builder.applyPerAppPlan(selfPackage: String, settings: SettingsData) {
    val plan = PerAppRouting.plan(settings.perAppProxyInclude, settings.perAppPackages, selfPackage)
    for (pkg in plan.allowed) runCatching { addAllowedApplication(pkg) }
    for (pkg in plan.disallowed) runCatching { addDisallowedApplication(pkg) }
}

/**
 * True when the user's per-app plan leaves apps outside the tunnel — include mode, or
 * exclude mode with something beyond our own package.
 *
 * Read off [PerAppRouting.plan] rather than off the settings fields directly, so the
 * fail-closed notification's wording is derived from the very same decision that built
 * the TUN and cannot drift from it. Lives here for the function-count reason above.
 */
internal fun Context.splitTunnelInUse(): Boolean {
    val settings = SettingsStore.data
    return splitTunnelInUse(settings.perAppProxyInclude, settings.perAppPackages, packageName)
}

/**
 * The decision itself, without a [Context] or the global store, so it is unit-testable and
 * so Home can ask the same question the fail-closed notification asks. That notification's
 * honesty rests on the wording being unable to drift from the routing; sharing one pure
 * function is what makes that true of the security line as well.
 */
internal fun splitTunnelInUse(include: Boolean, packages: Set<String>, selfPackage: String): Boolean {
    val plan = PerAppRouting.plan(include, packages, selfPackage)
    return plan.allowed.isNotEmpty() || plan.disallowed.any { it != selfPackage }
}

/**
 * Human-readable byte count for the ongoing notification. Explicit [Locale.US] so the
 * decimal separator never depends on the device locale (the two older copies in the
 * UI layer are grandfathered in the detekt baseline; they fold into one helper when
 * the UI is reworked).
 */
internal fun formatTraffic(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble() / 1024
    var idx = 0
    while (value >= 1024 && idx < units.size - 1) {
        value /= 1024
        idx++
    }
    return String.format(Locale.US, "%.1f %s", value, units[idx])
}

/**
 * "Connected · node" text for the ongoing notification, honouring the
 * `nodeInNotification` opt-out. Falls back to the plain "Connected" string whenever
 * the user asked not to see the node name, or no session profile is set.
 */
internal fun Context.connectedText(): String {
    val node = TunnelController.connectedProfile?.name?.let { NodeGeo.plainName(it) }
    return if (SettingsStore.data.nodeInNotification && node != null) {
        getString(R.string.notif_status_connected_node, node)
    } else {
        getString(R.string.notif_status_connected)
    }
}

/**
 * Refresh the ongoing notification with the current rates and totals. Lives here
 * rather than in the service class for the function-count reason above; the caller
 * checks that the tunnel is still Connected before posting.
 */
internal fun Context.postTrafficNotification(upRate: Long, downRate: Long, up: Long, down: Long) {
    val traffic = "↑ ${formatTraffic(upRate)}/s  ↓ ${formatTraffic(downRate)}/s" +
        "   (Σ ↑ ${formatTraffic(up)} ↓ ${formatTraffic(down)})"
    runCatching {
        getSystemService(NotificationManager::class.java)
            .notify(YukariVpnService.NOTIFICATION_ID, buildTunnelNotification(connectedText(), traffic))
    }
}

/**
 * The two PendingIntents every tunnel notification carries, built once per process.
 *
 * `PendingIntent.getActivity`/`getService` are binder round-trips into the system server, and
 * [buildTunnelNotification] was making both on every post. At the 4 s traffic cadence that is
 * about 1 800 avoidable crossings an hour on a session nobody is watching, and roughly 43 000
 * a day on an always-on tunnel. Nothing about either intent is per-post: both are immutable
 * and name a fixed explicit component.
 *
 * Not synchronized. The worst a race can do is build one extra pair, and the platform hands
 * back the same canonical PendingIntent for an equal request anyway.
 */
private object TunnelIntents {

    private var cachedOpen: PendingIntent? = null
    private var cachedStop: PendingIntent? = null

    fun open(context: Context): PendingIntent = cachedOpen ?: PendingIntent.getActivity(
        context, 0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE,
    ).also { cachedOpen = it }

    fun stop(context: Context): PendingIntent = cachedStop ?: PendingIntent.getService(
        context, 1,
        Intent(context, YukariVpnService::class.java).setAction(YukariVpnService.ACTION_STOP),
        PendingIntent.FLAG_IMMUTABLE,
    ).also { cachedStop = it }
}

/**
 * Build a tunnel notification. Both PendingIntents stay `FLAG_IMMUTABLE` and target
 * explicit components of this app, so no other app can rewrite or reuse them to drive
 * the tunnel.
 */
internal fun Context.buildTunnelNotification(
    text: String,
    subText: String? = null,
    channelId: String = YukariVpnService.CHANNEL_ID,
    ongoing: Boolean = true,
): Notification {
    return Notification.Builder(this, channelId)
        .setContentTitle(getString(R.string.app_name))
        .setContentText(text)
        .apply { subText?.let { style = Notification.BigTextStyle().bigText("$text\n$it") } }
        .setSmallIcon(R.drawable.ic_notification)
        .setContentIntent(TunnelIntents.open(this))
        .addAction(
            Notification.Action.Builder(
                null,
                getString(R.string.action_stop),
                TunnelIntents.stop(this),
            ).build(),
        )
        .setOngoing(ongoing)
        .setOnlyAlertOnce(true)
        .build()
}
