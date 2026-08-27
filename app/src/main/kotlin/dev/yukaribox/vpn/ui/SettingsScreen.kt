package dev.yukaribox.vpn.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.yukaribox.vpn.R
import dev.yukaribox.vpn.core.AppThreads
import dev.yukaribox.vpn.core.DohPreset
import dev.yukaribox.vpn.core.DohPresets
import dev.yukaribox.vpn.core.DohReachability
import dev.yukaribox.vpn.core.Ipv6Mode
import dev.yukaribox.vpn.core.Logs
import dev.yukaribox.vpn.core.ServiceMode
import dev.yukaribox.vpn.core.SettingsStore
import dev.yukaribox.vpn.core.TunStack
import dev.yukaribox.vpn.core.TunnelController
import java.util.concurrent.Executors
import dev.yukaribox.vpn.ui.kit.BusySweep
import dev.yukaribox.vpn.ui.kit.NavRow
import dev.yukaribox.vpn.ui.kit.Notice
import dev.yukaribox.vpn.ui.kit.PickerRow
import dev.yukaribox.vpn.ui.kit.ScreenMargin
import dev.yukaribox.vpn.ui.kit.SectionCaption
import dev.yukaribox.vpn.ui.kit.SwitchRow
import dev.yukaribox.vpn.ui.kit.TitleTopBar
import dev.yukaribox.vpn.ui.theme.YukariIcons
import dev.yukaribox.vpn.vpn.TunnelLauncher

/**
 * Everything that configures the tunnel itself.
 *
 * The three settings a user changes often — appearance, language, start on boot — are on
 * the profile screen instead, and are deliberately not repeated here: two switches
 * writing the same field is how a settings screen starts disagreeing with itself.
 *
 * Every row reads and writes [SettingsStore], which persists on each change. Most of the
 * network rows only take effect on the next connect, which is what the last row is for.
 *
 * The rows sit on the bare page: no card around a group, no divider between rows and no
 * explanatory second line — a caption, then a stack of single-line 42 dp rows, which is
 * what the reference's own settings list is. The captions carry the vertical rhythm
 * (32 dp above, 24 dp below), so there is no arrangement spacing between the sections
 * either. A row that has a current value shows it in its trailing slot; the six free-text
 * settings keep their sentence of explanation in the edit dialog, where there is room
 * for it.
 *
 * **Every row carries a leading glyph, and every one of them is outline.** With the group
 * cards gone there is nothing to mask a title that starts at 24 dp sitting under one that
 * starts at 60 dp, so the left edge is kept flush by giving every row a glyph — the
 * reference's own answer — and §6's rule for a list of settings is outline by default
 * (fill is the bottom bar's, and a fill/outline swap is how an on/off state is encoded, so
 * a filled glyph in a column of line art reads as a state that is not there). The five
 * that were filled now take an outline of the same idea: `Icons.Outlined.Person`,
 * `Icons.Outlined.PlayArrow`, the outline shield, a hollow wrench for the TUN stack and
 * the ringed `i` for the log level. The set is closed: `YukariIcons` plus the
 * `material-icons-core` glyphs — filled *or* outlined, both ship in the artifact already
 * on the classpath — with no new paths and no icon dependency. Where nothing fit exactly
 * the nearest existing glyph is used rather than a drawn one, so a few are
 * approximations: the pencil on all six free-text rows says "typed by hand" rather than
 * naming the setting, and the page glyph on "Node in notification" is the closest thing
 * the set has to a notification.
 */
@Composable
fun SettingsScreen(
    onOpenDrawer: () -> Unit,
    onOpenPerApp: () -> Unit,
    onOpenRoutes: () -> Unit,
    onOpenLogs: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        TitleTopBar(
            title = stringResource(R.string.title_settings),
            onNav = onOpenDrawer,
            navContentDescription = stringResource(R.string.cd_menu),
            navIcon = Icons.Default.Menu,
        )
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = ScreenMargin),
        ) {
            ConnectionSection()
            DnsSection()
            NetworkSection()
            RoutingSection(onOpenPerApp = onOpenPerApp, onOpenRoutes = onOpenRoutes)
            BehaviourSection()
            SubscriptionSection()
            SecuritySection()
            DiagnosticsSection(onOpenLogs = onOpenLogs)
            Spacer(Modifier.height(28.dp))
        }
    }
}

/**
 * How the service runs: a full TUN, or only a local proxy inbound.
 *
 * `ServiceMode` gets real labels rather than its enum names — unlike `TunStack` and
 * `Ipv6Mode` below, whose values are the upstream sing-box vocabulary a user comparing
 * configs needs to see verbatim. "Vpn" and "ProxyOnly" are neither upstream terms nor
 * English.
 */
@Composable
private fun ConnectionSection() {
    val settings = SettingsStore.data
    val labels = ServiceMode.entries.associateWith { mode ->
        stringResource(
            when (mode) {
                ServiceMode.Vpn -> R.string.settings_mode_vpn
                ServiceMode.ProxyOnly -> R.string.settings_mode_proxy
            },
        )
    }
    SectionCaption(stringResource(R.string.settings_cat_connection))
    PickerRow(
        label = stringResource(R.string.settings_service_mode),
        icon = YukariIcons.Shield,
        current = labels.getValue(settings.serviceMode),
        options = ServiceMode.entries.map { labels.getValue(it) },
        onPick = { picked ->
            val mode = labels.entries.first { it.value == picked }.key
            Logs.tap("settings:mode:${mode.name}")
            SettingsStore.update { it.copy(serviceMode = mode) }
        },
    )
    SwitchRow(
        label = stringResource(R.string.settings_node_in_notification),
        icon = YukariIcons.Document,
        checked = settings.nodeInNotification,
        onChange = { value -> SettingsStore.update { it.copy(nodeInNotification = value) } },
    )
    SwitchRow(
        label = stringResource(R.string.settings_animations),
        icon = Icons.Outlined.PlayArrow,
        checked = settings.animations,
        onChange = { value -> SettingsStore.update { it.copy(animations = value) } },
    )
    if (settings.serviceMode == ServiceMode.ProxyOnly) {
        Spacer(Modifier.height(CARD_GAP))
        ProxyCard()
    }
}

/** The two resolvers, their presets, and whether DNS is routed at all. */
@Composable
private fun DnsSection() {
    val settings = SettingsStore.data
    SectionCaption(stringResource(R.string.settings_cat_dns))
    TextRow(
        title = stringResource(R.string.settings_remote_dns),
        hint = stringResource(R.string.settings_remote_dns_sum),
        value = settings.remoteDns,
    ) { value -> SettingsStore.update { it.copy(remoteDns = value) } }
    DnsPresetRow(stringResource(R.string.settings_remote_dns_preset), DohPresets.remote) { url ->
        SettingsStore.update { it.copy(remoteDns = url) }
    }
    TextRow(
        title = stringResource(R.string.settings_direct_dns),
        hint = stringResource(R.string.settings_direct_dns_sum),
        value = settings.directDns,
    ) { value -> SettingsStore.update { it.copy(directDns = value) } }
    DnsPresetRow(stringResource(R.string.settings_direct_dns_preset), DohPresets.direct) { url ->
        SettingsStore.update { it.copy(directDns = url) }
    }
    SwitchRow(
        label = stringResource(R.string.settings_dns_routing),
        icon = YukariIcons.Routes,
        checked = settings.enableDnsRouting,
        onChange = { value -> SettingsStore.update { it.copy(enableDnsRouting = value) } },
    )
}

/** MTU, TUN stack and IPv6 — the three values that reach the kernel. */
@Composable
private fun NetworkSection() {
    val settings = SettingsStore.data
    SectionCaption(stringResource(R.string.settings_cat_network))
    TextRow(
        title = stringResource(R.string.settings_mtu),
        hint = stringResource(R.string.settings_mtu_sum),
        value = settings.mtu.toString(),
    ) { value ->
        // Bounds live in SettingsGuard, applied where the value is consumed, so the
        // field itself stays typeable digit by digit.
        value.toIntOrNull()?.let { mtu -> SettingsStore.update { it.copy(mtu = mtu) } }
    }
    PickerRow(
        label = stringResource(R.string.settings_tun_stack),
        icon = Icons.Outlined.Build,
        current = settings.tunStack.name,
        options = TunStack.entries.map { it.name },
        onPick = { picked -> SettingsStore.update { it.copy(tunStack = TunStack.valueOf(picked)) } },
    )
    PickerRow(
        label = stringResource(R.string.settings_ipv6),
        icon = YukariIcons.Globe,
        current = settings.ipv6Mode.name,
        options = Ipv6Mode.entries.map { it.name },
        onPick = { picked -> SettingsStore.update { it.copy(ipv6Mode = Ipv6Mode.valueOf(picked)) } },
    )
}

/** Where traffic goes: the rules screen, the per-app picker, and four switches. */
@Composable
private fun RoutingSection(onOpenPerApp: () -> Unit, onOpenRoutes: () -> Unit) {
    val settings = SettingsStore.data
    SectionCaption(stringResource(R.string.settings_cat_routing))
    NavRow(
        label = stringResource(R.string.settings_routes),
        icon = YukariIcons.Routes,
        onClick = onOpenRoutes,
    )
    NavRow(
        label = stringResource(R.string.settings_perapp),
        value = if (settings.perAppPackages.isEmpty()) {
            stringResource(R.string.settings_perapp_all)
        } else {
            stringResource(
                R.string.settings_perapp_summary,
                settings.perAppPackages.size,
                stringResource(
                    if (settings.perAppProxyInclude) {
                        R.string.settings_perapp_mode_proxy
                    } else {
                        R.string.settings_perapp_mode_bypass
                    },
                ),
            )
        },
        icon = YukariIcons.Apps,
        onClick = onOpenPerApp,
    )
    SwitchRow(
        label = stringResource(R.string.settings_bypass_lan),
        icon = YukariIcons.NavOff,
        checked = settings.bypassLan,
        onChange = { value -> SettingsStore.update { it.copy(bypassLan = value) } },
    )
    SwitchRow(
        label = stringResource(R.string.settings_sniffing),
        icon = YukariIcons.Eye,
        checked = settings.sniffing,
        onChange = { value -> SettingsStore.update { it.copy(sniffing = value) } },
    )
    SwitchRow(
        label = stringResource(R.string.settings_preset_ru_bypass),
        icon = YukariIcons.Folder,
        checked = settings.presetRuBypass,
        onChange = { value -> SettingsStore.update { it.copy(presetRuBypass = value) } },
    )
    SwitchRow(
        label = stringResource(R.string.settings_preset_adblock),
        icon = YukariIcons.Shield,
        checked = settings.presetAdBlock,
        onChange = { value -> SettingsStore.update { it.copy(presetAdBlock = value) } },
    )
}

/** What the app does on its own. Start-on-boot lives on the profile screen. */
@Composable
private fun BehaviourSection() {
    val settings = SettingsStore.data
    SectionCaption(stringResource(R.string.settings_cat_behavior))
    SwitchRow(
        label = stringResource(R.string.settings_autoconnect_network),
        icon = YukariIcons.Power,
        checked = settings.autoConnectOnNetwork,
        onChange = { value -> SettingsStore.update { it.copy(autoConnectOnNetwork = value) } },
    )
    SwitchRow(
        label = stringResource(R.string.settings_reconnect_network),
        icon = Icons.Default.Refresh,
        checked = settings.reconnectOnNetworkChange,
        onChange = { value -> SettingsStore.update { it.copy(reconnectOnNetworkChange = value) } },
    )
    SwitchRow(
        label = stringResource(R.string.settings_auto_switch),
        icon = YukariIcons.Radar,
        checked = settings.autoSwitchOnDrop,
        onChange = { value -> SettingsStore.update { it.copy(autoSwitchOnDrop = value) } },
    )
}

/** How subscriptions refresh themselves. */
@Composable
private fun SubscriptionSection() {
    val settings = SettingsStore.data
    SectionCaption(stringResource(R.string.settings_cat_subscription))
    SwitchRow(
        label = stringResource(R.string.settings_auto_update),
        icon = Icons.Default.Refresh,
        checked = settings.autoUpdate,
        onChange = { value -> SettingsStore.update { it.copy(autoUpdate = value) } },
    )
    TextRow(
        title = stringResource(R.string.settings_update_interval),
        hint = stringResource(R.string.settings_update_interval_sum),
        value = settings.autoUpdateInterval.toString(),
    ) { value ->
        value.toIntOrNull()?.let { minutes ->
            SettingsStore.update { it.copy(autoUpdateInterval = minutes) }
        }
    }
    TextRow(
        title = stringResource(R.string.settings_user_agent),
        hint = stringResource(R.string.settings_user_agent_sum),
        value = settings.subscriptionUserAgent,
    ) { value -> SettingsStore.update { it.copy(subscriptionUserAgent = value) } }
    TextRow(
        title = stringResource(R.string.settings_test_url),
        hint = stringResource(R.string.settings_test_url_sum),
        value = settings.connectionTestUrl,
    ) { value -> SettingsStore.update { it.copy(connectionTestUrl = value) } }
}

/**
 * The two opt-outs that weaken the app on purpose, together, so neither can be flipped
 * without seeing the other.
 *
 * `proxyAuthDisabled` had no control at all before this. That is worse than a scary
 * switch: the local mixed inbound is generated *with* a password, and a user whose proxy
 * client cannot do auth had no way to turn it off except editing `settings.json` — so the
 * documented opt-out was unreachable.
 *
 * What it costs is spelled out in a [Notice] rather than in the row's second line, which
 * is the one place this screen keeps a sentence: rows here are single-line, and deleting
 * the sentence outright would have left the most consequential switch in the app with
 * nothing but its label. The notice is the escalation pattern — a bordered card with the
 * sentence set Bold, no hue — and it only appears in the state that is actually paying
 * the cost. The proxy card repeats it beside the address in proxy-only mode.
 */
@Composable
private fun SecuritySection() {
    val settings = SettingsStore.data
    SectionCaption(stringResource(R.string.settings_cat_advanced))
    SwitchRow(
        label = stringResource(R.string.settings_allow_insecure),
        icon = YukariIcons.ShieldOff,
        checked = settings.allowInsecure,
        onChange = { value -> SettingsStore.update { it.copy(allowInsecure = value) } },
    )
    SwitchRow(
        label = stringResource(R.string.settings_proxy_auth_off),
        icon = Icons.Outlined.Person,
        checked = settings.proxyAuthDisabled,
        onChange = { value ->
            Logs.tap("settings:proxy-auth-off:$value")
            SettingsStore.update { it.copy(proxyAuthDisabled = value) }
        },
    )
    if (settings.proxyAuthDisabled) {
        Spacer(Modifier.height(CARD_GAP))
        Notice(text = stringResource(R.string.settings_proxy_auth_off_sum), emphasis = true)
    }
}

/**
 * The log, its level, and the one action that applies everything above live.
 *
 * "Reconnect now" is guarded by the tunnel's own state, not by taste:
 * `TunnelLauncher.reconnect` is a documented no-op while the tunnel is idle — it must
 * never *start* a session nobody asked for — and with this screen's rows carrying no
 * summary line, a tappable row that silently does nothing is the only thing the user
 * would see. Disabled, the row fades and reports itself as disabled to a screen reader.
 */
@Composable
private fun DiagnosticsSection(onOpenLogs: () -> Unit) {
    val context = LocalContext.current
    val settings = SettingsStore.data
    SectionCaption(stringResource(R.string.settings_cat_logs))
    // Off by default, so this is the first row in the section rather than a detail under it.
    SwitchRow(
        label = stringResource(R.string.settings_logging),
        summary = stringResource(R.string.settings_logging_sum),
        icon = YukariIcons.Document,
        checked = settings.logging,
        onChange = { on -> Logs.tap("settings:logging:$on"); Logs.setEnabled(on) },
    )
    // The switch reaches `Logs` at once and the core only at the next connect, because the
    // core's `log.disabled` is written into a config that is built when a session starts. So a
    // session already running keeps appending its own lines, and the honest thing is to say so
    // next to the "Reconnect now" row that fixes it two rows below.
    if (!settings.logging && TunnelController.state.isActive) {
        Spacer(Modifier.height(CARD_GAP))
        Notice(text = stringResource(R.string.settings_logging_pending), emphasis = true)
    }
    NavRow(
        label = stringResource(R.string.settings_view_logs),
        icon = YukariIcons.Document,
        onClick = { Logs.tap("settings:view-logs"); onOpenLogs() },
    )
    // Only while something is being recorded. A verbosity for a journal that keeps nothing is
    // a control with no subject, and `PickerRow` has no disabled state to say so with.
    if (settings.logging) {
        PickerRow(
            label = stringResource(R.string.settings_log_level),
            icon = Icons.Outlined.Info,
            current = settings.logLevel,
            options = LOG_LEVELS,
            onPick = { picked -> SettingsStore.update { it.copy(logLevel = picked) } },
        )
    }
    NavRow(
        label = stringResource(R.string.settings_reconnect_now),
        icon = Icons.Default.Refresh,
        enabled = TunnelController.state.isActive,
        onClick = { Logs.tap("settings:reconnect"); TunnelLauncher.reconnect(context) },
    )
}

/**
 * A free-text setting: the current value on the row, edited in a dialog rather than
 * inline.
 *
 * The dialog is what stops a half-typed MTU or a truncated DoH URL from reaching
 * `SettingsStore` on every keystroke, and it is also where [hint] lives now that the rows
 * carry no second line — a sentence of explanation has room under a text field and none
 * inside a 42 dp row. The field itself validates nothing: bounds for the values that
 * reach the kernel or the core are applied in `SettingsGuard`, where they cannot be
 * bypassed by a hand-edited `settings.json`, so this one stays typeable digit by digit.
 *
 * [icon] defaults to the pencil, which is the honest glyph for every row here: what they
 * have in common is that the value is typed by hand.
 */
@Composable
private fun TextRow(
    title: String,
    hint: String,
    value: String,
    icon: ImageVector = Icons.Default.Edit,
    onCommit: (String) -> Unit,
) {
    var editing by remember { mutableStateOf(false) }
    NavRow(
        label = title,
        value = value,
        icon = icon,
        onClick = { editing = true },
    )
    if (editing) {
        var draft by remember { mutableStateOf(value) }
        AlertDialog(
            onDismissRequest = { editing = false },
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text(title) },
            text = {
                Column {
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        hint,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { editing = false; onCommit(draft.trim()) }) {
                    Text(stringResource(R.string.action_save))
                }
            },
            dismissButton = {
                TextButton(onClick = { editing = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

/**
 * DoH preset picker.
 *
 * Every preset is probed off the main thread when the menu opens, and only the ones that
 * answer are selectable. A preset that is offered before it is validated is a setting that
 * silently breaks DNS — and a domain-named DoH server that cannot be reached takes the
 * whole tunnel's name resolution with it.
 */
@Composable
private fun DnsPresetRow(title: String, presets: List<DohPreset>, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    // url -> null = checking, true = reachable, false = unreachable
    val status = remember { mutableStateMapOf<String, Boolean?>() }
    Box {
        NavRow(
            label = title,
            icon = YukariIcons.Globe,
            onClick = {
                open = true
                // One shared background-priority executor, and only for presets that have not
                // answered yet. A bare `Thread` per preset ran five TLS handshakes at
                // `NORM_PRIORITY` -- the UI cpuset -- during the dropdown's opening
                // animation, which is the exact mechanism `AppThreads` exists to prevent, and
                // repeated taps stacked more of them with nothing to dedupe.
                presets.filter { status[it.url] != true }.forEach { preset ->
                    status[preset.url] = null
                    dohProbes.execute { status[preset.url] = DohReachability.validate(preset.url) }
                }
            },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            presets.forEach { preset ->
                val reachable = status[preset.url]
                DropdownMenuItem(
                    text = { Text(preset.name) },
                    enabled = reachable == true,
                    onClick = { open = false; onPick(preset.url) },
                    trailingIcon = { PresetStatus(reachable) },
                )
            }
        }
    }
}

@Composable
private fun PresetStatus(reachable: Boolean?) {
    when (reachable) {
        null -> BusySweep(Modifier.size(16.dp))
        true -> Icon(Icons.Default.Check, null, Modifier.size(18.dp))
        false -> Text(
            stringResource(R.string.settings_dns_preset_unreachable),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The levels the core's logger accepts, in increasing severity.
 *
 * Read from [Logs] rather than written out again. The vocabulary lived in three places --
 * here, `Logs.ORDER` (which orders it, and so decides what `records` filters) and
 * `SettingsGuard.LOG_LEVELS` (which resets anything unrecognised on load) -- so adding or
 * renaming a level in one of them left the picker offering a value the guard silently
 * replaced, or left `rank` mis-ordering the rest.
 */
private val LOG_LEVELS = Logs.ORDER

/**
 * The DoH reachability probes, on one shared background-priority executor.
 *
 * Two threads because the probe is a bounded network wait, not work: overlapping a couple of
 * them covers the latency of a five-preset menu without putting five TLS handshakes on the
 * UI cpuset, which is what a bare `Thread` per preset did.
 */
private val dohProbes = Executors.newFixedThreadPool(2, AppThreads.factory("doh-probe"))

/** Gap from the last row of a section to a card that belongs to it. */
private val CARD_GAP = 10.dp
