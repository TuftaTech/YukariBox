package dev.yukaribox.vpn.ui

/**
 * The shell's navigation model, kept pure so the back-stack rules are unit-tested
 * rather than discovered by tapping ([dev.yukaribox.vpn.ui.NavStateTest]).
 *
 * Three bottom-bar tabs and one shared stack of detail screens above them. There is no
 * navigation library: the whole model is an immutable data class and three
 * transitions, which is less code than a graph definition and keeps "where does Back
 * go" answerable by reading twelve lines.
 *
 * The stack is deliberately *shared* rather than one per tab. Everything the drawer
 * opens — Servers, Settings, the log — is reachable from whichever tab the user
 * happens to be on, so a per-tab stack would mean the same screen could be open in
 * three places at once with three different Back destinations.
 */
enum class Tab(val root: Screen) {
    Home(Screen.Home),
    Stats(Screen.Stats),
    Profile(Screen.Profile),
}

/**
 * The drawer's seven entries. Not a [Screen] set: `Share` is an action rather than a
 * destination and `Home` resets the whole stack rather than pushing anything, both of
 * which a `Screen`-typed drawer would have to lie about.
 *
 * `Home` is not in the mockup's drawer, and it is not decoration: every screen the drawer
 * opens now wears the hamburger instead of a back arrow, so without a way home from inside
 * the drawer the only route back to the connect screen would be the system Back gesture.
 */
internal enum class DrawerItem { Home, Servers, Groups, Settings, Log, Help, Share }

/**
 * Where the shell is: the selected [tab] and the detail screens pushed above it.
 *
 * An empty [stack] means a tab root is showing, which is also exactly the condition
 * for the bottom bar being visible — see [barVisible].
 */
data class NavState(
    val tab: Tab = Tab.Home,
    val stack: List<Screen> = emptyList(),
) {
    /** The screen to render. */
    val current: Screen get() = stack.lastOrNull() ?: tab.root

    /**
     * The bottom bar shows only at a tab root. A pushed screen is a focused task with
     * its own actions and often its own Save/Cancel, and the mockup draws every one of
     * them full-height.
     */
    val barVisible: Boolean get() = stack.isEmpty()

    /** True when Back has somewhere to go inside the shell. */
    val canPop: Boolean get() = stack.isNotEmpty() || tab != Tab.Home

    /**
     * Select a tab. Re-selecting the current one pops the stack instead of doing
     * nothing — that is what a second tap on an active tab means everywhere else, and
     * without it a user on a pushed screen taps its tab and the screen does not change.
     */
    fun select(target: Tab): NavState =
        if (target == tab) copy(stack = emptyList()) else NavState(target, emptyList())

    /**
     * Push a detail screen. A screen already on top is not re-pushed, so a
     * double-tapped row cannot stack two copies of the same editor.
     */
    fun push(screen: Screen): NavState =
        if (stack.lastOrNull() == screen) this else copy(stack = stack + screen)

    /**
     * Pop one level: the top detail screen, or — at a tab root that is not Home — back
     * to Home. Returns `this` at Home's root, so the caller can let the system handle
     * Back and close the app.
     */
    fun pop(): NavState = when {
        stack.isNotEmpty() -> copy(stack = stack.dropLast(1))
        tab != Tab.Home -> NavState(Tab.Home, emptyList())
        else -> this
    }
}
