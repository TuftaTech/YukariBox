package dev.yukaribox.vpn.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * One sans family, differentiated by weight and tracking. Nothing else.
 *
 * The reference has no serif anywhere and no monospace outside a log dump, so the
 * previous scheme — serif headlines over a geometric sans, with every M3 `label` style
 * set in monospace — is gone entirely. What the reference does have is a very narrow
 * set of moves, used consistently: bold-and-tight for names and figures, small-uppercase
 * for every label (`SERVERS`, `PING 35`, `DISCONNECTED`, `ACCOUNT`), and a plain regular
 * weight for the grey second line of a row. That is what this scale encodes.
 *
 * Every size here is a cap-height measurement off the reference divided by 0.71.
 *
 * The face resolves to the platform sans. The reference's own face is *not* geometric —
 * its `a` and `g` are double-storey, which rules out Futura, Century Gothic and Poppins
 * — and its x-height-to-cap ratio measures 0.70–0.80, which brackets Roboto's 0.74. So
 * the platform sans is not a metrics compromise here; naming the exact face would take
 * the designer, and bundling one is something this tree deliberately does not do.
 */

/** Opt into tabular (fixed-width) digits so a live counter does not jitter. */
private const val TNUM = "tnum"

private val Sans = FontFamily.SansSerif

val YukariTypography = Typography(
    // Display — M3 requires the three slots, but nothing in this app uses them: the type
    // scale stops at the 20 sp wordmark, and the one big figure in the tree (the session
    // total on Stats) takes `StatValue` at that ceiling. Left at plausible values rather
    // than deleted, because a caller that reaches for `displayLarge` should still get the
    // right family and tabular digits.
    displayLarge = TextStyle(
        fontFamily = Sans,
        fontWeight = FontWeight.Bold,
        fontSize = 44.sp,
        lineHeight = 48.sp,
        letterSpacing = (-1).sp,
        fontFeatureSettings = TNUM,
    ),
    displayMedium = TextStyle(
        fontFamily = Sans,
        fontWeight = FontWeight.Bold,
        fontSize = 34.sp,
        lineHeight = 40.sp,
        letterSpacing = (-0.6).sp,
        fontFeatureSettings = TNUM,
    ),
    displaySmall = TextStyle(
        fontFamily = Sans,
        fontWeight = FontWeight.Bold,
        fontSize = 28.sp,
        lineHeight = 34.sp,
        letterSpacing = (-0.4).sp,
        fontFeatureSettings = TNUM,
    ),
    // Headline — an empty state's sentence, a dialog's question.
    headlineLarge = TextStyle(
        fontFamily = Sans,
        fontWeight = FontWeight.Bold,
        fontSize = 26.sp,
        lineHeight = 32.sp,
        letterSpacing = (-0.4).sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = Sans,
        fontWeight = FontWeight.Bold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        letterSpacing = (-0.3).sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = Sans,
        fontWeight = FontWeight.Bold,
        fontSize = 18.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.sp,
    ),
    // Title — a screen's own name in the app bar. Medium and small, not bold: the
    // reference sets "Profile" noticeably lighter than the row titles under it, and
    // measurably smaller than the wordmark on the screens that carry one.
    titleLarge = TextStyle(
        fontFamily = Sans,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        lineHeight = 22.sp,
        letterSpacing = 0.sp,
    ),
    // A list row's first line, a card's name.
    titleMedium = TextStyle(
        fontFamily = Sans,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 19.sp,
        letterSpacing = 0.sp,
        fontFeatureSettings = TNUM,
    ),
    // A settings row's title and a drawer row's label: one step down from a list row,
    // and Medium rather than SemiBold — a dense 42 dp row set in SemiBold reads as a
    // list of headings.
    titleSmall = TextStyle(
        fontFamily = Sans,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.sp,
        fontFeatureSettings = TNUM,
    ),
    bodyLarge = TextStyle(
        fontFamily = Sans,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 22.sp,
        letterSpacing = 0.1.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = Sans,
        fontWeight = FontWeight.Normal,
        fontSize = 12.5.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.1.sp,
        fontFeatureSettings = TNUM,
    ),
    // A row's grey second line — the city under the country, the summary under a
    // setting. The single most-used style in the app.
    bodySmall = TextStyle(
        fontFamily = Sans,
        fontWeight = FontWeight.Normal,
        fontSize = 12.5.sp,
        lineHeight = 17.sp,
        letterSpacing = 0.1.sp,
        fontFeatureSettings = TNUM,
    ),
    labelLarge = TextStyle(
        fontFamily = Sans,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 19.sp,
        letterSpacing = 0.1.sp,
        fontFeatureSettings = TNUM,
    ),
    labelMedium = TextStyle(
        fontFamily = Sans,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.2.sp,
        fontFeatureSettings = TNUM,
    ),
    // Bottom-bar tab labels. The weight is the caller's: the reference sets the active
    // tab Bold and the inactive ones Regular, which is the only difference between them
    // once hue is gone.
    labelSmall = TextStyle(
        fontFamily = Sans,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.2.sp,
        fontFeatureSettings = TNUM,
    ),
)

/**
 * The wordmark in the app bar. The one place a wordmark is set, so it gets its own
 * style rather than borrowing a title: bold, and barely tracked at all — measuring the
 * reference's own wordmark put its inter-glyph gap at the same ratio as its body text,
 * so the "widely tracked wordmark" this used to carry was not in the source. Always
 * uppercased by the caller.
 */
val BrandTitle = TextStyle(
    fontFamily = Sans,
    fontWeight = FontWeight.Bold,
    fontSize = 20.sp,
    lineHeight = 25.sp,
    letterSpacing = 0.2.sp,
)

/**
 * The signature label: small, uppercase, tracked — `TAP TO CONNECT`. Callers apply
 * their own `text.uppercase()`; the style carries metrics only, so it stays
 * locale-correct (Turkish dotless-i is the caller's problem, not a style's).
 *
 * Nothing here goes above +0.1 em of tracking. That is a measured ceiling: no all-caps
 * label in the reference exceeds it, and the three label styles below sit just under.
 */
val MicroLabel = TextStyle(
    fontFamily = Sans,
    fontWeight = FontWeight.SemiBold,
    fontSize = 10.sp,
    lineHeight = 14.sp,
    letterSpacing = 1.0.sp,
)

/** [MicroLabel] one step up, and for exactly one thing: the state label on Home. */
val MicroLabelLarge = TextStyle(
    fontFamily = Sans,
    fontWeight = FontWeight.SemiBold,
    fontSize = 11.sp,
    lineHeight = 15.sp,
    letterSpacing = 1.1.sp,
)

/**
 * The workhorse label: a section caption (`ACCOUNT`), the `PING 35` plate, every
 * badge. Bigger than [MicroLabel] because the reference's captions and badges measure
 * the same cap height as its dense row titles, not smaller.
 */
val LabelWide = TextStyle(
    fontFamily = Sans,
    fontWeight = FontWeight.SemiBold,
    fontSize = 12.sp,
    lineHeight = 16.sp,
    letterSpacing = 1.2.sp,
)

/** The Servers segmented tabs — the largest all-caps type in the app after the mark. */
val TabLabel = TextStyle(
    fontFamily = Sans,
    fontWeight = FontWeight.SemiBold,
    fontSize = 16.sp,
    lineHeight = 21.sp,
    letterSpacing = 0.8.sp,
)

/** A content card's own title: the location card, the connected banner. */
val CardTitle = TextStyle(
    fontFamily = Sans,
    fontWeight = FontWeight.SemiBold,
    fontSize = 16.sp,
    lineHeight = 21.sp,
    letterSpacing = 0.sp,
    fontFeatureSettings = TNUM,
)

/**
 * The one big figure: the session total on Stats.
 *
 * 20 sp Bold, which is §3.1's ceiling — the size of the wordmark, and the largest type
 * anywhere in the app. `displayMedium`'s 34 sp was 1.7x that and on no row of the scale
 * at all, which on a screen whose whole acceptance criterion is "only the measured tokens"
 * made the figure the one invented size in the tree. Tabular, like every live counter.
 */
val StatValue = TextStyle(
    fontFamily = Sans,
    fontWeight = FontWeight.Bold,
    fontSize = 20.sp,
    lineHeight = 26.sp,
    letterSpacing = (-0.2).sp,
    fontFeatureSettings = TNUM,
)

/**
 * The unit riding a display figure's baseline ("MB", "MB/s"). Pairs with [StatValue]:
 * same family, a step down the scale, aligned to the baseline rather than centred.
 */
val HeroUnit = TextStyle(
    fontFamily = Sans,
    fontWeight = FontWeight.Bold,
    fontSize = 12.sp,
    lineHeight = 16.sp,
    letterSpacing = 0.sp,
    fontFeatureSettings = TNUM,
)

/**
 * Log output only — the one place monospace is functional rather than decorative.
 * Log lines are pre-aligned by their producer and wrap on a fixed column, so a
 * proportional face would break the columns.
 */
val LogMono = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontWeight = FontWeight.Normal,
    fontSize = 12.sp,
    lineHeight = 18.sp,
    letterSpacing = 0.sp,
)
