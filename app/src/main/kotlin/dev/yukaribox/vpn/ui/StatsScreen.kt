package dev.yukaribox.vpn.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.yukaribox.vpn.R
import dev.yukaribox.vpn.core.NodeGeo
import dev.yukaribox.vpn.core.TunnelController
import dev.yukaribox.vpn.ui.kit.EmptyState
import dev.yukaribox.vpn.ui.kit.IconCircle
import dev.yukaribox.vpn.ui.kit.Meter
import dev.yukaribox.vpn.ui.kit.PaperCard
import dev.yukaribox.vpn.ui.kit.ScreenMargin
import dev.yukaribox.vpn.ui.kit.SectionCaption
import dev.yukaribox.vpn.ui.kit.StatFigure
import dev.yukaribox.vpn.ui.kit.StatTile
import dev.yukaribox.vpn.ui.kit.TitleTopBar
import dev.yukaribox.vpn.ui.theme.LabelWide
import dev.yukaribox.vpn.ui.theme.YukariIcons
import dev.yukaribox.vpn.ui.theme.yukari
import java.util.Locale

/**
 * Traffic, broken down by outbound.
 *
 * The sing-box core reports byte counters per outbound tag, so what this can honestly
 * show is per-*route* usage: the connected server (`proxy`) against everything that went
 * out unproxied (`direct` — LAN, bypassed apps, bypassed rules). Per-app figures are not
 * exposed by this core build, so the screen does not pretend to have them.
 *
 * Every counter here is a session counter and resets to zero on disconnect, which is why
 * there is nothing to show at all when the tunnel is down: a lifetime total the app
 * cannot actually persist would be a made-up number.
 */
@Composable
fun StatsScreen(onOpenDrawer: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        TitleTopBar(
            title = stringResource(R.string.title_stats),
            onNav = onOpenDrawer,
            navContentDescription = stringResource(R.string.cd_menu),
            navIcon = Icons.Default.Menu,
        )
        if (TunnelController.connectedSinceMs == 0L) {
            EmptyState(
                title = stringResource(R.string.stats_empty_title),
                body = stringResource(R.string.stats_empty_hint),
                icon = YukariIcons.Stats,
            )
            return@Column
        }
        val proxyUp = TunnelController.uplinkBytes
        val proxyDown = TunnelController.downlinkBytes
        val directUp = TunnelController.directUplinkBytes
        val directDown = TunnelController.directDownlinkBytes
        val total = proxyUp + proxyDown + directUp + directDown
        // Strip the feed's flag emoji here too, and `remember` it: this screen recomposes
        // once a second off the stats loop, and a subscription sets no ceiling on how
        // long a node name can be.
        val rawName = TunnelController.connectedProfile?.name?.takeIf { it.isNotBlank() }
        val proxyName = remember(rawName) { rawName?.let { NodeGeo.plainName(it) } }
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(start = ScreenMargin, end = ScreenMargin, top = CARD_TOP),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SessionCard(total = total, up = proxyUp + directUp, down = proxyDown + directDown)
            SectionCaption(stringResource(R.string.stats_breakdown_title))
            OutboundCard(
                label = proxyName ?: stringResource(R.string.stats_proxy),
                icon = YukariIcons.Globe,
                up = proxyUp,
                down = proxyDown,
                total = total,
            )
            OutboundCard(
                label = stringResource(R.string.stats_direct),
                icon = YukariIcons.Routes,
                up = directUp,
                down = directDown,
                total = total,
            )
            Text(
                stringResource(R.string.stats_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

/**
 * The session total as one figure, with the live rates under it.
 *
 * A white card like every other card on the screen. The near-black one this replaced was
 * the only such surface in the app outside the bottom bar, the FAB and the drawer's
 * selected row — ink is *chrome* here, and a content card wearing it read as a different
 * design's card dropped onto the page. The figure is 20 sp Bold — the top of the type
 * scale, the wordmark's own size — and being the only Bold thing on a page of 12 and
 * 13 sp rows is enough to make it what the eye lands on.
 *
 * The rates are separate tiles rather than part of the figure because they answer a
 * different question — "is it moving right now" versus "how much has it moved".
 */
@Composable
private fun SessionCard(total: Long, up: Long, down: Long) {
    val figure = scaleBytes(total)
    PaperCard(Modifier.fillMaxWidth()) {
        Text(
            stringResource(R.string.stats_session_total).uppercase(),
            style = LabelWide,
            color = MaterialTheme.yukari.textTertiary,
        )
        Spacer(Modifier.height(8.dp))
        StatFigure(figure.first, figure.second)
        Spacer(Modifier.height(18.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            StatTile(stringResource(R.string.stats_down), formatBytes(down))
            StatTile(stringResource(R.string.stats_up), formatBytes(up))
            StatTile(stringResource(R.string.stats_rate), formatRate(TunnelController.downlinkRate))
        }
    }
}

/** One outbound: its name, its share of the session, and its two totals. */
@Composable
private fun OutboundCard(
    label: String,
    icon: ImageVector,
    up: Long,
    down: Long,
    total: Long,
) {
    val share = if (total <= 0L) 0f else (up + down).toFloat() / total
    PaperCard(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconCircle(size = 36.dp) { Icon(icon, null, Modifier.size(17.dp)) }
            Text(
                label,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                stringResource(R.string.stats_percent, (share * 100).toInt()),
                style = LabelWide,
                color = MaterialTheme.yukari.textTertiary,
            )
        }
        Spacer(Modifier.height(12.dp))
        Meter(fraction = share)
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            StatTile(stringResource(R.string.stats_down), formatBytes(down))
            StatTile(stringResource(R.string.stats_up), formatBytes(up))
        }
    }
}

private const val UNIT_STEP = 1024.0

/** Gap from the app bar to the first card — the reference's 9 dp, on the 8 dp grid. */
private val CARD_TOP = 8.dp

/** Bytes split into a number and its unit, so the two can be set at different sizes. */
private fun scaleBytes(bytes: Long): Pair<String, String> {
    if (bytes < UNIT_STEP) return bytes.toString() to "B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var value = bytes / UNIT_STEP
    var index = 0
    while (value >= UNIT_STEP && index < units.size - 1) {
        value /= UNIT_STEP
        index++
    }
    // One decimal below 100 and none above, so the string keeps a stable width as the
    // figure climbs; with the tabular digits in Type.kt it then stops jittering.
    val text = if (value < 100) {
        String.format(Locale.US, "%.1f", value)
    } else {
        String.format(Locale.US, "%.0f", value)
    }
    return text to units[index]
}

private fun formatBytes(bytes: Long): String = scaleBytes(bytes).let { "${it.first} ${it.second}" }

private fun formatRate(bytesPerSecond: Long): String =
    scaleBytes(bytesPerSecond).let { "${it.first} ${it.second}/s" }
