package dev.yukaribox.vpn

import android.net.VpnService
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import dev.yukaribox.vpn.core.Logs
import dev.yukaribox.vpn.core.ServiceMode
import dev.yukaribox.vpn.core.SettingsStore
import dev.yukaribox.vpn.core.TunnelController
import dev.yukaribox.vpn.data.NodeRepository
import dev.yukaribox.vpn.vpn.TunnelLauncher

/**
 * Headless, **non-exported** control surface for connect / disconnect / toggle.
 *
 * It is deliberately separate from the exported [MainActivity]: a launcher
 * activity cannot carry an `android:permission` without breaking the home-screen
 * icon, so an exported activity that acts on control intents lets any installed
 * app toggle the VPN. Keeping the control logic here — with `exported="false"` —
 * means only the system (launching our published shortcuts / Quick Settings tile)
 * and our own process can reach it. There is no UI; it finishes immediately,
 * showing only the system VPN-consent dialog when consent is still required.
 */
class ControlActivity : ComponentActivity() {

    private val consent =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) startWhenReady()
            finish()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val action = intent?.action
        val auto = intent?.getBooleanExtra(EXTRA_AUTO_CONNECT, false) == true
        Logs.i("Control", "action=$action auto=$auto")
        when {
            action == ACTION_DISCONNECT -> {
                if (TunnelController.state.isActive) TunnelLauncher.stop(this)
                finish()
            }
            action == ACTION_TOGGLE && TunnelController.state.isActive -> {
                TunnelLauncher.stop(this)
                finish()
            }
            action == ACTION_CONNECT || action == ACTION_TOGGLE || auto -> connect()
            else -> finish()
        }
    }

    private fun connect() {
        if (TunnelController.state.isActive) {
            finish()
            return
        }
        val prepare =
            if (SettingsStore.data.serviceMode == ServiceMode.ProxyOnly) null else VpnService.prepare(this)
        if (prepare != null) {
            consent.launch(prepare)
        } else {
            startWhenReady()
            finish()
        }
    }

    /**
     * Subscriptions load asynchronously; wait for them before reading the node.
     *
     * The *application* context, and a daemon thread. `finish()` runs immediately after this
     * returns, so passing `this` retained a destroyed Activity for the whole of
     * `awaitLoaded`'s wait, and the service only ever needed an application context to be
     * started with.
     */
    private fun startWhenReady() {
        val context = applicationContext
        Thread {
            NodeRepository.awaitLoaded()
            TunnelLauncher.start(context)
        }.also { it.isDaemon = true }.start()
    }

    companion object {
        const val EXTRA_AUTO_CONNECT = "dev.yukaribox.vpn.AUTO_CONNECT"
        const val ACTION_CONNECT = "dev.yukaribox.vpn.action.CONNECT"
        const val ACTION_DISCONNECT = "dev.yukaribox.vpn.action.DISCONNECT"
        const val ACTION_TOGGLE = "dev.yukaribox.vpn.action.TOGGLE"
    }
}
