package dev.yukaribox.vpn.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.yukaribox.vpn.R
import dev.yukaribox.vpn.core.Logs
import dev.yukaribox.vpn.core.QrTools
import dev.yukaribox.vpn.core.SettingsStore
import dev.yukaribox.vpn.core.SortMode
import dev.yukaribox.vpn.core.UrlTestEngine
import dev.yukaribox.vpn.data.NodeEntry
import dev.yukaribox.vpn.data.NodeRepository
import dev.yukaribox.vpn.data.StatusMessage
import dev.yukaribox.vpn.proxy.ProxyLinkExporter
import dev.yukaribox.vpn.proxy.SubscriptionDecoder
import dev.yukaribox.vpn.ui.kit.BarIconButton
import dev.yukaribox.vpn.ui.kit.ConfirmDialog
import dev.yukaribox.vpn.ui.theme.LabelWide
import dev.yukaribox.vpn.ui.theme.YukariIcons
import dev.yukaribox.vpn.ui.theme.yukari

/**
 * The `⋮` beside the group strip, and everything behind it.
 *
 * Every screen-level operation except one lives here: adding a server four different ways,
 * ordering the list, and the four destructive group operations. The exception is creating a
 * group, which is the `+` next door — it is the strip's own verb, and a user who has just
 * scrolled a row of groups looking for a place to add one should not have to open a menu to
 * find it. Sorting is in here rather than as a visible control because the list already
 * shows the result — favourites on top, then whatever order was chosen — and a chip row
 * spelling out the current mode would be the fourth element competing for the header.
 *
 * Lives in its own file: `ServersScreen.kt` is at detekt's per-file function budget, and
 * a menu that grows is exactly the thing that pushes a screen over it.
 */
@Composable
internal fun ServersMenu(onAddNode: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<BulkAction?>(null) }
    var editGroup by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val hasGroup = NodeRepository.activeSubId != null
    val hasNodes = NodeRepository.nodes.isNotEmpty()
    val group = NodeRepository.activeSubscription()
    val qrPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            val text = QrTools.decodeFromUri(context, uri)
            when {
                text == null -> NodeRepository.setTestStatus(StatusMessage.Text(R.string.msg_no_qr))
                else -> importLinks(text, R.string.msg_qr_no_links)
            }
        }
    }

    Box {
        BarIconButton(
            icon = Icons.Default.MoreVert,
            contentDescription = stringResource(R.string.cd_more),
            onClick = { open = true },
        )
        // Capped, and the cap is what fixes where it appears. At its natural height this
        // menu is taller than the room under the `⋮` — thirteen rows, three captions and two
        // dividers — so M3 could not place it below the anchor and shifted it to the top of
        // the screen instead, which read as a menu flying in from the top-left corner rather
        // than opening from the button. Capped it fits under the glyph, opens downward and to
        // the left of it, and scrolls the last row or two into view.
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            modifier = Modifier.heightIn(max = MENU_MAX_HEIGHT),
        ) {
            MenuCaption(stringResource(R.string.menu_section_add))
            MenuRow(stringResource(R.string.menu_new_node), Icons.Default.Add) {
                open = false
                Logs.tap("menu:add-node")
                onAddNode()
            }
            MenuRow(stringResource(R.string.menu_import_clipboard), YukariIcons.Copy) {
                open = false
                Logs.tap("menu:import-clipboard")
                val text = pasteText(context)
                if (text.isBlank()) {
                    NodeRepository.setTestStatus(StatusMessage.Text(R.string.msg_clipboard_empty))
                } else {
                    importLinks(text, R.string.msg_no_links_clipboard)
                }
            }
            MenuRow(stringResource(R.string.menu_import_qr), YukariIcons.Qr) {
                open = false
                Logs.tap("menu:import-qr")
                qrPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }
            HorizontalDivider()
            MenuCaption(stringResource(R.string.menu_section_list))
            MenuRow(stringResource(R.string.menu_test_all), YukariIcons.Radar, enabled = hasNodes) {
                open = false
                Logs.tap("menu:test-all")
                UrlTestEngine.testAll()
            }
            SortMode.entries.forEach { mode ->
                MenuRow(
                    label = stringResource(sortLabelRes(mode)),
                    icon = sortIcon(mode),
                    selected = SettingsStore.data.sortMode == mode,
                ) {
                    open = false
                    applySortMode(mode)
                }
            }
            HorizontalDivider()
            MenuCaption(
                // Stripped, like every other place a name is drawn: a group named with a
                // feed's flag-emoji convention would paint a colour flag in the caption.
                group?.name?.let { groupLabel(it) }
                    ?: stringResource(R.string.menu_section_no_group),
            )
            MenuRow(
                label = stringResource(R.string.menu_update_sub),
                icon = Icons.Default.Refresh,
                // A manual group has no URL to re-fetch, and the strip's own long-press
                // menu leaves the row out entirely for one — offering it here as well
                // would make the two surfaces disagree about what a group can do.
                enabled = group?.url?.isNotBlank() == true && !NodeRepository.importing,
            ) {
                open = false
                NodeRepository.updateActiveSubscription()
            }
            MenuRow(stringResource(R.string.menu_edit_group), Icons.Default.Edit, enabled = hasGroup) {
                open = false
                editGroup = true
            }
            MenuRow(stringResource(R.string.menu_remove_dups), YukariIcons.Copy, enabled = hasNodes) {
                open = false
                NodeRepository.removeDuplicates()
            }
            MenuRow(
                label = stringResource(R.string.menu_delete_unavailable),
                icon = YukariIcons.NavOff,
                enabled = hasNodes,
            ) {
                open = false
                confirm = BulkAction.DeleteUnavailable
            }
            MenuRow(stringResource(R.string.menu_clear_nodes), Icons.Default.Clear, enabled = hasNodes) {
                open = false
                confirm = BulkAction.ClearNodes
            }
            MenuRow(
                label = stringResource(R.string.menu_delete_group),
                icon = Icons.Default.Delete,
                enabled = hasGroup,
                destructive = true,
            ) {
                open = false
                confirm = BulkAction.DeleteGroup
            }
        }
    }

    if (editGroup) {
        group?.let { GroupEditDialog(existing = it, onDismiss = { editGroup = false }) }
    }
    confirm?.let { action ->
        BulkConfirmDialog(
            action = action,
            // Two of the three bodies name the group, so it is stripped here too.
            groupName = group?.name?.let { groupLabel(it) }.orEmpty(),
            onConfirm = { runBulk(action); confirm = null },
            onDismiss = { confirm = null },
        )
    }
}

/** A grey caption separating the menu's three groups of actions. */
@Composable
private fun MenuCaption(text: String) {
    Text(
        text.uppercase(),
        style = LabelWide,
        color = MaterialTheme.yukari.textTertiary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

/**
 * One menu entry. [selected] marks the active sort mode.
 *
 * [icon] is not optional, and that is the point: `DropdownMenuItem` reserves the leading
 * slot only when it is given one, so a menu where some rows carry a glyph and some do not
 * renders two label columns — eight labels indented for a glyph and six flush against the
 * edge, in one menu. Every row here names its own glyph, a few of them approximately (the
 * two-sheet glyph on "Remove duplicates" is the same one the clipboard rows use, because
 * a duplicate *is* the thing it draws), which is the call the settings list already makes.
 *
 * [destructive] sets the label **Bold** rather than tinting it: hue is not part of any
 * encoding in this interface, and the glyph beside a destructive entry — a trash can, a
 * struck arrow, an X — already names the operation. Weight is the escalation §9 allows.
 */
@Composable
private fun MenuRow(
    label: String,
    icon: ImageVector,
    enabled: Boolean = true,
    selected: Boolean = false,
    destructive: Boolean = false,
    onClick: () -> Unit,
) {
    val tint = MaterialTheme.colorScheme.onSurface
    DropdownMenuItem(
        text = {
            Text(label, color = tint, fontWeight = if (destructive) FontWeight.Bold else null)
        },
        leadingIcon = { Icon(icon, null, tint = tint) },
        trailingIcon = if (selected) {
            { Icon(Icons.Default.Check, null) }
        } else {
            null
        },
        enabled = enabled,
        onClick = onClick,
    )
}

private fun sortLabelRes(mode: SortMode): Int = when (mode) {
    SortMode.Manual -> R.string.sort_manual
    SortMode.Latency -> R.string.sort_latency
    SortMode.Name -> R.string.sort_name
}

/**
 * A sort mode's glyph. The three are one radio group, so they are distinguished by what
 * they order by rather than by three unrelated pictures: a plain list for "the order I
 * arranged", ascending bars for a numeric ordering, a page of ruled lines for an
 * alphabetical one.
 */
private fun sortIcon(mode: SortMode): ImageVector = when (mode) {
    SortMode.Manual -> Icons.AutoMirrored.Filled.List
    SortMode.Latency -> YukariIcons.Stats
    SortMode.Name -> YukariIcons.Document
}

/**
 * Apply an ordering. `Manual` means "keep what I arranged", so it only records the mode
 * — there is no reordering to perform, and re-sorting on the way in would destroy the
 * arrangement the mode exists to preserve.
 */
private fun applySortMode(mode: SortMode) {
    Logs.tap("sort:${mode.name}")
    when (mode) {
        SortMode.Latency -> NodeRepository.sortByLatency()
        SortMode.Name -> NodeRepository.sortByName()
        SortMode.Manual -> SettingsStore.update { it.copy(sortMode = SortMode.Manual) }
    }
}

/**
 * Decode a pasted or scanned payload, reporting "nothing usable in it" distinctly.
 *
 * [noLinksRes] is passed as a resource id rather than resolved text: the notice carries
 * ids now, so the caller has no reason to reach for a `Context`.
 */
private fun importLinks(text: String, @StringRes noLinksRes: Int) {
    val report = SubscriptionDecoder.decodeReport(text)
    if (report.nodes.isEmpty()) {
        NodeRepository.setTestStatus(StatusMessage.Text(noLinksRes))
    } else {
        NodeRepository.addNodes(report.nodes)
    }
}

/** The three destructive bulk operations, each behind a confirmation. */
private enum class BulkAction { DeleteUnavailable, ClearNodes, DeleteGroup }

private fun runBulk(action: BulkAction) {
    when (action) {
        BulkAction.DeleteUnavailable -> NodeRepository.deleteUnavailable()
        BulkAction.ClearNodes -> NodeRepository.clearActiveNodes()
        BulkAction.DeleteGroup ->
            NodeRepository.activeSubId?.let { NodeRepository.deleteSubscription(it) }
    }
}

@Composable
private fun BulkConfirmDialog(
    action: BulkAction,
    groupName: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    ConfirmDialog(
        title = stringResource(
            when (action) {
                BulkAction.DeleteUnavailable -> R.string.confirm_delete_unavailable_title
                BulkAction.ClearNodes -> R.string.confirm_clear_title
                BulkAction.DeleteGroup -> R.string.confirm_delete_group_title
            },
        ),
        body = when (action) {
            BulkAction.DeleteUnavailable -> stringResource(R.string.confirm_delete_unavailable_body)
            BulkAction.ClearNodes -> stringResource(R.string.confirm_clear_body, groupName)
            BulkAction.DeleteGroup -> stringResource(R.string.confirm_delete_group_body, groupName)
        },
        confirmLabel = stringResource(R.string.action_delete),
        cancelLabel = stringResource(R.string.action_cancel),
        onConfirm = onConfirm,
        onDismiss = onDismiss,
    )
}

/**
 * A server row's long-press menu: everything the row itself has no space for.
 *
 * Anchored where it is declared inside the card, so it opens over the row it belongs to.
 * Delete is last and the only entry set Bold, which is the only visual weight any of
 * these carry.
 */
@Composable
internal fun ServerCardMenu(
    entry: NodeEntry,
    expanded: Boolean,
    onDismiss: () -> Unit,
    onEdit: () -> Unit,
    onMove: () -> Unit,
    onDelete: () -> Unit,
    onShowQr: () -> Unit,
) {
    val context = LocalContext.current
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        MenuRow(stringResource(R.string.action_edit), Icons.Default.Edit) { onDismiss(); onEdit() }
        MenuRow(stringResource(R.string.node_clone), YukariIcons.Copy) {
            onDismiss()
            NodeRepository.cloneNode(entry.id)
        }
        MenuRow(stringResource(R.string.node_move), YukariIcons.Folder) { onDismiss(); onMove() }
        HorizontalDivider()
        MenuRow(stringResource(R.string.node_copy_link), YukariIcons.Copy) {
            onDismiss()
            runCatching { ProxyLinkExporter.export(entry.node) }
                .onSuccess { copyText(context, it) }
                .onFailure {
                    NodeRepository.setTestStatus(
                        StatusMessage.Text(R.string.msg_export_failed, listOf(it.message.orEmpty())),
                    )
                }
        }
        MenuRow(stringResource(R.string.node_show_qr), YukariIcons.Qr) { onDismiss(); onShowQr() }
        HorizontalDivider()
        MenuRow(stringResource(R.string.action_delete), Icons.Default.Delete, destructive = true) {
            onDismiss()
            onDelete()
        }
    }
}

/**
 * How tall the actions menu may get, and the number is load-bearing. Uncapped this menu
 * measures a shade over 600 dp, which does not fit under a `⋮` sitting at 205 dp once M3's
 * own 48 dp window margin is counted — so M3 stopped trying to place it below the anchor and
 * relocated it to the top of the screen, which read as a menu flying in from the top-left
 * corner. 580 dp is the largest cap measured to still open *below* the glyph on the dev panel —
 * 620 was enough for M3 to give up and relocate it again — and the last three rows scroll.
 * Keeping the menu on its button and showing every row at once are not both available here:
 * all thirteen need about 719 dp and there are ~650 under the glyph.
 */
private val MENU_MAX_HEIGHT = 580.dp
