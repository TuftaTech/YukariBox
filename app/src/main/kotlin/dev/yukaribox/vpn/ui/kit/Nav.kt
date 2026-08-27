package dev.yukaribox.vpn.ui.kit

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.yukaribox.vpn.ui.theme.yukari

/**
 * The two navigation surfaces.
 *
 * [InkBottomBar] is the app's most recognisable element: a solid near-black bar,
 * square, edge to edge, flush with the bottom of the screen. It replaces a floating
 * rounded bar with a raised connect button — the connect control moved onto Home,
 * where the mockup puts it, and the bar went back to being chrome. It stays dark in
 * both themes; a bar that flips to white in dark mode would be the loudest thing on
 * the screen.
 *
 * The servers screen's own tab row is not here: its tabs are the user's groups, so it
 * lives with them in `ui/GroupStrip.kt` rather than in the kit.
 */

/**
 * @param destinations three of them, evenly weighted. The count is the mockup's, and
 *   it is a deliberate ceiling: a fourth tab is the point at which a bottom bar stops
 *   being scannable, which is why everything else lives in the drawer.
 */
@Composable
fun InkBottomBar(
    destinations: List<NavDestination>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxWidth().background(MaterialTheme.yukari.ink)) {
        // A hairline along the top edge, drawn in the bar's own foreground at 8% rather
        // than in the page's hairline grey. Against the light page the bar's fill is
        // separation enough and a light line would be a stripe the reference does not
        // have; in dark theme the bar and the page are two near-blacks and this is the
        // only thing between them.
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(MaterialTheme.yukari.onInk.copy(alpha = EDGE_ALPHA)),
        )
        Row(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .height(BAR_HEIGHT),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            destinations.forEachIndexed { index, destination ->
                NavTab(
                    destination = destination,
                    selected = index == selectedIndex,
                    onClick = { onSelect(index) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun NavTab(
    destination: NavDestination,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val yukari = MaterialTheme.yukari
    // Selection is contrast and weight, not a pill or an indicator: the bar is already
    // the darkest surface in the app, so anything filled inside it would fight it.
    val tint = if (selected) yukari.onInk else yukari.onInkMuted
    Column(
        modifier
            .fillMaxWidth()
            .clickable(role = Role.Tab, onClick = onClick)
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Icon(destination.icon, destination.contentDescription, Modifier.size(TAB_GLYPH), tint = tint)
        Text(
            destination.label,
            style = MaterialTheme.typography.labelSmall.copy(
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            ),
            color = tint,
        )
    }
}

/** Bottom-bar height, excluding the navigation-bar inset it draws behind. */
private val BAR_HEIGHT = 64.dp

/** A bottom-bar tab's filled glyph. */
private val TAB_GLYPH = 20.dp

/** The bar's own top edge, in its foreground colour. */
private const val EDGE_ALPHA = 0.08f

