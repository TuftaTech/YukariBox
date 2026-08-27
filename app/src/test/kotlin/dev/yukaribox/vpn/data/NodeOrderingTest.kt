package dev.yukaribox.vpn.data

import dev.yukaribox.vpn.core.SortMode
import dev.yukaribox.vpn.proxy.ProxyNode
import dev.yukaribox.vpn.proxy.ProxyType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The ordering rules behind the servers list, and the two properties the screen's scroll
 * pin rests on: a sort is a permutation of the same instances, and it reports "changed"
 * only when something actually moved.
 */
class NodeOrderingTest {

    private fun entry(id: Int, name: String, latencyMs: Int = LATENCY_UNTESTED, favorite: Boolean = false) =
        NodeEntry(
            id = id,
            node = ProxyNode(
                type = ProxyType.VLESS,
                name = name,
                server = "n$id.example.com",
                port = 443,
                uuid = "00000000-0000-0000-0000-00000000000$id",
            ),
            latencyMs = latencyMs,
            favorite = favorite,
        )

    private fun ids(nodes: List<NodeEntry>) = nodes.map { it.id }

    // ---- permutation identity ----------------------------------------------------

    @Test
    fun sortedReturnsTheSameInstances() {
        val nodes = listOf(entry(0, "b", 120), entry(1, "a", 40), entry(2, "c", 80))
        val sorted = NodeOrdering.sorted(nodes, SortMode.Latency)

        assertEquals(nodes.size, sorted.size)
        // Every row in the result is one of the inputs, by identity — that is what lets
        // the repository write the result back slot by slot instead of emptying the list.
        sorted.forEach { row -> assertTrue(nodes.any { it === row }) }
    }

    // ---- idempotence: no reset when the order is already right --------------------

    @Test
    fun latencyOrderIsIdempotent() {
        val nodes = listOf(entry(0, "b", 120), entry(1, "a", 40), entry(2, "c", 80))
        val once = NodeOrdering.sorted(nodes, SortMode.Latency)
        val twice = NodeOrdering.sorted(once, SortMode.Latency)

        assertTrue(NodeOrdering.isSameOrder(once, twice))
    }

    @Test
    fun nameOrderIsIdempotent() {
        val nodes = listOf(entry(0, "Zulu"), entry(1, "alpha"), entry(2, "Mike"))
        val once = NodeOrdering.sorted(nodes, SortMode.Name)

        assertTrue(NodeOrdering.isSameOrder(once, NodeOrdering.sorted(once, SortMode.Name)))
    }

    @Test
    fun manualOrderOfAnUnstarredListChangesNothing() {
        val nodes = listOf(entry(0, "b", 120), entry(1, "a", 40), entry(2, "c", 80))

        // The default mode over a list with no favourites must be a no-op: this is the
        // case that runs on every measurement that lands, and a "changed" verdict here
        // would rewrite every row and move the viewport for nothing.
        assertTrue(NodeOrdering.isSameOrder(nodes, NodeOrdering.sorted(nodes, SortMode.Manual)))
    }

    @Test
    fun manualOrderWithFavouritesAlreadyOnTopChangesNothing() {
        val nodes = listOf(entry(0, "b", favorite = true), entry(1, "a"), entry(2, "c"))

        assertTrue(NodeOrdering.isSameOrder(nodes, NodeOrdering.sorted(nodes, SortMode.Manual)))
    }

    // ---- isSameOrder ------------------------------------------------------------

    @Test
    fun isSameOrderSeesAMovedRow() {
        val nodes = listOf(entry(0, "b", 120), entry(1, "a", 40))

        assertFalse(NodeOrdering.isSameOrder(nodes, NodeOrdering.sorted(nodes, SortMode.Latency)))
    }

    @Test
    fun isSameOrderRejectsASizeMismatch() {
        val nodes = listOf(entry(0, "a"), entry(1, "b"))

        assertFalse(NodeOrdering.isSameOrder(nodes, nodes.drop(1)))
        assertFalse(NodeOrdering.isSameOrder(nodes.drop(1), nodes))
    }

    @Test
    fun isSameOrderComparesIdentityNotValue() {
        val nodes = listOf(entry(0, "a"), entry(1, "b"))
        // Value-equal copies. The repository only ever passes a permutation of its own
        // list, so `===` is exact there; asserting it here keeps a future refactor from
        // quietly swapping in `NodeEntry.equals`, which walks a whole ProxyNode per row.
        val copies = nodes.map { it.copy() }

        assertEquals(nodes, copies)
        assertFalse(NodeOrdering.isSameOrder(nodes, copies))
    }

    // ---- latency ranking --------------------------------------------------------

    @Test
    fun sentinelsSinkBelowEveryMeasurement() {
        val nodes = listOf(
            entry(0, "untested", LATENCY_UNTESTED),
            entry(1, "failed", LATENCY_FAILED),
            entry(2, "slow", 400),
            entry(3, "testing", LATENCY_TESTING),
            entry(4, "fast", 30),
        )

        val sorted = NodeOrdering.sorted(nodes, SortMode.Latency)

        assertEquals(listOf(4, 2), ids(sorted).take(2))
        // The three sentinels share Int.MAX_VALUE, so a stable sort keeps their input
        // order among themselves.
        assertEquals(listOf(0, 1, 3), ids(sorted).drop(2))
    }

    @Test
    fun aDeadServerSinksToTheBottomAndStaysThere() {
        // The reported scenario: the row the user pinged is first, comes back dead, and
        // latency order moves it to the end. Re-sorting the result must then be a no-op,
        // so the screen is asked to pin its viewport exactly once.
        val dead = entry(0, "dead", LATENCY_FAILED)
        val nodes = listOf(dead, entry(1, "a", 40), entry(2, "b", 90))

        val sorted = NodeOrdering.sorted(nodes, SortMode.Latency)

        assertEquals(listOf(1, 2, 0), ids(sorted))
        assertTrue(NodeOrdering.isSameOrder(sorted, NodeOrdering.sorted(sorted, SortMode.Latency)))
    }

    // ---- favourites -------------------------------------------------------------

    @Test
    fun favouritesFloatInEveryMode() {
        val nodes = listOf(
            entry(0, "fast", 20),
            entry(1, "starred slow", 900, favorite = true),
            entry(2, "mid", 100),
        )

        assertEquals(1, ids(NodeOrdering.sorted(nodes, SortMode.Latency)).first())
        assertEquals(1, ids(NodeOrdering.sorted(nodes, SortMode.Name)).first())
        assertEquals(1, ids(NodeOrdering.sorted(nodes, SortMode.Manual)).first())
    }

    @Test
    fun favouritesKeepTheirRelativeOrderWithinTheChosenSort() {
        val nodes = listOf(
            entry(0, "plain", 10),
            entry(1, "star slow", 500, favorite = true),
            entry(2, "star fast", 50, favorite = true),
        )

        // Sorted among themselves by latency, then floated as a block.
        assertEquals(listOf(2, 1, 0), ids(NodeOrdering.sorted(nodes, SortMode.Latency)))
    }

    @Test
    fun nameOrderIgnoresCase() {
        val nodes = listOf(entry(0, "zulu"), entry(1, "Alpha"), entry(2, "mike"))

        assertEquals(listOf(1, 2, 0), ids(NodeOrdering.sorted(nodes, SortMode.Name)))
    }
}
