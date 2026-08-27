package dev.yukaribox.vpn.ui

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.yukaribox.vpn.R
import dev.yukaribox.vpn.core.SettingsStore
import dev.yukaribox.vpn.ui.kit.BusySweep
import dev.yukaribox.vpn.ui.kit.EmptyState
import dev.yukaribox.vpn.ui.kit.ListRow
import dev.yukaribox.vpn.ui.kit.ScreenMargin
import dev.yukaribox.vpn.ui.kit.SegmentedTabs
import dev.yukaribox.vpn.ui.kit.TitleTopBar
import dev.yukaribox.vpn.ui.theme.LabelWide
import dev.yukaribox.vpn.ui.theme.YukariIcons
import dev.yukaribox.vpn.ui.theme.yukari
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class AppRow(val packageName: String, val label: String, val system: Boolean)

/**
 * Per-app split tunnel.
 *
 * The mode picker is two segments rather than a switch, because the two modes are not
 * each other's negation from the user's side: "only these apps use the tunnel" and
 * "these apps skip the tunnel" produce opposite results from the same checkbox list, and
 * a switch would leave the list's meaning implicit.
 *
 * Changes apply on the next connection — the per-app set reaches the OS through
 * `VpnService.Builder`, which is only built when a tunnel is established.
 */
@Composable
fun PerAppScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val settings = SettingsStore.data
    var selected by remember { mutableStateOf(settings.perAppPackages) }
    var include by remember { mutableStateOf(settings.perAppProxyInclude) }
    val labelProxy = stringResource(R.string.perapp_mode_proxy)
    val labelBypass = stringResource(R.string.perapp_mode_bypass)

    // null while the package-manager scan is still running.
    val apps by produceState<List<AppRow>?>(initialValue = null) {
        value = withContext(Dispatchers.IO) { loadApps(context.packageManager, context.packageName) }
    }

    Column(Modifier.fillMaxSize()) {
        TitleTopBar(
            title = stringResource(R.string.title_perapp),
            onNav = onBack,
            navContentDescription = stringResource(R.string.cd_back),
        )
        Column(
            Modifier.padding(horizontal = ScreenMargin, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SegmentedTabs(
                options = listOf(true, false),
                selectedOption = include,
                label = { if (it) labelProxy else labelBypass },
                onSelect = { value ->
                    include = value
                    SettingsStore.update { it.copy(perAppProxyInclude = value) }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                stringResource(R.string.perapp_selected_count, selected.size).uppercase(),
                style = LabelWide,
                color = MaterialTheme.yukari.textTertiary,
                // 12 dp of screen margin plus 12 here is the 24 dp content column every
                // other list and settings screen reaches (a row gets there through its
                // own 12 dp padding). The picker above stays flush with the margin: it is
                // a full-width control, like the servers screen's search field.
                modifier = Modifier.padding(start = CONTENT_INSET),
            )
        }
        when {
            apps == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    BusySweep(Modifier.size(28.dp))
                    Text(
                        stringResource(R.string.perapp_loading),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            apps.orEmpty().isEmpty() -> EmptyState(
                title = stringResource(R.string.perapp_empty_title),
                body = stringResource(R.string.perapp_empty_hint),
                icon = YukariIcons.Apps,
            )
            else -> LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = ScreenMargin, end = ScreenMargin, bottom = 20.dp),
            ) {
                items(apps.orEmpty(), key = { it.packageName }) { app ->
                    val checked = app.packageName in selected
                    ListRow(
                        title = app.label,
                        subtitle = if (app.system) {
                            "${app.packageName} · ${stringResource(R.string.perapp_system_tag)}"
                        } else {
                            app.packageName
                        },
                        trailing = { Checkbox(checked = checked, onCheckedChange = null) },
                        onClick = {
                            selected = if (checked) {
                                selected - app.packageName
                            } else {
                                selected + app.packageName
                            }
                            SettingsStore.update { it.copy(perAppPackages = selected) }
                        },
                    )
                }
            }
        }
    }
}

/**
 * Installed apps that can use the network, this app excluded, user apps first.
 *
 * The exclusion is not cosmetic: routing our own traffic through the tunnel we create is
 * what `PerAppRouting.plan` exists to prevent, and offering it here would make that a
 * one-tap mistake.
 */
private fun loadApps(pm: PackageManager, selfPackage: String): List<AppRow> {
    val internet = android.Manifest.permission.INTERNET
    return pm.getInstalledApplications(PackageManager.GET_META_DATA)
        .asSequence()
        .filter { it.packageName != selfPackage }
        .filter { pm.checkPermission(internet, it.packageName) == PackageManager.PERMISSION_GRANTED }
        .map { info ->
            AppRow(
                packageName = info.packageName,
                label = pm.getApplicationLabel(info).toString(),
                system = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
            )
        }
        .sortedWith(compareBy({ it.system }, { it.label.lowercase() }))
        .toList()
}

/**
 * What a caption adds to [ScreenMargin] to land on the screen's 24 dp content column.
 * A row reaches the same column through its own horizontal padding, which is why the
 * list gets [ScreenMargin] and not this.
 */
private val CONTENT_INSET = 12.dp
