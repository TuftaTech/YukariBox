package dev.yukaribox.vpn.data

import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import dev.yukaribox.vpn.R
import dev.yukaribox.vpn.core.AppContext
import dev.yukaribox.vpn.core.AppThreads
import dev.yukaribox.vpn.core.DurableFile
import dev.yukaribox.vpn.core.Logs
import dev.yukaribox.vpn.core.SettingsStore
import dev.yukaribox.vpn.core.SortMode
import dev.yukaribox.vpn.proxy.ProxyNode
import dev.yukaribox.vpn.proxy.SubscriptionDecoder
import kotlinx.serialization.Serializable
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

const val LATENCY_UNTESTED = -1
const val LATENCY_TESTING = -3
const val LATENCY_FAILED = -2

/** One imported node plus its latest measured latency. */
@Serializable
data class NodeEntry(
    val id: Int,
    val node: ProxyNode,
    val latencyMs: Int = LATENCY_UNTESTED,
    /**
     * Starred by the user. Favourites float to the top of the list in every sort
     * mode, and survive a subscription refresh by connection identity — see
     * [NodeRepository.carryFavourites].
     *
     * A defaulted field, so a `subscriptions.json` written before this existed still
     * decodes.
     */
    val favorite: Boolean = false,
)

/**
 * Observable store of saved subscriptions (NekoBox-style groups) with the active
 * subscription's working node set. Subscriptions persist to disk; the active
 * subscription and its selected node are restored on launch.
 */
object NodeRepository {

    /** Saved subscriptions (metadata + persisted nodes). */
    val subscriptions = mutableStateListOf<Subscription>()

    var activeSubId by mutableStateOf<String?>(null)
        private set

    /**
     * Whether any group exists at all — the servers screen's "no groups yet" branch.
     *
     * A [derivedStateOf] rather than `subscriptions.isEmpty()` read from composition, and
     * rather than a counter kept by hand. [subscriptions] has one state record for the whole
     * list, so `persist()` folding the working set back into the active group — which happens
     * on every tap on a server row, every star and the end of every sweep — invalidated
     * everyone who had asked it merely whether it was empty. A derived state recomputes on
     * each of those and notifies its readers only when the *boolean* changes, so the screen
     * is left alone, and there is no second copy of the truth to fall out of step.
     */
    val hasGroups: Boolean by derivedStateOf { subscriptions.isNotEmpty() }

    /** Working node set of the active subscription (UI shows / URL test mutates this). */
    val nodes = mutableStateListOf<NodeEntry>()

    /**
     * Bumped every time [nodes] is genuinely re-ordered: a latency sweep landing while
     * latency order is chosen, a star moving a row, the user picking a sort. Not on a
     * group switch, which replaces the set rather than reordering it, and not when the
     * chosen order turns out to be the one already on screen.
     *
     * The servers list watches this to pin its viewport. `LazyListState` remembers the
     * *key* of the first visible row and follows that row when it moves, so a dead
     * server sinking to the bottom of a latency sort used to take the whole screen down
     * with it; the screen re-anchors by index instead — see `ServersScrollPin`.
     */
    var orderRevision by mutableIntStateOf(0)
        private set

    /**
     * Cached id→position map over [nodes], rebuilt on demand by [indexOfNode]. Plain
     * (non-snapshot) state: it is an index into [nodes], never something the UI reads.
     */
    private var idIndex: HashMap<Int, Int>? = null

    /**
     * The newest folded group list that has not been written yet, or `null` when everything
     * adopted is on disk. Published under [lock] and drained on [io] — see [persist].
     */
    private val pending = AtomicReference<List<Subscription>?>(null)

    /**
     * Global selection: the subscription that owns the selected node. Independent
     * of [activeSubId] — browsing another subscription must not steal the choice.
     */
    var selectedSubId by mutableStateOf<String?>(null)
        private set

    var selectedId by mutableStateOf<Int?>(null)
        private set

    /**
     * The last outcome worth telling the user about, or `null` for "nothing to say".
     *
     * A resource id plus its arguments rather than prose: this store reports from the
     * IO and network executors, which have no `Context` to resolve a string against,
     * and the English sentences it used to build showed up verbatim in a Russian
     * interface. The servers screen resolves it — see `StatusNotice`.
     *
     * Read from composition, so it stays a plain snapshot read: taking [lock] here
     * would let a service-thread mutation stall the UI thread.
     */
    var status by mutableStateOf<StatusMessage?>(null)
        private set

    var importing by mutableStateOf(false)
        private set

    /**
     * Set when `subscriptions.json` existed but could not be parsed. This store
     * holds every node credential the user cannot reproduce, so a reset to "no
     * subscriptions" must be reportable instead of looking like a fresh install.
     * The unreadable bytes are kept as `subscriptions.json.corrupt`.
     */
    var loadFailed by mutableStateOf(false)
        private set

    /** Disk IO (load + persist). Never used for network work — see [net]. */
    private val io = Executors.newSingleThreadExecutor(AppThreads.factory("nodes-io"))

    /**
     * Subscription fetches. Separate from [io] because a fetch takes up to
     * [HTTP_TOTAL_TIMEOUT_MS]: with both on one executor, an auto-update queued at
     * startup sat in front of every persist, and [awaitLoaded] — called from the QS
     * tile, the boot receiver and always-on start — waited out the network before
     * the tunnel could even be configured.
     */
    private val net = Executors.newSingleThreadExecutor(AppThreads.factory("nodes-net"))

    /**
     * Guards compound mutations of [nodes] / [subscriptions] and of the selection.
     *
     * The lists are Compose snapshot lists, safe for single operations but not for
     * the read-then-write pairs used throughout this file: `setLatency` looks a row up
     * and then assigns, from several URL-test pool threads, while `applySort` reorders
     * from the UI thread — and a "test all" followed by a sort threw
     * IndexOutOfBoundsException on a pool thread. That is an uncaught exception in a
     * non-UI thread: it killed the process, and with it the running service, closing the
     * TUN and letting traffic out unproxied.
     *
     * This lock keeps *writers* off each other. It does **not** hide a compound mutation
     * from a reader — reads are deliberately lock-free, and the main thread composes and
     * measures against the global snapshot — so anything that writes a list more than
     * once also needs one atomic apply: see [applySort] and [loadActiveNodes].
     *
     * Lock order is NodeRepository → SettingsStore (via [rememberSelectedSub]);
     * never take this lock from inside a `SettingsStore.update` transform.
     */
    private val lock = Any()

    private var loaded = false

    /**
     * Opened once the initial disk load has finished. A dedicated latch rather than
     * a task queued behind the loader, so waiting for "are my nodes available" does
     * not also wait for whatever else the executor is holding.
     */
    private val loadComplete = java.util.concurrent.CountDownLatch(1)

    private fun store() = DurableFile(File(AppContext.context.filesDir, "subscriptions.json"))

    /** Read saved subscriptions from disk once at startup. */
    fun load() {
        if (loaded) return
        loaded = true
        io.execute {
            try {
                val saved = when (val read = store().read { SubscriptionCodec.decode(it) }) {
                    is DurableFile.Read.Ok -> read.value
                    is DurableFile.Read.Recovered -> {
                        Logs.w("Nodes", "subscriptions.json was unreadable; recovered from backup copy")
                        read.value
                    }
                    is DurableFile.Read.Missing -> emptyList()
                    is DurableFile.Read.Corrupt -> {
                        Logs.e("Nodes", "subscriptions.json unreadable; saved nodes are not available", read.cause)
                        loadFailed = true
                        emptyList()
                    }
                }
                synchronized(lock) {
                    // One apply: the group strip and the groups list are keyed lists over
                    // this, and they compose on the main thread while this runs on the IO one.
                    Snapshot.withMutableSnapshot {
                        subscriptions.clear()
                        subscriptions.addAll(saved)
                    }
                    // Restore the global selection: the remembered owner sub, or the first.
                    val storedSel = SettingsStore.data.selectedSubId
                    val selSub = saved.firstOrNull { it.id == storedSel } ?: saved.firstOrNull()
                    selectedSubId = selSub?.id
                    // And open on that group rather than on whichever one happens to be
                    // first. The group strip is the servers screen's navigation now, so the
                    // tab it lands on is a statement: the group holding the node Connect
                    // would dial is the only one that is true on a cold start.
                    activeSubId = selSub?.id ?: saved.firstOrNull()?.id
                    selectedId = selSub?.let { sub ->
                        sub.selectedNodeId.takeIf { sel -> sub.nodes.any { it.id == sel } }
                            ?: sub.nodes.firstOrNull()?.id
                    }
                    loadActiveNodes()
                }
                maybeAutoUpdate(saved)
            } finally {
                // Always release waiters: a throw here used to leave every connect
                // path blocked on a load that had already given up.
                loadComplete.countDown()
            }
        }
    }

    /**
     * If auto-update is on, re-fetch any subscription whose age exceeds the
     * configured interval. Queued on [net] so the fetches cannot delay the disk
     * executor or [awaitLoaded].
     */
    private fun maybeAutoUpdate(saved: List<Subscription>) {
        if (!SettingsStore.data.autoUpdate) return
        val intervalMs = SettingsStore.data.autoUpdateInterval.coerceAtLeast(15) * 60_000L
        val now = System.currentTimeMillis()
        val stale = saved.filter { it.url.isNotBlank() && now - it.updatedAt > intervalMs }
        for (sub in stale) {
            net.execute { refreshSubscription(sub.id) }
        }
    }

    /** Re-fetch one subscription by id without changing the active selection. */
    private fun refreshSubscription(id: String) {
        try {
            refreshSubscriptionOrThrow(id)
        } catch (e: Exception) {
            // Notify the user a background update failed; working nodes are kept
            // (the fetch/decode throws before any node mutation).
            status = StatusMessage.Text(R.string.status_auto_update_failed, listOf(e.message.orEmpty()))
        }
    }

    private fun refreshSubscriptionOrThrow(id: String) {
        val sub = synchronized(lock) { subscriptions.firstOrNull { it.id == id } } ?: return
        // Fetch outside the lock: this blocks for up to HTTP_TOTAL_TIMEOUT_MS and
        // must not stall the UI thread's reads of the node list.
        val text = httpGet(sub.url.trim())
        val parsed = SubscriptionDecoder.decode(text)
        val entries = parsed.mapIndexed { index, node -> NodeEntry(index, node) }
        // Resilient update: a fetch that yields no nodes (empty body, error page,
        // unparseable payload) must not wipe the user's working nodes — keep them.
        if (SubscriptionUpdate.isResilientFailure(entries.size, sub.nodes.size)) {
            error("update produced no nodes; kept ${sub.nodes.size} working node(s)")
        }
        synchronized(lock) {
            val index = subscriptions.indexOfFirst { it.id == id }
            if (index < 0) return
            // Re-read under the lock: another mutation may have changed the sub
            // while the fetch was in flight.
            val current = subscriptions[index]
            val priorEntry = current.nodes.firstOrNull { it.id == current.selectedNodeId }
            val priorKey = priorEntry?.node?.dedupKey
            val kept = carryFavourites(current.nodes, entries)
            val selected = kept.firstOrNull { it.node.dedupKey == priorKey } ?: kept.firstOrNull()
            subscriptions[index] = current.copy(
                updatedAt = System.currentTimeMillis(),
                nodes = kept,
                selectedNodeId = selected?.id ?: -1,
            )
            if (id == activeSubId) loadActiveNodes()
            if (id == selectedSubId) selectedId = selected?.id
            persist()
            // The node the user picked may be gone from the feed, in which case the
            // selection silently moved to a different server — the connect button
            // would then dial somewhere the user never chose. Say so.
            if (priorKey != null && selected?.node?.dedupKey != priorKey) {
                val from = priorEntry.node.displayName
                val to = selected?.node?.displayName
                status = if (to != null) {
                    StatusMessage.Text(R.string.status_selected_changed, listOf(from, to))
                } else {
                    StatusMessage.Text(R.string.status_selected_gone, listOf(from))
                }
                Logs.w("Nodes", "subscription update reassigned the selected node")
            }
        }
    }

    /**
     * Block until the initial disk load has completed, bounded by
     * [LOAD_WAIT_TIMEOUT_MS]. Called from the QS tile, the boot receiver, always-on
     * start and [ControlActivity] — paths with no UI to show a spinner, but also no
     * business hanging forever on a stuck disk.
     */
    fun awaitLoaded() {
        load()
        val done = runCatching {
            loadComplete.await(LOAD_WAIT_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
        }.getOrDefault(false)
        if (!done) Logs.w("Nodes", "awaitLoaded timed out after ${LOAD_WAIT_TIMEOUT_MS}ms")
    }

    fun activeSubscription(): Subscription? = subscriptions.firstOrNull { it.id == activeSubId }

    /**
     * The globally selected node, regardless of which subscription is on screen.
     * For the active subscription the live working set is authoritative; for any
     * other subscription the folded (persisted) node list is used.
     */
    fun selected(): NodeEntry? {
        val subId = selectedSubId ?: return null
        return if (subId == activeSubId) {
            nodes.firstOrNull { it.id == selectedId }
        } else {
            subscriptions.firstOrNull { it.id == subId }?.nodes?.firstOrNull { it.id == selectedId }
        }
    }

    /**
     * Nodes of the subscription that owns the global selection (the live working
     * set when it is the active subscription, otherwise its folded list). Used by
     * auto-switch to enumerate failover candidates.
     */
    fun selectedSubNodes(): List<NodeEntry> {
        val subId = selectedSubId ?: return emptyList()
        return if (subId == activeSubId) nodes.toList()
        else subscriptions.firstOrNull { it.id == subId }?.nodes ?: emptyList()
    }

    fun select(id: Int): Unit = synchronized(lock) {
        if (nodes.any { it.id == id }) {
            selectedSubId = activeSubId
            selectedId = id
            // Lambda form: the interpolation walks the whole working set, and this runs on
            // the UI thread on every tap on a server row. The journal ships off, so with the
            // eager overload that scan was pure loss.
            Logs.i("Nodes") { "selected node id=$id (${nodes.firstOrNull { it.id == id }?.node?.displayName})" }
            rememberSelectedSub()
            persist()
        } else {
            Logs.w("Nodes", "select ignored: no node id=$id")
        }
    }

    /**
     * Set the global selection to [nodeId] within [subId] without requiring it to
     * be the active (on-screen) subscription — auto-switch may pick a node from the
     * selected group while another group is being browsed.
     */
    fun selectNode(subId: String?, nodeId: Int): Unit = synchronized(lock) {
        selectedSubId = subId
        selectedId = nodeId
        rememberSelectedSub()
        persist()
    }

    fun selectSubscription(id: String) {
        synchronized(lock) {
            if (id == activeSubId) return
            foldActiveNodes()
            activeSubId = id
            loadActiveNodes()
            Logs.i("Nodes") { "active subscription -> $id (${activeSubscription()?.name})" }
            persist()
        }
    }

    // ---- groups (manual or subscription) ----

    /** A subscription with no URL is a manual group: nodes are user-managed. */
    fun createGroup(name: String): Subscription = synchronized(lock) {
        val group = Subscription(
            id = UUID.randomUUID().toString(),
            name = name.ifBlank { "group" },
            url = "",
            updatedAt = System.currentTimeMillis(),
            nodes = emptyList(),
        )
        foldActiveNodes()
        subscriptions.add(group)
        activeSubId = group.id
        loadActiveNodes()
        persist()
        Logs.i("Nodes", "created manual group '${group.name}'")
        group
    }

    /**
     * Edit a group's name and URL. Setting a URL on a manual group turns it into
     * a subscription (and fetches it); clearing the URL turns a subscription into
     * a manual group, keeping its current nodes.
     */
    fun updateGroup(id: String, name: String, url: String) {
        val refresh: Boolean
        synchronized(lock) {
            val index = subscriptions.indexOfFirst { it.id == id }
            if (index < 0) return
            val prev = subscriptions[index]
            val newUrl = url.trim()
            subscriptions[index] = prev.copy(name = name.ifBlank { prev.name }, url = newUrl)
            persist()
            refresh = newUrl.isNotBlank() && newUrl != prev.url
        }
        if (refresh) net.execute { refreshSubscriptionReporting(id) }
    }

    /** Like [refreshSubscription] but surfaces errors via [status]. */
    private fun refreshSubscriptionReporting(id: String) {
        if (importing) return
        importing = true
        status = StatusMessage.Text(R.string.status_updating)
        try {
            refreshSubscriptionOrThrow(id)
            val count = subscriptions.firstOrNull { it.id == id }?.nodes?.size ?: 0
            status = StatusMessage.Count(R.plurals.status_updated, count)
        } catch (e: Exception) {
            status = StatusMessage.Text(R.string.status_update_failed, listOf(e.message.orEmpty()))
        } finally {
            importing = false
        }
    }

    // ---- single-node operations (manual editing) ----

    private fun nextNodeId(): Int = (nodes.maxOfOrNull { it.id } ?: -1) + 1

    /** Add a node to the active group (creating a default group when none exists). */
    fun addNode(node: ProxyNode): NodeEntry = synchronized(lock) {
        if (activeSubscription() == null) createGroup("local")
        val entry = NodeEntry(nextNodeId(), node)
        nodes.add(entry)
        if (selectedSubId == null) {
            selectedSubId = activeSubId
            selectedId = entry.id
            rememberSelectedSub()
        }
        persist()
        status = StatusMessage.Text(R.string.status_added_one, listOf(node.displayName))
        entry
    }

    /** Add many nodes (clipboard / QR import) to the active group. */
    fun addNodes(parsed: List<ProxyNode>): Int = synchronized(lock) {
        if (parsed.isEmpty()) return 0
        if (activeSubscription() == null) createGroup("local")
        var id = nextNodeId()
        parsed.forEach { nodes.add(NodeEntry(id++, it)) }
        ensureSelectionValid()
        persist()
        status = StatusMessage.Count(R.plurals.status_added, parsed.size)
        parsed.size
    }

    fun updateNode(id: Int, node: ProxyNode): Unit = synchronized(lock) {
        val index = nodes.indexOfFirst { it.id == id }
        if (index >= 0) {
            nodes[index] = nodes[index].copy(node = node, latencyMs = LATENCY_UNTESTED)
            persist()
        }
    }

    fun cloneNode(id: Int) {
        synchronized(lock) {
            val entry = nodes.firstOrNull { it.id == id } ?: return
            val copyName = entry.node.name.ifBlank { entry.node.displayName } + " (copy)"
            nodes.add(NodeEntry(nextNodeId(), entry.node.copy(name = copyName)))
            persist()
        }
    }

    fun deleteNode(id: Int): Unit = synchronized(lock) {
        nodes.removeAll { it.id == id }
        ensureSelectionValid()
        persist()
    }

    /** Move a node from the active group into another group. */
    fun moveNode(id: Int, targetGroupId: String) {
        synchronized(lock) {
            if (targetGroupId == activeSubId) return
            val entry = nodes.firstOrNull { it.id == id } ?: return
            val targetIndex = subscriptions.indexOfFirst { it.id == targetGroupId }
            if (targetIndex < 0) return
            val target = subscriptions[targetIndex]
            val newId = (target.nodes.maxOfOrNull { it.id } ?: -1) + 1
            subscriptions[targetIndex] = target.copy(nodes = target.nodes + NodeEntry(newId, entry.node))
            nodes.removeAll { it.id == id }
            ensureSelectionValid()
            persist()
            status = StatusMessage.Text(R.string.status_moved, listOf(target.name))
        }
    }

    fun deleteSubscription(id: String): Unit = synchronized(lock) {
        subscriptions.removeAll { it.id == id }
        if (selectedSubId == id) {
            // The selection's owner is gone — fall back to the first remaining sub.
            // (No `it.id != id` filter: the removeAll above already dropped it.)
            val fallback = subscriptions.firstOrNull()
            selectedSubId = fallback?.id
            selectedId = fallback?.let { sub ->
                sub.selectedNodeId.takeIf { sel -> sub.nodes.any { it.id == sel } }
                    ?: sub.nodes.firstOrNull()?.id
            }
            rememberSelectedSub()
        }
        if (activeSubId == id) {
            activeSubId = subscriptions.firstOrNull()?.id
            loadActiveNodes()
        }
        persist()
    }

    /**
     * Import (or refresh) a subscription by URL; becomes the active one.
     *
     * [subId] names the group to refresh. Pass it whenever the target is known
     * (any refresh of an existing group): matching by URL alone merged two
     * distinct groups that happened to share a URL — refreshing one silently
     * overwrote the other, including its name and selected node. URL matching is
     * kept for the add-by-URL path, where re-adding a known URL should still
     * update the existing group rather than duplicate it.
     */
    fun importFromUrl(url: String, name: String = deriveName(url), subId: String? = null) {
        if (importing) return
        importing = true
        status = StatusMessage.Text(R.string.status_importing)
        net.execute {
            try {
                // Fetch first, outside the lock: it blocks for up to
                // HTTP_TOTAL_TIMEOUT_MS and the UI keeps reading the node list.
                val text = httpGet(url.trim())
                val parsed: List<ProxyNode> = SubscriptionDecoder.decode(text)
                val entries = parsed.mapIndexed { index, node -> NodeEntry(index, node) }
                synchronized(lock) {
                    applyImport(url.trim(), name, subId, entries)
                }
                status = StatusMessage.Count(R.plurals.status_imported, entries.size)
            } catch (e: Exception) {
                status = StatusMessage.Text(R.string.status_import_failed, listOf(e.message.orEmpty()))
            } finally {
                importing = false
            }
        }
    }

    /** Fold a fetched node set into the store. Caller holds [lock]. */
    private fun applyImport(url: String, name: String, subId: String?, entries: List<NodeEntry>) {
        val existing = if (subId != null) {
            subscriptions.indexOfFirst { it.id == subId }
        } else {
            subscriptions.indexOfFirst { it.url == url }
        }
        // On refresh, keep the user's selected node if it still exists.
        val priorNodes = if (existing >= 0) subscriptions[existing].nodes else emptyList()
        val priorSelectedKey = if (existing >= 0) {
            val prior = subscriptions[existing]
            prior.nodes.firstOrNull { it.id == prior.selectedNodeId }?.node?.dedupKey
        } else null
        // Resilient update: never let a zero-node fetch wipe working nodes.
        val priorCount = priorNodes.size
        if (SubscriptionUpdate.isResilientFailure(entries.size, priorCount)) {
            error("update produced no nodes; kept $priorCount working node(s)")
        }
        val kept = carryFavourites(priorNodes, entries)
        val selected = kept.firstOrNull { it.node.dedupKey == priorSelectedKey }
            ?: kept.firstOrNull()
        val sub = Subscription(
            id = if (existing >= 0) subscriptions[existing].id else UUID.randomUUID().toString(),
            name = name,
            url = url,
            updatedAt = System.currentTimeMillis(),
            nodes = kept,
            selectedNodeId = selected?.id ?: -1,
        )
        if (existing >= 0) subscriptions[existing] = sub else subscriptions.add(sub)
        activeSubId = sub.id
        if (sub.id == selectedSubId) selectedId = selected?.id
        loadActiveNodes()
        persist()
    }

    /**
     * Record a latency measurement for node [id] of subscription [subId].
     *
     * [subId] is checked against the group currently on screen: node ids are
     * per-group small integers, so a batch that finished after the user switched
     * groups used to stamp its timings onto whichever nodes happened to hold the
     * same ids in the new group. `null` skips the check (single-node re-test of a
     * node the caller just read from the live set).
     */
    fun setLatency(id: Int, latencyMs: Int, subId: String? = null): Unit = synchronized(lock) {
        if (subId != null && subId != activeSubId) return
        val index = indexOfNode(id)
        if (index >= 0) nodes[index] = nodes[index].copy(latencyMs = latencyMs)
    }

    /**
     * Position of the node with [id] in [nodes], or -1. Callers must hold [lock].
     *
     * A cached id→position map, because the linear scan this replaces was called once
     * per node twice over during a sweep — to mark the row in flight and again when its
     * result landed — which made opening a batch over a large subscription quadratic.
     *
     * The cached position is **verified rather than trusted**: every structural mutation
     * of [nodes] would otherwise have to remember to invalidate the map, and the one that
     * forgot would write a measurement onto the wrong server. A stale entry fails the
     * identity check in O(1) and falls through to a rebuild, which repairs the whole map
     * at once — so a sort that shuffles every position costs exactly one rebuild.
     */
    private fun indexOfNode(id: Int): Int {
        idIndex?.get(id)?.let { cached ->
            if (cached < nodes.size && nodes[cached].id == id) return cached
        }
        val rebuilt = HashMap<Int, Int>(nodes.size)
        nodes.forEachIndexed { index, entry -> rebuilt[entry.id] = index }
        idIndex = rebuilt
        return rebuilt[id] ?: -1
    }

    /**
     * Mark every node in the active group as "probe in flight", in one pass.
     *
     * [subId] is checked the same way [setLatency] checks it: a sweep opened against one
     * group must not stamp another one the user switched to in the meantime.
     */
    fun markAllTesting(subId: String? = null): Unit = synchronized(lock) {
        if (subId != null && subId != activeSubId) return
        restamp(from = null, to = LATENCY_TESTING)
    }

    /** Reset every in-flight badge to untested — a stopped sweep, in one pass. */
    fun clearTesting(): Unit = synchronized(lock) {
        restamp(from = LATENCY_TESTING, to = LATENCY_UNTESTED)
    }

    /**
     * Stamp [to] onto every node whose latency is [from] (`null` meaning "any but [to]"),
     * as **one** snapshot apply.
     *
     * Written slot by slot outside a snapshot, each assignment is its own global write with
     * its own round of invalidations — a thousand of them for a thousand-node group, from the
     * UI thread, before the frame that shows the progress bar can be drawn. Inside
     * `withMutableSnapshot` the observers are notified once, and they see either the whole
     * new set of badges or none of them. Unchanged rows keep their instance, so the list stays
     * a permutation of itself and only the badges that moved recompose.
     */
    private fun restamp(from: Int?, to: Int) {
        Snapshot.withMutableSnapshot {
            nodes.forEachIndexed { index, entry ->
                val hit = if (from == null) entry.latencyMs != to else entry.latencyMs == from
                if (hit) nodes[index] = entry.copy(latencyMs = to)
            }
        }
    }

    fun setTestStatus(message: StatusMessage?) {
        status = message
    }

    /**
     * Re-apply the chosen ordering once a batch of measurements has landed.
     *
     * A no-op unless the user picked latency order: in `Manual` and `Name` the numbers
     * change and the rows must not. With `Latency` chosen, the *mode* was already
     * remembered and re-applied on every load and group switch — what was missing is this:
     * new timings arrived through [setLatency] and nobody re-sorted, so "test all" left the
     * list in the order the *previous* measurements had produced and the user had to pick
     * the sort again to see the result of the test they had just run.
     *
     * Deliberately called at the end of a batch rather than per result. Re-sorting 788 rows
     * under a finger for the length of a full sweep is a list nobody can use, and a row that
     * moves between a press and a release is a tap on the wrong server.
     */
    fun resortAfterTest() {
        // Read the mode outside the lock: the documented lock order is repository ->
        // settings, never the other way round.
        if (SettingsStore.data.sortMode != SortMode.Latency) return
        synchronized(lock) {
            applySort(SortMode.Latency)
            persist()
        }
    }

    /**
     * Star or unstar a node. Re-applies the ordering so the row moves immediately: a
     * star that changes nothing on screen reads as a control that did not work.
     */
    fun toggleFavorite(id: Int): Unit = synchronized(lock) {
        val index = nodes.indexOfFirst { it.id == id }
        if (index < 0) return
        nodes[index] = nodes[index].copy(favorite = !nodes[index].favorite)
        applySort(SettingsStore.data.sortMode)
        persist()
    }

    /**
     * Carry the user's stars from [previous] onto a freshly fetched node set, matched
     * by connection identity rather than by id (ids are per-group positions and a feed
     * reorders freely). Without this, every subscription update silently cleared every
     * star the user had set.
     */
    private fun carryFavourites(previous: List<NodeEntry>, fetched: List<NodeEntry>): List<NodeEntry> {
        val starred = previous.filter { it.favorite }.map { it.node.dedupKey }.toHashSet()
        if (starred.isEmpty()) return fetched
        return fetched.map { entry ->
            if (entry.node.dedupKey in starred) entry.copy(favorite = true) else entry
        }
    }

    /** Reorder nodes by ascending latency; untested/failed sink to the bottom. */
    fun sortByLatency() {
        SettingsStore.update { it.copy(sortMode = SortMode.Latency) }
        synchronized(lock) {
            applySort(SortMode.Latency)
            persist()
        }
    }

    /** Reorder nodes alphabetically by display name. */
    fun sortByName() {
        SettingsStore.update { it.copy(sortMode = SortMode.Name) }
        synchronized(lock) {
            applySort(SortMode.Name)
            persist()
        }
    }

    /**
     * Reorder the working set in place per [mode]. The rules live in [NodeOrdering],
     * which is pure and unit-tested; what stays here is *how* the result is written.
     *
     * **One atomic apply.** This runs on the URL-test pool as well as on the UI thread,
     * and the main thread composes and *measures* against the global snapshot, so every
     * write it can observe separately is a state it can render. Neither shape of
     * piecewise write is safe: `clear()` then `addAll()` exposes an empty list, which
     * dropped the servers screen into its "this group is empty" branch for a frame and
     * disposed the `LazyColumn` along with the scroll position; and writing the
     * permutation slot by slot exposes a list holding one entry **twice** — `[A,B,C]`
     * becoming `[B,C,A]` passes through `[B,B,C]` — which is a duplicate `LazyColumn`
     * key, and that is an `IllegalArgumentException` on the main thread, i.e. the
     * process and the running tunnel gone. So the reorder and [orderRevision] land
     * inside one `Snapshot.withMutableSnapshot`: observers see the whole new order or
     * the whole old one, and they never see the order without the revision that
     * announces it (which is what `ServersScrollPin` relies on to capture an anchor
     * from before the move).
     *
     * Inside that snapshot it is still written as a permutation rather than a
     * clear-and-refill: the sort is a permutation of the list's own contents, so the
     * size never changes, and rewriting only the slots that moved is what lets
     * `animateItem` read the change as rows moving.
     *
     * And **silent when nothing moved**: rewriting every slot with the value already
     * there still makes `LazyColumn` re-resolve every key, and it would bump
     * [orderRevision] — the signal the screen uses to decide its viewport needs pinning.
     * [announce] is false for [loadActiveNodes], which does not reorder a set the user
     * is looking at; it replaces one set with another.
     */
    private fun applySort(mode: SortMode, announce: Boolean = true) {
        val sorted = NodeOrdering.sorted(nodes, mode)
        if (NodeOrdering.isSameOrder(nodes, sorted)) return
        // No `return` inside this block: withMutableSnapshot applies in a `finally`-less
        // path, so a non-local return out of an inline snapshot silently discards the
        // writes.
        Snapshot.withMutableSnapshot {
            // Size-guarded even though `sorted` is a permutation of `nodes` taken under
            // this same lock and so cannot differ in length: an IndexOutOfBounds here
            // would be an uncaught exception on a URL-test pool thread, and that is
            // exactly the crash that killed the process — and with it the running
            // tunnel — the last time this list was written from two threads.
            if (sorted.size != nodes.size) {
                nodes.clear()
                nodes.addAll(sorted)
            } else {
                sorted.forEachIndexed { index, entry ->
                    if (nodes[index] !== entry) nodes[index] = entry
                }
            }
            if (announce) orderRevision++
        }
    }

    /** Re-fetch the active subscription from its URL, replacing its nodes. */
    fun updateActiveSubscription() {
        val sub = activeSubscription() ?: return
        if (sub.url.isBlank()) {
            status = StatusMessage.Text(R.string.status_manual_group)
            return
        }
        // By id: refreshing this group must not touch another group with the same URL.
        importFromUrl(sub.url, sub.name, sub.id)
    }

    /**
     * Drop nodes that share a connection identity with an earlier node, keeping
     * the first occurrence. Returns nothing; updates the working set in place.
     */
    fun removeDuplicates(): Unit = synchronized(lock) {
        val seen = HashSet<String>()
        val unique = nodes.filter { seen.add(it.node.dedupKey) }
        val removed = nodes.size - unique.size
        if (removed > 0) {
            nodes.clear()
            nodes.addAll(unique)
            ensureSelectionValid()
            persist()
        }
        status = if (removed > 0) {
            StatusMessage.Count(R.plurals.status_dups_removed, removed)
        } else {
            StatusMessage.Text(R.string.status_dups_none)
        }
    }

    /** Remove every node that failed its latency test (timeout). */
    fun deleteUnavailable(): Unit = synchronized(lock) {
        val kept = nodes.filter { it.latencyMs != LATENCY_FAILED }
        val removed = nodes.size - kept.size
        if (removed > 0) {
            nodes.clear()
            nodes.addAll(kept)
            ensureSelectionValid()
            persist()
        }
        status = if (removed > 0) {
            StatusMessage.Count(R.plurals.status_dead_removed, removed)
        } else {
            StatusMessage.Text(R.string.status_dead_none)
        }
    }

    /** Empty the active subscription's node list (keeps the subscription). */
    fun clearActiveNodes() {
        synchronized(lock) {
            if (activeSubId == null) return
            nodes.clear()
            if (selectedSubId == activeSubId) selectedId = null
            persist()
            status = StatusMessage.Text(R.string.status_cleared)
        }
    }

    /** Replace all groups (backup restore). The first group becomes active. */
    fun replaceAll(newSubs: List<Subscription>): Unit = synchronized(lock) {
        subscriptions.clear()
        subscriptions.addAll(newSubs)
        activeSubId = newSubs.firstOrNull()?.id
        selectedSubId = null
        selectedId = null
        loadActiveNodes()
        rememberSelectedSub()
        persist()
        status = StatusMessage.Count(R.plurals.status_restored, newSubs.size)
    }

    /**
     * Keep the selection sane after mutations of the active sub's node set. Only
     * relevant when the selection lives in the active subscription; a selection
     * owned by another subscription is untouched.
     */
    private fun ensureSelectionValid() {
        if (selectedSubId != activeSubId) return
        if (nodes.none { it.id == selectedId }) {
            selectedId = nodes.firstOrNull()?.id
        }
    }

    private fun loadActiveNodes() {
        val sub = activeSubscription()
        // Atomic for the same reason as [applySort]: this runs on the IO thread at
        // startup and on the network thread for a subscription refresh, and the main
        // thread's measure pass reads `size` and then elements against the global
        // snapshot — a `clear()` landing between those two reads is an
        // IndexOutOfBoundsException on the main thread.
        Snapshot.withMutableSnapshot {
            nodes.clear()
            if (sub != null) nodes.addAll(sub.nodes)
        }
        // Reapply the persisted ordering so a chosen sort survives restarts and
        // follows the on-screen subscription. Manual leaves the saved order intact.
        // Silent: this is a new set, not a reorder of the one the user was looking at,
        // so the servers screen must not pin its scroll to an index from another group.
        applySort(SettingsStore.data.sortMode, announce = false)
        // The global selection does not follow the on-screen subscription. Only
        // adopt one when nothing is selected anywhere yet.
        if (selectedSubId == null && sub != null) {
            selectedSubId = sub.id
            selectedId = sub.selectedNodeId.takeIf { sel -> sub.nodes.any { it.id == sel } }
                ?: sub.nodes.firstOrNull()?.id
            rememberSelectedSub()
        }
    }

    private fun foldActiveNodes() {
        val index = subscriptions.indexOfFirst { it.id == activeSubId }
        if (index >= 0) {
            subscriptions[index] = subscriptions[index].copy(
                nodes = nodes.toList(),
                selectedNodeId = if (selectedSubId == activeSubId) selectedId ?: -1
                else subscriptions[index].selectedNodeId,
            )
        }
    }

    /** Persist which subscription owns the global selection. */
    private fun rememberSelectedSub() {
        val id = selectedSubId.orEmpty()
        if (SettingsStore.data.selectedSubId != id) {
            SettingsStore.update { it.copy(selectedSubId = id) }
        }
    }

    /**
     * Fold the working set back into its subscription and queue a durable write.
     * Callers must hold [lock] — the snapshot has to be taken atomically with the
     * mutation that prompted it, or a concurrent change can be persisted twice or
     * lost. Encoding happens on the IO thread, which is single-threaded, so the
     * writes still land in the order the states were adopted.
     */
    private fun persist() {
        foldActiveNodes()
        // Snapshot under the lock, encode on the IO thread. The *snapshot* has to be
        // atomic with the mutation that prompted it; the encoding does not, and it walks
        // every node of every group. Both `select()` and `toggleFavorite()` are UI-thread
        // taps, so a large subscription used to serialise its entire credential set on
        // the main thread every time the user tapped a server row or a star. The list
        // itself holds immutable `Subscription` values, so the copy is safe to hand
        // across threads.
        //
        // **Coalesced, and ordered by [pending] rather than by submission.** One gesture
        // can adopt several states — `toggleFavorite` mutates, sorts and persists; the end
        // of a sweep re-sorts and persists — and each of those used to queue a full encode
        // of every credential in every group. The newest snapshot is published here, under
        // the lock, and whichever queued writer reaches it first takes it; the others find
        // nothing left to do. That also closes a hole the old comment claimed was covered:
        // `io` being single-threaded orders the *tasks*, but two callers can leave this lock
        // and reach `io.execute` in the opposite order, which wrote the older state last.
        pending.set(subscriptions.toList())
        io.execute {
            val snapshot = pending.getAndSet(null) ?: return@execute
            try {
                store().write(SubscriptionCodec.encode(snapshot))
            } catch (e: Exception) {
                // Persisting node credentials is not optional: if it fails the user
                // must know their edit only lives in memory.
                Logs.e("Nodes", "persist failed", e)
                status = StatusMessage.Text(R.string.status_save_failed, listOf(e.message.orEmpty()))
                // Unless a newer state has arrived, leave this one pending so the next
                // write retries it rather than dropping the user's edit on the floor.
                pending.compareAndSet(null, snapshot)
            }
        }
    }

    /** Host-based default group name — see [SubscriptionUpdate.deriveName]. */
    private fun deriveName(url: String): String = SubscriptionUpdate.deriveName(url)

    private fun httpGet(urlStr: String): String {
        // Subscription bodies carry every node's credentials. Restrict fetches to
        // http/https so a crafted url can't reach file://, content:// or other
        // schemes; cleartext http is additionally blocked by the network security
        // config, so in practice this resolves to https. Redirects are followed
        // manually so each hop's scheme is re-validated — auto-follow would let a
        // hostile server bounce us to a non-http(s) target or an internal host.
        var current = urlStr
        var redirects = 0
        // One deadline for the whole exchange. Per-hop timeouts alone let a hostile
        // (or merely broken) server hold the fetch for redirects x 2 x per-hop by
        // stalling each hop just short of its limit — minutes of an occupied
        // executor, and with it a stuck "Updating…" the user cannot clear.
        val deadline = System.currentTimeMillis() + HTTP_TOTAL_TIMEOUT_MS
        while (true) {
            val url = URL(current)
            val scheme = url.protocol.lowercase()
            if (scheme != "https" && scheme != "http") {
                throw IllegalArgumentException("unsupported subscription scheme: $scheme (use https)")
            }
            val budget = deadline - System.currentTimeMillis()
            if (budget <= 0) error("subscription fetch timed out")
            val hopTimeout = budget.coerceAtMost(HTTP_HOP_TIMEOUT_MS).toInt()
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = hopTimeout
                readTimeout = hopTimeout
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", SettingsStore.data.subscriptionUserAgent.ifBlank { "YukariBox/0.1" })
            }
            try {
                val code = conn.responseCode
                if (code in 301..308 && code != 304) {
                    val location = conn.getHeaderField("Location")
                        ?: throw IllegalStateException("redirect ($code) without Location")
                    if (++redirects > MAX_REDIRECTS) throw IllegalStateException("too many redirects")
                    // Resolve relative redirects against the current URL, then re-check scheme above.
                    current = URL(url, location).toString()
                    continue
                }
                if (code !in 200..299) throw IllegalStateException("HTTP $code")
                // Bound the body: a hostile server must not be able to OOM the app.
                return conn.inputStream.use { input ->
                    val out = java.io.ByteArrayOutputStream()
                    val buf = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > MAX_SUBSCRIPTION_BYTES) {
                            throw IllegalStateException("subscription too large (> ${MAX_SUBSCRIPTION_BYTES / 1024 / 1024} MB)")
                        }
                        // The read timeout only bounds the gap between packets, so a
                        // server dripping one byte just inside it could stream for
                        // hours under the size cap. The deadline covers the body too.
                        if (System.currentTimeMillis() > deadline) error("subscription fetch timed out")
                        out.write(buf, 0, n)
                    }
                    out.toString("UTF-8")
                }
            } finally {
                conn.disconnect()
            }
        }
    }

    private const val MAX_SUBSCRIPTION_BYTES = 8L * 1024 * 1024
    private const val MAX_REDIRECTS = 5

    /** Per-hop connect/read timeout. */
    private const val HTTP_HOP_TIMEOUT_MS = 15_000L

    /** Ceiling for one whole fetch, redirects and body included. */
    private const val HTTP_TOTAL_TIMEOUT_MS = 45_000L

    /**
     * How long a connect path will wait for the initial load. Generous enough for a
     * cold disk, short enough that a wedged load surfaces as a failed connect
     * instead of a frozen QS tile.
     */
    private const val LOAD_WAIT_TIMEOUT_MS = 10_000L
}
