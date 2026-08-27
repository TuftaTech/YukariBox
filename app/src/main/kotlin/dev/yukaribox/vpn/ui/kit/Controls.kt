package dev.yukaribox.vpn.ui.kit

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.yukaribox.vpn.ui.theme.ChipShape
import dev.yukaribox.vpn.ui.theme.ControlShape
import dev.yukaribox.vpn.ui.theme.LabelWide
import dev.yukaribox.vpn.ui.theme.MicroLabel
import dev.yukaribox.vpn.ui.theme.YukariIcons
import dev.yukaribox.vpn.ui.theme.YukariMotion
import dev.yukaribox.vpn.ui.theme.yukari

/**
 * Controls.
 *
 * The split the mockup makes: **round** for the app's own switches — the connect
 * circle, the FAB, the banner's stop button, a leading icon plate — and
 * **soft-rounded rectangles** for everything that carries a word. There is exactly
 * one big round control per screen, and it is always the tunnel.
 */

/** Diameter of Home's connect control. Large on purpose: it is the screen's subject. */
private val POWER_SIZE = 148.dp

/** The halo ring outside it. */
private val POWER_HALO = 166.dp

/** Diameter of the servers screen's FAB and the banner's stop button. */
private val FAB_SIZE = 58.dp

/**
 * The connect control: a ring around a power glyph, filled while a session is live.
 *
 * Fill state — not colour — is the signal, and it is the only one: the reference draws
 * this ring in one neutral and never fills it with anything else, so the control reads
 * the same in greyscale and to a colour-blind user. Everything the fill cannot say is
 * said in words, in the uppercase label directly above the circle and the caption below
 * it.
 *
 * @param busy draws a rotating arc over the ring while a transition is in flight.
 *   The connect has no measurable progress to report — the core either comes up or it
 *   does not — so this stands for "working", never for a fraction.
 * @param enabled false stops the control accepting taps. Load-bearing during a
 *   transition: a second tap while a Stop is in flight is read as a *connect* (the state
 *   machine is Disconnecting, which is not "active"), which clears the pending stop and
 *   can leave a doomed session arming the kill switch the user just asked to tear down.
 *   The sweep arc is what says "working"; this is what makes it true.
 */
@Composable
fun PowerButton(
    onClick: () -> Unit,
    contentDescription: String,
    modifier: Modifier = Modifier,
    filled: Boolean = false,
    busy: Boolean = false,
    enabled: Boolean = true,
) {
    val yukari = MaterialTheme.yukari
    // The flip between "off" and "live" is three colours moving together at [FLIP], not a
    // swap: the ring, the disc and the glyph arrive as one click. Snapping them made the
    // most consequential control in the app look like a redraw.
    val ring by animateColorAsState(
        targetValue = if (filled) yukari.ink else yukari.ring,
        animationSpec = flipSpec(),
        label = "powerRing",
    )
    val disc by animateColorAsState(
        targetValue = if (filled) yukari.ink else MaterialTheme.colorScheme.surface,
        animationSpec = flipSpec(),
        label = "powerDisc",
    )
    val glyph by animateColorAsState(
        targetValue = if (filled) yukari.onInk else MaterialTheme.colorScheme.onSurface,
        animationSpec = flipSpec(),
        label = "powerGlyph",
    )
    Box(modifier.size(POWER_HALO), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .size(POWER_HALO)
                .border(1.dp, yukari.ring.copy(alpha = HALO_ALPHA), CircleShape),
        )
        Box(
            Modifier
                .size(POWER_SIZE)
                .shadow(2.dp, CircleShape)
                .clip(CircleShape)
                .background(disc)
                .border(RING_WIDTH, ring, CircleShape)
                .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            // Drawn in the glyph's colour, not the ring's: the ring and the arc were the
            // same neutral, so the one element that says "working" was invisible against
            // the thing it rides on. The glyph colour contrasts with the fill by
            // construction, in both states.
            if (busy) {
                BusySweep(
                    modifier = Modifier.fillMaxSize().padding(RING_WIDTH / 2),
                    color = glyph,
                    stroke = RING_WIDTH,
                )
            }
            Icon(
                YukariIcons.Power,
                contentDescription,
                Modifier.size(POWER_GLYPH),
                tint = glyph,
            )
        }
    }
}

/**
 * The one thing in the app that moves continuously: a [SWEEP_DEGREES] arc making one
 * revolution every `YukariMotion.SWEEP` ms, at a constant rate.
 *
 * Linear on purpose, and the only place linear is allowed — an eased revolution reads as a
 * stutter twice a turn. It also ignores the "Animations" setting, because it is not
 * decoration: it is how the app says it is working, and a user who turned transitions off
 * still needs to know a probe is in flight. Every "busy" in the app is this arc — the
 * connect circle, the per-app list while it loads, a DNS preset being reached for — so the
 * state looks the same wherever it happens.
 */
@Composable
fun BusySweep(
    modifier: Modifier = Modifier,
    color: Color = LocalContentColor.current,
    stroke: Dp = SWEEP_STROKE,
) {
    val transition = rememberInfiniteTransition(label = "sweep")
    // Not `by`: unwrapping the state here read it in the composable body, so the app's one
    // continuous animation restarted this scope and rebuilt its modifier chain on every
    // frame -- 120 recompositions a second on a 120 Hz panel, for the whole of every connect,
    // every per-app scan and every DNS probe. Read inside the graphicsLayer lambda instead
    // and the frame costs a draw, not a recomposition.
    val angle = transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            tween(YukariMotion.SWEEP, easing = LinearEasing),
            RepeatMode.Restart,
        ),
        label = "sweepAngle",
    )
    Canvas(modifier.graphicsLayer { rotationZ = angle.value }) {
        drawArc(
            color = color,
            startAngle = -90f,
            sweepAngle = SWEEP_DEGREES,
            useCenter = false,
            style = Stroke(width = stroke.toPx(), cap = StrokeCap.Round),
        )
    }
}

/**
 * The round action on the servers screen, and the stop button on the banner.
 *
 * [enabled] carries the same weight it does on [PowerButton]: on the servers screen this
 * *is* the tunnel toggle, so it has to refuse taps while a transition is in flight.
 *
 * The state-colour `accent` this used to take is gone with its last caller: the servers
 * screen tells its four readings apart by glyph and content description now, so there was
 * never a hue left to pass.
 */
@Composable
fun CircleButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    filled: Boolean = true,
    enabled: Boolean = true,
    busy: Boolean = false,
    size: Dp = FAB_SIZE,
) {
    val yukari = MaterialTheme.yukari
    val background = if (filled) yukari.ink else MaterialTheme.colorScheme.surface
    val tint = if (filled) yukari.onInk else MaterialTheme.colorScheme.onSurface
    Box(
        modifier
            .size(size)
            .shadow(if (filled) 8.dp else 0.dp, CircleShape)
            .clip(CircleShape)
            .background(background)
            .then(if (filled) Modifier else Modifier.border(1.dp, yukari.ring, CircleShape))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        // The same arc the connect circle uses. It matters more here than it looks: this
        // button is tappable *while* a connect is in flight (that tap cancels it), and a
        // control that accepts a tap while showing no sign of working invites the second
        // tap that would start the thing again.
        if (busy) {
            BusySweep(
                modifier = Modifier.fillMaxSize().padding(FAB_SWEEP_INSET),
                color = tint,
                stroke = FAB_SWEEP_STROKE,
            )
        }
        Icon(icon, contentDescription, Modifier.size(size * GLYPH_RATIO), tint = tint)
    }
}

/** Filled button — the primary action of a screen region. */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    val fg = MaterialTheme.colorScheme.onPrimary
    Row(
        modifier
            .defaultMinSize(minHeight = TOUCH)
            .alpha(if (enabled) 1f else DISABLED_ALPHA)
            .clip(ControlShape)
            .background(MaterialTheme.colorScheme.primary)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) Icon(icon, null, Modifier.size(16.dp), tint = fg)
        Text(text.uppercase(), style = MicroLabel, color = fg)
    }
}

/** Outlined button — the secondary action. */
@Composable
fun GhostButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    val fg = MaterialTheme.colorScheme.onSurface
    Row(
        modifier
            .defaultMinSize(minHeight = TOUCH)
            .alpha(if (enabled) 1f else DISABLED_ALPHA)
            .clip(ControlShape)
            .border(1.dp, fg, ControlShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 13.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) Icon(icon, null, Modifier.size(16.dp), tint = fg)
        Text(text.uppercase(), style = MicroLabel, color = fg)
    }
}

/** Selector chip. Selected inverts to a filled chip — the only selection signal. */
@Composable
fun SelectChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    trailing: @Composable (() -> Unit)? = null,
) {
    val bg = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
    val fg = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
    Row(
        modifier
            .defaultMinSize(minHeight = 36.dp)
            .clip(ChipShape)
            .background(bg)
            .clickable(role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = MaterialTheme.typography.labelMedium, color = fg)
        if (trailing != null) CompositionLocalProvider(LocalContentColor provides fg) { trailing() }
    }
}

/** Segmented control for 2–4 mutually exclusive views. */
@Composable
fun <T> SegmentedTabs(
    options: List<T>,
    selectedOption: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .clip(ControlShape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        options.forEach { option ->
            val active = option == selectedOption
            Box(
                Modifier
                    .weight(1f)
                    .clip(ChipShape)
                    .background(if (active) MaterialTheme.colorScheme.surface else Color.Transparent)
                    .clickable(role = Role.Tab) { onSelect(option) }
                    .padding(vertical = 11.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label(option).uppercase(),
                    style = LabelWide,
                    color = if (active) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.yukari.textTertiary
                    },
                )
            }
        }
    }
}

/** Round grey plate behind a row's leading glyph — the location card, a drawer row. */
@Composable
fun IconCircle(
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    emphasized: Boolean = false,
    content: @Composable () -> Unit,
) {
    val yukari = MaterialTheme.yukari
    val bg = if (emphasized) yukari.ink else yukari.chip
    val fg = if (emphasized) yukari.onInk else MaterialTheme.colorScheme.onSurface
    Box(
        modifier.size(size).clip(CircleShape).background(bg),
        contentAlignment = Alignment.Center,
    ) {
        CompositionLocalProvider(LocalContentColor provides fg) { content() }
    }
}

/** Borderless icon button for row-level actions and overflow. */
@Composable
fun QuietIconButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tint: Color? = null,
) {
    Box(
        modifier
            .size(TOUCH)
            .alpha(if (enabled) 1f else DISABLED_ALPHA)
            .clip(CircleShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription,
            Modifier.size(21.dp),
            tint = tint ?: MaterialTheme.colorScheme.outline,
        )
    }
}

private val RING_WIDTH = 3.dp
private val POWER_GLYPH = 38.dp
private const val HALO_ALPHA = 0.4f
private const val DISABLED_ALPHA = 0.38f
private const val GLYPH_RATIO = 0.38f
/** Stroke of a standalone sweep, where no ring sets it. */
private val SWEEP_STROKE = 2.dp

/** The sweep inside a FAB: just inside its edge, a touch heavier than the small one. */
private val FAB_SWEEP_INSET = 5.dp
private val FAB_SWEEP_STROKE = 2.5.dp
private const val SWEEP_DEGREES = 70f
