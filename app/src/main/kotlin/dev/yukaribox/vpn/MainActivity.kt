package dev.yukaribox.vpn

import android.Manifest
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.remember
import android.content.Intent
import androidx.core.content.ContextCompat
import dev.yukaribox.vpn.core.Logs
import dev.yukaribox.vpn.core.ServiceMode
import dev.yukaribox.vpn.core.SettingsStore
import dev.yukaribox.vpn.core.ThemeMode
import dev.yukaribox.vpn.core.TunnelController
import dev.yukaribox.vpn.data.NodeRepository
import dev.yukaribox.vpn.ui.YukariBoxApp
import dev.yukaribox.vpn.ui.theme.YukariBoxTheme
import dev.yukaribox.vpn.vpn.TunnelLauncher
import dev.yukaribox.vpn.vpn.YukariVpnService

class MainActivity : ComponentActivity() {

    private val vpnConsentLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            Logs.i("UI", "VPN consent result=${if (result.resultCode == RESULT_OK) "granted" else "denied"}")
            if (result.resultCode == RESULT_OK) startTunnelNow()
        }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* best-effort */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        maybeRequestNotificationPermission()
        publishShortcuts()
        setContent {
            val mode = SettingsStore.themeMode
            val dark = when (mode) {
                ThemeMode.System -> isSystemInDarkTheme()
                ThemeMode.Light -> false
                ThemeMode.Dark -> true
            }
            YukariBoxTheme(darkTheme = dark) {
                // Remembered, because `this::onToggleConnection` allocates a fresh function
                // object every time this lambda runs and Compose cannot memoise a callable
                // reference for us. Unremembered it was a parameter that changed on every
                // root recomposition, so the shell — drawer sheet, bottom bar and current
                // screen — could never skip.
                YukariBoxApp(onToggleConnection = remember { ::onToggleConnection })
            }
        }
    }

    /** Launcher long-press shortcuts (dynamic — survive applicationId suffixes). */
    private fun publishShortcuts() {
        runCatching {
            val sm = getSystemService(android.content.pm.ShortcutManager::class.java) ?: return
            // Shortcuts target the non-exported ControlActivity; the system launcher
            // is allowed to start it on our behalf, but other apps are not.
            fun shortcut(id: String, label: String, action: String) =
                android.content.pm.ShortcutInfo.Builder(this, id)
                    .setShortLabel(label)
                    .setIcon(android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_notification))
                    .setIntent(
                        Intent(this, ControlActivity::class.java)
                            .setAction(action)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
                    )
                    .build()
            sm.dynamicShortcuts = listOf(
                shortcut("toggle", getString(R.string.shortcut_toggle), ControlActivity.ACTION_TOGGLE),
                shortcut("connect", getString(R.string.shortcut_connect), ControlActivity.ACTION_CONNECT),
                shortcut("disconnect", getString(R.string.shortcut_disconnect), ControlActivity.ACTION_DISCONNECT),
            )
        }
    }

    private fun onToggleConnection() {
        Logs.tap(if (TunnelController.state.isActive) "disconnect" else "connect")
        if (TunnelController.state.isActive) {
            YukariVpnService.stop(this)
        } else {
            // Proxy-only mode serves a local inbound and needs no VPN consent.
            val prepareIntent =
                if (SettingsStore.data.serviceMode == ServiceMode.ProxyOnly) null
                else VpnService.prepare(this)
            if (prepareIntent != null) {
                Logs.i("UI", "requesting VPN consent")
                vpnConsentLauncher.launch(prepareIntent)
            } else {
                startTunnelNow()
            }
        }
    }

    private fun startTunnelNow() {
        Logs.i("UI", "startTunnel node=${NodeRepository.selected()?.node?.displayName ?: "(none selected)"}")
        TunnelLauncher.start(this)
    }

    private fun maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

}
