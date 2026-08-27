package dev.yukaribox.vpn.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.yukaribox.vpn.R
import dev.yukaribox.vpn.core.Logs
import dev.yukaribox.vpn.data.NodeRepository
import dev.yukaribox.vpn.data.Subscription
import dev.yukaribox.vpn.ui.kit.BarIconButton
import dev.yukaribox.vpn.ui.kit.motionEnabled
import dev.yukaribox.vpn.ui.theme.TabLabel
import dev.yukaribox.vpn.ui.theme.yukari

/**
 * The group strip: every group the user has, as a scrollable row of tabs, with the
 * screen's two actions pinned after them.
 *
 * This replaces the `SERVERS | MY GROUPS` pair, and the difference is not cosmetic. Under
 * that pair a group was a place you went to look at and came back from, and the list on
 * the other tab belonged to whichever group you had last opened — two taps and a screen
 * change to answer "what is in my other subscription?". Here the tabs **are** the groups:
 * one tap swaps which group's servers fill the list below, and nothing else moves.
 *
 * Only the tabs scroll, and they scroll inside a [STRIP_WINDOW]-wide viewport rather than
 * taking the whole row: `+` and the actions menu sit immediately after it, on the same
 * ground the single `+` held before there was a strip. Further right is the portrait, and
 * a glyph drawn over the tank print on her shirt is a glyph users hunt for. They are
 * outside the scroll area either way, because a control that scrolls off with the content
 * it acts on is a control users conclude is missing. `+` creates a group and does nothing
 * else; every other action — including adding a *server* — is behind the `⋮` beside it
 * ([ServersMenu]).
 *
 * Long-pressing a tab opens that group's own menu, the same gesture and the same menu the
 * cards on the groups screen use ([GroupActionsMenu]), so the two surfaces share one
 * vocabulary instead of growing two.
 *
 * Labels keep the author's own case. Everything else in the reference's tab row is
 * uppercased by its caller, but these are names a user typed or a feed supplied, and
 * `ALL CAPS` on user data reads as shouting in one locale and mangles case rules in
 * another.
 */
@Composable
internal fun GroupStrip(onAddNode: () -> Unit, modifier: Modifier = Modifier) {
    val groups = NodeRepository.subscriptions
    val activeId = NodeRepository.activeSubId
    val listState = rememberLazyListState()
    var createOpen by remember { mutableStateOf(false) }
    var editTarget by remember { mutableStateOf<Subscription?>(null) }
    var deleteTarget by remember { mutableStateOf<Subscription?>(null) }

    // Follow the selection, not the tap: a group created from `+`, chosen on the groups
    // screen, moved to by a node's "move to group", or restored at launch is as likely to
    // be off-screen as one the user tapped, and all of them arrive as a change of
    // `activeSubId`. Keyed on the count as well, so a delete re-centres what is left.
    //
    // The scroll keeps the platform's own physics rather than the app's tween — see
    // `YukariMotion` — and honours the motion setting by becoming a jump.
    val animateScroll = motionEnabled()
    LaunchedEffect(activeId, groups.size) {
        val index = groups.indexOfFirst { it.id == activeId }
        if (index < 0) return@LaunchedEffect
        if (animateScroll) listState.animateScrollToItem(index) else listState.scrollToItem(index)
    }

    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        LazyRow(
            state = listState,
            modifier = Modifier.width(STRIP_WINDOW),
            contentPadding = PaddingValues(start = STRIP_INSET),
        ) {
            items(groups, key = { it.id }) { sub ->
                GroupTab(
                    sub = sub,
                    selected = sub.id == activeId,
                    onSelect = {
                        Logs.tap("group:select")
                        NodeRepository.selectSubscription(sub.id)
                    },
                    onEdit = { editTarget = sub },
                    onDelete = { deleteTarget = sub },
                )
            }
        }
        Row {
            BarIconButton(
                icon = Icons.Default.Add,
                contentDescription = stringResource(R.string.cd_add_group),
                onClick = { Logs.tap("group:new"); createOpen = true },
            )
            ServersMenu(onAddNode = onAddNode)
        }
    }

    if (createOpen) GroupEditDialog(existing = null, onDismiss = { createOpen = false })
    editTarget?.let { sub -> GroupEditDialog(existing = sub, onDismiss = { editTarget = null }) }
    deleteTarget?.let { sub -> GroupDeleteDialog(sub = sub, onDismiss = { deleteTarget = null }) }
}

/**
 * One tab: the group's name, the 3 dp ink underline when it is the one on screen, and its
 * menu on a long press.
 *
 * The underline is drawn *inside* the tab's own box and aligned to its bottom rather than
 * as a sliding bar under the row, which is what lets the row scroll: a shared indicator
 * has to know every tab's offset, and in a lazily composed row the tabs off-screen have no
 * offset to know.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GroupTab(
    sub: Subscription,
    selected: Boolean,
    onSelect: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    // A group name is a name, and a feed's naming convention is a flag emoji first — the
    // one full-colour thing a label can inject into a monochrome interface. Remembered
    // because a subscription's name has no length bound and this row recomposes with the
    // list below it.
    val label = remember(sub.name) { groupLabel(sub.name) }
    val ink = MaterialTheme.colorScheme.onSurface
    val indicatorInset = with(LocalDensity.current) { INDICATOR_INSET.toPx() }
    val indicatorHeight = with(LocalDensity.current) { INDICATOR.toPx() }
    val indicatorRadius = with(LocalDensity.current) { INDICATOR_RADIUS.toPx() }
    Box {
        Box(
            Modifier
                .combinedClickable(
                    role = Role.Tab,
                    onLongClickLabel = stringResource(R.string.cd_group_actions),
                    onLongClick = { menuOpen = true },
                    onClick = onSelect,
                )
                // Drawn rather than laid out, because a lazy row measures its items with
                // an *unbounded* width: a `fillMaxWidth` underline inside one has nothing
                // to fill and silently collapses to zero, which is exactly how this shipped
                // its first build. `drawBehind` gets the cell's final size instead.
                .drawBehind {
                    if (!selected) return@drawBehind
                    drawRoundRect(
                        color = ink,
                        topLeft = Offset(indicatorInset, size.height - indicatorHeight),
                        size = Size(size.width - 2 * indicatorInset, indicatorHeight),
                        cornerRadius = CornerRadius(indicatorRadius),
                    )
                },
        ) {
            Text(
                label,
                style = TabLabel,
                color = if (selected) ink else MaterialTheme.yukari.textTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .widthIn(max = TAB_LABEL_MAX)
                    .padding(horizontal = TAB_PADDING_H, vertical = TAB_PADDING_V),
            )
        }
        GroupActionsMenu(
            sub = sub,
            expanded = menuOpen,
            onDismiss = { menuOpen = false },
            onEdit = onEdit,
            onDelete = onDelete,
        )
    }
}

/**
 * What puts the strip on the screen's 24 dp content column, and the same 14 dp the tab
 * row it replaces used: a label is padded [TAB_PADDING_H] and the underline sits
 * [INDICATOR_INSET] inside its cell, so 14 + 10 lands the underline under the hamburger
 * above it and 14 + 16 the label's ink 7 dp to its right, as the mockup has them.
 */
private val STRIP_INSET = 14.dp
private val TAB_PADDING_H = 16.dp
private val TAB_PADDING_V = 15.dp
private val INDICATOR = 3.dp
private val INDICATOR_INSET = 10.dp
private val INDICATOR_RADIUS = 2.dp

/**
 * How wide one tab's label may get. A subscription names itself and some feeds name
 * themselves at length; without a ceiling one group can be wider than the screen, and
 * scrolling past it to reach the others is worse than an ellipsis.
 */
private val TAB_LABEL_MAX = 160.dp

/**
 * How much of the row the tabs get. 186 dp puts the `+` at 200 dp and the `⋮` at 248 —
 * i.e. where the old `SERVERS | MY GROUPS | +` row left its plus, and clear of the
 * portrait's shirt print, which starts at 315 dp. One group name at a time is the deal:
 * the strip scrolls, and the alternative is a control nobody can see.
 */
private val STRIP_WINDOW = 186.dp
