package dev.yukaribox.vpn.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import dev.yukaribox.vpn.R
import dev.yukaribox.vpn.core.ConnectedProfile
import dev.yukaribox.vpn.core.Ipv6Mode
import dev.yukaribox.vpn.core.Logs
import dev.yukaribox.vpn.core.ReconnectPolicy
import dev.yukaribox.vpn.core.ServiceMode
import dev.yukaribox.vpn.core.SettingsGuard
import dev.yukaribox.vpn.core.SettingsStore
import dev.yukaribox.vpn.core.TrafficWatchdog
import dev.yukaribox.vpn.core.TunnelController
import dev.yukaribox.vpn.core.TunnelState
import dev.yukaribox.vpn.core.TunnelStateMachine
import dev.yukaribox.vpn.data.NodeRepository
import libcore.Libcore
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

/**
 * How the supervised connect loop ended. Only [Exhausted] arms the kill switch:
 * [Connected] is the happy path and [Abandoned] means the user asked to stop, so the
 * queued teardown owns the TUN from there.
 */
private enum class ConnectOutcome { Connected, Abandoned, Exhausted }

/**
 * How long a teardown waits for the stats loop to leave the core. Comfortably above the
 * connect probe timeout, which bounds the one call on that thread that can be slow.
 */
private const val STATS_JOIN_TIMEOUT_MS = 8_000L

/**
 * The VpnService that owns the TUN device and the sing-box core. Lifecycle is
 * guarded by a [TunnelStateMachine] so it can never wedge: START brings the core
 * up (which calls back into [establishTun]); STOP tears it down cleanly.
 */
class YukariVpnService : VpnService() {

    private val runner = BoxRunner()
    private val worker = Executors.newSingleThreadExecutor()
    private val stateMachine = TunnelStateMachine(onChange = { newState ->
        Logs.i("Tunnel", "state -> $newState")
        TunnelController.onState(newState)
    })

    @Volatile
    private var tunFd: ParcelFileDescriptor? = null

    /**
     * Guards the *pair* of writes that swap [tunFd]: publish the new descriptor, then close
     * the one it replaced. `establishTun` runs on a core thread and `cleanupCore` on the
     * worker, so without this the two can interleave into closing the descriptor the other
     * has just installed.
     */
    private val tunLock = Any()

    /**
     * Set by [stopTunnel] before the teardown task is queued. The connect loop runs
     * on the same single-thread executor, so a Stop request cannot preempt a backoff
     * sleep — it polls this flag instead (see [sleepUnlessCancelled]) and abandons
     * the loop, which also stops a doomed session from installing a blocking TUN the
     * user just asked to tear down.
     */
    @Volatile
    private var stopRequested = false

    /**
     * True while a blocking ("fail-closed") TUN is held after an unexpected core
     * failure. In this state the TUN still captures 0.0.0.0/0 and ::/0 but has no
     * core behind it, so every packet is dropped instead of leaking to the
     * physical interface. There is deliberately no setting to disable this.
     */
    @Volatile
    private var failClosed = false

    /**
     * True while a captive-portal alert is showing. The underlying network reported
     * NET_CAPABILITY_CAPTIVE_PORTAL (a sign-in page), so the tunnel cannot reach the
     * internet yet. We notify the user and *wait* — we never route around the tunnel
     * to reach the portal, so nothing leaks. Cleared once the network validates again.
     */
    @Volatile
    private var captivePortalNotified = false

    /**
     * Written by the connect worker, read and interrupted from [onDestroy] on the
     * main thread — without the barrier the teardown could see a stale null and
     * leave the stats thread polling a closed core.
     */
    @Volatile
    private var statsThread: Thread? = null

    private val connectivity get() =
        getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager

    /**
     * The physical networks this tunnel can run over, and which one it is running over now.
     *
     * `by lazy` because it needs a `ConnectivityManager`, and a field initialiser runs before
     * the service has a base context to get one from.
     */
    private val networks by lazy {
        UnderlyingNetworks(
            connectivity = connectivity,
            onChosen = { network ->
                // The two places the choice has to land: the resolver's idea of the physical
                // link, and the platform's idea of what this VPN sits on top of. Passing null
                // for both when nothing is up is deliberate -- LocalDns then falls back to
                // `activeNetwork` behind its own not-a-VPN guard and answers "retry" while
                // there is genuinely nothing to resolve over.
                LocalDns.underlyingNetwork = network
                runCatching { setUnderlyingNetworks(network?.let { arrayOf(it) }) }
                Logs.i("Tunnel", "underlying network -> ${network ?: "none"}")
            },
            onHandover = { from, to ->
                // Wi-Fi <-> LTE: streams pinned to the old interface hang until their own
                // timeout, and resetting lets them re-dial at once.
                //
                // Queued on the worker, never run here. This arrives on ConnectivityThread, and
                // `Libcore.resetAllConnections` is the one core entry point libcore does *not*
                // wrap in its panic-to-error helper, so a panic inside it is fatal to the
                // process rather than an exception we catch. Run inline it also read
                // `stateMachine.state` from a third thread while the worker was changing it.
                if (SettingsStore.data.reconnectOnNetworkChange) {
                    runCatching {
                        worker.execute {
                            if (stopRequested || stateMachine.state != TunnelState.Connected) {
                                return@execute
                            }
                            Logs.i("Tunnel", "physical network changed ($from -> $to), resetting")
                            runCatching { Libcore.resetAllConnections(true) }
                        }
                    }
                }
            },
            onCaptivePortal = { captive ->
                // Notify and *wait*; never bypass the tunnel to reach the portal, so nothing
                // leaks. Reported for the network actually chosen rather than for any network in
                // sight, so a captive guest Wi-Fi the tunnel is not using raises nothing. The
                // alert clears once that network validates.
                if (captive != captivePortalNotified) {
                    captivePortalNotified = captive
                    if (captive) {
                        Logs.w("Tunnel", "captive portal on the underlying network; waiting")
                    }
                    showCaptivePortalNotice(captive)
                }
            },
        )
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
        networks.register()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Logs.d("Service", "onStartCommand action=${intent?.action}")
        when (intent?.action) {
            ACTION_STOP -> stopTunnel()
            // The config carries node credentials. It is handed over via an
            // in-process holder rather than an Intent extra so it never lands in
            // ActivityManager's intent history (dumpsys / bugreport).
            else -> {
                val handoff = pendingConfig.getAndSet(null)
                if (handoff != null) startTunnel(handoff) else startFromSystem()
            }
        }
        return START_NOT_STICKY
    }

    private fun startTunnel(config: String?) {
        if (stateMachine.state.isActive) {
            Logs.w("Tunnel", "start ignored: already ${stateMachine.state}")
            return
        }
        if (config.isNullOrBlank()) {
            Logs.w("Tunnel", "start aborted: empty config")
            stopSelf()
            return
        }
        // Transition first, and only clear `stopRequested` once it took. Disconnecting is
        // not `isActive`, so the guard above lets a Start through while a Stop's teardown
        // is still queued behind a sleeping backoff slice — and clearing the flag there
        // un-cancelled the Stop, letting the doomed connect loop run on and install a
        // blocking TUN for a session the user had just asked to tear down.
        if (!stateMachine.beginConnecting()) {
            Logs.w("Tunnel", "start ignored: cannot enter Connecting from ${stateMachine.state}")
            return
        }
        stopRequested = false
        startForegroundNotification(TunnelState.Connecting)
        worker.execute { bringCoreUp(config) }
    }

    /**
     * Started by the system with no in-process config — Android always-on VPN or a
     * lockdown ("Block connections without VPN") restart. Rebuild the config from
     * persisted settings, like [BootReceiver] does, so always-on actually connects
     * and the OS lockdown becomes an effective kill-switch. Foreground must be
     * entered promptly, so do that here and build the (possibly slow) config and
     * start the core off the main thread.
     */
    private fun startFromSystem() {
        if (stateMachine.state.isActive) {
            Logs.w("Tunnel", "system start ignored: already ${stateMachine.state}")
            return
        }
        Logs.i("Tunnel", "system-initiated start (always-on); rebuilding config from settings")
        // Same ordering rule as startTunnel: the transition has to take before the
        // pending-stop flag is cleared.
        if (!stateMachine.beginConnecting()) {
            Logs.w("Tunnel", "system start ignored: cannot enter Connecting from ${stateMachine.state}")
            return
        }
        stopRequested = false
        startForegroundNotification(TunnelState.Connecting)
        worker.execute {
            NodeRepository.awaitLoaded()
            val selected = NodeRepository.selected()
            TunnelController.connectedProfile = ConnectedProfile(
                subId = NodeRepository.selectedSubId,
                nodeId = selected?.id,
                name = selected?.node?.displayName.orEmpty(),
            )
            val config = runCatching { TunnelLauncher.buildConfig(this) }.getOrNull()
            if (config.isNullOrBlank()) {
                // Always-on with no usable config: hold the blocking TUN rather than
                // stopping. Stopping used to leave traffic on the physical interface
                // whenever always-on was enabled *without* the optional OS lockdown,
                // which is the default pairing — the in-app kill switch must not
                // depend on a separate system toggle.
                Logs.e("Tunnel", "always-on start aborted: config build failed; failing closed")
                TunnelController.lastError = "always-on: no config"
                enterFailClosed()
            } else {
                bringCoreUp(config)
            }
        }
    }

    /**
     * Bring the core up on the current (worker) thread. Caller has entered foreground.
     *
     * Every exit from here leaves the session connected, deliberately abandoned, or
     * failed *closed*: an escaped exception used to end the connect path with no TUN
     * at all, so the loop is guarded and anything but success funnels into
     * [enterFailClosed].
     */
    private fun bringCoreUp(config: String, allowAutoSwitch: Boolean = true) {
        // Re-entered after a fail-closed transition when auto-switch found another
        // node; the state machine is Disconnected by then, so re-arm Connecting and
        // bail out if the edge is illegal (a Stop is already in flight).
        if (!stateMachine.beginConnecting()) {
            Logs.w("Tunnel", "connect aborted: cannot enter Connecting from ${stateMachine.state}")
            return
        }
        failClosed = false
        TunnelController.lastError = null
        runCatching { getSystemService(NotificationManager::class.java).cancel(ALERT_NOTIFICATION_ID) }
        // box.log can hold credentials at trace level — reset each session.
        runCatching { File(filesDir, "box.log").writeText("") }
        val outcome = runCatching { runConnectLoop(config) }
            .onFailure {
                Logs.e("Tunnel", "connect path threw; failing closed", it)
                TunnelController.lastError = it.message ?: it.javaClass.simpleName
            }
            .getOrDefault(ConnectOutcome.Exhausted)
        if (outcome != ConnectOutcome.Exhausted) return
        // Retries spent (or the loop threw). Arm the kill switch FIRST: the optional
        // auto-switch probe below tests alternative nodes with a 5 s timeout each,
        // which used to postpone fail-closed by minutes.
        if (!enterFailClosed()) return
        val nextConfig = if (allowAutoSwitch) NodeAutoSwitch.switchToFastest(this) else null
        if (nextConfig != null && !stopRequested) {
            Logs.i("Tunnel", "auto-switch picked another node; reconnecting")
            bringCoreUp(nextConfig, allowAutoSwitch = false)
        }
    }

    /**
     * Node-down = handshake error (the core failed to start) OR traffic timeout (it
     * came up but nothing flowed). Retry with exponential backoff until the policy is
     * spent, polling [stopRequested] so a Stop cuts the wait short.
     */
    private fun runConnectLoop(config: String): ConnectOutcome {
        val policy = ReconnectPolicy()
        while (!stopRequested) {
            Logs.i("Tunnel", "starting core (${config.length}B), try ${policy.attempts + 1}")
            val failure = runner.attemptConnect(
                config,
                SettingsStore.data.connectionTestUrl,
                PROBE_TIMEOUT_MS,
            )
            if (failure == null) return onCoreConnected()
            val delayMs = policy.nextDelayMs() ?: return ConnectOutcome.Exhausted
            Logs.w("Tunnel", "node down ($failure); retry ${policy.attempts}/${policy.maxAttempts} in ${delayMs}ms")
            // Live TUN has no core behind it during the backoff, so traffic drops (no leak).
            notifyReconnecting(policy.attempts, policy.maxAttempts, delayMs)
            if (!sleepUnlessCancelled(delayMs) { stopRequested }) break
        }
        Logs.i("Tunnel", "connect abandoned: stop requested")
        return ConnectOutcome.Abandoned
    }

    /** The core is up and the probe succeeded — publish Connected, or stand down. */
    private fun onCoreConnected(): ConnectOutcome {
        if (!stateMachine.markConnected()) {
            // Stop landed while the core was starting: don't report Connected and don't
            // start the stats loop — let the queued teardown run.
            Logs.w("Tunnel", "connected but state is ${stateMachine.state}; tearing down")
            runner.stop()
            return ConnectOutcome.Abandoned
        }
        startForegroundNotification(TunnelState.Connected)
        startStatsLoop()
        Logs.i("Tunnel", "core started, connected")
        return ConnectOutcome.Connected
    }

    /**
     * Arm the kill switch: hold a *blocking* TUN so packets drop instead of leaking to
     * the physical interface. Returns true when such a TUN is actually held.
     *
     * Two cases deliberately hold nothing. Proxy-only mode never owned a TUN, so a
     * device-wide blocking TUN would black-hole every app over a local-proxy failure.
     * And when the establish itself fails (consent revoked, another VPN took over, a
     * Builder rejection) traffic is *not* protected — the previous code logged that
     * and still posted "traffic blocked", the one lie this service must not tell.
     */
    private fun enterFailClosed(): Boolean {
        val stats = statsThread
        statsThread = null
        joinStatsLoop(stats)
        runner.stop()
        if (SettingsStore.data.serviceMode == ServiceMode.ProxyOnly) {
            Logs.w("Tunnel", "proxy-only session failed; no TUN to hold")
            failClosed = false
            stateMachine.fail()
            runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
            stopSelf()
            return false
        }
        if (stopRequested) {
            // The user asked to stop while the retries were still running. That is a clean
            // stop, not a failure to protect: the queued teardown owns the session from
            // here. Folded into `held` (as `!stopRequested && ...`) it fell through to the
            // unprotected branch instead, so every Stop tapped during the last retry
            // posted a NOT PROTECTED alert about a session the user had just ended.
            Logs.i("Tunnel", "fail-closed skipped: stop already requested")
            failClosed = false
            stateMachine.fail()
            return false
        }
        val held = runCatching { establishTun(blocking = true) }
            .onFailure { Logs.e("Tunnel", "fail-closed TUN setup failed", it) }
            .isSuccess
        failClosed = held
        // fail() routes through TunnelController.onState, which clears the fail-closed
        // flags, so the outcome is re-asserted immediately after it.
        stateMachine.fail()
        TunnelController.onFailClosed(held)
        if (held) {
            Logs.w("Tunnel", "failed closed: blocking TUN held, traffic dropped")
            // The blocking TUN carries the per-app plan, so apps the user kept outside
            // the tunnel keep their ordinary connection. "Traffic blocked" is then not
            // true of them, and this notification is the one surface that must never
            // overstate what is being held — derived from the same pure plan that built
            // the TUN, so the wording cannot drift from the routing.
            val partial = splitTunnelInUse()
            startForegroundNotification(
                TunnelState.Disconnected,
                buildTunnelNotification(
                    getString(
                        if (partial) R.string.notif_failclosed_partial else R.string.notif_failclosed,
                    ),
                    getString(
                        if (partial) {
                            R.string.notif_failclosed_body_partial
                        } else {
                            R.string.notif_failclosed_body
                        },
                    ),
                ),
            )
            postAlert(R.string.notif_drop, R.string.notif_drop_body)
        } else {
            Logs.e("Tunnel", "cannot fail closed: no blocking TUN — traffic is NOT protected")
            postAlert(R.string.notif_unprotected, R.string.notif_unprotected_body)
            // Deliberately NOT stopForeground/stopSelf. Stopping tore the service down, and
            // `onDestroy`'s queued `cleanupCore` then cleared `TunnelController.unprotected`
            // and cancelled this very alert a few milliseconds after both were set, so the
            // one state this service must never soften settled back into a plain
            // DISCONNECTED card carrying no notice at all. Staying in the foreground keeps
            // both the notification that tells the truth and the flag Home reads alive; the
            // user's own Stop is what tears it down.
            startForegroundNotification(
                TunnelState.Disconnected,
                buildTunnelNotification(
                    getString(R.string.notif_unprotected),
                    getString(R.string.notif_unprotected_body),
                ),
            )
        }
        return held
    }

    private fun stopTunnel() {
        Logs.i("Tunnel", "stop requested (state=${stateMachine.state}, failClosed=$failClosed)")
        // Set before anything is queued: the connect loop polls this between backoff
        // slices, so a Stop during a retry window takes effect instead of waiting for
        // the whole backoff (and cannot be overtaken by a late successful connect).
        stopRequested = true
        if (stateMachine.state == TunnelState.Disconnected) {
            // A fail-closed blocking TUN may still be held even though the state
            // machine reads Disconnected — drop it now that the user chose to stop.
            if (failClosed) {
                failClosed = false
                // On the worker like every other teardown. `cleanupCore` closes the native
                // core and is a blocking native call, which `onDestroy`'s KDoc rules out on
                // the main thread; this branch was safe only because `enterFailClosed` had
                // already stopped the core, and that precondition is enforced nowhere.
                runCatching { worker.execute { cleanupCore() } }
            }
            runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
            stopSelf()
            return
        }
        stateMachine.beginDisconnecting()
        worker.execute {
            cleanupCore()
            stateMachine.markDisconnected()
            runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
            stopSelf()
        }
    }

    /**
     * Called by the core (via [NativeInterface.openTun]) on the worker thread to
     * bring up the live tunnel, and from [bringCoreUp]'s fail-closed path with
     * [blocking] = true to stand up a kill-switch TUN. Both capture 0.0.0.0/0 and
     * ::/0; a blocking TUN has no core behind it and offers no DNS server, so every
     * packet (DNS included) is dropped instead of leaking to the physical interface.
     */
    fun establishTun(blocking: Boolean = false): Int {
        val settings = SettingsStore.data
        // Clamped: VpnService.Builder rejects a non-positive MTU with an
        // IllegalArgumentException, and a rejected *blocking* establish would turn the
        // kill switch into a no-op while the notification still claimed traffic was
        // blocked. The settings screen does not bound the field, and a restored backup
        // can carry any number, so the bound is enforced here as well.
        val mtu = SettingsGuard.mtu(settings.mtu)
        val builder = Builder()
            .setSession(if (blocking) "YukariBox (blocked)" else "YukariBox")
            .setMtu(mtu)
            .addAddress(TUN_ADDR4, 30)
            .addRoute("0.0.0.0", 0)
        // Both kinds of TUN publish the same resolver, and for the blocking one that is the
        // point: the address lives inside the TUN with no core behind it, so a query is
        // captured and dropped. Publishing *no* server was the earlier theory -- that queries
        // would dead-end on their own -- but a VPN that advertises no resolver can have
        // resolution fall back to the underlying network's on some Android versions, which
        // would put cleartext DNS on the physical interface while the notification says
        // traffic is blocked. Capturing it is the same outcome on every version.
        builder.addDnsServer(TUN_DNS4)
        // Capture IPv6 into the TUN unconditionally. Android only diverts address
        // families that have a route, so without a ::/0 route here the kernel keeps
        // the physical IPv6 default route and all v6 traffic leaks outside the tunnel
        // (the sing-box config has no auto_route — this Builder is the only gate).
        // "Disable IPv6" is enforced via DNS strategy in ConfigBuilder, not by leaving
        // v6 unrouted. Some devices reject a v6 TUN address outright: rather than
        // silently shipping a v4-only TUN, that failure is fatal when the user asked
        // for IPv6, and tolerated only in the default ipv4_only mode (where the DNS
        // strategy already keeps apps off AAAA).
        val v6 = runCatching {
            builder.addAddress(TUN_ADDR6, 126)
            builder.addRoute("::", 0)
        }
        if (v6.isFailure) {
            val message = "IPv6 TUN setup failed: ${v6.exceptionOrNull()?.message}"
            if (settings.ipv6Mode == Ipv6Mode.Enable) {
                error("$message (IPv6 enabled; refusing a v4-only TUN)")
            }
            Logs.w("Tun", message)
        }
        builder.applyPerAppPlan(packageName, settings)
        if (!blocking) runCatching { LocalDns.underlyingNetwork?.let { setUnderlyingNetworks(arrayOf(it)) } }
        val pfd = builder.establish() ?: throw IllegalStateException("VpnService.establish() returned null")
        // Close any TUN we were already holding (e.g. a prior fail-closed blocking
        // TUN). establish() has already superseded it, so apps never see a gap.
        //
        // Publish first, close second, both under [tunLock]. This runs on a *core* thread
        // (the core calls openTun from its own goroutine's JNI attachment) while the worker
        // can be in `cleanupCore` doing the mirror image, and `@Volatile` makes each field
        // access atomic without making the pair atomic: interleaved, the teardown could
        // close the descriptor this call had just installed and leave the live TUN alive on
        // the dup the core holds, with no handle left on the Java side to release it.
        synchronized(tunLock) {
            val previous = tunFd
            tunFd = pfd
            if (previous !== pfd) runCatching { previous?.close() }
        }
        Logs.i(
            "Tun",
            "established fd=${pfd.fd} blocking=$blocking mtu=$mtu stack=${settings.tunStack.value} " +
                "ipv6=${settings.ipv6Mode} perApp=${settings.perAppPackages.size} " +
                "include=${settings.perAppProxyInclude}",
        )
        return pfd.fd
    }

    private fun startStatsLoop() {
        val watchdog = TrafficWatchdog()
        // Built, published, then started. `Thread { ... }.also { it.start() }` assigned the
        // field *after* the thread was already running, leaving a window in which a
        // teardown read null and left the loop it meant to interrupt alive.
        val thread = Thread {
            var prevUp = 0L
            var prevDown = 0L
            var lastNotified = 0L
            try {
                while (!Thread.currentThread().isInterrupted && stateMachine.state == TunnelState.Connected) {
                    val up = runner.queryStats("proxy", "uplink")
                    val down = runner.queryStats("proxy", "downlink")
                    val upRate = (up - prevUp).coerceAtLeast(0L)
                    val downRate = (down - prevDown).coerceAtLeast(0L)
                    prevUp = up
                    prevDown = down
                    TunnelController.onStats(up, down, upRate, downRate)
                    // Throttled, unlike the counters above. The stats themselves have to
                    // land every second — Stats renders a live meter off them — but the
                    // notification is a status line, and re-posting it at 1 Hz for the
                    // whole session is churn in system_server and on the battery for a
                    // number nobody is watching that closely. The first sample still posts
                    // immediately, so the text switches off "Connected" without a wait.
                    val now = System.currentTimeMillis()
                    if (now - lastNotified >= NOTIFICATION_PERIOD_MS) {
                        lastNotified = now
                        postTrafficNotification(upRate, downRate, up, down)
                        // Per-route breakdown (US-005): the `direct` outbound carries
                        // LAN/bypassed traffic that never went through the proxy node.
                        // Both tags are registered via setV2rayStats("proxy\ndirect").
                        //
                        // On this cadence rather than the loop's. These two are cumulative
                        // totals, not rates, and the only surface that reads them is the Stats
                        // screen, so polling them every second spent two extra JNI crossings a
                        // second on a figure that is usually not on screen and never needs
                        // per-second resolution. The first sample still lands immediately.
                        TunnelController.onDirectStats(
                            runner.queryStats("direct", "uplink"),
                            runner.queryStats("direct", "downlink"),
                        )
                    }
                    Logs.v("Stats") { "up=$up(${upRate}/s) down=$down(${downRate}/s)" }

                    // Supervise the live session, not just the connect: a node blocked
                    // mid-session used to leave this loop reporting Connected forever.
                    // Only a tunnel that passed nothing for a full window is probed, so
                    // an active tunnel is never disturbed.
                    if (watchdog.onSample(upRate, downRate) &&
                        runner.probe(SettingsStore.data.connectionTestUrl, PROBE_TIMEOUT_MS) <= 0
                    ) {
                        Logs.w("Tunnel", "watchdog: tunnel idle and probe failed — node is down")
                        // Guarded, and the only cross-thread submit on this thread that was
                        // not. A teardown queues its own cleanup and then calls
                        // `worker.shutdown()`, after which `execute` throws
                        // RejectedExecutionException — an uncaught throwable on a background
                        // thread, which kills the process and the tunnel with it. It is
                        // reachable, not theoretical: `cleanupCore` nulls the box, so the
                        // in-flight probe returns -1 immediately and this branch is taken.
                        // Nothing is lost by dropping the recovery here — a service that is
                        // shutting down has no session to recover.
                        if (!stopRequested) {
                            runCatching { worker.execute { recoverDeadSession() } }
                                .onFailure { Logs.w("Tunnel", "recovery not queued: service is shutting down") }
                        }
                        return@Thread
                    }
                    Thread.sleep(1000)
                }
            } catch (_: InterruptedException) {
            }
        }
        thread.isDaemon = true
        statsThread = thread
        thread.start()
    }

    /**
     * The watchdog found a connected-but-dead session. Arm the kill switch first, then
     * rebuild the config from current settings and re-run the connect loop; if that
     * fails too, the blocking TUN stays and the user is told the truth. Runs on the
     * worker so it serialises with Stop and with the connect loop.
     */
    private fun recoverDeadSession() {
        if (stopRequested || stateMachine.state != TunnelState.Connected) return
        TunnelController.lastError = "node unreachable"
        if (!enterFailClosed()) return
        val config = runCatching { TunnelLauncher.buildConfig(this) }.getOrNull()
        if (config.isNullOrBlank()) {
            Logs.e("Tunnel", "watchdog recovery: no usable config; staying failed closed")
            return
        }
        bringCoreUp(config)
    }

    private fun cleanupCore() {
        // Join, not just interrupt, and before the core is closed. See [joinStatsLoop].
        val stats = statsThread
        statsThread = null
        joinStatsLoop(stats)
        runner.stop()
        synchronized(tunLock) {
            val previous = tunFd
            tunFd = null
            runCatching { previous?.close() }
        }
        failClosed = false
        TunnelController.clearFailClosed()
        captivePortalNotified = false
        // Cleared with the session. `onLost` is the only other place that clears it, so after
        // a teardown the handle of whatever network the last session ran on stayed behind --
        // and `LocalDns.currentNetwork` only falls back to `activeNetwork` when the field is
        // null, so every later transient probe box resolved against a handle that may already
        // be dead and answered `errorCode(3)` instead of resolving.
        // Dropped with the session, and idempotent: `onDestroy` does the same, in whichever
        // order the platform runs the two. `release()` reports "no network", which is what nulls
        // the resolver's handle and hands the platform `setUnderlyingNetworks(null)`; the two
        // lines after it are what still hold on a second call, once the CAS has fired.
        networks.release()
        LocalDns.underlyingNetwork = null
        runCatching { setUnderlyingNetworks(null) }
        runCatching {
            getSystemService(NotificationManager::class.java).apply {
                cancel(ALERT_NOTIFICATION_ID)
                cancel(CAPTIVE_NOTIFICATION_ID)
            }
        }
    }

    private fun createNotificationChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notif_channel_status),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.notif_channel_status_desc)
            setShowBadge(false)
        }
        // Separate, higher-importance channel for node-drop alerts so they surface
        // even though the ongoing status notification stays silent (IMPORTANCE_LOW).
        val alertChannel = NotificationChannel(
            ALERT_CHANNEL_ID,
            getString(R.string.notif_channel_alert),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = getString(R.string.notif_channel_alert_desc)
        }
        nm.createNotificationChannels(listOf(channel, alertChannel))
    }

    /**
     * Enter (or refresh) the foreground state. [notification] overrides the text
     * derived from [state] — used by the fail-closed path, whose message has no
     * matching [TunnelState].
     */
    private fun startForegroundNotification(state: TunnelState, notification: Notification? = null) {
        val text = when (state) {
            TunnelState.Connecting -> getString(R.string.notif_status_connecting)
            TunnelState.Connected -> connectedText()
            else -> getString(R.string.notif_status_tunnel)
        }
        val posted = notification ?: buildTunnelNotification(text)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, posted, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, posted)
        }
    }

    /** Update the ongoing notification during a reconnect backoff. */
    private fun notifyReconnecting(attempt: Int, maxAttempts: Int, delayMs: Long) {
        runCatching {
            getSystemService(NotificationManager::class.java).notify(
                NOTIFICATION_ID,
                buildTunnelNotification(
                    getString(R.string.notif_reconnecting, attempt, maxAttempts),
                    getString(R.string.notif_reconnecting_body, delayMs / 1000),
                ),
            )
        }
    }

    /**
     * Post a dismissible alert on the higher-importance channel — the node dropped, or
     * the kill switch could not be armed. Deliberately names no node: the name used to
     * be interpolated here, but [TunnelStateMachine.fail] has already cleared
     * [TunnelController.connectedProfile] by this point so that branch was dead, and a
     * lock-screen alert is exactly where the `nodeInNotification` opt-out should hold.
     */
    private fun postAlert(titleRes: Int, bodyRes: Int) {
        val alert = buildTunnelNotification(
            getString(titleRes),
            getString(bodyRes),
            channelId = ALERT_CHANNEL_ID,
            ongoing = false,
        )
        runCatching { getSystemService(NotificationManager::class.java).notify(ALERT_NOTIFICATION_ID, alert) }
    }

    override fun onRevoke() {
        stopTunnel()
        super.onRevoke()
    }

    override fun onDestroy() {
        networks.release()
        // Tear down *on the worker*, not here. `cleanupCore` closes the native core, and
        // the worker may be inside `attemptConnect` doing the opposite: the two used to
        // run concurrently from two threads, and a close() racing a start() is undefined
        // in the core — a native crash here takes the process down with the TUN still
        // open, which is the one failure mode this service exists to prevent. It is also
        // a blocking native call, so it does not belong on the main thread at all.
        //
        // Queued rather than waited on, because onDestroy has to return promptly;
        // `shutdown()` (not `shutdownNow()`) lets this last task run and refuses new
        // ones. `stopRequested` first, so a connect loop still in a backoff slice
        // abandons instead of arming a kill switch for a service that is going away —
        // `enterFailClosed` checks the same flag before it establishes.
        stopRequested = true
        runCatching { worker.execute { cleanupCore() } }
        if (stateMachine.state != TunnelState.Disconnected) stateMachine.fail()
        worker.shutdown()
        if (instance === this) instance = null
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "dev.yukaribox.vpn.START"
        const val ACTION_STOP = "dev.yukaribox.vpn.STOP"

        /** In-process handoff for the credential-bearing config (never an Intent extra). */
        private val pendingConfig = AtomicReference<String?>(null)

        internal const val CHANNEL_ID = "yukari_vpn"
        internal const val ALERT_CHANNEL_ID = "yukari_vpn_alert"
        internal const val NOTIFICATION_ID = 0x59554B49 // "YUKI"
        private const val ALERT_NOTIFICATION_ID = 0x59554B41 // "YUKA"
        internal const val CAPTIVE_NOTIFICATION_ID = 0x59554B43 // "YUKC"
        private const val TUN_ADDR4 = "172.19.0.1"
        private const val TUN_ADDR6 = "fdfe:dcba:9876::1"
        private const val TUN_DNS4 = "172.19.0.2"

        /** Connect-time traffic probe timeout (ms) — matches the URL-test default. */
        private const val PROBE_TIMEOUT_MS = 5000

        /** How often the ongoing notification is refreshed with traffic (ms). */
        private const val NOTIFICATION_PERIOD_MS = 4_000L

        @Volatile
        var instance: YukariVpnService? = null
            private set

        fun start(context: android.content.Context, config: String) {
            pendingConfig.set(config)
            val intent = Intent(context, YukariVpnService::class.java).setAction(ACTION_START)
            context.startForegroundService(intent)
        }

        fun stop(context: android.content.Context) {
            val intent = Intent(context, YukariVpnService::class.java).setAction(ACTION_STOP)
            context.startService(intent)
        }
    }
}

/**
 * Stop the stats loop and **wait** for it to leave the core.
 *
 * `interrupt()` does not cancel a JNI call, and both calls that loop makes cross into Go:
 * `queryStats` on every tick, and, when the watchdog fires, a `Libcore.urlTest` bounded
 * only by its own timeout. Returning from here with that thread still inside the core let
 * the `runner.stop()` that follows close the box under a live call. `BoxRunner`'s
 * read/write lock now makes that particular overlap safe, and this is still not redundant:
 * the join is what guarantees the thread is *gone* before the service is, so it can no
 * longer publish session stats after a teardown, and can no longer submit
 * `recoverDeadSession` to an executor that is about to be shut down.
 *
 * Top-level rather than a member because the class sits at detekt's per-class function
 * budget and this needs nothing from the service but the thread itself.
 */
private fun joinStatsLoop(thread: Thread?) {
    if (thread == null || thread === Thread.currentThread()) return
    thread.interrupt()
    runCatching { thread.join(STATS_JOIN_TIMEOUT_MS) }
    if (thread.isAlive) {
        Logs.w("Tunnel", "stats thread still inside the core after ${STATS_JOIN_TIMEOUT_MS}ms")
    }
}
