package dev.yukaribox.vpn.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.yukaribox.vpn.R
import dev.yukaribox.vpn.core.LatencyTier
import dev.yukaribox.vpn.core.NodeGeo
import dev.yukaribox.vpn.core.TunnelController
import dev.yukaribox.vpn.data.LATENCY_UNTESTED
import dev.yukaribox.vpn.data.NodeRepository
import dev.yukaribox.vpn.ui.kit.PaperCard
import dev.yukaribox.vpn.ui.kit.SignalMeter
import dev.yukaribox.vpn.ui.kit.YukariBust
import dev.yukaribox.vpn.ui.theme.CardTitle
import kotlinx.coroutines.delay
import java.util.Locale

/**
 * The live-session card: that you are connected, to what, for how long, and how good the
 * link is.
 *
 * It replaces the location card on Home while a session is up rather than sitting
 * beside it, because both answer the same question ("which server?") and showing two
 * answers at once is how a user ends up unsure which one the tunnel is on. For the same
 * reason it keeps that card's job: the whole card opens the server list.
 *
 * It carries no stop control. The mockup's own status card puts a small outlined power
 * circle above the meter, and the owner cut it — a card whose tap opens the server list
 * should not also hold a button that ends the session, and the control that does end it is
 * the 148 dp circle directly above this card. What sits here instead is the meter, centred.
 *
 * The name it shows is the node the session was *started with* — `connectedProfile`,
 * not the current selection — so browsing the server list cannot rewrite what this
 * claims to be connected to.
 */
@Composable
internal fun ConnectedBanner(onOpenServers: () -> Unit, modifier: Modifier = Modifier) {
    val rawName = TunnelController.connectedProfile?.name?.takeIf { it.isNotBlank() }
    // Keyed on the raw label rather than derived inline: the session timer below
    // recomposes this card once a second, and a feed puts no ceiling on a node name.
    val plainName = remember(rawName) { rawName?.let { NodeGeo.plainName(it) } }
    val node = plainName ?: stringResource(R.string.state_no_node)
    val elapsed = rememberElapsed(TunnelController.connectedSinceMs)
    PaperCard(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(CARD_PADDING),
        onClick = onOpenServers,
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // A bust, not a circular avatar: measured, the reference's own status card
            // holds free-standing ink with no ring, cut by the card's bottom edge and
            // flush against its left one. The only circular avatar in the reference is
            // the profile card's.
            //
            // She is therefore drawn far taller than the slot she occupies —
            // `requiredSize` ignores the slot's constraints — and the card's own clip
            // does the cutting. The slot's height is what sets the card's 104 dp; the
            // lift takes her ink up out of the content padding to 6 dp below the card's
            // edge and the bleed takes it out sideways to the edge itself, which is
            // where the reference has both.
            Box(Modifier.size(width = BUST_WIDTH, height = BUST_SLOT_HEIGHT)) {
                YukariBust(
                    Modifier
                        .align(Alignment.TopStart)
                        // Anchors her top. Without this the over-sized child is *centred*
                        // in the slot — `align(TopStart)` does not survive `requiredSize`
                        // exceeding the parent — which drew her 24 dp higher than these
                        // numbers say: her hair was cut flat by the card's top edge and
                        // the shirt print, which belongs below the card, was pulled up
                        // into the middle of it. The servers header hits the same trap
                        // with the same fix.
                        .wrapContentHeight(Alignment.Top, unbounded = true)
                        .offset(x = -BUST_BLEED_X, y = -BUST_LIFT)
                        .requiredSize(width = BUST_WIDTH, height = BUST_DRAW_HEIGHT),
                )
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    stringResource(R.string.state_connected_title),
                    style = CardTitle,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.onSurface),
                    )
                    Text(
                        node,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    formatElapsed(elapsed),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // One tier->bars mapping in the tree, and it lives in `LatencyTier`. A
            // connected node that was never probed keeps [UNMEASURED_BARS] rather than the
            // tier's zero: the tunnel demonstrably works, and an empty meter over a working
            // session says the opposite. A *failed* probe is a measurement, so it draws the
            // tier's own count.
            //
            // The Row centres it: with the power circle gone this is the whole right-hand
            // slot, and a meter sitting at the bottom of an 80 dp column would read as
            // something that lost the thing above it.
            val tier = LatencyTier.of(connectedLatency())
            val bars = if (tier.hasMeasurement || tier == LatencyTier.Failed) {
                tier.filledBars
            } else {
                UNMEASURED_BARS
            }
            SignalMeter(filledBars = bars, modifier = Modifier.padding(end = METER_END))
        }
    }
}

/**
 * Seconds since the session came up, re-read once a second.
 *
 * A ticking clock rather than a value derived from the stats loop: the stats loop only
 * runs while the core reports counters, and a session that is up but idle would freeze
 * the timer at whatever second the last sample arrived.
 */
@Composable
private fun rememberElapsed(sinceMs: Long): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(sinceMs) {
        while (sinceMs > 0L) {
            now = System.currentTimeMillis()
            delay(TICK_MS)
        }
    }
    return if (sinceMs <= 0L) 0L else (now - sinceMs).coerceAtLeast(0L)
}

/** `H:MM:SS` once past an hour, `MM:SS` before it — the mockup's `00:01:25`. */
private fun formatElapsed(millis: Long): String {
    val total = millis / 1000
    val hours = total / 3600
    val minutes = (total % 3600) / 60
    val seconds = total % 60
    return if (hours > 0) {
        String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.US, "%02d:%02d", minutes, seconds)
    }
}

/**
 * The latency of the node this session is running, looked up by the profile's group so
 * a per-group node id cannot resolve against whichever group is on screen.
 */
@Composable
private fun connectedLatency(): Int {
    val profile = TunnelController.connectedProfile ?: return LATENCY_UNTESTED
    val nodeId = profile.nodeId ?: return LATENCY_UNTESTED
    // The working set is authoritative for the group that is on screen; every other
    // group only has its folded copy.
    if (profile.subId == NodeRepository.activeSubId) {
        return NodeRepository.nodes.firstOrNull { it.id == nodeId }?.latencyMs ?: LATENCY_UNTESTED
    }
    return NodeRepository.subscriptions
        .firstOrNull { it.id == profile.subId }
        ?.nodes
        ?.firstOrNull { it.id == nodeId }
        ?.latencyMs
        ?: LATENCY_UNTESTED
}

private const val TICK_MS = 1000L

/** Bars drawn for a live session whose node has never been probed. */
private const val UNMEASURED_BARS = 3

/** The card's own padding. Also what [BUST_LIFT] has to escape. */
private val CARD_PADDING = 12.dp

/**
 * The bust. `measured` on the mockup's own status card: her hair is 90 dp wide, starts
 * 6 dp below the card's top edge, sits flush against its left one, and the shirt print
 * shows only as the top of the tank glyph at the bottom edge (y 96 of a 104 dp card).
 *
 * These four numbers put ours within a few dp of all of that: her ink starts at
 * 12 − [BUST_LIFT], and her hair measures 92.6 dp wide against the mockup's 90. There is no
 * shirt print to place — `yukari_bust` is framed at the collar and the tank glyph is outside
 * it — which costs nothing here, since the mockup only ever showed the glyph's top edge. The
 * card cuts her at 104 dp across 77 dp of shirt (`measured` off the asset), so the clip
 * reads as a clip rather than as a drawing that happened to end. What made the card wrong
 * was never the size but the anchoring: see the `wrapContentHeight` above.
 *
 * [BUST_SLOT_HEIGHT] is what makes the card 104 dp tall — she is its tallest child, and
 * `requiredSize` means her drawn height is not what the slot measures. [BUST_DRAW_HEIGHT]
 * is [BUST_WIDTH] over the asset's 1032x1103 aspect, so the overflow the card clips is
 * artwork rather than a stretch — 103 rather than the 140 the taller crop needed, and the
 * 4.6 dp of her below the card's edge is all this framing leaves over.
 */
private val BUST_WIDTH = 96.dp
private val BUST_SLOT_HEIGHT = 80.dp
private val BUST_DRAW_HEIGHT = 103.dp
private val BUST_LIFT = 6.dp

/** Cancels the card's own padding on the leading side, as the mockup's bust does. */
private val BUST_BLEED_X = 12.dp

/**
 * Where the meter stops. The mockup's own meter sits under a 48 dp circle whose edge is
 * 12 dp from the card's edge, so its bars land about 15 dp inside; alone in the slot it
 * keeps that column rather than sliding out to the padding.
 */
private val METER_END = 12.dp
