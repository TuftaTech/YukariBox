package dev.yukaribox.vpn.ui.kit

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.yukaribox.vpn.ui.theme.BrandTitle
import dev.yukaribox.vpn.ui.theme.CardShape
import dev.yukaribox.vpn.ui.theme.LabelWide
import dev.yukaribox.vpn.ui.theme.yukari

/**
 * Surfaces and chrome.
 *
 * The reference has exactly one content surface — a near-white card with an 11 dp
 * radius, a 1 dp hairline and a shadow soft enough to read as a single dp of
 * elevation — and it uses it for everything: a server row, the location card, the
 * profile card, the connected banner. That is [PaperCard], and it is the only one: the
 * near-black inverse this file used to carry went with the Stats card that was its last
 * caller, which now sits on a [PaperCard] like everything else.
 *
 * The hairline is not decoration. The page and the card are the *same* near-white, so
 * a card with no border is invisible: what separates it is the outline plus the
 * shadow, never a fill step. `elevated = false` drops the shadow and keeps the line.
 */

/**
 * The white content card. Always outlined — see the file header.
 *
 * [borderColor] exists only so a card can *escalate* its outline: §9 lets a notice
 * stand out by drawing a heavier line rather than by tinting anything. It never turns
 * the line off.
 */
@Composable
fun PaperCard(
    modifier: Modifier = Modifier,
    shape: Shape = CardShape,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    elevated: Boolean = true,
    borderColor: Color = MaterialTheme.colorScheme.outlineVariant,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .then(if (elevated) Modifier.shadow(SHADOW, shape) else Modifier)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface)
            .border(HAIRLINE, borderColor, shape)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(contentPadding),
        content = content,
    )
}

/**
 * The wordmark bar: hamburger, `YUKARIBOX`, and the spacer that balances it.
 *
 * [centered] is the difference between the two app bars in the reference — Home centres
 * the wordmark, Servers pushes it against the hamburger so the illustration has the whole
 * right half of the header.
 *
 * There is **no trailing control**, and no slot for one. The reference draws a crown here
 * on Home and it shipped as a second route into the profile; the owner cut it 2026-08-26.
 * The bottom bar's Profile tab and the drawer's row are that route, and the bar is the only
 * place in the app where a tap did nothing a visible tab did not already do. What is left in
 * that corner is a [TOUCH]-sized empty box — not decoration: it is what balances the
 * hamburger so the centred wordmark sits on the screen's midline rather than 24 dp right of
 * it.
 */
@Composable
fun BrandTopBar(
    title: String,
    onMenu: () -> Unit,
    menuContentDescription: String,
    modifier: Modifier = Modifier,
    centered: Boolean = true,
) {
    Row(
        modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .height(BAR_HEIGHT)
            .padding(horizontal = BAR_PADDING),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BarIconButton(
            icon = Icons.Default.Menu,
            contentDescription = menuContentDescription,
            onClick = onMenu,
        )
        Text(
            title.uppercase(),
            style = BrandTitle,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = if (centered) TextAlign.Center else TextAlign.Start,
            modifier = Modifier
                .weight(1f)
                .padding(start = if (centered) 0.dp else 4.dp, end = if (centered) 0.dp else 8.dp),
        )
        Box(Modifier.size(TOUCH))
    }
}

/**
 * A plain screen title with one leading control.
 *
 * The control defaults to a back arrow, which is what every pushed destination wants.
 * A tab root passes the hamburger instead — the bar is otherwise identical, and having
 * one composable for both keeps the title's size and inset from drifting between the
 * two kinds of screen.
 */
@Composable
fun TitleTopBar(
    title: String,
    onNav: () -> Unit,
    navContentDescription: String,
    modifier: Modifier = Modifier,
    navIcon: ImageVector = Icons.AutoMirrored.Filled.ArrowBack,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .height(BAR_HEIGHT)
            .padding(horizontal = BAR_PADDING),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BarIconButton(
            icon = navIcon,
            contentDescription = navContentDescription,
            onClick = onNav,
        )
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(start = 6.dp),
        )
        actions()
    }
}

/**
 * A 48 dp tappable glyph, sized for the app bar.
 *
 * The glyph is 22 dp inside a 48 dp target, which — with the bar's own [BAR_PADDING] —
 * lands its leading edge at the measured 24 dp content margin. Both numbers move
 * together; changing one alone moves the bar's content off every other screen's grid.
 */
@Composable
fun BarIconButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color? = null,
) {
    Box(
        modifier
            .size(TOUCH)
            .clip(CircleShape)
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription,
            Modifier.size(BAR_GLYPH),
            tint = tint ?: MaterialTheme.colorScheme.onSurface,
        )
    }
}

/**
 * `ACCOUNT` / `GENERAL` — the grey caption above a group of rows. Left-aligned with
 * the rows it labels, never inside their card.
 *
 * The 12 dp start inset plus a screen's [ScreenMargin] is the measured 24 dp content
 * margin, and the 32/24 dp vertical pair is the reference's own rhythm: 35 dp from the
 * last row of one group to the next caption, 24 dp from a caption to its first row.
 */
@Composable
fun SectionCaption(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        style = LabelWide,
        color = MaterialTheme.yukari.textTertiary,
        modifier = modifier.padding(start = 12.dp, top = 32.dp, bottom = 24.dp),
    )
}

/**
 * Card shadow. One dp: the reference's cards sit *on* the page, and the integrated ink
 * under one measures about twice its hairline spread over some 5 px.
 */
private val SHADOW = 1.dp

/** The line that makes a card a card, since the page behind it is the same white. */
private val HAIRLINE = 1.dp

/** Gap between stacked list cards — the reference's shadow band, not a visible step. */
val GAP = 2.dp

/** Horizontal margin from the screen edge to a content card. */
val ScreenMargin = 12.dp

/** Horizontal margin for the card-per-row lists, which sit a little wider. */
val ListMargin = 10.dp

/** App bar height, excluding the status-bar inset. */
private val BAR_HEIGHT = 56.dp

/** The app bar's own side inset. Pairs with [BAR_GLYPH] — see [BarIconButton]. */
private val BAR_PADDING = 12.dp

/** The glyph inside an app-bar touch target. */
private val BAR_GLYPH = 22.dp

/** Minimum touch target. */
internal val TOUCH = 48.dp
