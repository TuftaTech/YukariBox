package dev.yukaribox.vpn.data

import dev.yukaribox.vpn.core.SortMode

/**
 * The servers list's order, as a pure function of the node set and the chosen
 * [SortMode].
 *
 * Extracted out of [NodeRepository] for the reason every pure layer in this tree is:
 * the repository reaches Android through `AppContext`, `Logs` and the durable store,
 * so these rules could not be asserted anywhere. They are worth asserting, because the
 * servers screen now pins its viewport to "the order actually changed" — a sort that
 * claims a change when nothing moved makes that pin fire on every measurement that
 * lands.
 */
object NodeOrdering {

    /**
     * [nodes] in the order [mode] asks for.
     *
     * A **permutation**: the returned list holds the very same [NodeEntry] instances in
     * a different order, which is what lets [isSameOrder] compare by identity and lets
     * the caller write the result back slot by slot instead of emptying the list first.
     *
     * Favourites float to the top in *every* mode including [SortMode.Manual] — Manual
     * is the default, and a star that only moved a row once the user had also picked a
     * sort would read as a broken control. It is expressed as one comparator per mode
     * (`favourite` first, then the mode's own key) rather than as a sort followed by a
     * stable re-sort on `favorite`: the two are the same order, and one pass copies the
     * list once instead of twice. Every sort here is stable, so rows that tie — and the
     * starred block as a whole — keep their input order.
     */
    fun sorted(nodes: List<NodeEntry>, mode: SortMode): List<NodeEntry> = when (mode) {
        SortMode.Manual -> nodes.sortedByDescending { it.favorite }
        SortMode.Latency -> nodes.sortedWith(
            compareByDescending<NodeEntry> { it.favorite }.thenBy { latencyRank(it.latencyMs) },
        )
        SortMode.Name -> byName(nodes)
    }

    /**
     * Name order, decorate-sort-undecorate.
     *
     * `sortedBy { it.node.displayName.lowercase() }` reads like one `lowercase()` per row and
     * is one per *comparison* — the selector is called from inside the comparator — so a
     * group of ten thousand allocated on the order of 140 000 throwaway strings every time
     * the list was sorted, and `toggleFavorite` sorts on the UI thread. Lowering each name
     * exactly once and sorting the decorated array costs n strings and one array.
     *
     * `Array.sortWith` is `java.util.Arrays.sort`, i.e. TimSort, i.e. stable — which is what
     * keeps equal names (and the whole favourites block) in their input order, the property
     * [sorted]'s contract and `NodeOrderingTest` both rest on.
     */
    private fun byName(nodes: List<NodeEntry>): List<NodeEntry> {
        val keyed = Array(nodes.size) { index -> nodes[index] to nodes[index].node.displayName.lowercase() }
        keyed.sortWith(
            compareByDescending<Pair<NodeEntry, String>> { it.first.favorite }.thenBy { it.second },
        )
        return keyed.map { it.first }
    }

    /**
     * Where [latencyMs] sorts in latency order. Untested, in-flight and failed probes
     * all sink to the bottom instead of ranking as "0 ms, excellent" — the core's
     * urlTest reports a non-positive value for an unreachable node, and the sentinels
     * are negative.
     */
    private fun latencyRank(latencyMs: Int): Int = when (latencyMs) {
        LATENCY_FAILED, LATENCY_UNTESTED, LATENCY_TESTING -> Int.MAX_VALUE
        else -> latencyMs
    }

    /**
     * True when [sorted] is the identity permutation of [current] — i.e. there is
     * nothing to write.
     *
     * By identity rather than by value. [sorted] always comes out of [sorted] over the
     * same list, so `===` is exact here, and it costs one reference compare per row
     * where `NodeEntry.equals` would walk a whole [dev.yukaribox.vpn.proxy.ProxyNode]
     * with its nested TLS and transport blocks for every row on every measurement.
     */
    fun isSameOrder(current: List<NodeEntry>, sorted: List<NodeEntry>): Boolean {
        if (current.size != sorted.size) return false
        return current.indices.all { current[it] === sorted[it] }
    }
}
