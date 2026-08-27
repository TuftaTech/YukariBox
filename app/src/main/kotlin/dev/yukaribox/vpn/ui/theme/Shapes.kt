package dev.yukaribox.vpn.ui.theme

import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * Two silhouettes, and the split between them is the whole shape language.
 *
 * **Soft-rounded rectangles** for anything that holds content or text: a card, a
 * row, a chip, a button, a badge. The radius scales with the element — a 5 dp tag
 * next to an 11 dp card next to a 22 dp sheet — so the family reads as one system.
 * The card and tag radii are measured: five least-squares fits of the reference's
 * card corners land between 10.6 and 11.4 dp, its list rows at 9.8 and 9.9, its
 * badges at 4.7 to 5.7. Nothing in the reference is a full pill, however much a
 * 24 dp-tall `PING` plate looks like one — a pill would be 12.
 *
 * **True circles** for the app's own controls: the power button, the FAB, an avatar,
 * a leading icon plate. It is the reference's clearest structural decision: the one
 * thing the user is meant to reach for on Home is a 148 dp circle in a page made
 * entirely of rectangles. Nothing else is round, which is what makes it read as the
 * control rather than as decoration.
 *
 * Use `androidx.compose.foundation.shape.CircleShape` for the round half; there is
 * no point aliasing it here.
 */
val YukariShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    // What `OutlinedTextField` and the kit's `medium` callers resolve to, so a field
    // tracks the card radius. Note what this is *not*: stock M3 resolves a dialog to
    // `extraLarge` and a dropdown menu to `extraSmall`, so tuning either from here edits
    // the wrong line.
    medium = RoundedCornerShape(11.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(22.dp),
)

/** Content cards: the location card, the profile card, the connected banner. */
val CardShape = RoundedCornerShape(11.dp)

/** A list row's own card — measurably tighter than a content card. */
val ListCardShape = RoundedCornerShape(10.dp)

/** Buttons and the search field: every tappable rectangle that is not a card. */
val ControlShape = RoundedCornerShape(10.dp)

/** Chips and small selectors. */
val ChipShape = RoundedCornerShape(10.dp)

/** Badges and tags: the `PING 35` plate, `NO TLS`, a node count. */
val TagShape = RoundedCornerShape(5.dp)

/** The drawer's selected row — a full-width filled slab behind the label. */
val DrawerItemShape = RoundedCornerShape(10.dp)

/** A flag plate. Just enough radius to keep a 37x25 rectangle from looking sharp. */
val FlagShape = RoundedCornerShape(3.dp)

/** Thin indicator geometry: a latency meter bar, a tab's underline, a notice's rule. */
val MeterShape = RoundedCornerShape(2.dp)

/**
 * One cell of a segmented row: [index] of [count], rounded on the outside only.
 *
 * Exists because `SegmentedButtonDefaults.itemShape` resolves to M3's 50%-corner cell —
 * measured at a ~19.3 dp radius on a 39.7 dp row, i.e. a full pill within measurement
 * error, and the only pill in the app. Nothing in the reference is a pill (§4: the drawer's
 * 50 dp selected row has a 10 dp radius and the 24 dp `PING` plate has 5), so the ends
 * take the control radius every other tappable rectangle uses and the inner joins stay
 * square, which is what makes three cells read as one control.
 */
fun segmentShape(index: Int, count: Int): CornerBasedShape {
    val end = (count - 1).coerceAtLeast(0)
    val leading = if (index == 0) SEGMENT_RADIUS else 0.dp
    val trailing = if (index == end) SEGMENT_RADIUS else 0.dp
    return RoundedCornerShape(
        topStart = leading,
        bottomStart = leading,
        topEnd = trailing,
        bottomEnd = trailing,
    )
}

/** The outer corners of a segmented row — [ControlShape]'s radius, and the same reason. */
private val SEGMENT_RADIUS = 10.dp
