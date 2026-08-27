package dev.yukaribox.vpn.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import dev.yukaribox.vpn.R
import dev.yukaribox.vpn.data.NodeRepository

/**
 * `815 servers · 2 groups` — what this install actually holds, in one sentence.
 *
 * Two surfaces show it: the drawer header and the profile summary card, both in place of
 * the mockup's `Free Plan`. It lives here rather than in either of them because they had
 * a pair of plurals each and were free to drift — the same two numbers described two
 * different ways is a defect the user can see, and the empty case (which says so instead
 * of printing two zeros) is exactly the branch that gets forgotten in the second copy.
 *
 * Reads [NodeRepository.subscriptions] from composition, so both surfaces follow a
 * subscription refresh without either of them subscribing to anything.
 */
@Composable
internal fun libraryCounts(): String {
    val groups = NodeRepository.subscriptions
    val servers = groups.sumOf { it.nodes.size }
    if (servers == 0) return stringResource(R.string.counts_empty)
    return stringResource(
        R.string.counts_pair,
        pluralStringResource(R.plurals.counts_servers, servers, servers),
        pluralStringResource(R.plurals.counts_groups, groups.size, groups.size),
    )
}
