package dev.yukaribox.vpn.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.yukaribox.vpn.R
import dev.yukaribox.vpn.core.Logs
import dev.yukaribox.vpn.data.BackupManager
import dev.yukaribox.vpn.data.NodeRepository
import dev.yukaribox.vpn.ui.kit.GhostButton
import dev.yukaribox.vpn.ui.kit.IconCircle
import dev.yukaribox.vpn.ui.kit.Notice
import dev.yukaribox.vpn.ui.kit.PaperCard
import dev.yukaribox.vpn.ui.kit.PrimaryButton
import dev.yukaribox.vpn.ui.kit.ScreenMargin
import dev.yukaribox.vpn.ui.kit.TitleTopBar
import dev.yukaribox.vpn.ui.theme.YukariIcons

/**
 * Export/import the whole app state (settings + groups + routes) as a JSON file
 * through SAF, plus the portable NekoBox-compatible link list.
 *
 * Three stacked content cards and a selective-restore dialog. The export card keeps its
 * warning: a full backup carries every node credential in the clear, and the user is the
 * only one who can decide where that file is allowed to land.
 */
@Composable
fun BackupScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var message by remember { mutableStateOf<String?>(null) }
    var peek by remember { mutableStateOf<BackupManager.Peek?>(null) }
    var configPassword by remember { mutableStateOf("") }

    // Resolved up front: SAF callbacks below land off-composition (worker threads).
    val msgExportFailed = stringResource(R.string.backup_export_failed)
    val msgSaved = stringResource(R.string.backup_saved)
    val msgReadFailed = stringResource(R.string.backup_read_failed)
    val msgRestored = stringResource(R.string.backup_restored)
    val msgConfigSaved = stringResource(R.string.backup_config_saved)
    val msgConfigImported = stringResource(R.string.backup_config_imported)
    val msgConfigImportFailed = stringResource(R.string.backup_config_import_failed)

    // SAF callbacks land on the main thread; serialization of a 32 MB backup
    // must not run there.
    val exporter = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri != null) {
            Thread {
                val err = BackupManager.exportTo(context, uri)
                message = err?.let { msgExportFailed.format(it) } ?: msgSaved
            }.start()
        }
    }
    val importer = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            Thread {
                BackupManager.peek(context, uri)
                    .onSuccess { peek = it }
                    .onFailure { message = msgReadFailed.format(it.message) }
            }.start()
        }
    }
    val configExporter = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri ->
        if (uri != null) {
            val pw = configPassword
            Thread {
                val err = BackupManager.exportConfig(context, uri, pw)
                message = err?.let { msgExportFailed.format(it) }
                    ?: msgConfigSaved.format(NodeRepository.subscriptions.sumOf { it.nodes.size })
            }.start()
        }
    }
    val configImporter = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            val pw = configPassword
            Thread {
                BackupManager.importConfig(context, uri, pw)
                    .onSuccess { message = msgConfigImported.format(NodeRepository.addNodes(it)) }
                    .onFailure { message = msgConfigImportFailed.format(it.message) }
            }.start()
        }
    }

    Column(Modifier.fillMaxSize()) {
        TitleTopBar(
            title = stringResource(R.string.title_backup),
            onNav = onBack,
            navContentDescription = stringResource(R.string.cd_back),
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(start = ScreenMargin, end = ScreenMargin, top = CARD_TOP),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // ----- Full backup -----
            PaperCard(Modifier.fillMaxWidth()) {
                CardHeading(stringResource(R.string.backup_export_title), Icons.Default.Share)
                Text(
                    stringResource(R.string.backup_export_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 10.dp),
                )
                Row(
                    Modifier.padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        Icons.Default.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(16.dp),
                    )
                    // Ink, the glyph, and the sentence in bold. The amber pair this
                    // replaced was the warning's only signal beyond the words, and there
                    // is no hue left in the palette to spend on it — so the escalation is
                    // weight, exactly as a badge escalates by filling rather than tinting.
                    // The sentence itself is load-bearing: this export carries every node
                    // credential in the clear.
                    Text(
                        stringResource(R.string.backup_export_warning),
                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                PrimaryButton(
                    text = stringResource(R.string.backup_export_button),
                    onClick = {
                        Logs.tap("backup:export")
                        exporter.launch(BackupManager.suggestedFileName())
                    },
                    modifier = Modifier.padding(top = 14.dp),
                )
            }

            // ----- Restore -----
            PaperCard(Modifier.fillMaxWidth()) {
                CardHeading(stringResource(R.string.backup_import_title), YukariIcons.Backup)
                Text(
                    stringResource(R.string.backup_import_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 10.dp),
                )
                GhostButton(
                    text = stringResource(R.string.backup_import_button),
                    onClick = {
                        Logs.tap("backup:import")
                        importer.launch(arrayOf("application/json", "text/*", "application/octet-stream"))
                    },
                    modifier = Modifier.padding(top = 14.dp),
                )
            }

            // ----- Portable link list (NekoBox / sing-box compatible) -----
            PaperCard(Modifier.fillMaxWidth()) {
                CardHeading(stringResource(R.string.backup_config_title), YukariIcons.Document)
                Text(
                    stringResource(R.string.backup_config_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 10.dp),
                )
                OutlinedTextField(
                    value = configPassword,
                    onValueChange = { configPassword = it },
                    label = { Text(stringResource(R.string.backup_config_password_label)) },
                    supportingText = { Text(stringResource(R.string.backup_config_password_hint)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                )
                Row(
                    Modifier.padding(top = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    PrimaryButton(
                        text = stringResource(R.string.backup_config_export_button),
                        onClick = {
                            Logs.tap("backup:export-config")
                            configExporter.launch(BackupManager.suggestedConfigFileName())
                        },
                    )
                    GhostButton(
                        text = stringResource(R.string.backup_config_import_button),
                        onClick = {
                            Logs.tap("backup:import-config")
                            configImporter.launch(
                                arrayOf("text/*", "application/json", "application/octet-stream"),
                            )
                        },
                    )
                }
            }

            message?.let { text ->
                Notice(
                    text = text,
                    actionLabel = stringResource(R.string.action_close),
                    onAction = { message = null },
                )
            }
            Spacer(Modifier.height(20.dp))
        }
    }
    peek?.let { p ->
        var restoreSettings by remember { mutableStateOf(p.hasSettings) }
        var restoreGroups by remember { mutableStateOf(p.groups > 0) }
        var restoreRoutes by remember { mutableStateOf(p.rules > 0) }
        AlertDialog(
            onDismissRequest = { peek = null },
            containerColor = MaterialTheme.colorScheme.surface,
            icon = { Icon(YukariIcons.Backup, contentDescription = null) },
            title = { Text(stringResource(R.string.backup_restore_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        stringResource(R.string.backup_restore_body),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    CheckRow(
                        stringResource(R.string.backup_part_settings),
                        p.hasSettings,
                        restoreSettings,
                    ) { restoreSettings = it }
                    CheckRow(
                        stringResource(R.string.backup_part_groups, p.groups, p.nodes),
                        p.groups > 0,
                        restoreGroups,
                    ) { restoreGroups = it }
                    CheckRow(
                        stringResource(R.string.backup_part_routes, p.rules),
                        p.rules > 0,
                        restoreRoutes,
                    ) { restoreRoutes = it }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    BackupManager.restore(p.backup, restoreSettings, restoreGroups, restoreRoutes)
                    peek = null
                    message = msgRestored
                }) {
                    // A restore overwrites live settings, groups and routes, so the verb
                    // is the destructive one — carried by weight, like every other
                    // destructive verb in the app, since `error` is a neutral now.
                    Text(
                        stringResource(R.string.backup_restore_action),
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { peek = null }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

/**
 * A card's plated glyph and heading — the three cards share one opening, not one glyph.
 *
 * [icon] is the card's own: a share glyph for the export that sends state out of the app,
 * the crate-with-an-inbound-arrow for the restore that brings it back, a page for the
 * portable link list. All three used to draw the crate, which made the plate a decoration
 * that repeated the heading instead of naming it.
 */
@Composable
private fun CardHeading(title: String, icon: ImageVector) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconCircle(size = 36.dp) { Icon(icon, null, Modifier.size(17.dp)) }
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun CheckRow(label: String, enabled: Boolean, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked && enabled, onCheckedChange = onChange, enabled = enabled)
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (enabled) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

/** Gap from the app bar to the first card — the reference's 9 dp, on the 8 dp grid. */
private val CARD_TOP = 8.dp
