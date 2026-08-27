package dev.yukaribox.vpn.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.yukaribox.vpn.R
import dev.yukaribox.vpn.core.NodeGeo
import dev.yukaribox.vpn.core.QrTools
import dev.yukaribox.vpn.data.NodeEntry
import dev.yukaribox.vpn.data.NodeRepository
import dev.yukaribox.vpn.proxy.ProxyLinkExporter
import dev.yukaribox.vpn.ui.kit.ConfirmDialog
import dev.yukaribox.vpn.ui.kit.ListRow

/**
 * The three dialogs a server row can raise.
 *
 * Split out of `ServersScreen.kt` so that file stays inside detekt's per-file function
 * budget — the same reason `ServersMenu` is its own file.
 */

/** The server's share link as a QR image, with a copy fallback when it cannot be built. */
@Composable
internal fun NodeQrDialog(entry: NodeEntry, onDismiss: () -> Unit) {
    val link = remember(entry) { runCatching { ProxyLinkExporter.export(entry.node) }.getOrNull() }
    val qr = remember(link) { link?.let { runCatching { QrTools.encode(it) }.getOrNull() } }
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        title = { Text(NodeGeo.plainName(entry.node.displayName), maxLines = 1) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (qr != null) {
                    Image(
                        bitmap = qr.asImageBitmap(),
                        contentDescription = stringResource(R.string.cd_qr_image),
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    Text(
                        stringResource(R.string.qr_cannot_export),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { link?.let { copyText(context, it) }; onDismiss() },
                enabled = link != null,
            ) { Text(stringResource(R.string.node_copy_link)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) }
        },
    )
}

/** Move a server into another group. */
@Composable
internal fun MoveNodeDialog(entry: NodeEntry, onDismiss: () -> Unit) {
    val targets = NodeRepository.subscriptions.filter { it.id != NodeRepository.activeSubId }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        title = { Text(stringResource(R.string.move_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                if (targets.isEmpty()) {
                    Text(stringResource(R.string.move_none))
                } else {
                    targets.forEach { sub ->
                        ListRow(
                            // A group name is stripped for display exactly like a node's:
                            // a feed's flag-emoji prefix is the one full-colour element a
                            // label can inject. The stored name keeps the author's text.
                            title = groupLabel(sub.name),
                            subtitle = stringResource(R.string.groups_count, sub.nodes.size),
                            onClick = { NodeRepository.moveNode(entry.id, sub.id); onDismiss() },
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@Composable
internal fun DeleteNodeDialog(entry: NodeEntry, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    ConfirmDialog(
        title = stringResource(R.string.node_delete_title),
        body = stringResource(R.string.node_delete_body, NodeGeo.plainName(entry.node.displayName)),
        confirmLabel = stringResource(R.string.action_delete),
        cancelLabel = stringResource(R.string.action_cancel),
        onConfirm = onConfirm,
        onDismiss = onDismiss,
    )
}
