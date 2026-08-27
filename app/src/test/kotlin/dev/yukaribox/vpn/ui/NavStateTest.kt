package dev.yukaribox.vpn.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The shell's back-stack rules — the ones that would otherwise only be discoverable by
 * tapping, and three of which are easy to regress: re-selecting a tab must pop it, the
 * bottom bar must disappear on any pushed screen, and Back from a non-Home tab root must
 * land on Home rather than closing the app.
 */
class NavStateTest {

    @Test
    fun startsAtHomeRoot() {
        val state = NavState()
        assertEquals(Tab.Home, state.tab)
        assertEquals(Screen.Home, state.current)
        assertTrue(state.barVisible)
        // Home's root is the one place Back should fall through to the system.
        assertFalse(state.canPop)
    }

    @Test
    fun everyTabRootShowsTheBar() {
        Tab.entries.forEach { tab ->
            val state = NavState(tab)
            assertEquals(tab.root, state.current)
            assertTrue("bar hidden at ${tab.name} root", state.barVisible)
        }
    }

    @Test
    fun everyTabRootIsADistinctScreen() {
        val roots = Tab.entries.map { it.root }
        assertEquals("two tabs share a root screen", roots.size, roots.distinct().size)
    }

    @Test
    fun pushHidesTheBarAndPopRestoresIt() {
        val pushed = NavState(Tab.Profile).push(Screen.Settings)
        assertEquals(Screen.Settings, pushed.current)
        assertFalse(pushed.barVisible)
        assertTrue(pushed.canPop)

        val popped = pushed.pop()
        assertEquals(Screen.Profile, popped.current)
        assertTrue(popped.barVisible)
    }

    @Test
    fun everyPushedScreenHidesTheBar() {
        // Exhaustive: the bar carries no tunnel control any more, but it does carry the
        // three tabs, and a pushed screen showing them would let one stray tap discard a
        // half-filled server form.
        val roots = Tab.entries.map { it.root }.toSet()
        Screen.entries.filterNot { it in roots }.forEach { screen ->
            val pushed = NavState().push(screen)
            assertEquals(screen, pushed.current)
            assertFalse("$screen still shows the bottom bar", pushed.barVisible)
        }
    }

    @Test
    fun pushIsNotDuplicatedForTheSameScreen() {
        val once = NavState().push(Screen.NodeEdit)
        val twice = once.push(Screen.NodeEdit)
        // A double-tapped row must not stack two editors, or one Back leaves the user
        // staring at an identical screen.
        assertSame(once, twice)
        assertEquals(1, twice.stack.size)
    }

    @Test
    fun deepStackPopsOneLevelAtATime() {
        val deep = NavState(Tab.Profile).push(Screen.Settings).push(Screen.PerApp)
        assertEquals(Screen.PerApp, deep.current)
        assertEquals(Screen.Settings, deep.pop().current)
        assertEquals(Screen.Profile, deep.pop().pop().current)
    }

    @Test
    fun reselectingTheCurrentTabPopsToItsRoot() {
        val deep = NavState(Tab.Profile).push(Screen.Settings).push(Screen.Logs)
        val reselected = deep.select(Tab.Profile)
        assertEquals(Screen.Profile, reselected.current)
        assertTrue(reselected.stack.isEmpty())
    }

    @Test
    fun switchingTabsClearsTheStack() {
        val fromDeep = NavState(Tab.Profile).push(Screen.Settings).select(Tab.Stats)
        assertEquals(Tab.Stats, fromDeep.tab)
        assertEquals(Screen.Stats, fromDeep.current)
        // The abandoned tab's stack is dropped rather than kept: coming back to a
        // half-finished editor the user navigated away from is worse than a root.
        assertTrue(fromDeep.stack.isEmpty())
    }

    @Test
    fun popFromNonHomeTabRootGoesToHome() {
        Tab.entries.filterNot { it == Tab.Home }.forEach { tab ->
            val state = NavState(tab).pop()
            assertEquals("Back from ${tab.name} did not reach Home", Tab.Home, state.tab)
            assertEquals(Screen.Home, state.current)
        }
    }

    @Test
    fun popAtHomeRootIsIdentity() {
        val home = NavState()
        assertSame(home, home.pop())
    }

    @Test
    fun theDrawerCanReachTheSameScreenFromAnyTab() {
        // The stack is shared rather than per-tab, so a drawer destination opened from
        // Stats and from Profile is one screen with one Back destination, not two.
        Tab.entries.forEach { tab ->
            val opened = NavState(tab).push(Screen.Servers)
            assertEquals(Screen.Servers, opened.current)
            assertEquals(tab, opened.tab)
            assertEquals(tab.root, opened.pop().current)
        }
    }
}
