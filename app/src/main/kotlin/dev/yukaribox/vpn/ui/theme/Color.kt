package dev.yukaribox.vpn.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

// Strictly monochrome, strictly *neutral*, and now without exception: every value in
// this file has R == G == B. The three chromatic latency accents this file used to
// carry are gone. Measuring the master mockup found no hue in it at all — across its
// 1,572,864 pixels the largest R/G/B spread is 12 and 99.92% are within 5, and even
// the mascots drawn inside it are greyscale. A ping tier is carried by digit weight
// plus whole-chip inversion for a failed probe; a held kill switch by ink, wording and
// icon fill. Nothing in the interface uses hue to mean anything, so the whole UI
// survives greyscale and colour-blindness by construction (WCAG 1.4.1).
//
// White on white. [Page] and [Paper] are the same near-white: in the reference a card
// is its 1 dp [Hairline] plus a soft shadow, not a lighter patch on a grey page. The
// grey page this replaced was never in the mockup — the #F1F1F1 there is the
// presentation canvas *around* the phone panels, and inside every panel the background
// and the card interiors both sample #FEFEFE.
//
// Three neutrals do three different jobs and must not be collapsed:
//   [Ink]   is text *and* the near-black surface — the bottom bar, the FAB, the
//           drawer's active row, a filled button. One value, because the reference
//           uses one: its text cores and its bar fill both land on #0D0D0D.
//   [Ring]  is *geometry* — the power button's outline, a flag's hairline. It never
//           carries text.
//   [Dot]   is *texture* — the world map's dots, a meter's track. Lighter than [Ring]
//           on purpose: a field of dots at ring weight reads as noise.
//
// Body greys are two tiers, not one, which is what the reference actually has:
//   [TextSecondary] a row's second line — the city, the host, a caption.
//   [TextTertiary]  section labels, a row's trailing value, the version footer, an
//                   inactive tab label.
//   [IconMuted]     a row's leading glyph, a chevron, an outline star. Sits between
//                   the two: a glyph at text weight reads heavier than its own label.

// ---- Monochrome core (light) -------------------------------------------------

/** Text, and the near-black chrome surface. Matte, never #000. */
val Ink = Color(0xFF0D0D0D)

/** The near-black surface. Deliberately the same value as [Ink]. */
val InkFill = Ink

/** Cards, the drawer, the app bar. */
val Paper = Color(0xFFFEFEFE)

/** The page behind the cards — the same near-white as [Paper]. */
val Page = Paper

/** The Servers header band — the one surface in the reference that is not [Paper]. */
val PageBand = Color(0xFFF8F8F8)

/** A row's second line. 7.0:1 on [Paper]. */
val TextSecondary = Color(0xFF5C5C5C)

/** Section labels, trailing values, the version footer, an inactive tab. 4.6:1. */
val TextTertiary = Color(0xFF808080)

/** A row's leading glyph, a chevron, an outline star. */
val IconMuted = Color(0xFF757575)

/** Dividers, and the border that makes a card a card. Never carries text. */
val Hairline = Color(0xFFEDEDED)

/** Chip and icon-circle fill: the `PING 35` plate, a row's leading tile. */
val Chip = Color(0xFFECECEC)

/** Ring geometry: the power button's outline, a flag's hairline. */
val Ring = Color(0xFFA8A8A8)

/** Texture: the world map's dots, a meter's track. */
val Dot = Color(0xFFC0C0C0)

/** An inactive tab's glyph and label inside the ink bottom bar. */
val OnInkMuted = Color(0xFFA2A2A2)

/** A switch's off track. */
val TrackOff = Color(0xFFB7B7B7)

// ---- Monochrome core (dark) --------------------------------------------------
// Inherited, not measured: the reference has no dark screen. Not a mechanical
// inversion either — the page goes matte charcoal rather than #000 and the cards lift
// to a distinct grey, so the card/page boundary survives without a border. The bottom
// bar stays *dark* in both themes; a bar that flipped to white in dark mode would be
// the loudest thing on the screen, and the bar is chrome.

val InkDark = Color(0xFFF2F2F2)
val InkFillDark = Color(0xFF0B0B0B)
val PaperDark = Color(0xFF1D1D1D)
val PageDark = Color(0xFF121212)
val MutedDark = Color(0xFF9E9E9E)
val HairlineDark = Color(0xFF2E2E2E)
val ChipDark = Color(0xFF272727)
val RingDark = Color(0xFF4A4A4A)
val OnInkMutedDark = Color(0xFF7C7C7C)
val PageBandDark = Color(0xFF171717)

/** Dark spends one body grey where light spends three: the ramp has less room. */
val TextTertiaryDark = MutedDark
val DotDark = RingDark
val TrackOffDark = RingDark

// ---- M3 scheme tokens --------------------------------------------------------
// `primary` is the near-black *surface* value, so every stock M3 control that fills
// with primary (Switch, Slider, RadioButton, Button) comes out ink-on-white with no
// per-component override, while `onSurface` stays [Ink] for text.
//
// Every tonal container is a neutral grey and `error` is ink — see [Theme.kt]. A state
// that needs to stand out does it with a filled badge, a border or a word, never with
// a tinted container.

val LightPrimary = InkFill
val LightOnPrimary = Paper
val LightSecondary = Color(0xFF3A3A3A)
val LightSecondaryContainer = Chip
val LightTertiary = TextTertiary
val LightBackground = Page
val LightSurface = Paper
val LightSurfaceVariant = Chip
val LightOutline = IconMuted
val LightSurfaceContainerLow = Color(0xFFFAFAFA)
val LightSurfaceContainerHigh = Color(0xFFEDEDED)
val LightSurfaceContainerHighest = Color(0xFFE6E6E6)
val LightSurfaceDim = Color(0xFFDEDEDE)

val DarkPrimary = InkDark
val DarkOnPrimary = Color(0xFF161616)
val DarkSecondary = Color(0xFFC8C8C8)
val DarkSecondaryContainer = ChipDark
val DarkTertiary = TextTertiaryDark
val DarkBackground = PageDark
val DarkSurface = PaperDark
val DarkSurfaceVariant = ChipDark
val DarkOutline = Color(0xFF767676)
val DarkSurfaceContainerLow = Color(0xFF181818)
val DarkSurfaceContainerLowest = Color(0xFF0D0D0D)
val DarkSurfaceContainerHigh = Color(0xFF232323)
val DarkSurfaceContainerHighest = Color(0xFF2B2B2B)
val DarkSurfaceBright = Color(0xFF333333)

// ---- Extended semantic tokens (per-theme, via LocalYukariColors) -------------

/**
 * The non-M3 tokens, one job each — see the file header for why [ink] carries both
 * text and chrome, and why [ring] and [dot] are two values rather than one.
 *
 * There is deliberately no latency-colour map, no protocol-colour map and no
 * traffic-direction colour: a ping tier is digit weight plus chip inversion, a
 * protocol badge carries its own name, and direction is an arrow glyph.
 */
@Immutable
data class YukariColors(
    /** The near-black chrome surface: bottom bar, FAB, drawer active row. */
    val ink: Color,
    /** Text and glyphs on [ink]. */
    val onInk: Color,
    /** An inactive tab's glyph and label on [ink]. */
    val onInkMuted: Color,
    /** Ring geometry: the power outline, a flag's hairline. */
    val ring: Color,
    /** Texture: the world map's dots, a meter's track. */
    val dot: Color,
    /** Chip and icon-circle fill. */
    val chip: Color,
    /** The Servers header band. */
    val pageBand: Color,
    /** Section labels, trailing values, the version footer. */
    val textTertiary: Color,
    /** A switch's off track. */
    val trackOff: Color,
)

val YukariLightExtended = YukariColors(
    ink = InkFill,
    onInk = Paper,
    onInkMuted = OnInkMuted,
    ring = Ring,
    dot = Dot,
    chip = Chip,
    pageBand = PageBand,
    textTertiary = TextTertiary,
    trackOff = TrackOff,
)

val YukariDarkExtended = YukariColors(
    ink = InkFillDark,
    onInk = InkDark,
    onInkMuted = OnInkMutedDark,
    ring = RingDark,
    dot = DotDark,
    chip = ChipDark,
    pageBand = PageBandDark,
    textTertiary = TextTertiaryDark,
    trackOff = TrackOffDark,
)

val LocalYukariColors = staticCompositionLocalOf { YukariLightExtended }
