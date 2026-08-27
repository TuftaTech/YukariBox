package dev.yukaribox.vpn.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit tests for the pure per-app split-tunnel decision (US-010). */
class PerAppRoutingTest {

    private val self = "dev.yukaribox.vpn"

    @Test
    fun allAppsViaVpnByDefault() {
        // No packages selected (the default): every app is tunneled, only our own
        // app is excluded — nothing is added to the allow list.
        val plan = PerAppRouting.plan(include = false, packages = emptySet(), selfPackage = self)
        assertTrue(plan.allowed.isEmpty())
        assertEquals(listOf(self), plan.disallowed)
    }

    @Test
    fun defaultIsAllAppsRegardlessOfMode() {
        // Mode flag is irrelevant while nothing is selected.
        val plan = PerAppRouting.plan(include = true, packages = emptySet(), selfPackage = self)
        assertTrue(plan.allowed.isEmpty())
        assertEquals(listOf(self), plan.disallowed)
    }

    @Test
    fun excludeModeBypassesListedAppsPlusSelf() {
        // Exclusion mode: listed apps + our own app bypass the tunnel; nothing is
        // force-allowed (everything not disallowed stays tunneled).
        val plan = PerAppRouting.plan(include = false, packages = setOf("com.a", "com.b"), selfPackage = self)
        assertTrue(plan.allowed.isEmpty())
        assertTrue(plan.disallowed.contains(self))
        assertTrue(plan.disallowed.contains("com.a"))
        assertTrue(plan.disallowed.contains("com.b"))
    }

    @Test
    fun includeModeTunnelsOnlyListedApps() {
        val plan = PerAppRouting.plan(include = true, packages = setOf("com.a", "com.b"), selfPackage = self)
        assertTrue(plan.disallowed.isEmpty())
        assertTrue(plan.allowed.contains("com.a"))
        assertTrue(plan.allowed.contains("com.b"))
    }

    @Test
    fun selfIsNeverTunneled() {
        // Self filtered out of the allow list in include mode (so it bypasses)...
        val inc = PerAppRouting.plan(include = true, packages = setOf(self, "com.a"), selfPackage = self)
        assertTrue(self !in inc.allowed)
        assertEquals(listOf("com.a"), inc.allowed)
        // ...and listed exactly once (no duplicate) in the disallow list in exclude mode.
        val exc = PerAppRouting.plan(include = false, packages = setOf(self, "com.a"), selfPackage = self)
        assertEquals(1, exc.disallowed.count { it == self })
    }

    @Test
    fun includeModeWithOnlyOurOwnPackageStillExcludesUs() {
        // The degenerate case: filtering self out left nothing, and two empty lists
        // mean "tunnel everything" to VpnService.Builder — including us, which is the
        // routing loop self-exclusion exists to prevent.
        val plan = PerAppRouting.plan(include = true, packages = setOf(self), selfPackage = self)
        assertTrue(plan.allowed.isEmpty())
        assertEquals(listOf(self), plan.disallowed)
    }

    @Test
    fun excludeModeWithOnlyOurOwnPackageIsTheDefaultPlan() {
        val plan = PerAppRouting.plan(include = false, packages = setOf(self), selfPackage = self)
        assertTrue(plan.allowed.isEmpty())
        assertEquals(listOf(self), plan.disallowed)
    }

    @Test
    fun neitherModeEverLeavesBothListsEmpty() {
        // The invariant behind both cases above: some plan must always keep us out.
        for (include in listOf(true, false)) {
            for (packages in listOf(emptySet(), setOf(self), setOf(self, "com.a"), setOf("com.a"))) {
                val plan = PerAppRouting.plan(include, packages, self)
                assertTrue(
                    "include=$include packages=$packages tunnels everything including self",
                    plan.allowed.isNotEmpty() || plan.disallowed.contains(self),
                )
            }
        }
    }
}
