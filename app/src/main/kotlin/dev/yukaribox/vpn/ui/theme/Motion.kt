package dev.yukaribox.vpn.ui.theme

import androidx.compose.animation.core.CubicBezierEasing

/**
 * Three durations, one curve, one revolution. That is the whole motion vocabulary, and it
 * is short on purpose: the interface is an instrument panel, so movement is there to keep
 * a change legible, never to perform.
 *
 * The rules that go with the numbers, because the numbers alone do not enforce them:
 *
 * - **No physics.** Springs belong to interfaces that imitate matter; this one imitates a
 *   panel, where a needle settles on a value and stops. Every *transition* in the app is a
 *   tween on [Standard], including the three lazy lists, whose `animateItem` would spring
 *   by default and is handed a spec instead. The one exception is a **scroll**: the group
 *   strip's jump to the active tab keeps the platform's own spring, because a scroll is a
 *   fling and a flung list that arrives on a curve feels held rather than thrown. The
 *   setting still turns it off, and it becomes an instant jump.
 * - **Measurements never animate.** Byte counters, the session clock, a ping, the log: a
 *   tweened number puts values on screen that were never measured. They snap.
 * - **Nothing moves on Home.** The ring, the map and the portrait hold still; state there
 *   changes by crossfade, never by sliding something into place.
 * - **Only a revolution may be linear.** [SWEEP] is a constant-rate rotation, and easing a
 *   rotation reads as a stutter. Everywhere else [Standard] is the only curve.
 *
 * The "Animations" setting is honoured in one place — `ui/kit/motionSpec` — which hands out
 * every spec below and returns `snap()` when the user has turned motion off. Two things
 * ignore it because they carry information rather than decoration: the busy sweep, which is
 * how the app says it is working, and the navigation drawer's own M3 animation, which is
 * not ours to remove.
 */
object YukariMotion {

    /**
     * A control changing its own state: the switch knob travelling, a star filling, a chip
     * inverting, the connect ring going solid. Short enough to read as a click.
     */
    const val FLIP = 90

    /**
     * Content replacing content in place: a tab crossfade, Home's status card becoming the
     * connected banner, a list row arriving or leaving, a progress bar advancing.
     */
    const val SWAP = 160

    /**
     * Hierarchy: a detail screen arriving over a tab and leaving again. Longer than [SWAP]
     * because it also carries a direction, and near the M3 drawer's own 250 ms so the two
     * read as one family rather than two.
     */
    const val PUSH = 240

    /** One revolution of the busy sweep — the app's only continuous motion. */
    const val SWEEP = 1100

    /** The only easing: quick to leave, quiet to arrive, no overshoot. */
    val Standard = CubicBezierEasing(0.2f, 0f, 0f, 1f)
}
