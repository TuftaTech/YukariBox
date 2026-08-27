package dev.yukaribox.vpn.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.VpnService
import dev.yukaribox.vpn.ControlActivity
import dev.yukaribox.vpn.core.Ipv6Mode
import dev.yukaribox.vpn.core.Logs
import dev.yukaribox.vpn.core.ServiceMode
import dev.yukaribox.vpn.core.SettingsGuard
import dev.yukaribox.vpn.core.SettingsStore
import dev.yukaribox.vpn.core.ThemeMode
import dev.yukaribox.vpn.core.TunStack
import dev.yukaribox.vpn.core.TunnelController
import dev.yukaribox.vpn.data.NodeRepository
import dev.yukaribox.vpn.data.AvatarStore
import dev.yukaribox.vpn.proxy.SubscriptionDecoder
import dev.yukaribox.vpn.vpn.TunnelLauncher
import java.io.File

/**
 * Debug-only broadcast surface: drive every meaningful app action from adb so the
 * tunnel can be exercised without touching the screen. Present in debug builds
 * only (this whole source set is excluded from release).
 *
 * ```
 * P=dev.yukaribox.vpn.debug ; A=dev.yukaribox.vpn.CONTROL
 * adb shell am broadcast -p $P -a $A --es cmd connect
 * adb shell am broadcast -p $P -a $A --es cmd disconnect
 * adb shell am broadcast -p $P -a $A --es cmd reconnect
 * adb shell am broadcast -p $P -a $A --es cmd selectNode --ei nodeId 3
 * adb shell am broadcast -p $P -a $A --es cmd selectSub  --es subId <uuid>
 * adb shell am broadcast -p $P -a $A --es cmd set    --es key tunStack --es value gvisor --ez reconnect true
 * adb shell am broadcast -p $P -a $A --es cmd toggle --es key sniffing --ez reconnect true
 * adb shell am broadcast -p $P -a $A --es cmd urlTest
 * adb shell am broadcast -p $P -a $A --es cmd updateSub
 * adb shell am broadcast -p $P -a $A --es cmd toggle --es key logging
 * adb shell am broadcast -p $P -a $A --es cmd setNickname --es value Vasya
 * adb shell am broadcast -p $P -a $A --es cmd setAvatar --es path /sdcard/Android/data/$P/files/a.jpg
 * adb shell am broadcast -p $P -a $A --es cmd clearAvatar
 * adb shell am broadcast -p $P -a $A --es cmd dumpState
 * adb shell am broadcast -p $P -a $A --es cmd clearLog
 * adb shell am broadcast -p $P -a $A --es cmd addGroup --es name Test
 * adb shell am broadcast -p $P -a $A --es cmd importLinks --es links '<newline-separated links>'
 * adb shell am broadcast -p $P -a $A --es cmd setLatency --ei nodeId 0 --ei ms 42
 * adb shell am broadcast -p $P -a $A --es cmd fakeState --es view Blocked|Unprotected|Connecting|Connected|Clear
 * ```
 */
class AdbControlReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val cmd = intent.getStringExtra("cmd") ?: run {
            Logs.w("Adb", "broadcast with no 'cmd' extra")
            return
        }
        val reconnect = intent.getBooleanExtra("reconnect", false)
        Logs.i("Adb", "cmd=$cmd ${intent.extras?.keySet()?.filter { it != "cmd" }}")

        when (cmd) {
            "connect" -> connect(context)
            "disconnect" -> TunnelLauncher.stop(context)
            "reconnect" -> TunnelLauncher.reconnect(context)
            "selectNode" -> NodeRepository.select(intent.getIntExtra("nodeId", -1))
            "selectSub" -> intent.getStringExtra("subId")?.let { NodeRepository.selectSubscription(it) }
            "set" -> applySetting(intent.getStringExtra("key"), intent.getStringExtra("value"), reconnect, context)
            "toggle" -> toggle(intent.getStringExtra("key"), reconnect, context)
            "urlTest" -> dev.yukaribox.vpn.core.UrlTestEngine.testAll()
            "updateSub" -> NodeRepository.updateActiveSubscription()
            "setNickname" -> setNickname(intent.getStringExtra("value"))
            "setAvatar" -> setAvatar(intent.getStringExtra("path"))
            "clearAvatar" -> offMainThread("clearAvatar") { AvatarStore.clear() }
            "dumpState" -> dumpState()
            "clearLog" -> dev.yukaribox.vpn.core.LogReader.clear()
            "fakeState" -> fakeState(intent.getStringExtra("view"))
            "addGroup" -> addGroup(intent.getStringExtra("name"))
            "importLinks" -> importLinks(intent.getStringExtra("links"))
            "setLatency" -> NodeRepository.setLatency(
                id = intent.getIntExtra("nodeId", -1),
                latencyMs = intent.getIntExtra("ms", 0),
                subId = NodeRepository.activeSubId,
            )
            else -> Logs.w("Adb", "unknown cmd: $cmd")
        }
    }

    /**
     * Force the *display* state without a real session, so all five hero states can
     * be inspected on demand.
     *
     * Blocked and Unprotected are otherwise only reachable by making a live tunnel
     * collapse and its kill switch succeed or fail, which depends on the node, the
     * network and the core's probe — not something a UI check can arrange. This only
     * writes the flags the UI folds into its display state; it installs no TUN and
     * starts no service, so it cannot be mistaken for a working tunnel.
     */
    private fun fakeState(view: String?) {
        when (view) {
            "Blocked" -> {
                TunnelController.onState(dev.yukaribox.vpn.core.TunnelState.Disconnected)
                TunnelController.onFailClosed(blockingTunHeld = true)
            }
            "Unprotected" -> {
                TunnelController.onState(dev.yukaribox.vpn.core.TunnelState.Disconnected)
                TunnelController.onFailClosed(blockingTunHeld = false)
            }
            "Connecting" -> TunnelController.onState(dev.yukaribox.vpn.core.TunnelState.Connecting)
            // The connected banner and the Stats header are unreachable otherwise: both
            // read `connectedProfile`, which only a real session writes. The profile is
            // taken from the current selection so the card names a node that exists,
            // and — like every other branch here — no TUN is installed and no service is
            // started, so this cannot be mistaken for a working tunnel.
            "Connected" -> {
                TunnelController.clearFailClosed()
                val entry = NodeRepository.selected()
                TunnelController.connectedProfile = dev.yukaribox.vpn.core.ConnectedProfile(
                    subId = NodeRepository.selectedSubId,
                    nodeId = entry?.id,
                    name = entry?.node?.displayName.orEmpty(),
                )
                TunnelController.onState(dev.yukaribox.vpn.core.TunnelState.Connected)
            }
            "Clear" -> {
                TunnelController.clearFailClosed()
                TunnelController.onState(dev.yukaribox.vpn.core.TunnelState.Disconnected)
            }
            else -> Logs.w(
                "Adb",
                "fakeState needs --es view Blocked|Unprotected|Connecting|Connected|Clear",
            )
        }
        Logs.i(
            "Adb",
            "fakeState=$view failClosed=${TunnelController.failClosed} " +
                "unprotected=${TunnelController.unprotected}",
        )
    }

    /** Create a manual group (no URL) and make it active. */
    private fun addGroup(name: String?) {
        if (name.isNullOrBlank()) {
            Logs.w("Adb", "addGroup needs --es name")
            return
        }
        val group = NodeRepository.createGroup(name)
        NodeRepository.selectSubscription(group.id)
        Logs.i("Adb", "created group '$name' (${group.id})")
    }

    /**
     * Parse newline-separated share links into the active group. Goes through the
     * same [SubscriptionDecoder] path a clipboard import uses, so what lands in the
     * list is exactly what a real paste would produce.
     */
    private fun importLinks(links: String?) {
        if (links.isNullOrBlank()) {
            Logs.w("Adb", "importLinks needs --es links")
            return
        }
        val report = SubscriptionDecoder.decodeReport(links)
        val added = NodeRepository.addNodes(report.nodes)
        Logs.i("Adb", "importLinks: parsed ${report.nodes.size}, added $added")
    }

    private fun connect(context: Context) {
        if (TunnelController.state.isActive) {
            Logs.i("Adb", "connect ignored: already ${TunnelController.state}")
            return
        }
        // VPN consent needs an Activity. If already granted, prepare() is null and
        // we can start headless; otherwise bounce through MainActivity to ask.
        val needsConsent = SettingsStore.data.serviceMode != ServiceMode.ProxyOnly &&
            VpnService.prepare(context) != null
        if (needsConsent) {
            Logs.i("Adb", "consent required — launching ControlActivity to grant + connect")
            context.startActivity(
                Intent(context, ControlActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra(ControlActivity.EXTRA_AUTO_CONNECT, true),
            )
        } else {
            TunnelLauncher.start(context)
        }
    }

    private fun toggle(key: String?, reconnect: Boolean, context: Context) {
        if (key == null) return
        SettingsStore.update { s ->
            when (key) {
                "sniffing" -> s.copy(sniffing = !s.sniffing)
                "bypassLan" -> s.copy(bypassLan = !s.bypassLan)
                "enableDnsRouting" -> s.copy(enableDnsRouting = !s.enableDnsRouting)
                "allowInsecure" -> s.copy(allowInsecure = !s.allowInsecure)
                "nodeInNotification" -> s.copy(nodeInNotification = !s.nodeInNotification)
                "animations" -> s.copy(animations = !s.animations)
                // Journalling is off by default, so this is how a headless run gets its lines
                // back. Through `Logs.setEnabled` rather than a copy, so switching off wipes.
                "logging" -> { offMainThread("logging") { Logs.setEnabled(!s.logging) }; s }
                "autoUpdate" -> s.copy(autoUpdate = !s.autoUpdate)
                else -> { Logs.w("Adb", "toggle: unknown bool key $key"); s }
            }
        }
        if (reconnect) TunnelLauncher.reconnect(context)
    }

    private fun applySetting(key: String?, value: String?, reconnect: Boolean, context: Context) {
        if (key == null || value == null) {
            Logs.w("Adb", "set needs key and value")
            return
        }
        SettingsStore.update { s ->
            when (key) {
                "serviceMode" -> s.copy(serviceMode = enumOf(ServiceMode.entries, value) ?: s.serviceMode)
                "tunStack" -> s.copy(tunStack = enumOf(TunStack.entries, value) ?: s.tunStack)
                "ipv6Mode" -> s.copy(ipv6Mode = enumOf(Ipv6Mode.entries, value) ?: s.ipv6Mode)
                "mtu" -> s.copy(mtu = (value.toIntOrNull() ?: s.mtu).coerceIn(576, 9000))
                "logging" -> { offMainThread("logging") { Logs.setEnabled(value.toBoolean()) }; s }
                "sniffing" -> s.copy(sniffing = value.toBoolean())
                "bypassLan" -> s.copy(bypassLan = value.toBoolean())
                "enableDnsRouting" -> s.copy(enableDnsRouting = value.toBoolean())
                "allowInsecure" -> s.copy(allowInsecure = value.toBoolean())
                "logLevel" -> s.copy(logLevel = value)
                "remoteDns" -> s.copy(remoteDns = value)
                "directDns" -> s.copy(directDns = value)
                "themeMode" -> s.copy(themeMode = enumOf(ThemeMode.entries, value) ?: s.themeMode)
                "animations" -> s.copy(animations = value.toBoolean())
                else -> { Logs.w("Adb", "set: unknown key $key"); s }
            }
        }
        if (reconnect) TunnelLauncher.reconnect(context)
    }

    private fun dumpState() {
        val s = SettingsStore.data
        Logs.i(
            "Adb",
            "state=${TunnelController.state} node=${NodeRepository.selected()?.node?.displayName} " +
                "sub=${NodeRepository.activeSubscription()?.name} " +
                "up=${TunnelController.uplinkBytes} down=${TunnelController.downlinkBytes} " +
                "err=${TunnelController.lastError}",
        )
        Logs.i(
            "Adb",
            "settings serviceMode=${s.serviceMode} tunStack=${s.tunStack} mtu=${s.mtu} " +
                "ipv6=${s.ipv6Mode} sniffing=${s.sniffing} bypassLan=${s.bypassLan} " +
                "dnsRouting=${s.enableDnsRouting} logLevel=${s.logLevel}",
        )
    }

    /** Case-insensitive enum lookup so adb can pass `gvisor` for `GVisor`. */
    private inline fun <reified T : Enum<T>> enumOf(entries: List<T>, raw: String): T? =
        entries.firstOrNull { it.name.equals(raw, ignoreCase = true) }
            ?: run { Logs.w("Adb", "bad enum '$raw' for ${T::class.simpleName}"); null }
    /**
     * Set the nickname through the same guard the dialog and the settings loader use, so a
     * headless run exercises the bounds rather than bypassing them.
     */
    private fun setNickname(value: String?) {
        if (value == null) {
            Logs.w("Adb", "setNickname needs --es value")
            return
        }
        SettingsStore.update { it.copy(nickname = SettingsGuard.nickname(value)) }
    }

    /**
     * Import an avatar from a file, for testing the store without driving the system photo
     * picker by blind taps.
     *
     * The path has to be somewhere this app can already read, which in practice means its own
     * external files dir — `/sdcard/Android/data/<pkg>/files/` — because a debug build holds
     * no storage permission and is not going to be given one to make a test easier.
     */
    private fun setAvatar(path: String?) {
        if (path == null) {
            Logs.w("Adb", "setAvatar needs --es path")
            return
        }
        offMainThread("setAvatar") { AvatarStore.set(Uri.fromFile(File(path))) }
    }

    /**
     * `onReceive` runs on the main thread and [AvatarStore] is filesystem work, so every
     * avatar command goes through here. Daemon, like every other background thread in the
     * app, so it can never hold the process open.
     */
    private fun offMainThread(name: String, block: () -> Unit) {
        Thread({
            runCatching(block).onFailure { Logs.e("Adb", "$name failed", it) }
        }, "adb-$name").apply { isDaemon = true }.start()
    }

}
