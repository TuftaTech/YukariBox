package dev.yukaribox.vpn.ui

import androidx.annotation.StringRes
import androidx.compose.ui.graphics.vector.ImageVector
import dev.yukaribox.vpn.R
import dev.yukaribox.vpn.core.TunnelState
import dev.yukaribox.vpn.ui.theme.YukariIcons

/**
 * What the connect circle, its two labels and the security line render — plus the
 * string lookups that go with them.
 *
 * [TunnelState] alone cannot express the two fail-closed outcomes: the state machine
 * reads Disconnected while a *blocking* TUN is still installed ([Blocked]) and also
 * when arming that TUN failed ([Unprotected]). Both have to be distinguishable from a
 * tunnel the user simply switched off — in the first case packets are being dropped on
 * purpose, in the second they are leaving unproxied — which is why every surface folds
 * `TunnelController.failClosed` and `.unprotected` in through [statusView] instead of
 * reading the state enum directly.
 *
 * Lives outside the screens so those rules are one `when` each rather than repeated on
 * Home, on the banner and in the security line.
 */
internal enum class StatusView {
    Disconnected,
    Connecting,
    Connected,
    Disconnecting,
    Blocked,
    Unprotected,
    ;

    /** True while a transition is in flight and the toggle must not be re-entered. */
    val isTransitioning: Boolean get() = this == Connecting || this == Disconnecting

    /** Whether the connect circle is drawn filled — see `PowerButton`. */
    val filled: Boolean get() = this == Connected
}

/** Fold the tunnel state and the two fail-closed flags into one display state. */
internal fun statusView(state: TunnelState, failClosed: Boolean, unprotected: Boolean): StatusView =
    when (state) {
        TunnelState.Connecting -> StatusView.Connecting
        TunnelState.Connected -> StatusView.Connected
        TunnelState.Disconnecting -> StatusView.Disconnecting
        TunnelState.Disconnected -> when {
            failClosed -> StatusView.Blocked
            unprotected -> StatusView.Unprotected
            else -> StatusView.Disconnected
        }
    }

/**
 * Which of Home's three cards a state calls for.
 *
 * Not the same thing as [StatusView]: six states share three cards, and the crossfade
 * between them has to key on the card rather than on the state, or Disconnected becoming
 * Connecting — the same card with a different word on the circle above it — would fade a
 * card out and back in for nothing.
 */
internal enum class StatusSlot { Location, Connected, FailClosed }

/** @see StatusSlot */
internal fun statusSlot(view: StatusView): StatusSlot = when (view) {
    StatusView.Blocked, StatusView.Unprotected -> StatusSlot.FailClosed
    StatusView.Connected -> StatusSlot.Connected
    else -> StatusSlot.Location
}

/** The word above the circle: `DISCONNECTED`, `CONNECTED`, `TRAFFIC BLOCKED`… */
@StringRes
internal fun stateLabelRes(view: StatusView): Int = when (view) {
    StatusView.Disconnected -> R.string.state_disconnected
    StatusView.Connecting -> R.string.state_connecting
    StatusView.Connected -> R.string.state_connected
    StatusView.Disconnecting -> R.string.state_disconnecting
    StatusView.Blocked -> R.string.state_blocked
    StatusView.Unprotected -> R.string.state_unprotected
}

/**
 * The caption below the circle. It names the *action* the tap performs, which is why
 * the two fail-closed views read "tap to reconnect" rather than "tap to connect":
 * there is still a session, and what the user wants is the tunnel back.
 */
@StringRes
internal fun captionRes(view: StatusView): Int = when (view) {
    StatusView.Disconnected -> R.string.tap_to_connect
    StatusView.Connecting -> R.string.tap_connecting
    StatusView.Connected -> R.string.tap_to_disconnect
    StatusView.Disconnecting -> R.string.tap_disconnecting
    StatusView.Blocked, StatusView.Unprotected -> R.string.tap_to_reconnect
}

/**
 * How much of the device's traffic a session actually carries.
 *
 * [StatusView] answers "is the tunnel up"; this answers "up for what", and the security
 * line needs both. A tunnel that is Connected is not the same claim in all three cases:
 * [ProxyOnly] owns no TUN and captures nothing (only a client the user pointed at the local
 * mixed inbound is proxied), and [SplitTunnel] leaves whole apps on the physical interface
 * by the user's own instruction.
 */
internal enum class TunnelScope { Full, SplitTunnel, ProxyOnly }

/** Pure fold of the two settings that decide the scope; proxy-only wins over split. */
internal fun tunnelScope(proxyOnly: Boolean, splitTunnel: Boolean): TunnelScope = when {
    proxyOnly -> TunnelScope.ProxyOnly
    splitTunnel -> TunnelScope.SplitTunnel
    else -> TunnelScope.Full
}

/**
 * The security line under the location card. Six distinct sentences, because the situations
 * are genuinely different and one of them ([StatusView.Unprotected]) is the only one the app
 * must never soften: the kill switch failed and traffic is in the clear.
 *
 * [scope] is what stops the opposite error. Derived from tunnel state alone, this said "your
 * connection is protected" for a proxy-only session that owns no TUN and captures nothing,
 * and for an include-mode split tunnel where every app the user did not list goes out
 * directly. That is the most prominent sentence on Home, and in both cases it was false.
 * Only [StatusView.Connected] varies with scope: in every other view the tunnel is not
 * carrying traffic, so how much of it *would* be carried says nothing.
 */
@StringRes
internal fun securityRes(view: StatusView, scope: TunnelScope): Int = when (view) {
    StatusView.Connected -> when (scope) {
        TunnelScope.Full -> R.string.security_protected
        TunnelScope.SplitTunnel -> R.string.security_protected_partial
        TunnelScope.ProxyOnly -> R.string.security_proxy_only
    }
    StatusView.Connecting -> R.string.security_connecting
    StatusView.Disconnecting, StatusView.Disconnected -> R.string.security_unprotected
    StatusView.Blocked -> R.string.security_blocked
    StatusView.Unprotected -> R.string.security_failed
}

/**
 * The shield beside the security sentence. Fill state, not colour, and four shapes for
 * four readings: filled with a check means the tunnel is carrying traffic, filled plain
 * means the kill switch is holding every packet, outlined means the tunnel is simply
 * off, and struck through means there is no protection at all because arming the kill
 * switch failed.
 *
 * [StatusView.Blocked] must share a glyph with neither [StatusView.Connected] nor
 * [StatusView.Disconnected]. It is not a clean stop — the app is actively dropping
 * packets — and it is not protection either, so it takes the solid shield between the
 * two: the fill says "being enforced", the missing check says "nothing is getting
 * through". [StatusView.Unprotected] is its opposite and takes the struck shield. With
 * no hue left in the palette the glyph is half of what tells the three apart; the other
 * half is [connectDescriptionRes], which names each one for a screen reader.
 */
internal fun statusGlyph(view: StatusView): ImageVector = when (view) {
    StatusView.Connected -> YukariIcons.ShieldOk
    StatusView.Blocked -> YukariIcons.ShieldFilled
    StatusView.Unprotected -> YukariIcons.ShieldOff
    else -> YukariIcons.Shield
}

/**
 * Accessible label for the connect control. The circle shows a glyph only, so this is
 * its whole label — it has to name the action the tap actually performs.
 *
 * The two fail-closed views read as *reconnect*, not as "stop": the state machine is
 * Disconnected in both, so the toggle takes the connect branch. Labelling it "stop the
 * session" told a screen-reader user the opposite of what the visible caption said, and
 * the opposite of what the control does. Releasing the block is a separate, labelled
 * button on the fail-closed card and is never this control.
 *
 * They read as two *different* reconnects, though. One sentence for both said nothing
 * about which of the two situations the user is in, and a screen-reader user has no
 * shield glyph and no fail-closed card to fall back on — so the action comes first and
 * the state qualifies it.
 */
@StringRes
internal fun connectDescriptionRes(view: StatusView): Int = when (view) {
    StatusView.Disconnected -> R.string.cd_connect
    StatusView.Connecting -> R.string.cd_cancel_connect
    StatusView.Connected -> R.string.cd_disconnect
    StatusView.Disconnecting -> R.string.cd_disconnecting
    StatusView.Blocked -> R.string.cd_reconnect_blocked
    StatusView.Unprotected -> R.string.cd_reconnect_unprotected
}
