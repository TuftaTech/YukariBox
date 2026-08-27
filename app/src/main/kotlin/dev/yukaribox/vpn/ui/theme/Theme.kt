package dev.yukaribox.vpn.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material.ripple.RippleAlpha
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RippleConfiguration
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

// Light: ink text and near-black fills on white paper over a page that is the same
// white — a card is its hairline and its shadow, not a lighter patch.
private val YukariLightScheme = lightColorScheme(
    primary = LightPrimary,
    onPrimary = LightOnPrimary,
    primaryContainer = LightPrimary,
    onPrimaryContainer = LightOnPrimary,
    inversePrimary = Paper,
    secondary = LightSecondary,
    onSecondary = Paper,
    secondaryContainer = LightSecondaryContainer,
    onSecondaryContainer = Ink,
    tertiary = LightTertiary,
    onTertiary = Paper,
    tertiaryContainer = Chip,
    onTertiaryContainer = Ink,
    background = LightBackground,
    onBackground = Ink,
    surface = LightSurface,
    onSurface = Ink,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = TextSecondary,
    surfaceTint = LightPrimary,
    inverseSurface = InkFill,
    inverseOnSurface = Paper,
    // `error` is ink, not red. Severity is carried by wording, weight and icon fill;
    // every stock M3 control that resolves a colour from this slot comes out neutral.
    error = Ink,
    onError = Paper,
    errorContainer = Chip,
    onErrorContainer = Ink,
    outline = LightOutline,
    outlineVariant = Hairline,
    scrim = Color.Black,
    surfaceBright = Paper,
    surfaceDim = LightSurfaceDim,
    surfaceContainer = Page,
    surfaceContainerHigh = LightSurfaceContainerHigh,
    surfaceContainerHighest = LightSurfaceContainerHighest,
    surfaceContainerLow = LightSurfaceContainerLow,
    surfaceContainerLowest = Paper,
)

// Dark: the same neutral ramp inverted — matte charcoal page, lifted grey cards.
private val YukariDarkScheme = darkColorScheme(
    primary = DarkPrimary,
    onPrimary = DarkOnPrimary,
    primaryContainer = DarkPrimary,
    onPrimaryContainer = DarkOnPrimary,
    inversePrimary = DarkOnPrimary,
    secondary = DarkSecondary,
    onSecondary = DarkOnPrimary,
    secondaryContainer = DarkSecondaryContainer,
    onSecondaryContainer = InkDark,
    tertiary = DarkTertiary,
    onTertiary = DarkOnPrimary,
    tertiaryContainer = ChipDark,
    onTertiaryContainer = InkDark,
    background = DarkBackground,
    onBackground = InkDark,
    surface = DarkSurface,
    onSurface = InkDark,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = MutedDark,
    surfaceTint = DarkPrimary,
    inverseSurface = InkDark,
    inverseOnSurface = DarkOnPrimary,
    error = InkDark,
    onError = DarkOnPrimary,
    errorContainer = ChipDark,
    onErrorContainer = InkDark,
    outline = DarkOutline,
    outlineVariant = HairlineDark,
    scrim = Color.Black,
    surfaceBright = DarkSurfaceBright,
    surfaceDim = PageDark,
    surfaceContainer = PaperDark,
    surfaceContainerHigh = DarkSurfaceContainerHigh,
    surfaceContainerHighest = DarkSurfaceContainerHighest,
    surfaceContainerLow = DarkSurfaceContainerLow,
    surfaceContainerLowest = DarkSurfaceContainerLowest,
)

/**
 * The app theme.
 *
 * The navigation-bar icons are forced to *light* in both themes, unlike the status
 * bar: the ink bottom bar runs to the bottom edge of the screen, so on a device with
 * gesture navigation the system's handle sits on top of it and dark handles would
 * disappear into the bar.
 */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun YukariBoxTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) YukariDarkScheme else YukariLightScheme
    val extended = if (darkTheme) YukariDarkExtended else YukariLightExtended
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            val controller = WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = !darkTheme
            controller.isAppearanceLightNavigationBars = false
        }
    }
    CompositionLocalProvider(
        LocalYukariColors provides extended,
        // Stated rather than inherited. M3 derives a ripple from `contentColor` through its
        // own alpha table, which on an ink-on-paper palette lands somewhere between a wash
        // and a stain depending on the surface underneath. Ink at [YukariRippleAlpha] is the
        // same quiet grey on a white card, on the near-black bottom bar and in dark theme.
        LocalRippleConfiguration provides RippleConfiguration(
            color = colorScheme.onSurface,
            rippleAlpha = YukariRippleAlpha,
        ),
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = YukariTypography,
            shapes = YukariShapes,
            content = content,
        )
    }
}

/** Extended (non-M3) tokens: the ink chrome surface, ring and dot geometry, the band. */
val MaterialTheme.yukari: YukariColors
    @Composable get() = LocalYukariColors.current

/**
 * How loud a touch is. Deliberately below M3's own numbers (0.12/0.16 pressed on a light
 * surface): the interface has one dark fill, one ink and a lot of white, so a press that
 * reads as a grey breath is enough to say "counted" without becoming the most visible thing
 * on the screen. The wave itself keeps the platform's timing — it is feedback on a gesture,
 * not a transition between two states.
 */
private val YukariRippleAlpha = RippleAlpha(
    draggedAlpha = 0.10f,
    focusedAlpha = 0.10f,
    hoveredAlpha = 0.06f,
    pressedAlpha = 0.08f,
)
