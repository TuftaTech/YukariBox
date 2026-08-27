package dev.yukaribox.vpn.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.yukaribox.vpn.core.AppContext
import dev.yukaribox.vpn.core.AppThreads
import dev.yukaribox.vpn.core.DurableFile
import dev.yukaribox.vpn.core.Logs
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors

/** Where matched traffic goes. */
@Serializable
enum class RuleOutbound { Proxy, Direct, Block }

/**
 * One user routing rule. Matches are AND-ed across populated fields (like a
 * sing-box route rule); empty fields don't constrain. List order = priority.
 */
@Serializable
data class RouteRule(
    val id: String,
    val name: String = "",
    val enabled: Boolean = true,
    /** Domain suffixes/keywords, comma- or newline-separated in UI; stored split. */
    val domains: List<String> = emptyList(),
    /** Destination CIDRs, e.g. "1.1.1.0/24". */
    val ipCidrs: List<String> = emptyList(),
    /** Destination ports/ranges, e.g. "443" or "1000:2000". */
    val ports: List<String> = emptyList(),
    /** Android package names. */
    val packages: List<String> = emptyList(),
    val outbound: RuleOutbound = RuleOutbound.Proxy,
) {
    val isEmpty: Boolean
        get() = domains.isEmpty() && ipCidrs.isEmpty() && ports.isEmpty() && packages.isEmpty()
}

/** Observable, disk-persisted list of user routing rules (`files/routes.json`). */
object RouteRepository {

    val rules = mutableStateListOf<RouteRule>()

    private val io = Executors.newSingleThreadExecutor(AppThreads.factory("routes-io"))
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; isLenient = true }
    private val serializer = ListSerializer(RouteRule.serializer())
    private var loaded = false

    /**
     * Set when `routes.json` existed but could not be parsed. Route rules decide
     * what bypasses the proxy, so a silent reset to "no rules" changes where the
     * user's traffic goes — it must be reportable, not just logged.
     */
    var loadFailed by mutableStateOf(false)
        private set

    private fun store() = DurableFile(File(AppContext.context.filesDir, "routes.json"))

    fun load() {
        if (loaded) return
        loaded = true
        val saved = when (val read = store().read { json.decodeFromString(serializer, it) }) {
            is DurableFile.Read.Ok -> read.value
            is DurableFile.Read.Recovered -> {
                Logs.w("Routes", "routes.json was unreadable; recovered from backup copy")
                read.value
            }
            is DurableFile.Read.Missing -> emptyList()
            is DurableFile.Read.Corrupt -> {
                Logs.e("Routes", "routes.json unreadable, starting with no rules", read.cause)
                loadFailed = true
                emptyList()
            }
        }
        rules.clear()
        rules.addAll(saved)
    }

    fun add(rule: RouteRule) {
        rules.add(rule.ensureId())
        persist()
    }

    fun update(rule: RouteRule) {
        val index = rules.indexOfFirst { it.id == rule.id }
        if (index >= 0) {
            rules[index] = rule
            persist()
        }
    }

    fun delete(id: String) {
        rules.removeAll { it.id == id }
        persist()
    }

    fun move(id: String, up: Boolean) {
        val index = rules.indexOfFirst { it.id == id }
        val target = if (up) index - 1 else index + 1
        if (index < 0 || target < 0 || target >= rules.size) return
        val tmp = rules[target]
        rules[target] = rules[index]
        rules[index] = tmp
        persist()
    }

    fun setEnabled(id: String, enabled: Boolean) {
        val index = rules.indexOfFirst { it.id == id }
        if (index >= 0) {
            rules[index] = rules[index].copy(enabled = enabled)
            persist()
        }
    }

    /** Enabled, non-empty rules in priority order — what the config builder consumes. */
    fun activeRules(): List<RouteRule> = rules.filter { it.enabled && !it.isEmpty }

    /** Replace everything (backup restore). */
    fun replaceAll(newRules: List<RouteRule>) {
        rules.clear()
        rules.addAll(newRules.map { it.ensureId() })
        persist()
    }

    fun decode(text: String): List<RouteRule> =
        if (text.isBlank()) emptyList() else json.decodeFromString(serializer, text)

    private fun RouteRule.ensureId(): RouteRule =
        if (id.isBlank()) copy(id = UUID.randomUUID().toString()) else this

    private fun persist() {
        val snapshot = rules.toList()
        io.execute {
            try {
                store().write(json.encodeToString(serializer, snapshot))
            } catch (e: Exception) {
                Logs.e("Routes", "persist failed", e)
            }
        }
    }
}
