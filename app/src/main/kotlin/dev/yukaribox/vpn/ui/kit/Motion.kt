package dev.yukaribox.vpn.ui.kit

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import dev.yukaribox.vpn.core.SettingsStore
import dev.yukaribox.vpn.ui.theme.YukariMotion

/**
 * Where every surface in the app gets its animation spec, and the one place the
 * "Animations" setting is honoured.
 *
 * Three functions for the three durations in `YukariMotion`, generic in the value being
 * animated so a `Dp`, a `Color`, an `IntOffset` and a plain `Float` all come from the same
 * three names. With motion switched off each returns `snap()` — the change still happens,
 * it simply happens on the frame it was asked for, which is what a user who turned
 * animations off asked for. Shortening the durations instead would leave a flicker.
 *
 * Call these rather than writing `tween(...)` at a call site: a spec typed out locally is
 * how an app ends up with five timings and four curves, which is exactly the state this
 * replaced. The two exceptions are documented where they live — the busy sweep's
 * `infiniteRepeatable`, which is a rotation rather than a transition, and `ScreenHost`,
 * which needs whole `EnterTransition`s and turns motion off by removing them outright.
 */
@Composable
fun <T> flipSpec(): FiniteAnimationSpec<T> = motionSpec(YukariMotion.FLIP)

/** @see flipSpec */
@Composable
fun <T> swapSpec(): FiniteAnimationSpec<T> = motionSpec(YukariMotion.SWAP)

/** True when the user has left motion on; for the few places that need the flag itself. */
@Composable
fun motionEnabled(): Boolean = SettingsStore.animations

@Composable
private fun <T> motionSpec(duration: Int): FiniteAnimationSpec<T> =
    if (motionEnabled()) tween(duration, easing = YukariMotion.Standard) else snap()
