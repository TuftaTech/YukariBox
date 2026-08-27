package dev.yukaribox.vpn.vpn

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import dev.yukaribox.vpn.core.AppContext
import libcore.ExchangeContext
import libcore.LocalDNSTransport
import java.net.InetAddress

/**
 * Resolves DNS for the sing-box core over the *underlying* physical network
 * (never the VPN), preventing resolution loops. Uses the lookup path
 * ([raw] = false), which is sufficient for A/AAAA resolution on all supported
 * API levels.
 *
 * This is the platform resolver behind a sing-box `address: "local"` server, which
 * `ConfigBuilder` emits only as a bootstrap — when the *direct* DNS server is itself
 * named by hostname and so cannot resolve itself. With the default IP-literal direct
 * server it is never consulted at all. It resolves in the clear over the physical link,
 * because that is what a platform resolver is; what it must never do is resolve over the
 * tunnel (a loop) or over the process-default resolver when no network is known (see
 * [lookup]).
 */
object LocalDns : LocalDNSTransport {

    @Volatile
    var underlyingNetwork: Network? = null

    private val connectivity: ConnectivityManager
        get() = AppContext.context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    /**
     * The network to resolve on: the one the service handed us, or the system default
     * as long as it is not a VPN.
     *
     * The fallback matters because the URL-test engine builds transient boxes with this
     * transport and no service running, so [underlyingNetwork] is null there. The VPN
     * check is what makes "never over the tunnel" a guarantee rather than a consequence:
     * for a process *inside* a VPN, `activeNetwork` **is** the tunnel, and resolving the
     * node's own hostname through the tunnel that is trying to reach it is the loop this
     * class exists to avoid. Our own package is excluded from the tunnel by
     * [PerAppRouting], so today that case needs a mistake elsewhere to happen — which is
     * exactly the kind of case worth closing here rather than relying on.
     */
    private fun currentNetwork(): Network? {
        underlyingNetwork?.let { return it }
        val manager = connectivity
        val active = manager.activeNetwork ?: return null
        val capabilities = manager.getNetworkCapabilities(active) ?: return null
        return active.takeIf { !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) }
    }

    override fun raw(): Boolean = false

    override fun networkHandle(): Long = currentNetwork()?.networkHandle ?: 0L

    override fun exchange(ctx: ExchangeContext, message: ByteArray) {
        // Not used while raw() == false; fail gracefully if the core calls it anyway.
        ctx.errorCode(2)
    }

    override fun lookup(ctx: ExchangeContext, network: String, domain: String) {
        val net = currentNetwork()
        if (net == null) {
            // No usable underlying network (connect window, a brief gap after a network
            // flap, or a default network that is itself a VPN). Do NOT fall back to
            // InetAddress.getAllByName — that uses the process-default resolver over
            // plaintext UDP/53 on whatever route the kernel picks, leaking the queried
            // domain. Fail; the core retries.
            ctx.errorCode(2)
            return
        }
        try {
            val answers: Array<InetAddress> = net.getAllByName(domain)
            val wantV4 = network.endsWith("4")
            val wantV6 = network.endsWith("6")
            val ips = answers.mapNotNull { it.hostAddress }.filter { ip ->
                when {
                    wantV4 -> !ip.contains(':')
                    wantV6 -> ip.contains(':')
                    else -> true
                }
            }
            if (ips.isEmpty()) ctx.errorCode(3) else ctx.success(ips.joinToString("\n"))
        } catch (_: Exception) {
            ctx.errorCode(3)
        }
    }
}
