package dev.yukaribox.vpn.data

import android.content.Context
import android.net.Uri
import dev.yukaribox.vpn.core.BackupCrypto
import dev.yukaribox.vpn.core.Logs
import dev.yukaribox.vpn.core.SettingsData
import dev.yukaribox.vpn.core.SettingsGuard
import dev.yukaribox.vpn.core.SettingsStore
import dev.yukaribox.vpn.proxy.ProxyLinkExporter
import dev.yukaribox.vpn.proxy.ProxyNode
import dev.yukaribox.vpn.proxy.SubscriptionDecoder
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Full-app backup: settings + groups/nodes + routing rules in one JSON document,
 * written/read through SAF so the user picks the location. The file contains
 * node credentials in the clear (same trade-off as NekoBox backups) — the UI
 * warns before export.
 */
object BackupManager {

    private const val VERSION = 1
    private const val MAX_BACKUP_BYTES = 32L * 1024 * 1024

    @Serializable
    data class Backup(
        val version: Int = VERSION,
        val app: String = "YukariBox",
        val settings: SettingsData? = null,
        val subscriptions: List<Subscription> = emptyList(),
        val routes: List<RouteRule> = emptyList(),
    )

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = true
        prettyPrint = true
    }

    fun suggestedFileName(): String {
        val now = java.text.SimpleDateFormat("yyyyMMdd_HHmm", java.util.Locale.US)
            .format(java.util.Date())
        return "yukaribox_backup_$now.json"
    }

    /** Serialize current state and write it to [uri]. Returns an error message or null. */
    fun exportTo(context: Context, uri: Uri): String? {
        return try {
            val backup = Backup(
                settings = SettingsStore.data,
                subscriptions = NodeRepository.subscriptions.toList(),
                routes = RouteRepository.rules.toList(),
            )
            val text = json.encodeToString(Backup.serializer(), backup)
            val out = context.contentResolver.openOutputStream(uri, "wt")
                ?: return "cannot open output"
            out.use { it.write(text.toByteArray(Charsets.UTF_8)) }
            Logs.i("Backup", "exported ${NodeRepository.subscriptions.size} group(s), ${RouteRepository.rules.size} rule(s)")
            null
        } catch (e: Exception) {
            Logs.e("Backup", "export failed", e)
            e.message ?: "export failed"
        }
    }

    fun suggestedConfigFileName(): String {
        val now = java.text.SimpleDateFormat("yyyyMMdd_HHmm", java.util.Locale.US)
            .format(java.util.Date())
        return "yukaribox_config_$now.txt"
    }

    /**
     * Export every saved node as a NekoBox / sing-box-compatible share-link list
     * (one `scheme://…` link per line — the interchange format those clients read
     * and write). When [password] is non-blank the list is AES-GCM encrypted via
     * [BackupCrypto] before writing. Returns an error message or null on success.
     */
    fun exportConfig(context: Context, uri: Uri, password: String): String? {
        return try {
            val links = NodeRepository.subscriptions
                .flatMap { it.nodes }
                .joinToString("\n") { ProxyLinkExporter.export(it.node) }
            val payload = if (password.isBlank()) links else BackupCrypto.encrypt(links, password)
            val out = context.contentResolver.openOutputStream(uri, "wt")
                ?: return "cannot open output"
            out.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
            val count = NodeRepository.subscriptions.sumOf { it.nodes.size }
            Logs.i("Backup", "exported config: $count link(s)${if (password.isBlank()) "" else " (encrypted)"}")
            null
        } catch (e: Exception) {
            Logs.e("Backup", "config export failed", e)
            e.message ?: "export failed"
        }
    }

    /**
     * Read a NekoBox / sing-box-compatible config file and decode its nodes,
     * decrypting first with [password] when the file is encrypted. The caller
     * applies the result (e.g. NodeRepository.addNodes).
     */
    fun importConfig(context: Context, uri: Uri, password: String): Result<List<ProxyNode>> = try {
        val raw = context.contentResolver.openInputStream(uri)?.use { readBounded(it) }
            ?: return Result.failure(IllegalStateException("cannot open file"))
        val encrypted = BackupCrypto.isEncrypted(raw)
        val text = when {
            !encrypted && password.isNotBlank() ->
                // The user supplied a password but the file carries no envelope. Importing
                // it anyway would silently accept a plaintext substitute for the encrypted
                // file they meant to open, so say so instead of guessing.
                return Result.failure(IllegalStateException("file is not encrypted; clear the password to import it"))
            !encrypted -> raw
            password.isBlank() -> return Result.failure(IllegalStateException("password required"))
            else -> BackupCrypto.decrypt(raw, password)
        }
        val nodes = SubscriptionDecoder.decode(text)
        if (nodes.isEmpty()) {
            Result.failure(IllegalStateException("no nodes found"))
        } else {
            Result.success(nodes)
        }
    } catch (e: Exception) {
        Logs.e("Backup", "config import failed", e)
        Result.failure(e)
    }

    /** What's inside a backup file — shown before the user confirms a restore. */
    data class Peek(val backup: Backup, val groups: Int, val nodes: Int, val rules: Int, val hasSettings: Boolean)

    /** Read and validate a backup file without applying it. */
    fun peek(context: Context, uri: Uri): Result<Peek> = try {
        val text = context.contentResolver.openInputStream(uri)?.use { input ->
            readBounded(input)
        } ?: return Result.failure(IllegalStateException("cannot open file"))
        val backup = json.decodeFromString(Backup.serializer(), text)
        if (backup.version > VERSION) {
            Result.failure(IllegalStateException("backup version ${backup.version} is newer than supported"))
        } else {
            Result.success(
                Peek(
                    backup = backup,
                    groups = backup.subscriptions.size,
                    nodes = backup.subscriptions.sumOf { it.nodes.size },
                    rules = backup.routes.size,
                    hasSettings = backup.settings != null,
                ),
            )
        }
    } catch (e: Exception) {
        Logs.e("Backup", "read failed", e)
        Result.failure(e)
    }

    /** Apply a previously [peek]ed backup. Replaces the selected components. */
    fun restore(
        backup: Backup,
        restoreSettings: Boolean,
        restoreGroups: Boolean,
        restoreRoutes: Boolean,
    ) {
        if (restoreSettings && backup.settings != null) {
            // Sanitized: a backup file is untrusted input even when the user picked it.
            // Restoring it verbatim could plant an MTU that disarms the fail-closed TUN or
            // a cleartext DNS resolver that watches every domain the user visits. The
            // restore variant additionally drops the proxy-only password, which the
            // backup's author would otherwise know for this device.
            SettingsStore.update { SettingsGuard.sanitizeRestored(backup.settings, it) }
        }
        if (restoreGroups) {
            NodeRepository.replaceAll(backup.subscriptions)
        }
        if (restoreRoutes) {
            RouteRepository.replaceAll(backup.routes)
        }
        Logs.i("Backup", "restored settings=$restoreSettings groups=$restoreGroups routes=$restoreRoutes")
    }

    /** Read at most [MAX_BACKUP_BYTES]; a backup larger than that is rejected. */
    private fun readBounded(input: java.io.InputStream): String {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            total += n
            if (total > MAX_BACKUP_BYTES) throw IllegalStateException("backup too large (> 32 MB)")
            out.write(buf, 0, n)
        }
        return out.toString("UTF-8")
    }
}
