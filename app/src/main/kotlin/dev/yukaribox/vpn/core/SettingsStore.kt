package dev.yukaribox.vpn.core

import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

/** Theme mode for the app shell. */
enum class ThemeMode { System, Light, Dark }

/** Whether the service runs a full VPN (TUN) or only a local mixed proxy inbound. */
enum class ServiceMode { Vpn, ProxyOnly }

/** TUN network stack passed to the sing-box `tun` inbound. */
enum class TunStack(val value: String) { Mixed("mixed"), GVisor("gvisor"), System("system") }

/** IPv6 handling for the TUN interface / route. */
enum class Ipv6Mode { Disable, Enable }

/** Persistent ordering applied to the node list. [Manual] keeps the saved order. */
enum class SortMode { Manual, Latency, Name }

/** Plain, serializable snapshot of every app setting (no Android types). */
@Serializable
data class SettingsData(
    // Connection / service
    val serviceMode: ServiceMode = ServiceMode.Vpn,
    // Identity — the Profile tab's two personalisations. The avatar is not here: its
    // truth is whether `files/avatar.png` exists, and a boolean beside it could disagree.
    /** Empty means "not set", and the built-in persona name shows instead. */
    val nickname: String = "",
    // Appearance
    val themeMode: ThemeMode = ThemeMode.System,
    val animations: Boolean = true,
    val nodeInNotification: Boolean = true,
    // DNS
    val remoteDns: String = "https://1.1.1.1/dns-query",
    val directDns: String = "https://223.5.5.5/dns-query",
    val enableDnsRouting: Boolean = true,
    // TUN / network
    val mtu: Int = 9000,
    // gVisor is the userspace stack NekoBox/MikuBox default to. The "mixed" stack
    // routes TCP through the system stack, whose forwarder fails to bind on this
    // device ("bind forwarder to interface: no such device"), so all TCP silently
    // dies while UDP/DNS still works — some sites load, most don't.
    val tunStack: TunStack = TunStack.GVisor,
    val ipv6Mode: Ipv6Mode = Ipv6Mode.Disable,
    // Routing
    /**
     * Bypass LAN: send private/local ranges straight out instead of through the
     * tunnel. OFF by default so ALL traffic (incl. LAN) goes through the tunnel;
     * enable to reach local devices (router, printer, NAS) while connected.
     */
    val bypassLan: Boolean = false,
    val sniffing: Boolean = true,
    /** true = only listed apps are proxied; false = listed apps are bypassed. */
    val perAppProxyInclude: Boolean = false,
    val perAppPackages: Set<String> = emptySet(),
    /** Opt-in routing presets (OFF by default). Route .ru/.рф/.su local domains direct. */
    val presetRuBypass: Boolean = false,
    /** Opt-in routing preset (OFF by default). Block well-known ad/tracker domains. */
    val presetAdBlock: Boolean = false,
    // Behavior
    /** Start the tunnel automatically after device boot. */
    val autoConnectOnBoot: Boolean = false,
    /**
     * Start the tunnel automatically when an internet-capable network becomes
     * available while the app is running and the tunnel is idle. OFF by default —
     * manual connect stays the default; requires VPN consent already granted.
     */
    val autoConnectOnNetwork: Boolean = false,
    /** Reset core connections when the underlying network changes (Wi-Fi <-> LTE). */
    val reconnectOnNetworkChange: Boolean = true,
    /**
     * Opt-in: when the active node drops and reconnect attempts are exhausted,
     * automatically switch to the lowest-ping reachable node (URL-tested outside
     * the tunnel) instead of failing closed. OFF by default — manual switch.
     */
    val autoSwitchOnDrop: Boolean = false,
    // Security
    val allowInsecure: Boolean = false,
    /**
     * Password for the proxy-only mixed inbound, generated on first use (see
     * [ProxyAuth]). Empty means "not generated yet", not "no password" — the
     * opt-out is [proxyAuthDisabled].
     */
    val proxyPassword: String = "",
    /**
     * Opt-out: serve the proxy-only inbound with no credentials. Off by default.
     * When on, any app on the device can route through the tunnel — kept only for
     * clients that cannot do proxy auth.
     */
    val proxyAuthDisabled: Boolean = false,
    // Testing
    val connectionTestUrl: String = "http://cp.cloudflare.com/generate_204",
    // Subscription
    val autoUpdate: Boolean = false,
    /** Auto-update interval in minutes. */
    val autoUpdateInterval: Int = 1440,
    val subscriptionUserAgent: String = "YukariBox/0.1",
    // Logging
    /**
     * Whether anything is journalled at all. **Off by default**: an ordinary user has no use
     * for a log, and one that records by default keeps node names and endpoints on disk for
     * everybody to pay for the few who read them. Turning it on is one switch on the settings
     * screen, or one broadcast on a debug build.
     */
    val logging: Boolean = false,
    val logLevel: String = "info",
    /** Subscription that holds the globally selected node (survives restarts). */
    val selectedSubId: String = "",
    /** Persisted ordering of the node list, reapplied across sessions. */
    val sortMode: SortMode = SortMode.Manual,
)

/**
 * Observable, disk-persisted application settings. Mirrors [NodeRepository]'s
 * pattern: a Compose-observable façade over a serializable [SettingsData], saved
 * to `files/settings.json` and restored on launch. Every mutation persists.
 */
object SettingsStore {

    private val io = Executors.newSingleThreadExecutor(AppThreads.factory("settings-io"))
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; isLenient = true }
    private var loaded = false

    /** Guards the read-modify-write in [update] (see its KDoc). */
    private val lock = Any()

    /**
     * The newest adopted state that has not been written yet, or `null` when every adopted
     * state is on disk. Published under [lock]; drained by whichever [io] task gets there
     * first. See [update] for why it exists.
     */
    private val pending = AtomicReference<SettingsData?>(null)

    var data by mutableStateOf(SettingsData())
        private set

    /**
     * The three fields read from *wide* composition scopes, each as its own derived state.
     *
     * [data] is one `mutableStateOf` holding sixty-odd fields, so reading any of them
     * subscribes the reading scope to a change in *all* of them. Three places made that
     * expensive rather than merely untidy:
     *
     * - `MainActivity.setContent` reads the theme in the **root** scope, so every settings
     *   write — a sort mode, an MTU keystroke, the journal switch — re-ran the whole theme
     *   and the shell under it.
     * - `ui/kit/Motion.kt`'s `motionEnabled()` reads the animation flag, and it is called
     *   from inside list-item scopes, so every visible server row was subscribed to every
     *   setting in the app.
     * - `personaName()` reads the nickname from the drawer header and the profile card.
     *
     * A [derivedStateOf] recomputes on any change to [data] but notifies its readers only
     * when its own value differs, which is what narrows those scopes to the field they
     * actually care about. Derived rather than mirrored into a second `mutableStateOf` on
     * purpose: a copy is a second source of truth that a future `update` can forget to
     * maintain, and this store already holds the settings the kill switch depends on.
     */
    val themeMode: ThemeMode by derivedStateOf { data.themeMode }

    /** @see themeMode */
    val animations: Boolean by derivedStateOf { data.animations }

    /** @see themeMode */
    val nickname: String by derivedStateOf { data.nickname }

    /**
     * Set when the settings file existed but could not be parsed, so a surface can
     * tell the user their settings were reset rather than leaving it silent. The
     * unreadable bytes are kept as `settings.json.corrupt` by [DurableFile].
     */
    var loadFailed by mutableStateOf(false)
        private set

    private fun store() = DurableFile(File(AppContext.context.filesDir, "settings.json"))

    /** Read saved settings from disk once at startup (synchronous, cheap). */
    fun load() {
        if (loaded) return
        loaded = true
        // Sanitized on the way in: the file may have been hand-edited, restored
        // from a foreign backup, or truncated, and an out-of-range MTU would
        // break both the live TUN and the fail-closed one (see SettingsGuard).
        val decode: (String) -> SettingsData = {
            SettingsGuard.sanitize(json.decodeFromString(SettingsData.serializer(), it))
        }
        when (val read = store().read(decode)) {
            is DurableFile.Read.Ok -> data = read.value
            is DurableFile.Read.Recovered -> {
                data = read.value
                Logs.w("Settings", "settings.json was unreadable; recovered from backup copy")
            }
            is DurableFile.Read.Missing -> data = SettingsData()
            is DurableFile.Read.Corrupt -> {
                Logs.e("Settings", "settings.json unreadable, reset to defaults", read.cause)
                loadFailed = true
            }
        }
    }

    /**
     * Apply a change to the settings and persist the result.
     *
     * The transform and the state write are locked together: this is a
     * read-modify-write over [data] reachable from the UI thread, the service
     * worker and the repository executor at once, and the unlocked version lost
     * whichever concurrent update read first (`selectedSubId` written by an
     * auto-update racing a settings toggle would silently revert one of them).
     * **Encoding does not.** It used to happen inside the lock, on the caller's thread, and
     * the caller is very often the UI thread: `NodeRepository.select` reaches here through
     * `rememberSelectedSub`, so tapping a server row serialised sixty-odd fields on the main
     * thread before the frame that showed the selection could be drawn. What has to be atomic
     * with the mutation is *adopting* the state, not writing it out.
     *
     * Ordering is kept by [pending] rather than by where the encode happens — and it is now
     * actually kept. Encoding under the lock never guaranteed it: two updaters can both leave
     * the lock and then reach `io.execute` in the opposite order, which queued the older state
     * last and left it on disk. [pending] is published *inside* the lock, so it always holds
     * the newest adopted state, and each queued writer takes whatever is newest and skips if
     * another writer already took it. A burst — a sort, a star and a selection in the same
     * gesture — therefore costs one encode and one fsync instead of three.
     */
    fun update(transform: (SettingsData) -> SettingsData) {
        val prev: SettingsData
        val next: SettingsData
        synchronized(lock) {
            prev = data
            next = transform(prev)
            data = next
            pending.set(next)
        }
        if (next != prev) Logs.i("Settings") { "changed: ${diff(prev, next)}" }
        io.execute {
            val state = pending.getAndSet(null) ?: return@execute
            try {
                store().write(json.encodeToString(SettingsData.serializer(), state))
            } catch (e: java.io.IOException) {
                Logs.e("Settings", "persist failed", e)
                // Put it back unless a newer state has arrived, so a failed write leaves the
                // change for the next writer to retry instead of dropping it silently.
                pending.compareAndSet(null, state)
            }
        }
    }

    /** Human-readable list of fields that differ between two snapshots. */
    private fun diff(a: SettingsData, b: SettingsData): String = buildList {
        if (a.serviceMode != b.serviceMode) add("serviceMode ${a.serviceMode}->${b.serviceMode}")
        if (a.themeMode != b.themeMode) add("themeMode ${a.themeMode}->${b.themeMode}")
        // The value stays out, like the DoH URLs below: it is the user's own name, and
        // the log is rendered back to them line by line on the Log screen.
        if (a.nickname != b.nickname) add("nickname changed")
        // DoH URLs can embed auth tokens (NextDNS/AdGuard) — log only that they changed.
        if (a.remoteDns != b.remoteDns) add("remoteDns changed")
        if (a.directDns != b.directDns) add("directDns changed")
        if (a.enableDnsRouting != b.enableDnsRouting) add("enableDnsRouting ${a.enableDnsRouting}->${b.enableDnsRouting}")
        if (a.mtu != b.mtu) add("mtu ${a.mtu}->${b.mtu}")
        if (a.tunStack != b.tunStack) add("tunStack ${a.tunStack}->${b.tunStack}")
        if (a.ipv6Mode != b.ipv6Mode) add("ipv6Mode ${a.ipv6Mode}->${b.ipv6Mode}")
        if (a.bypassLan != b.bypassLan) add("bypassLan ${a.bypassLan}->${b.bypassLan}")
        if (a.sniffing != b.sniffing) add("sniffing ${a.sniffing}->${b.sniffing}")
        if (a.perAppProxyInclude != b.perAppProxyInclude) add("perAppInclude ${a.perAppProxyInclude}->${b.perAppProxyInclude}")
        if (a.perAppPackages != b.perAppPackages) add("perAppPackages ${a.perAppPackages.size}->${b.perAppPackages.size}")
        if (a.presetRuBypass != b.presetRuBypass) add("presetRuBypass=${b.presetRuBypass}")
        if (a.presetAdBlock != b.presetAdBlock) add("presetAdBlock=${b.presetAdBlock}")
        if (a.allowInsecure != b.allowInsecure) add("allowInsecure ${a.allowInsecure}->${b.allowInsecure}")
        // Never the value: this is a live credential for the local inbound.
        if (a.proxyPassword != b.proxyPassword) add("proxyPassword changed")
        if (a.proxyAuthDisabled != b.proxyAuthDisabled) add("proxyAuthDisabled=${b.proxyAuthDisabled}")
        if (a.autoConnectOnBoot != b.autoConnectOnBoot) add("autoConnectOnBoot ${a.autoConnectOnBoot}->${b.autoConnectOnBoot}")
        if (a.autoConnectOnNetwork != b.autoConnectOnNetwork) add("autoConnectOnNetwork=${b.autoConnectOnNetwork}")
        if (a.reconnectOnNetworkChange != b.reconnectOnNetworkChange) add("reconnectOnNetworkChange ${a.reconnectOnNetworkChange}->${b.reconnectOnNetworkChange}")
        if (a.autoSwitchOnDrop != b.autoSwitchOnDrop) add("autoSwitchOnDrop=${b.autoSwitchOnDrop}")
        // Worth reading twice: `update` assigns `data` before it logs this, so switching
        // logging ON records its own line and switching it OFF does not. That is the right way
        // round -- the "off" line would be written after the user asked for no lines -- and it
        // is not a bug to fix.
        if (a.logging != b.logging) add("logging ${a.logging}->${b.logging}")
        if (a.logLevel != b.logLevel) add("logLevel ${a.logLevel}->${b.logLevel}")
        if (a.connectionTestUrl != b.connectionTestUrl) add("connectionTestUrl")
        if (a.autoUpdate != b.autoUpdate) add("autoUpdate ${a.autoUpdate}->${b.autoUpdate}")
        if (a.autoUpdateInterval != b.autoUpdateInterval) add("autoUpdateInterval ${a.autoUpdateInterval}->${b.autoUpdateInterval}")
        if (a.subscriptionUserAgent != b.subscriptionUserAgent) add("subscriptionUserAgent")
        if (a.animations != b.animations) add("animations ${a.animations}->${b.animations}")
        if (a.nodeInNotification != b.nodeInNotification) add("nodeInNotification ${a.nodeInNotification}->${b.nodeInNotification}")
        if (a.sortMode != b.sortMode) add("sortMode ${a.sortMode}->${b.sortMode}")
    }.joinToString(", ").ifEmpty { "(no field diff)" }

    /**
     * The password for the proxy-only inbound, generating and persisting one on
     * first use. Returns blank when the user has opted out of authentication.
     *
     * Lazy rather than generated at install time so a user who never touches
     * proxy-only mode never has a credential on disk at all.
     */
    fun ensureProxyPassword(): String {
        if (data.proxyAuthDisabled) return ""
        data.proxyPassword.takeIf { it.isNotBlank() }?.let { return it }
        // Generated inside the transform, which [update] runs under the lock: the
        // check and the write are then atomic, so two surfaces starting proxy-only
        // at once cannot end up with one password in the running config and a
        // different one on disk.
        update { current ->
            if (current.proxyPassword.isBlank()) {
                Logs.i("Settings", "generated proxy-only inbound credentials")
                current.copy(proxyPassword = ProxyAuth.newPassword())
            } else {
                current
            }
        }
        return data.proxyPassword
    }

    /**
     * Map the current settings to the core's [ConfigOptions].
     *
     * One snapshot of [data] rather than a field-by-field re-read. The proxy-only auth
     * toggle is user-editable while a connect is building this, and reading it twice
     * could pair a blank `proxyUser` with a non-blank password — which `ConfigBuilder`
     * turns into a mixed inbound with no `users[]` at all, i.e. an open loopback proxy
     * while the settings screen still shows authentication as on.
     */
    fun configOptions(logOutput: String = ""): ConfigOptions {
        val current = data
        val proxyOnly = current.serviceMode == ServiceMode.ProxyOnly
        // Read once and reused for both credential fields so they cannot disagree.
        val authenticated = proxyOnly && !current.proxyAuthDisabled
        return ConfigOptions(
            proxyOnly = proxyOnly,
            // Clamped at the point of use, not on edit, so the settings text field stays
            // typeable while the core can never receive an MTU the kernel rejects.
            mtu = SettingsGuard.mtu(current.mtu),
            tunStack = current.tunStack.value,
            logging = current.logging,
            logLevel = current.logLevel,
            logOutput = logOutput,
            dnsRemote = current.remoteDns,
            dnsDirect = current.directDns,
            enableDnsRouting = current.enableDnsRouting,
            ipv6 = current.ipv6Mode == Ipv6Mode.Enable,
            bypassLan = current.bypassLan,
            sniffing = current.sniffing,
            globalAllowInsecure = current.allowInsecure,
            // User rules outrank presets, so a custom rule can override a preset decision.
            // Sanitized here: a malformed CIDR or port stops the core from starting at all,
            // and a rule left with no valid condition matches everything (see
            // RouteRuleValidation).
            userRules = (
                dev.yukaribox.vpn.data.RouteRepository.activeRules() +
                    RoutePresets.enabledRules(current)
                )
                .mapNotNull { it.sanitizedForConfig() },
            // Only meaningful in proxy-only mode, and only generated when that mode is
            // actually used — a VPN-mode user never gets a credential written to disk.
            proxyUser = if (authenticated) ProxyAuth.USER else "",
            proxyPassword = if (authenticated) ensureProxyPassword() else "",
        )
    }
}
