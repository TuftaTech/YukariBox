package dev.yukaribox.vpn.ui

/**
 * Every destination in the app.
 *
 * Three of these are bottom-bar tabs ([Home], [Stats], [Profile]); the rest are
 * pushed over one. Which is which lives in `Navigation.kt` rather than here, so this
 * stays a flat list of places.
 *
 * [Servers] is the one hybrid: it is pushed (so it has no bottom bar) but it wears the
 * hamburger rather than a back arrow, because it is one of the drawer's own
 * destinations and the mockup draws it that way. [Groups] is a drawer destination too,
 * but an ordinary pushed one — it is opened *from* somewhere and returns there.
 *
 * Deliberately carries no title resource. Every screen names itself — in its own app
 * bar, from the string its content is built around — and the copy this enum used to
 * hold had already drifted from what one of them rendered (`About` here against
 * `Help & Support` on screen). One name per screen, in the screen.
 */
enum class Screen {
    Home,
    Stats,
    Profile,
    Servers,
    Groups,
    Settings,
    PerApp,
    Routes,
    Logs,
    Backup,
    NodeEdit,
    About,
}
