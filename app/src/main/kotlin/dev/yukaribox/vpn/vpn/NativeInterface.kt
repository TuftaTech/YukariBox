package dev.yukaribox.vpn.vpn

import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import dev.yukaribox.vpn.core.AppContext
import dev.yukaribox.vpn.core.Logs
import libcore.BoxPlatformInterface
import libcore.NB4AInterface
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Bridges the sing-box core to Android: opens the TUN through the running
 * [YukariVpnService], protects the core's own sockets, and answers UID/package
 * queries for routing. Implements both libcore platform interfaces; a single
 * instance is passed to `Libcore.initCore`.
 */
class NativeInterface : BoxPlatformInterface, NB4AInterface {

    private val packageManager get() = AppContext.context.packageManager
    private val connectivity
        get() = AppContext.context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    /** Latch for [findConnectionOwner]'s one-shot warning — see the catch there. */
    private val ownerLookupFailed = AtomicBoolean(false)

    // ---- BoxPlatformInterface ----

    override fun openTun(singTunOptionsJson: String, tunPlatformOptionsJson: String): Long {
        Logs.d("Native", "openTun requested by core")
        val service = YukariVpnService.instance
            ?: throw IllegalStateException("openTun called with no active VpnService")
        return service.establishTun().toLong()
    }

    override fun autoDetectInterfaceControl(fd: Int) {
        val ok = YukariVpnService.instance?.protect(fd)
        if (ok == true) {
            // Lazily built: this fires once per socket the core dials, and with the journal
            // off by default every one of those strings was formatted and then dropped.
            Logs.v("Native") { "protect(fd=$fd) -> true" }
        } else {
            // A socket the platform did not protect goes *into* the tunnel the core is trying
            // to build, or out over a route it did not choose. At trace level, under a journal
            // that ships off and defaults to `info` when it is on, the one outcome worth
            // knowing about was the one that could never be read.
            Logs.w("Native", "protect(fd=$fd) failed (service=${YukariVpnService.instance != null})")
        }
    }

    @Suppress("TooGenericExceptionCaught") // a native callback must never let anything escape
    override fun findConnectionOwner(
        ipProto: Int,
        srcIp: String,
        srcPort: Int,
        destIp: String,
        destPort: Int,
    ): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return -1
        return try {
            connectivity.getConnectionOwnerUid(
                ipProto,
                InetSocketAddress(srcIp, srcPort),
                InetSocketAddress(destIp, destPort),
            )
        } catch (e: Exception) {
            // A route rule that matches by package cannot match a connection whose owner
            // is unknown, so it silently does not apply — and for a `block` rule that
            // means the traffic falls through to `final` (the proxy) instead of being
            // dropped. Nothing here can recover the uid, so the only honest answer is to
            // make it diagnosable. Logged once: this runs per connection, and a flood
            // would bury everything else in the log.
            if (ownerLookupFailed.compareAndSet(false, true)) {
                Logs.w("Native", "connection owner lookup failed; package_name rules cannot match", e)
            }
            -1
        }
    }

    override fun packageNameByUid(uid: Int): String {
        if (uid <= 1000) return "android"
        return packageManager.getPackagesForUid(uid)?.firstOrNull() ?: "android"
    }

    override fun uidByPackageName(packageName: String): Int {
        return try {
            packageManager.getPackageUid(packageName, 0)
        } catch (_: Exception) {
            // -1, not 0: uid 0 is root, so an uninstalled or misspelled package in a
            // routing rule would resolve to a real uid and the rule would silently
            // apply to root's traffic instead of matching nothing. -1 is also what
            // findConnectionOwner returns for "unknown".
            -1
        }
    }

    override fun useProcFS(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q

    override fun wifiState(): String = ""

    // ---- NB4AInterface ----

    override fun selector_OnProxySelected(selectorTag: String, outboundTag: String) {
        // No selector groups yet; single-node mode.
    }

    override fun useOfficialAssets(): Boolean = true
}
