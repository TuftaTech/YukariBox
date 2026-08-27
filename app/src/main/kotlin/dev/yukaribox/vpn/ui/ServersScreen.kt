package dev.yukaribox.vpn.ui

import android.content.Context
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import dev.yukaribox.vpn.R
import dev.yukaribox.vpn.core.LatencyTier
import dev.yukaribox.vpn.core.Logs
import dev.yukaribox.vpn.core.NodeGeo
import dev.yukaribox.vpn.core.TunnelController
import dev.yukaribox.vpn.core.TunnelState
import dev.yukaribox.vpn.core.UrlTestEngine
import dev.yukaribox.vpn.data.NodeEntry
import dev.yukaribox.vpn.data.NodeRepository
import dev.yukaribox.vpn.data.StatusMessage
import dev.yukaribox.vpn.proxy.ProxyNode
import dev.yukaribox.vpn.ui.kit.AlertBadge
import dev.yukaribox.vpn.ui.kit.BrandTopBar
import dev.yukaribox.vpn.ui.kit.CircleButton
import dev.yukaribox.vpn.ui.kit.EmptyState
import dev.yukaribox.vpn.ui.kit.FlagPlate
import dev.yukaribox.vpn.ui.kit.GAP
import dev.yukaribox.vpn.ui.kit.LineProgress
import dev.yukaribox.vpn.ui.kit.ListMargin
import dev.yukaribox.vpn.ui.kit.Notice
import dev.yukaribox.vpn.ui.kit.PaperCard
import dev.yukaribox.vpn.ui.kit.PingBadge
import dev.yukaribox.vpn.ui.kit.QuietIconButton
import dev.yukaribox.vpn.ui.kit.ScreenMargin
import dev.yukaribox.vpn.ui.kit.SearchField
import dev.yukaribox.vpn.ui.kit.YukariLean
import dev.yukaribox.vpn.ui.kit.swapSpec
import dev.yukaribox.vpn.ui.theme.LabelWide
import dev.yukaribox.vpn.ui.theme.ListCardShape
import dev.yukaribox.vpn.ui.theme.MeterShape
import dev.yukaribox.vpn.ui.theme.YukariIcons
import dev.yukaribox.vpn.ui.theme.yukari
import dev.yukaribox.vpn.vpn.TunnelLauncher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * The server library: the group strip over one list, an actions menu, and the tunnel
 * toggle.
 *
 * This is the screen the drawer opens, so it wears the hamburger rather than a back
 * arrow even though it is pushed — it is a place you go to, not a detail of Home.
 *
 * A card per row rather than hairline-separated rows inside one card. That costs a
 * hairline and a 1 dp shadow per server — which is what the reference's own shadow band
 * between consecutive rows is — and it buys the thing the mockup is built on: each
 * server is an object you can point at, with its own selected state marked by an ink bar
 * down its leading edge.
 */
@Composable
fun ServersScreen(
    onOpenDrawer: () -> Unit,
    onEditNode: (Int?) -> Unit,
    onToggleConnection: () -> Unit,
) {
    // Saveable, not merely remembered: `ScreenHost` now keeps each destination's state
    // across a tab switch, and a restored scroll position on an unfiltered list is an
    // arbitrary position. The query and the viewport have to come back together or neither
    // should. Still keyed on the group, so switching groups clears it.
    var search by rememberSaveable(NodeRepository.activeSubId) { mutableStateOf("") }
    val view = statusView(
        TunnelController.state,
        TunnelController.failClosed,
        TunnelController.unprotected,
    )
    Column(Modifier.fillMaxSize()) {
        // Above the block below it, which is the whole point of the portrait's overflow:
        // siblings paint in order, so without this her legs would be *behind* the search
        // field rather than in front of it. The header's own bounds still end at the tab
        // underline and its background is opaque, so nothing else changes stacking.
        ServersHeader(
            onOpenDrawer = onOpenDrawer,
            onEditNode = onEditNode,
            modifier = Modifier.zIndex(1f),
        )
        Box(Modifier.weight(1f)) {
            ServersTab(
                search = search,
                onSearch = { search = it },
                onEditNode = onEditNode,
            )
            CircleButton(
                // Glyph, never a colour: the two fail-closed views take `statusGlyph`,
                // so a held kill switch draws a plain shield and a failed one a struck
                // shield. Off is the struck navigation arrow and a live session the
                // plain one — four readings from three glyphs, none of them a hue. A
                // screen reader gets the same four apart through
                // `connectDescriptionRes`, which names the two fail-closed cases
                // separately.
                icon = when {
                    view.filled -> YukariIcons.Nav
                    view == StatusView.Blocked || view == StatusView.Unprotected -> statusGlyph(view)
                    else -> YukariIcons.NavOff
                },
                contentDescription = stringResource(connectDescriptionRes(view)),
                onClick = onToggleConnection,
                // Cancellable while connecting, disabled only while tearing down — the same
                // asymmetry as the power circle on Home, for the same reasons.
                enabled = view != StatusView.Disconnecting,
                busy = view.isTransitioning,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = FAB_END, bottom = FAB_BOTTOM),
            )
        }
    }
}

/**
 * Wordmark bar, tab row, and Yukari in the corner, over the one band of grey the
 * reference has anywhere: `pageBand` from the status bar down to the tab underline,
 * against the white list below it.
 *
 * The portrait is a sibling of the bar rather than its trailing slot, because in the
 * mockup it spans both rows — it starts beside the wordmark and is cut off by the tab
 * row's underline. `matchParentSize` keeps it out of the header's own measurement, so
 * the band stays exactly as tall as the bar plus the gap plus the tabs.
 *
 * She is painted **last, and over everything** — owner decision, 2026-08-26, reversing the
 * layering this header shipped with. She used to be drawn first, under the column, and cut
 * off by the band's own `clipToBounds`; the brief now is that she stands in front of the
 * screen and reaches down onto the search field. So the clip is gone, she is the box's last
 * child, and `ServersScreen` gives this header a `zIndex` so her overflow lands in front of
 * the search row instead of behind it.
 *
 * What that costs, all `measured` on the device at [LEAN_WIDTH] flush right: her ink starts
 * at x 267 dp on the strip's row, so it covers the last **2 dp** of the `⋮` glyph (247–269)
 * and 15 dp of that button's empty padding — the glyph still reads and the whole 48 dp
 * target still takes taps, because she carries no pointer input and events fall through her.
 * On the search row she covers the trailing **125 dp of the field's 363**, i.e. 34% of it,
 * from x 264. The field's text begins at 54 dp, so what is behind her is trailing space
 * until a query grows long enough to reach it.
 *
 * The alternative was keeping her clear of both, and it is recorded here because it is the
 * thing to reach for if the overlap ever reads badly: flush right *and* clear of the `⋮`
 * caps her at 121 dp wide, a fifth smaller than this, floating 20 dp above the underline —
 * or the strip's window shrinks so its two controls move left instead (§7.2 of the design
 * system fixes that window at 186 dp, so that is a change to make there, not here).
 *
 * There is deliberately no world map behind this band: it measures flat in the
 * reference, and the map belongs to Home.
 */
@Composable
private fun ServersHeader(
    onOpenDrawer: () -> Unit,
    onEditNode: (Int?) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .fillMaxWidth()
            .background(MaterialTheme.yukari.pageBand),
    ) {
        Column {
            BrandTopBar(
                title = stringResource(R.string.app_name),
                onMenu = onOpenDrawer,
                menuContentDescription = stringResource(R.string.cd_menu),
                centered = false,
            )
            Spacer(Modifier.height(HEADER_GAP))
            GroupStrip(onAddNode = { onEditNode(null) })
        }
        // Inset here as well as inside the bar: the overlay spans the whole header box,
        // whose top edge is the screen's, so without it the portrait starts under the
        // clock and loses the top of her hair to the status bar. It is what makes
        // [LEAN_DROP] a drop *below the clock* rather than an absolute y.
        Box(
            Modifier.matchParentSize().statusBarsPadding(),
            contentAlignment = Alignment.TopEnd,
        ) {
            // Required, not preferred: she is taller than the band by design, and now
            // nothing cuts her at all — the header's clip is gone. Sized to *fit* she
            // would shrink to the band's height and letterbox inside it.
            //
            // And the overflow has to hang off the *bottom*, which is what
            // `wrapContentHeight(Top, unbounded)` is for — the same pair the drawer's
            // bust uses, for the same reason. `matchParentSize` fixes this Box to the
            // header's height, so a 182 dp child inside a ~167 dp box is over-sized and
            // gets **centred**: measured on the device, her node landed 187 px above the
            // box, i.e. 63 px above the screen's top edge, which is exactly the 124 px of
            // status-bar padding cancelled and then some. The padding was doing its job
            // all along (the box measured at y=124); what dropped it was the centring of
            // an over-sized child, so the fix is to anchor her top rather than to inset
            // anything by hand.
            YukariLean(
                Modifier
                    .wrapContentHeight(Alignment.Top, unbounded = true)
                    .requiredSize(width = LEAN_WIDTH, height = LEAN_HEIGHT)
                    .offset(y = LEAN_DROP),
            )
        }
    }
}

/**
 * The `SERVERS` tab: an optional search field, the batch-test progress, and the list.
 *
 * Three empty states rather than one. "No groups yet", "this group is empty" and "your
 * search matched nothing" need different instructions, and the third must not look like
 * the second — a user who filters everything out and is told to add a subscription will
 * conclude their servers are gone.
 */
@Composable
private fun ServersTab(
    search: String,
    onSearch: (String) -> Unit,
    onEditNode: (Int?) -> Unit,
) {
    val context = LocalContext.current
    val list = rememberServerList(search)
    var moveNode by remember { mutableStateOf<NodeEntry?>(null) }
    var deleteNode by remember { mutableStateOf<NodeEntry?>(null) }
    var qrNode by remember { mutableStateOf<NodeEntry?>(null) }
    // Hoisted above the `when` below, not left to the LazyColumn. A composable's
    // rememberSaveable does not survive it leaving the tree, so with the state owned by
    // the list a single frame in any of the three empty branches — a search that matches
    // nothing, or a refill observed mid-flight — threw the scroll position away.
    val listState = rememberLazyListState()
    ServersScrollPin(listState, list.visible.size)

    Column(Modifier.fillMaxSize()) {
        // Only once the list is long enough to need it: at eight servers the field is
        // clutter, at eighty it is the only way to find one.
        if (list.total > SEARCH_THRESHOLD) {
            SearchField(
                value = search,
                onValueChange = onSearch,
                placeholder = stringResource(R.string.search_label),
                leadingIcon = Icons.Default.Search,
                modifier = Modifier.padding(horizontal = ScreenMargin, vertical = 6.dp),
                trailing = {
                    if (search.isNotEmpty()) {
                        QuietIconButton(
                            icon = Icons.Default.Close,
                            contentDescription = stringResource(R.string.action_close),
                            onClick = { onSearch("") },
                        )
                    }
                },
            )
        }
        TestProgress()
        StatusNotice()
        when {
            !NodeRepository.hasGroups -> EmptyState(
                title = stringResource(R.string.servers_no_group_title),
                body = stringResource(R.string.servers_no_group_hint),
                icon = YukariIcons.Folder,
            )
            list.total == 0 -> EmptyState(
                title = stringResource(R.string.servers_empty_title),
                body = stringResource(R.string.servers_empty_hint),
                icon = YukariIcons.Globe,
            )
            list.visible.isEmpty() -> EmptyState(
                title = stringResource(R.string.search_empty_title),
                body = stringResource(R.string.search_empty_hint),
                icon = Icons.Default.Search,
            )
            else -> {
                // Hoisted out of the `items` scope, all three of them.
                //
                // `swapSpec()` is a composable that reads `SettingsStore.animations`, so
                // called inside the item lambda it both allocated three `tween`s per row per
                // composition and subscribed every visible row's scope to the animation
                // setting. The group id was read inside the `key` lambda, which `LazyColumn`
                // invokes from its *measure* pass for each visible item — a snapshot read and
                // a string allocation per row per frame of a fling. Read here it is a captured
                // value.
                val groupKey = NodeRepository.activeSubId
                val fadeSpec = swapSpec<Float>()
                val placeSpec = swapSpec<IntOffset>()
                LazyColumn(
                    Modifier.fillMaxSize(),
                    state = listState,
                    contentPadding = PaddingValues(
                        start = ListMargin,
                        end = ListMargin,
                        top = 4.dp,
                        bottom = LIST_BOTTOM,
                    ),
                    verticalArrangement = Arrangement.spacedBy(GAP),
                ) {
                    // Node ids restart at 0 per group, so the key includes the group —
                    // otherwise switching groups makes animateItem() morph unrelated rows.
                    items(list.visible, key = { "$groupKey:${it.id}" }) { entry ->
                        ServerCard(
                            entry = entry,
                            selected = entry.id == NodeRepository.selectedId &&
                                NodeRepository.activeSubId == NodeRepository.selectedSubId,
                            modifier = Modifier.animateItem(
                                fadeInSpec = fadeSpec,
                                placementSpec = placeSpec,
                                fadeOutSpec = fadeSpec,
                            ),
                            onSelect = { selectNode(context, entry) },
                            onEdit = { onEditNode(entry.id) },
                            onMove = { moveNode = entry },
                            onDelete = { deleteNode = entry },
                            onShowQr = { qrNode = entry },
                        )
                    }
                }
            }
        }
    }

    moveNode?.let { entry -> MoveNodeDialog(entry, onDismiss = { moveNode = null }) }
    qrNode?.let { entry -> NodeQrDialog(entry, onDismiss = { qrNode = null }) }
    deleteNode?.let { entry ->
        DeleteNodeDialog(
            entry = entry,
            onConfirm = { NodeRepository.deleteNode(entry.id); deleteNode = null },
            onDismiss = { deleteNode = null },
        )
    }
}

/**
 * What the list is showing: how many servers the group holds, and the ones the query left.
 *
 * One value rather than two states, so the count and the rows can never disagree — the
 * screen picks between three empty states from them and "0 of 0" and "0 of 800" need
 * different sentences.
 */
@Immutable
private class ServerList(val total: Int, val visible: List<NodeEntry>) {
    companion object {
        fun of(nodes: List<NodeEntry>, search: String) = ServerList(nodes.size, filterNodes(nodes, search))
    }
}

/**
 * The visible list, computed off the composition and off the main thread.
 *
 * This is the screen's whole answer to a large subscription, and it replaces reading
 * `NodeRepository.nodes` here directly. A `SnapshotStateList` has one state record for the
 * whole list, so `setLatency` writing a single element invalidates **every** reader of it —
 * and the reader was this composable, the screen's root. During a sweep, results landing
 * from six probe threads therefore recomposed the root on every frame, and each
 * recomposition copied the list and re-ran the search filter: at ten thousand nodes, a
 * ten-thousand-element copy plus twenty thousand case-insensitive substring scans, per
 * frame, for the length of the sweep. That is the "micro-freeze when a ping lands".
 *
 * Now the list is read by a [snapshotFlow] on [Dispatchers.Default]: the copy and the
 * filter happen there, and what reaches composition is one immutable [ServerList]. The
 * recomposition it triggers does no work proportional to the node count — `LazyColumn`
 * re-runs its `items` scope and re-measures the viewport, and rows whose `NodeEntry`
 * instance did not change skip outright, which is per-row granularity in all but name.
 *
 * Three details are load-bearing:
 *
 * - **The first value is computed synchronously**, inside `Snapshot.withoutReadObservation`
 *   so taking it does not subscribe this scope to the very list it is trying not to observe.
 *   Seeded from the flow instead, the screen's first frame would show "this group is empty"
 *   and then correct itself.
 * - **`conflate()` plus a one-frame [PUBLISH_INTERVAL_MS] pause** in the collector. Without
 *   it the filter would re-run as fast as a core can go for the whole sweep; with it the
 *   list is republished at most once a frame, and `conflate` guarantees the *last* state
 *   still arrives rather than being dropped as stale.
 * - **The effect is keyed on [search] only.** A query change restarts the flow, whose first
 *   emission is the current list, so the new filter lands within a frame or two — while the
 *   node list itself is followed continuously by the flow rather than by a key.
 */
@Composable
private fun rememberServerList(search: String): ServerList {
    val published = remember {
        mutableStateOf(Snapshot.withoutReadObservation { ServerList.of(NodeRepository.nodes.toList(), search) })
    }
    LaunchedEffect(search) {
        withContext(Dispatchers.Default) {
            snapshotFlow { NodeRepository.nodes.toList() }
                .conflate()
                .collect { nodes ->
                    published.value = ServerList.of(nodes, search)
                    delay(PUBLISH_INTERVAL_MS)
                }
        }
    }
    return published.value
}

/**
 * Keep the viewport still across a re-sort.
 *
 * `LazyListState` anchors on the **key** of the first visible row and re-resolves that
 * key's new index whenever the item set changes, so a row that moves takes the screen
 * with it. That is the right behaviour for rows inserted *above* the anchor, and the
 * wrong one here: with latency order chosen, one dead server's new number sent its row
 * to the bottom of the list and the list rode down after it — from the user's side, the
 * screen jumped to the end because they asked whether one server was alive.
 *
 * So re-anchor by index instead. The position is read during the composition that
 * [NodeRepository.orderRevision] triggers, which is before the list re-measures — that is
 * why `firstVisibleItem*` still describe the order the user was looking at rather than
 * the one being applied. Read without observation, so this does not resubscribe on every
 * scrolled pixel; the effect then puts the viewport back. A re-sort may still show one
 * frame at the followed position before the restore lands, which is why the sorts that
 * survive are the ones the user asked for (the menu) or was already watching (the end of
 * a batch sweep) rather than a single tap on a ping badge.
 *
 * [itemCount] clamps the restore: a sweep can be followed by a delete, and a stale index
 * past the end would throw the viewport to the bottom — the very thing this prevents.
 */
@Composable
private fun ServersScrollPin(listState: LazyListState, itemCount: Int) {
    val revision = NodeRepository.orderRevision
    val pinned = remember(revision) {
        Snapshot.withoutReadObservation {
            listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
        }
    }
    LaunchedEffect(revision) {
        // 0 is "nothing has been re-ordered yet": restoring there would fight a scroll
        // the user is in the middle of on the screen's first composition.
        if (revision == 0) return@LaunchedEffect
        listState.scrollToItem(
            pinned.first.coerceIn(0, (itemCount - 1).coerceAtLeast(0)),
            pinned.second,
        )
    }
}

/**
 * One server.
 *
 * Four slots, left to right: the country plate, the name over the endpoint, the ping,
 * and the star. Selection is the ink bar down the leading edge — drawn over the card
 * rather than inside its padding, so it reads as a marker on the card instead of as a
 * fifth column of content.
 *
 * The row's second and third actions are deliberately quiet: tapping the ping re-probes
 * this server alone (the common "is *this* one alive?" question, which used to need the
 * whole batch), and a long press opens everything else — edit, clone, move, copy link,
 * show QR, delete. The mockup has no overflow button and adding one would put a sixth
 * target in a row that already has three.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ServerCard(
    entry: NodeEntry,
    selected: Boolean,
    onSelect: () -> Unit,
    onEdit: () -> Unit,
    onMove: () -> Unit,
    onDelete: () -> Unit,
    onShowQr: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuOpen by remember { mutableStateOf(false) }
    // Whether this row has ever had its menu open. The menu is composed only from that point
    // on: `DropdownMenu` collapsed is not free — it remembers a transition state and reads
    // three composition locals — and it was being composed for every row on a list where a
    // long press is a rare gesture. Latched rather than gated on [menuOpen] itself so the
    // dismiss animation still has something to run on.
    var menuUsed by remember { mutableStateOf(false) }
    val tier = LatencyTier.of(entry.latencyMs)
    val labels = rowLabels(entry.node)
    Box(modifier.fillMaxWidth()) {
        PaperCard(shape = ListCardShape, contentPadding = PaddingValues(0.dp)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = ROW_HEIGHT)
                    .combinedClickable(onClick = onSelect, onLongClick = { menuUsed = true; menuOpen = true })
                    .padding(start = ROW_START, end = ROW_END),
                horizontalArrangement = Arrangement.spacedBy(SLOT_GAP),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FlagPlate(
                    code = labels.country ?: protocolPlate(entry.node.type),
                    country = labels.country != null,
                )
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            labels.label,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        // The plaintext warning wins the width fight with the name: a
                        // truncated "NO TL…" is worse than a truncated server label. An
                        // inverted plate rather than a colour — it sits in a column
                        // beside the ping plate, and two filled plates on one row would
                        // read as one alarm.
                        if (entry.node.isPlaintext) {
                            AlertBadge(text = stringResource(R.string.badge_no_tls))
                        }
                    }
                    Text(
                        labels.host,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                PingBadge(
                    tier = tier,
                    text = pingLabel(entry.latencyMs, tier),
                    onClick = {
                        Logs.tap("ping:${entry.node.displayName}")
                        UrlTestEngine.testSingle(entry.id, entry.node)
                    }.takeIf { tier != LatencyTier.Testing },
                )
                StarToggle(entry)
            }
            if (menuUsed) {
                ServerCardMenu(
                    entry = entry,
                    expanded = menuOpen,
                    onDismiss = { menuOpen = false },
                    onEdit = onEdit,
                    onMove = onMove,
                    onDelete = onDelete,
                    onShowQr = onShowQr,
                )
            }
        }
        if (selected) {
            Box(
                Modifier
                    .align(Alignment.CenterStart)
                    .width(SELECT_BAR)
                    .height(SELECT_BAR_HEIGHT)
                    .clip(MeterShape)
                    .background(MaterialTheme.yukari.ink),
            )
        }
    }
}

/**
 * The three strings a row draws that are a pure function of its node: the country the plate
 * shows, the name with any flag emoji taken out, and the endpoint with the same treatment.
 *
 * Held together so one lookup answers all three, and memoised process-wide by
 * [RowLabels.cache] rather than by a per-row `remember`. A `remember` is the right scope for
 * a row that stays on screen and the wrong one for a fling: the row's composition is
 * discarded as it leaves the viewport and rebuilt when it comes back, and `NodeGeo.codeFor`
 * is not cheap — it lower-cases the name, rebuilds it through a `StringBuilder`, and then
 * allocates a `" $alias "` probe string for each of thirty-three multi-word aliases. Paid
 * once per row per pass, over a group of a couple of thousand, that is tens of thousands of
 * short-lived strings for answers that never change.
 *
 * The endpoint is stripped as well as the name. A hostname cannot legitimately contain a
 * regional-indicator pair — an internationalised host arrives as punycode — but the string
 * comes from a subscription feed by way of a parser that only rejects `/`, quotes and
 * whitespace in the authority, so `🇯🇵.example.com` would reach the row and paint a colour
 * flag. `plainName` returns its input untouched when there is no flag in it, so every real
 * host is byte-for-byte what the node dials.
 */
@Immutable
private class RowLabels(val country: String?, val label: String, val host: String) {

    companion object {

        /**
         * Keyed on the two strings the answers are derived from, not on the node's identity:
         * a subscription refresh replaces every `ProxyNode` instance with an equal one, and an
         * identity map would hold the old ones forever.
         *
         * Cleared wholesale on overflow rather than evicted one by one. Entries are three
         * short strings, the cap covers several large subscriptions at once, and the cost of
         * being wrong is recomputing a label — so an LRU here would be more machinery than the
         * thing it protects.
         */
        private val cache = ConcurrentHashMap<String, RowLabels>()

        private const val MAX_ENTRIES = 4096

        fun of(node: ProxyNode): RowLabels {
            val key = node.displayName + ' ' + node.server
            cache[key]?.let { return it }
            val computed = RowLabels(
                country = NodeGeo.codeFor("${node.displayName} ${node.server}"),
                label = NodeGeo.plainName(node.displayName),
                host = NodeGeo.plainName(node.server),
            )
            if (cache.size >= MAX_ENTRIES) cache.clear()
            return cache.putIfAbsent(key, computed) ?: computed
        }
    }
}

/** [RowLabels.of], from composition. */
@Composable
private fun rowLabels(node: ProxyNode): RowLabels = remember(node) { RowLabels.of(node) }

/** The favourite star. Starred servers float to the top of every sort mode. */
@Composable
private fun StarToggle(entry: NodeEntry) {
    QuietIconButton(
        icon = if (entry.favorite) YukariIcons.StarFilled else YukariIcons.StarOutline,
        contentDescription = stringResource(
            if (entry.favorite) R.string.cd_unstar else R.string.cd_star,
        ),
        onClick = { Logs.tap("star:${entry.node.displayName}"); NodeRepository.toggleFavorite(entry.id) },
        tint = if (entry.favorite) {
            MaterialTheme.colorScheme.onSurface
        } else {
            MaterialTheme.colorScheme.outline
        },
    )
}

/**
 * The batch latency test, while it runs.
 *
 * A live counter with a Stop beside it rather than a disabled button: a six-thread pool
 * over two hundred servers takes long enough that a control which merely looked inert
 * read as a hang.
 */
@Composable
private fun TestProgress() {
    if (!UrlTestEngine.running) return
    Column(
        Modifier.padding(horizontal = ScreenMargin, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(
                    R.string.testing_progress,
                    UrlTestEngine.testedCount,
                    UrlTestEngine.totalCount,
                ),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Text(
                stringResource(R.string.action_stop).uppercase(),
                style = LabelWide,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .clip(MeterShape)
                    .clickable { UrlTestEngine.cancel() }
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            )
        }
        LineProgress(
            fraction = UrlTestEngine.totalCount
                .takeIf { it > 0 }
                ?.let { UrlTestEngine.testedCount.toFloat() / it },
        )
    }
}

/**
 * Import counts and errors from the repository, dismissible.
 *
 * The store hands over a resource id, not prose — it reports from executors with no
 * `Context` — so resolving happens here, in the current locale: a plain sentence through
 * `stringResource`, a count through `pluralStringResource` so Russian gets its own
 * four-form rules rather than English's two.
 */
@Composable
private fun StatusNotice() {
    val message = NodeRepository.status ?: return
    // Spread suppressed rather than baselined: a vararg is the only way to hand a
    // variable number of format arguments to stringResource, and the array being
    // copied here holds at most two.
    @Suppress("SpreadOperator")
    val text = when (message) {
        is StatusMessage.Text -> stringResource(message.res, *message.args.toTypedArray())
        is StatusMessage.Count -> pluralStringResource(message.res, message.quantity, message.quantity)
    }
    Box(Modifier.padding(horizontal = ScreenMargin, vertical = 4.dp)) {
        Notice(
            text = text,
            actionLabel = stringResource(R.string.action_close),
            onAction = { NodeRepository.setTestStatus(null) },
        )
    }
}

/** Search over the label and the endpoint — the two things a user remembers. */
private fun filterNodes(nodes: List<NodeEntry>, search: String): List<NodeEntry> {
    if (search.isBlank()) return nodes
    return nodes.filter { entry ->
        entry.node.displayName.contains(search, ignoreCase = true) ||
            entry.node.server.contains(search, ignoreCase = true)
    }
}

/** `PING 35`, or what happened instead of a number. */
@Composable
private fun pingLabel(latencyMs: Int, tier: LatencyTier): String = when (tier) {
    LatencyTier.Untested -> stringResource(R.string.ping_untested)
    LatencyTier.Testing -> stringResource(R.string.ping_testing)
    LatencyTier.Failed -> stringResource(R.string.lat_timeout)
    else -> stringResource(R.string.ping_value, latencyMs)
}

/**
 * Select a server, and move a live tunnel onto it.
 *
 * Tapping a different server while connected moves the session rather than making the
 * user disconnect first — but only when the tunnel is actually on a *different* one, or
 * a tap on the already-active row would tear down a working session and rebuild it for
 * nothing.
 */
private fun selectNode(context: Context, entry: NodeEntry) {
    Logs.tap("node:${entry.node.displayName}")
    NodeRepository.select(entry.id)
    if (shouldHotSwitch(entry)) {
        Logs.tap("node-switch:${entry.node.displayName}")
        TunnelLauncher.reconnect(context)
    }
}

private fun shouldHotSwitch(entry: NodeEntry): Boolean {
    if (TunnelController.state != TunnelState.Connected) return false
    val profile = TunnelController.connectedProfile ?: return false
    return profile.nodeId != entry.id || profile.subId != NodeRepository.activeSubId
}

/**
 * The portrait in the header band. `yukari_lean` is 457x546 px at xxhdpi, so 152 x 182 dp
 * is her own aspect and the pair is exact at mdpi, where the generator emits 152x182 —
 * a box of the wrong ratio letterboxes a `ContentScale.Fit` drawing.
 *
 * **Flush right, nothing cut.** The 24 dp of bleed this used to carry was what kept her ink
 * off the group strip's `⋮`; with the layering reversed (see [ServersHeader]) the owner's
 * brief is the opposite — as far left as the edge allows, whole, and in front. Her drawn box
 * is her ink box, so flush right means her arm ends exactly on the screen's edge and no
 * bleed is needed to make that true.
 *
 * [LEAN_DROP] is measured off the status-bar inset, not off the screen: 14 dp below it puts
 * her hair at y 55 and her thighs at 237, which is 24 dp into the search field's 48 dp row.
 * Half over it reads as standing in front of it; flush with its top edge read as an accident.
 */
private val LEAN_WIDTH = 152.dp
private val LEAN_HEIGHT = 182.dp
private val LEAN_DROP = 14.dp

/**
 * Bar to tab row. The reference's band runs 208 dp from the panel's top edge down to the
 * tab underline and spends ~52 of them on this stretch — empty on the left, the portrait
 * on the right. 57 rather than 52 because our status-bar inset is 41 dp against the
 * mockup's ~30: 41 + 56 (bar) + 57 + 54 (tabs) measures 208.0 dp on the dev panel.
 *
 * A fixed gap rather than a 208 dp total, so a device with a taller status bar grows the
 * band instead of squeezing the bar and the tabs together inside it.
 */
private val HEADER_GAP = 57.dp


/**
 * The row's four slots. `ROW_END` is the one number the plan and its own measurements
 * disagree on: it names 8 dp, but the star's glyph centre is measured 39 dp inside the
 * card's trailing edge and the star is a 48 dp target, which puts the padding at 15 —
 * and lands the ping plate's right edge at the measured 83 dp. 8 would leave the star
 * centre at 32.
 */
private val ROW_HEIGHT = 66.dp
private val ROW_START = 26.dp
private val ROW_END = 15.dp
private val SLOT_GAP = 21.dp

/** Above this many servers in a group, the search field appears. */
private const val SEARCH_THRESHOLD = 8

/**
 * Shortest gap between two republications of the list — see [rememberServerList].
 *
 * One frame at 60 Hz. A batch sweep produces results faster than that from six threads at
 * once, and a list of numbers has no reason to be redrawn more often than the panel is; the
 * scroll is not affected either way, because scrolling does not republish anything.
 */
private const val PUBLISH_INTERVAL_MS = 16L

/** The selected server's leading marker: 3 dp of ink, inset ~6 dp of a 66 dp card. */
private val SELECT_BAR = 3.dp
private val SELECT_BAR_HEIGHT = 54.dp

/** The toggle's own inset from the screen's trailing and bottom edges. */
private val FAB_END = 18.dp
private val FAB_BOTTOM = 24.dp

/** Room under the last card so the floating toggle never covers it. */
private val LIST_BOTTOM = 92.dp
