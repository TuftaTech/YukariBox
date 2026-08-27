package dev.yukaribox.vpn.ui.kit

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import dev.yukaribox.vpn.core.LatencyTier
import dev.yukaribox.vpn.ui.theme.FlagShape
import dev.yukaribox.vpn.ui.theme.HeroUnit
import dev.yukaribox.vpn.ui.theme.LabelWide
import dev.yukaribox.vpn.ui.theme.MeterShape
import dev.yukaribox.vpn.ui.theme.StatValue
import dev.yukaribox.vpn.ui.theme.TagShape
import dev.yukaribox.vpn.ui.theme.yukari

/**
 * Rows, plates, badges and figures — everything that displays a value.
 *
 * The reference's row is a fixed four-slot layout and every list in the app uses it:
 * a leading plate, a bold first line over a grey second line, a trailing value, and
 * an optional action at the very end. [ListRow] is that layout; [NavRow] and
 * [SwitchRow] are the two settings variants of it, and they are `dense` — the
 * reference's settings rows run at a 42 dp pitch against a 66 dp list card.
 */

/**
 * Standard row: leading plate, title over subtitle, trailing slot.
 *
 * [dense] is the settings row: 42 dp of pitch and a 13 sp Medium title instead of the
 * list card's 60 dp and 14 sp SemiBold. Both are measured, and the difference matters —
 * a whole settings screen set at list-row weight reads as a list of headings.
 *
 * [enabled] fades the whole row and marks its click disabled, so a control that cannot
 * act right now says so instead of swallowing the tap. The fade is a layer alpha over
 * the entire row rather than a per-slot colour, which is what keeps the glyph, the
 * title, the trailing value and a switch all at one tier.
 */
@Composable
fun ListRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    dense: Boolean = false,
    enabled: Boolean = true,
    leading: @Composable (() -> Unit)? = null,
    titleSuffix: @Composable (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    Row(
        modifier
            .fillMaxWidth()
            .then(
                if (onClick != null) {
                    Modifier.clickable(enabled = enabled, role = Role.Button, onClick = onClick)
                } else {
                    Modifier
                },
            )
            .alpha(if (enabled) 1f else DISABLED_ALPHA)
            .defaultMinSize(minHeight = if (dense) DENSE_ROW_HEIGHT else ROW_HEIGHT)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) leading()
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    title,
                    style = if (dense) {
                        MaterialTheme.typography.titleSmall
                    } else {
                        MaterialTheme.typography.titleMedium
                    },
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    // The title yields and the suffix does not: the suffix is where a
                    // `NO TLS` warning lives, and a truncated warning is worse than a
                    // truncated server name.
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (titleSuffix != null) titleSuffix()
            }
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (trailing != null) trailing()
    }
}

/**
 * Settings row that opens something: glyph, label, optional current value, chevron.
 * The value sits *before* the chevron, right-aligned, as in the mockup's
 * `App Language — English >`.
 *
 * [valueMaxWidth] is why this row can carry a URL. The value is measured before the
 * weighted label, so an unbounded 25-to-40-character DoH or probe URL squeezes the
 * label itself down to an ellipsis; capping the value and ellipsizing *it* makes the
 * label win. The reference's own trailing values (`English`, `System`) are single
 * words, so it never had to answer this.
 *
 * [enabled] is not decoration either: a row wired to an action that is a documented
 * no-op in the current state (`Reconnect now` while the tunnel is idle) would
 * otherwise log a line and do nothing, with no summary line left to explain why.
 */
@Composable
fun NavRow(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    summary: String? = null,
    value: String? = null,
    valueMaxWidth: Dp = VALUE_WIDTH,
    enabled: Boolean = true,
) {
    ListRow(
        title = label,
        subtitle = summary,
        modifier = modifier,
        dense = true,
        enabled = enabled,
        leading = icon?.let { { RowGlyph(it) } },
        trailing = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (value != null) {
                    Text(
                        value,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.yukari.textTertiary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = valueMaxWidth),
                    )
                }
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    null,
                    Modifier.padding(start = 4.dp).size(20.dp),
                    tint = MaterialTheme.colorScheme.outline,
                )
            }
        },
        onClick = onClick,
    )
}

/**
 * Settings row that toggles something.
 *
 * **The row is the tap target, and it is 42 dp rather than Android's recommended 48.**
 * That is deliberate: settings rows are contiguous at a 42 dp pitch, so there is no
 * gap between them to absorb the extra 6 dp — a 48 dp target would either overlap its
 * neighbour or force the pitch the reference measures to 48. The whole row (label,
 * glyph, switch) is one target, so the reachable area is 42 dp by the full screen
 * width, and the accepted trade-off is the height only. Where a row *does* have room
 * (the last one before a section caption) Android's own accessibility layer widens its
 * touch bounds to 48 dp for free. Do not "fix" this by giving the switch its own 48 dp
 * box: that re-introduces two targets in one row.
 *
 * The switch itself is [SwitchTrack], drawn rather than stock M3 — `Switch` carries a
 * 48 dp `minimumInteractiveComponentSize`, which pushed this row to 68 dp and broke the
 * rhythm on every screen that has one. The semantics live on the row rather than on the
 * switch: it is the only focusable node in the row and it carries `Role.Switch` and the
 * checked state, so a screen reader reads the label and the state together instead of a
 * button followed by an unlabelled switch.
 */
@Composable
fun SwitchRow(
    label: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    summary: String? = null,
    enabled: Boolean = true,
) {
    ListRow(
        title = label,
        subtitle = summary,
        modifier = modifier
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = onChange,
            )
            .semantics(mergeDescendants = true) { if (!enabled) disabled() },
        dense = true,
        enabled = enabled,
        leading = icon?.let { { RowGlyph(it) } },
        trailing = { SwitchTrack(checked) },
    )
}

/**
 * The kit's switch, standalone: the same [SwitchTrack] with its own 48 dp target and
 * its own semantics, for the one place a switch is not part of a settings row (a
 * routing rule's card, where the card itself opens the editor).
 *
 * One drawn switch and one colour set for both sites — a rule card taking stock M3's
 * defaults while [SwitchRow] overrode the off track meant an off switch looked
 * different depending on which screen it was on.
 */
@Composable
fun YukariSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Box(
        modifier
            .defaultMinSize(minWidth = TOUCH, minHeight = TOUCH)
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = onCheckedChange,
            )
            .alpha(if (enabled) 1f else DISABLED_ALPHA),
        contentAlignment = Alignment.Center,
    ) {
        SwitchTrack(checked)
    }
}

/**
 * The switch itself: a 38x19 dp track with a 16 dp knob.
 *
 * Measured off the reference, which is closer to Material 2's proportions than to M3's
 * 52x32. Purely visual — it carries no semantics and no click, because in both of its
 * call sites something larger owns the target. The knob travels on [flipSpec] — a
 * control changing its own state — so it reads as the click it is rather than a jump.
 */
@Composable
private fun SwitchTrack(checked: Boolean, modifier: Modifier = Modifier) {
    val knobX by animateDpAsState(
        targetValue = if (checked) SWITCH_WIDTH - SWITCH_KNOB - SWITCH_INSET else SWITCH_INSET,
        animationSpec = flipSpec(),
        label = "switchKnob",
    )
    Box(
        modifier
            .size(width = SWITCH_WIDTH, height = SWITCH_HEIGHT)
            .clip(CircleShape)
            .background(
                if (checked) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.yukari.trackOff
                },
            ),
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            Modifier
                // Lambda overload on purpose: the knob's position is state-backed, and
                // this way the slide is a layout pass rather than a recomposition.
                .offset { IntOffset(knobX.roundToPx(), 0) }
                .size(SWITCH_KNOB)
                .clip(CircleShape)
                .background(
                    if (checked) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        MaterialTheme.colorScheme.surface
                    },
                )
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape),
        )
    }
}

/**
 * A settings row's leading glyph: bare, not plated.
 *
 * `iconMuted` rather than ink, and this differs from the drawer on purpose — the
 * reference's settings rows draw a lighter glyph than their own label, while the
 * drawer's rows draw both in ink.
 */
@Composable
fun RowGlyph(icon: ImageVector, modifier: Modifier = Modifier) {
    Icon(
        icon,
        null,
        modifier.padding(horizontal = 2.dp).size(20.dp),
        tint = MaterialTheme.colorScheme.outline,
    )
}

/**
 * A row whose value is chosen from a short list, opened as a menu over the row itself.
 *
 * A menu rather than a dialog because the choice is always small and always instant:
 * every caller here writes straight to `SettingsStore`, so a dialog's Save button would
 * be a second tap that confirms nothing.
 */
@Composable
fun PickerRow(
    label: String,
    current: String,
    options: List<String>,
    onPick: (String) -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    summary: String? = null,
) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        NavRow(
            label = label,
            icon = icon,
            summary = summary,
            value = current,
            onClick = { open = true },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option) },
                    trailingIcon = { if (option == current) Icon(Icons.Default.Check, null) },
                    onClick = { open = false; onPick(option) },
                )
            }
        }
    }
}

/**
 * The flag slot on a server row: a 37x25 plate carrying the country's flag, or its
 * two-letter code when there is no flag to be had.
 *
 * The flag is a flat greyscale rectangle from `assets/flags/`, one per country, baked by
 * `design/tools/make_flag_assets.py` — see [FlagArt]. It is the slot the reference draws:
 * a flag is recognised where two letters have to be read, and the plate behind it — chip
 * fill, hairline, 3 dp corners — is what gives a mostly-white flag (Japan, Poland) an
 * edge to sit against.
 *
 * Greyscale costs something and it is worth naming: flags that differ only in hue
 * differ here only in *tone*, so Germany, the Netherlands and Russia are three bands
 * each, told apart by which band is dark. The plate places a node; its name and
 * endpoint are what identify it, and both are on the row already.
 *
 * Selection is not drawn here. The selected row is marked by 3 dp of ink inside its
 * leading edge and by nothing else, so a plate that also inverted was a second cue for
 * one fact — and inverting the plate under a flag would frame it in black.
 *
 * [code] is whatever the caller resolved and [country] says which it is: an ISO 3166-1
 * alpha-2 code, or the protocol tag when the node's name carries no country at all. The
 * flag path is taken only for a country, because a protocol tag is two letters too and
 * two of them name real ones — Shadowsocks would fly South Sudan and Trojan Turkey.
 * Both kinds are **two** characters by construction ([MAX_CODE] is the backstop, not
 * the mechanism): the slot is measured for an alpha-2 code, and a third glyph in it is
 * wider than anything a country puts there.
 */
@Composable
fun FlagPlate(code: String, modifier: Modifier = Modifier, country: Boolean = false) {
    val flag = if (country) rememberFlag(code, FLAG_HEIGHT) else null
    Box(
        modifier
            .size(width = FLAG_WIDTH, height = FLAG_HEIGHT)
            .clip(FlagShape)
            .background(MaterialTheme.yukari.chip)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, FlagShape),
        contentAlignment = Alignment.Center,
    ) {
        if (flag != null) {
            // Fit rather than crop, though the asset is cut to the plate's aspect and
            // the two agree: a square flag is padded rather than stretched, and that
            // padding is transparent, so cropping would trim a Swiss cross instead of
            // letting the plate show beside it.
            Image(
                bitmap = flag,
                contentDescription = code,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
            )
        } else {
            Text(
                code.take(MAX_CODE).uppercase(),
                style = LabelWide,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                softWrap = false,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * `PING 35` — the trailing plate on a server row.
 *
 * The plate never changes hue, because there is no hue left to change: the tier rides
 * in the *weight* of the digits at the fast end and in a whole-plate **inversion** at
 * the slow end. Inversion is what makes ">150 ms" and "dead" legible without reading
 * the number, and it survives greyscale by construction.
 *
 * Ink rather than red for that inversion: a long list from a large subscription is
 * mostly slow nodes and timeouts, and a column of red blocks reads as an app-level
 * fault rather than as a per-row fact.
 */
@Composable
fun PingBadge(
    tier: LatencyTier,
    text: String,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    val yukari = MaterialTheme.yukari
    val inverted = tier == LatencyTier.Bad || tier == LatencyTier.Failed
    val weight = when (tier) {
        LatencyTier.Good -> FontWeight.Bold
        LatencyTier.Mid -> FontWeight.Normal
        else -> FontWeight.SemiBold
    }
    val label = when {
        inverted -> MaterialTheme.colorScheme.onPrimary
        tier == LatencyTier.Untested || tier == LatencyTier.Testing -> yukari.textTertiary
        else -> MaterialTheme.colorScheme.onSurface
    }
    Text(
        text.uppercase(),
        style = LabelWide.copy(fontWeight = weight),
        color = label,
        maxLines = 1,
        softWrap = false,
        modifier = modifier
            .clip(TagShape)
            .background(if (inverted) MaterialTheme.colorScheme.primary else yukari.chip)
            .then(
                if (onClick != null) {
                    Modifier.clickable(role = Role.Button, onClick = onClick)
                } else {
                    Modifier
                },
            )
            .padding(horizontal = 8.dp, vertical = 5.dp),
    )
}

/** Neutral tag: a protocol name, a node count, an outbound kind. */
@Composable
fun MetaBadge(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        style = LabelWide,
        color = MaterialTheme.yukari.textTertiary,
        maxLines = 1,
        softWrap = false,
        modifier = modifier
            .clip(TagShape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 7.dp, vertical = 4.dp),
    )
}

/**
 * Inverted tag for something the user must notice — `NO TLS` above all. Filled
 * against the neutral [MetaBadge] beside it, so the contrast does the work rather
 * than a hue.
 *
 * [heavy] is the third step of that ramp: filled, then outlined ([OutlineBadge]), then
 * filled with heavier wording. Three shapes, not three colours — which is what lets a
 * route's `BLOCK` outrank its `PROXY` without either of them being red.
 */
@Composable
fun AlertBadge(text: String, modifier: Modifier = Modifier, heavy: Boolean = false) {
    Text(
        text.uppercase(),
        style = if (heavy) LabelWide.copy(fontWeight = FontWeight.Bold) else LabelWide,
        color = MaterialTheme.colorScheme.onPrimary,
        maxLines = 1,
        softWrap = false,
        modifier = modifier
            .clip(TagShape)
            .background(MaterialTheme.colorScheme.primary)
            .padding(horizontal = 7.dp, vertical = 4.dp),
    )
}

/** Outlined tag — a state that is neither neutral nor urgent (`DIRECT`, `BLOCK`). */
@Composable
fun OutlineBadge(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        style = LabelWide,
        color = MaterialTheme.colorScheme.onSurface,
        maxLines = 1,
        softWrap = false,
        modifier = modifier
            .clip(TagShape)
            .border(1.dp, MaterialTheme.colorScheme.outline, TagShape)
            .padding(horizontal = 7.dp, vertical = 4.dp),
    )
}

/**
 * A big figure with a small unit riding its baseline — the session total on Stats.
 *
 * `StatValue`, not `displayMedium`: the type scale stops at 20 sp (§3.1), and a 34 sp
 * figure was 1.7x the wordmark on a screen that is meant to use nothing but the measured
 * tokens. Bold at 20 sp is still the largest thing on the card by a wide margin.
 */
@Composable
fun StatFigure(
    value: String,
    unit: String,
    modifier: Modifier = Modifier,
    onInk: Boolean = false,
) {
    val fg = if (onInk) MaterialTheme.yukari.onInk else MaterialTheme.colorScheme.onSurface
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.Bottom) {
        Text(value, style = StatValue, color = fg)
        Text(unit, style = HeroUnit, color = fg, modifier = Modifier.padding(bottom = UNIT_BASELINE))
    }
}

/** Caption over value — one cell of a stats block. */
@Composable
fun StatTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    onInk: Boolean = false,
) {
    val yukari = MaterialTheme.yukari
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            label.uppercase(),
            style = LabelWide,
            color = if (onInk) yukari.onInkMuted else yukari.textTertiary,
        )
        Text(
            value,
            style = MaterialTheme.typography.titleMedium,
            color = if (onInk) yukari.onInk else MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** A filled share of a track — one outbound's slice of the session on Stats. */
@Composable
fun Meter(fraction: Float, modifier: Modifier = Modifier, onInk: Boolean = false) {
    val yukari = MaterialTheme.yukari
    val track = if (onInk) yukari.onInk.copy(alpha = TRACK_ALPHA) else yukari.dot
    val fill = if (onInk) yukari.onInk else MaterialTheme.colorScheme.primary
    Box(
        modifier
            .fillMaxWidth()
            .height(METER_HEIGHT)
            .clip(MeterShape)
            .background(track),
    ) {
        Box(
            Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .height(METER_HEIGHT)
                .clip(MeterShape)
                .background(fill),
        )
    }
}

/**
 * Determinate hairline over the full width — the batch latency test's progress.
 * A `null` fraction draws the bare track, which is what "queued, nothing measured
 * yet" looks like.
 */
@Composable
fun LineProgress(fraction: Float?, modifier: Modifier = Modifier) {
    // Eased, not stepped. The fraction arrives one finished probe at a time — 788 of them
    // in the dev subscription — and a bar that jumps 0.1% per step next to a UI that
    // crossfades is the loudest kind of inconsistency: the same event drawn two ways.
    val filled by animateFloatAsState(
        targetValue = fraction?.coerceIn(0f, 1f) ?: 0f,
        animationSpec = swapSpec(),
        label = "progress",
    )
    Box(
        modifier
            .fillMaxWidth()
            .height(2.dp)
            .background(MaterialTheme.colorScheme.outlineVariant),
    ) {
        if (fraction != null) {
            Box(
                Modifier
                    .fillMaxWidth(filled)
                    .height(2.dp)
                    .background(MaterialTheme.colorScheme.primary),
            )
        }
    }
}

/**
 * Link quality as ascending bars — the connected banner's right edge.
 *
 * The bar count is [LatencyTier]'s, not this file's: there used to be a second
 * tier→bars mapping on the banner itself, on a different scale, and the two disagreed.
 */
@Composable
fun SignalMeter(filledBars: Int, modifier: Modifier = Modifier) {
    Row(
        modifier,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        repeat(SIGNAL_BARS) { index ->
            Box(
                Modifier
                    .width(3.dp)
                    .height((5 + index * 4).dp)
                    .clip(MeterShape)
                    .background(
                        if (index < filledBars) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.outlineVariant
                        },
                    ),
            )
        }
    }
}

/** A list card's row. */
private val ROW_HEIGHT = 60.dp

/** A settings row — the reference's 42 dp pitch. */
private val DENSE_ROW_HEIGHT = 42.dp

/**
 * Ceiling for a row's trailing value. Wide enough for a DoH URL's host, narrow enough
 * that the row's own label is never the thing that gets truncated.
 */
private val VALUE_WIDTH = 150.dp

/** How far a disabled row fades. Enough to read as off, not enough to be unreadable. */
private const val DISABLED_ALPHA = 0.45f

/** The switch: track, knob, and the knob's inset inside the track. Measured. */
private val SWITCH_WIDTH = 38.dp
private val SWITCH_HEIGHT = 19.dp
private val SWITCH_KNOB = 16.dp
private val SWITCH_INSET = 1.5.dp

private val FLAG_WIDTH = 37.dp
private val FLAG_HEIGHT = 25.dp
private val METER_HEIGHT = 6.dp

/** How far a figure's unit is lifted so the two share a baseline rather than a box. */
private val UNIT_BASELINE = 3.dp

/** Glyphs a flag plate will draw: an ISO alpha-2 code's worth, and no more. */
private const val MAX_CODE = 2
private const val TRACK_ALPHA = 0.25f

/** Bound to the tier enum so the meter and the mapping into it cannot drift apart. */
private const val SIGNAL_BARS = LatencyTier.TOTAL_BARS
