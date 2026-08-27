package dev.yukaribox.vpn.ui.kit

import androidx.compose.ui.graphics.vector.ImageVector

/**
 * One destination in [InkBottomBar].
 *
 * Its own file because the bar's file holds only functions, and a single public data
 * class in a file named after the bar would be a name mismatch — the same reason
 * `DrawerItem` sits with the navigation model rather than with the drawer's layout.
 */
data class NavDestination(
    val icon: ImageVector,
    val label: String,
    /** Defaults to the label: the tab shows both a glyph and its word. */
    val contentDescription: String = label,
)
