package dev.yukaribox.vpn.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.yukaribox.vpn.R
import dev.yukaribox.vpn.core.Logs
import dev.yukaribox.vpn.data.NodeRepository
import dev.yukaribox.vpn.data.Subscription
import dev.yukaribox.vpn.ui.kit.BarIconButton
import dev.yukaribox.vpn.ui.kit.ConfirmDialog
import dev.yukaribox.vpn.ui.kit.EmptyState
import dev.yukaribox.vpn.ui.kit.GAP
import dev.yukaribox.vpn.ui.kit.IconCircle
import dev.yukaribox.vpn.ui.kit.ListMargin
import dev.yukaribox.vpn.ui.kit.MetaBadge
import dev.yukaribox.vpn.ui.kit.PaperCard
import dev.yukaribox.vpn.ui.kit.PrimaryButton
import dev.yukaribox.vpn.ui.kit.SegmentedTabs
import dev.yukaribox.vpn.ui.kit.TitleTopBar
import dev.yukaribox.vpn.ui.kit.swapSpec
import dev.yukaribox.vpn.ui.theme.ListCardShape
import dev.yukaribox.vpn.ui.theme.YukariIcons

/**
 * The groups screen: one card per group, and the only place several of them are visible
 * at once.
 *
 * A drawer destination now rather than a tab, and it wears the hamburger for that reason
 * — the drawer is where it is opened from, and a back arrow on a place you *go to* sends
 * users looking for the way back to Home in the wrong corner. System Back still pops it.
 * The strip above the server list
 * ([GroupStrip]) is the fast switch — one tap per group, always in view over the list it
 * changes — and this is where they are surveyed: which are subscriptions, how many
 * servers each holds, and the empty state that tells a fresh install what a group is
 * for. Both surfaces raise the same three actions from the same menu, so neither becomes
 * a second dialect of the other.
 *
 * A group is either a subscription (a URL that is re-fetched) or a manual list, and the
 * distinction matters enough to show on every card: "Update" does nothing to a manual
 * group, and a subscription refresh replaces its contents wholesale. Tapping a group
 * makes it the one the server list shows and goes there, because picking a group and
 * then not seeing its servers is the reason this used to be a screen nobody found.
 */
@Composable
internal fun GroupsScreen(onOpenDrawer: () -> Unit, onOpenServers: () -> Unit) {
    var editTarget by remember { mutableStateOf<Subscription?>(null) }
    var deleteTarget by remember { mutableStateOf<Subscription?>(null) }
    var createOpen by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        TitleTopBar(
            title = stringResource(R.string.groups_title),
            onNav = onOpenDrawer,
            navContentDescription = stringResource(R.string.cd_menu),
            navIcon = Icons.Default.Menu,
            actions = {
                BarIconButton(
                    icon = Icons.Default.Add,
                    contentDescription = stringResource(R.string.cd_add_group),
                    onClick = { Logs.tap("group:new"); createOpen = true },
                )
            },
        )
        GroupList(
            onSelect = { sub -> NodeRepository.selectSubscription(sub.id); onOpenServers() },
            onEdit = { sub -> editTarget = sub },
            onDelete = { sub -> deleteTarget = sub },
            onCreate = { createOpen = true },
        )
    }

    if (createOpen) GroupEditDialog(existing = null, onDismiss = { createOpen = false })
    editTarget?.let { sub ->
        GroupEditDialog(existing = sub, onDismiss = { editTarget = null })
    }
    deleteTarget?.let { sub -> GroupDeleteDialog(sub = sub, onDismiss = { deleteTarget = null }) }
}

/** The cards themselves, or the one instruction that exists when there are none. */
@Composable
private fun GroupList(
    onSelect: (Subscription) -> Unit,
    onEdit: (Subscription) -> Unit,
    onDelete: (Subscription) -> Unit,
    onCreate: () -> Unit,
) {
    if (NodeRepository.subscriptions.isEmpty()) {
        EmptyState(
            title = stringResource(R.string.groups_empty_title),
            body = stringResource(R.string.groups_empty_hint),
            icon = YukariIcons.Folder,
            // The bar's + can also do this, but an empty screen whose instruction is
            // "create a group" and whose only affordance is elsewhere is a dead end.
            action = {
                PrimaryButton(
                    text = stringResource(R.string.groups_dialog_new_title),
                    onClick = onCreate,
                    icon = Icons.Default.Add,
                )
            },
        )
    } else {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = ListMargin,
                end = ListMargin,
                top = 8.dp,
                bottom = LIST_BOTTOM_GROUPS,
            ),
            verticalArrangement = Arrangement.spacedBy(GAP),
        ) {
            items(NodeRepository.subscriptions, key = { it.id }) { sub ->
                GroupCard(
                    sub = sub,
                    active = sub.id == NodeRepository.activeSubId,
                    modifier = Modifier.animateItem(
                        fadeInSpec = swapSpec(),
                        placementSpec = swapSpec(),
                        fadeOutSpec = swapSpec(),
                    ),
                    onSelect = { onSelect(sub) },
                    onEdit = { onEdit(sub) },
                    onDelete = { onDelete(sub) },
                )
            }
        }
    }
}

/**
 * Deleting a group takes its servers with it, so the count is in the sentence: a
 * subscription's name says nothing about how much is behind it.
 */
@Composable
internal fun GroupDeleteDialog(sub: Subscription, onDismiss: () -> Unit) {
    ConfirmDialog(
        title = stringResource(R.string.groups_delete_title),
        // Stripped for display like every other name: a group whose label starts with a
        // regional-indicator pair would paint the system's colour flag inside the
        // sentence. The stored name is untouched.
        body = stringResource(R.string.groups_delete_body, groupLabel(sub.name), sub.nodes.size),
        confirmLabel = stringResource(R.string.action_delete),
        cancelLabel = stringResource(R.string.action_cancel),
        onConfirm = { NodeRepository.deleteSubscription(sub.id); onDismiss() },
        onDismiss = onDismiss,
    )
}

/**
 * One group. Long press for edit / update / delete, the same gesture the server rows
 * use, so the screen has one interaction vocabulary rather than two.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GroupCard(
    sub: Subscription,
    active: Boolean,
    onSelect: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val isSubscription = sub.url.isNotBlank()
    // A group name is a name, and a feed's naming convention is a flag emoji first —
    // the one full-colour thing a label can inject into a monochrome interface. Node
    // names are stripped at every display site for exactly this reason; a group card
    // sitting above them cannot be the exception. Display only: the stored name, the
    // export and the search index all keep the author's text.
    val label = remember(sub.name) { groupLabel(sub.name) }
    PaperCard(
        modifier = modifier.fillMaxWidth(),
        shape = ListCardShape,
        contentPadding = PaddingValues(0.dp),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = onSelect, onLongClick = { menuOpen = true })
                .padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconCircle(size = 40.dp, emphasized = active) {
                Icon(
                    if (isSubscription) YukariIcons.Globe else YukariIcons.Folder,
                    null,
                    Modifier.size(19.dp),
                )
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    label,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    stringResource(
                        if (isSubscription) R.string.groups_kind_subscription else R.string.groups_kind_manual,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            MetaBadge(sub.nodes.size.toString())
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
 * Edit / update / delete for one group — the whole vocabulary of group actions, raised by
 * a long press from a card here and from a tab in [GroupStrip] alike. "Update" is offered
 * only for subscriptions, because it does nothing to a manual list.
 */
@Composable
internal fun GroupActionsMenu(
    sub: Subscription,
    expanded: Boolean,
    onDismiss: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val isSubscription = sub.url.isNotBlank()
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(
            text = { Text(stringResource(R.string.action_edit)) },
            leadingIcon = { Icon(Icons.Default.Edit, null) },
            onClick = { onDismiss(); onEdit() },
        )
        if (isSubscription) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.groups_menu_update)) },
                leadingIcon = { Icon(Icons.Default.Refresh, null) },
                enabled = !NodeRepository.importing,
                onClick = {
                    onDismiss()
                    NodeRepository.selectSubscription(sub.id)
                    NodeRepository.updateActiveSubscription()
                },
            )
        }
        DropdownMenuItem(
            // Weight, not a hue: the delete verb is the only Bold entry in the menu and
            // the trash glyph beside it already names the operation. There is no error
            // colour left to spend, and a bold verb against its neighbours is the
            // clearer pair anyway.
            text = {
                Text(
                    stringResource(R.string.action_delete),
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Bold,
                )
            },
            leadingIcon = {
                Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.onSurface)
            },
            onClick = { onDismiss(); onDelete() },
        )
    }
}

/**
 * Create or edit a group, covering both kinds.
 *
 * The kind is a segmented choice rather than an inferred one: a blank URL used to mean
 * "manual", which made "I meant to paste a URL and forgot" indistinguishable from "I
 * want a manual list". Saving a subscription fetches it immediately, so the user finds
 * out about a bad URL now rather than at connect time.
 */
@Composable
internal fun GroupEditDialog(existing: Subscription?, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(existing?.name.orEmpty()) }
    var url by remember { mutableStateOf(existing?.url.orEmpty()) }
    var subscription by remember { mutableStateOf(existing?.url?.isNotBlank() == true) }
    val labelManual = stringResource(R.string.groups_kind_manual)
    val labelSubscription = stringResource(R.string.groups_kind_subscription)
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        title = {
            Text(
                stringResource(
                    if (existing == null) R.string.groups_dialog_new_title else R.string.groups_dialog_edit_title,
                ),
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SegmentedTabs(
                    options = listOf(false, true),
                    selectedOption = subscription,
                    label = { if (it) labelSubscription else labelManual },
                    onSelect = { subscription = it },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = {
                        Text(
                            stringResource(
                                if (subscription) {
                                    R.string.groups_field_name_optional
                                } else {
                                    R.string.groups_field_name
                                },
                            ),
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (subscription) {
                    OutlinedTextField(
                        value = url,
                        onValueChange = { url = it },
                        singleLine = true,
                        label = { Text(stringResource(R.string.groups_field_url)) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        stringResource(R.string.groups_url_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            val valid = if (subscription) url.isNotBlank() else name.isNotBlank()
            TextButton(
                enabled = valid,
                onClick = {
                    saveGroup(existing, name.trim(), if (subscription) url.trim() else "")
                    onDismiss()
                },
            ) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

/**
 * Four cases, and they are genuinely different operations on the store: edit an existing
 * group, create a manual one, or import a subscription with or without a name of its own.
 */
private fun saveGroup(existing: Subscription?, name: String, url: String) {
    Logs.tap(if (existing == null) "groups:create" else "groups:edit")
    when {
        existing != null -> NodeRepository.updateGroup(existing.id, name, url)
        url.isBlank() -> NodeRepository.createGroup(name)
        name.isBlank() -> NodeRepository.importFromUrl(url)
        else -> NodeRepository.importFromUrl(url, name)
    }
}

/** Room under the last card so the floating toggle never covers it. */
private val LIST_BOTTOM_GROUPS = 92.dp
