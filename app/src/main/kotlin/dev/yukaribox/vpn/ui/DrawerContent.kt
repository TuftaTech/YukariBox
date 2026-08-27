package dev.yukaribox.vpn.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.yukaribox.vpn.R
import dev.yukaribox.vpn.ui.kit.YukariBust
import dev.yukaribox.vpn.ui.theme.DrawerItemShape
import dev.yukaribox.vpn.ui.theme.YukariIcons
import dev.yukaribox.vpn.ui.theme.yukari

/**
 * The navigation drawer.
 *
 * This holds everything the three-tab bottom bar cannot: the server list, group
 * management, the full settings screen, the log, help, and sharing the app. The split
 * is by frequency — what you touch every session is a tab, what you touch when
 * something needs configuring is in here.
 *
 * The exception is the first row. Home *is* a tab, and it is repeated here because every
 * screen this drawer opens wears the hamburger rather than a back arrow: without it, the
 * only way from the log back to the connect screen is the system Back gesture. It resets
 * the stack rather than pushing anything, so it is also the way out of three screens deep.
 *
 * The active row is a filled ink slab, which is the only place in the app besides the
 * bottom bar and the FAB that uses that fill. It marks *where you are*, so it follows
 * the current destination rather than the last thing tapped.
 *
 * The glyphs are deliberately a **mixed** family — an outline folder beside a filled
 * gear and a filled share — because that is what the reference draws. It is the one place
 * the design system's "outline by default" line loses to the literal pixels, and it is an
 * owner decision: do not unify them.
 */
@Composable
internal fun DrawerContent(
    active: DrawerItem?,
    onItem: (DrawerItem) -> Unit,
    onProfile: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val version = remember(context) { appVersion(context) }
    Column(
        modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .systemBarsPadding(),
    ) {
        DrawerHeader(onClick = onProfile)
        // No top padding: the first slab's top edge sits exactly where the header's
        // bottom edge cuts the bust off, which is what the reference measures (slab top
        // 202 against her ink ending at 204).
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(ROW_GAP),
        ) {
            DrawerRow(
                icon = Icons.Default.Home,
                label = stringResource(R.string.nav_home),
                active = active == DrawerItem.Home,
                onClick = { onItem(DrawerItem.Home) },
            )
            DrawerRow(
                icon = YukariIcons.Globe,
                label = stringResource(R.string.nav_servers),
                active = active == DrawerItem.Servers,
                onClick = { onItem(DrawerItem.Servers) },
            )
            DrawerRow(
                icon = YukariIcons.Folder,
                label = stringResource(R.string.nav_groups),
                active = active == DrawerItem.Groups,
                onClick = { onItem(DrawerItem.Groups) },
            )
            DrawerRow(
                icon = Icons.Default.Settings,
                label = stringResource(R.string.nav_settings),
                active = active == DrawerItem.Settings,
                onClick = { onItem(DrawerItem.Settings) },
            )
            DrawerRow(
                icon = YukariIcons.Document,
                label = stringResource(R.string.nav_log),
                active = active == DrawerItem.Log,
                onClick = { onItem(DrawerItem.Log) },
            )
            DrawerRow(
                icon = YukariIcons.Help,
                label = stringResource(R.string.nav_help),
                active = active == DrawerItem.Help,
                onClick = { onItem(DrawerItem.Help) },
            )
            DrawerRow(
                icon = Icons.Default.Share,
                label = stringResource(R.string.nav_share),
                active = false,
                onClick = { onItem(DrawerItem.Share) },
            )
        }
        Text(
            stringResource(R.string.drawer_version, version),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.yukari.textTertiary,
            modifier = Modifier.padding(start = 24.dp, bottom = 20.dp, top = 12.dp),
        )
    }
}

/**
 * The bust, the persona's name, what the app actually holds, and a chevron into the
 * profile.
 *
 * Not an avatar. The reference stands Yukari free at the leading edge and lets the
 * header's bottom edge cut her off mid-shirt-print — measured at 118 dp wide by 121 dp
 * of visible height, bottom-flush with the header — so this is a clipped [Box] rather
 * than a row of three vertically-centred things, and she is given her own natural
 * height and allowed to overflow it.
 *
 * The second line is the app's honest answer to the mockup's `Free Plan`: this client
 * has no account, no tier and no expiry, so the line carries the two counts that
 * actually determine what the user can connect to — [libraryCounts], the same sentence
 * the profile card shows. No crown either — a plan marker with no plan behind it is
 * decoration pretending to be data.
 */
@Composable
private fun DrawerHeader(onClick: () -> Unit) {
    val counts = libraryCounts()
    Box(
        Modifier
            .fillMaxWidth()
            .height(HEADER_HEIGHT)
            .clickable(role = Role.Button, onClick = onClick)
            .clipToBounds(),
    ) {
        YukariBust(
            Modifier
                .align(Alignment.TopStart)
                .padding(start = BUST_INSET, top = BUST_TOP)
                // She is taller than the header on purpose, and the overflow has to hang
                // off the *bottom*. `unbounded` is what lets her be measured at her full
                // height inside a shorter parent; without it the size modifier is clamped
                // to the space left and the drawing is centred in it, which crops the top
                // of her hair and shows shirt the reference does not.
                .wrapContentHeight(Alignment.Top, unbounded = true)
                .size(BUST_WIDTH, BUST_HEIGHT),
        )
        Row(
            Modifier
                .align(Alignment.CenterStart)
                .fillMaxWidth()
                .padding(start = TEXT_INSET, end = CHEVRON_INSET),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    // The same helper the profile card uses. Read the setting twice and the
                    // two surfaces drift: a nickname on the card, "Yukari" here.
                    personaName(),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    counts,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.yukari.textTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                null,
                Modifier.size(CHEVRON),
                tint = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

/**
 * One drawer entry, and — when it is the current destination — the app's only
 * selected-row pattern: a [DrawerItemShape] slab of [ink][dev.yukaribox.vpn.ui.theme.YukariColors.ink]
 * with white content, inset from the sheet's leading edge rather than bled to it.
 *
 * The glyph is `ink`, not the muted grey a settings row uses. That difference is
 * measured, not stylistic: the drawer is a short list of places and reads as one block,
 * while a settings screen's icons sit behind their labels.
 */
@Composable
private fun DrawerRow(
    icon: ImageVector,
    label: String,
    active: Boolean,
    onClick: () -> Unit,
) {
    val yukari = MaterialTheme.yukari
    val fg = if (active) yukari.onInk else MaterialTheme.colorScheme.onSurface
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = SLAB_START, end = SLAB_END)
            .clip(DrawerItemShape)
            .background(if (active) yukari.ink else MaterialTheme.colorScheme.surface)
            .clickable(role = Role.Tab, onClick = onClick)
            .defaultMinSize(minHeight = SLAB_HEIGHT)
            .padding(horizontal = SLAB_PADDING, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(GLYPH_GAP),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(GLYPH), tint = fg)
        Text(label, style = MaterialTheme.typography.titleSmall, color = fg, maxLines = 1)
    }
}

// ---- measured geometry -------------------------------------------------------
// All from the reference's drawer panel, normalised to dp (1 mockup px = 1 dp). The
// header numbers are one system: BUST_HEIGHT is BUST_WIDTH at the asset's own 118:126
// aspect, and HEADER_HEIGHT minus BUST_TOP is the 121 dp of her the reference shows.
// Change one and she stops being cut where the slab begins.

/** Header height. Her visible 121 dp plus the 11 dp of air above her. */
private val HEADER_HEIGHT = 132.dp

/** Bust width — visible x 542–660 of a sheet at 533. */
private val BUST_WIDTH = 118.dp

/**
 * [BUST_WIDTH] at the asset's aspect; taller than the header, which clips the rest.
 *
 * 126 rather than the 173 the hands-behind-head crop needed: `yukari_bust` is a much wider
 * drawing for its height (0.936 against 0.683), so the same 118 dp of width is only 126 dp
 * tall. The 121 dp of her the reference shows is unchanged — what changed is how little of
 * her is left over, 5 dp instead of 52. Her ink runs x 11–125 against the name column at
 * [TEXT_INSET] 135, and her head y 11–104 (`measured` off the asset).
 */
private val BUST_HEIGHT = 126.dp

/** Her leading inset, and the air above her. */
private val BUST_INSET = 9.dp
private val BUST_TOP = 11.dp

/** Where the name column starts — the reference's own x 670 of a sheet at 533. */
private val TEXT_INSET = 135.dp

/** Trailing inset of the chevron's touch box, landing its centre 24 dp from the edge. */
private val CHEVRON_INSET = 14.dp
private val CHEVRON = 20.dp

/**
 * The selected slab: 50 dp tall, 8 dp inside the sheet's edge at both ends.
 *
 * Symmetric on purpose. The trailing inset was 13 dp against the leading 8, which
 * measured on the device as 8.3 / 12.7 — a slab that is visibly not centred in its sheet,
 * and the one inset the plan actually names is the leading 8 (§12.5). One number for both
 * is also one number to keep in step with [SLAB_PADDING].
 */
private val SLAB_HEIGHT = 50.dp
private val SLAB_START = 8.dp
private val SLAB_END = 8.dp

/** Slab padding, its glyph, and the gap to the label — glyph centre lands at 32 dp. */
private val SLAB_PADDING = 16.dp
private val GLYPH = 20.dp
private val GLYPH_GAP = 18.dp

/** Gap between slabs: with [SLAB_HEIGHT] this is the measured 56 dp row pitch. */
private val ROW_GAP = 6.dp
