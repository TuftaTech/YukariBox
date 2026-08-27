package dev.yukaribox.vpn.vpn

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pure half of the split-tunnel question, which two surfaces now share: the fail-closed
 * notification (choosing between "traffic blocked" and the partial wording) and Home's
 * security line (choosing between "protected" and "some apps bypass"). The notification's
 * honesty rests on the wording being unable to drift from the routing, and one function
 * derived from [PerAppRouting.plan] is what makes that true of both.
 */
class SplitTunnelInUseTest {

    private val self = "dev.yukaribox.vpn"

    @Test
    fun theDefaultPlanIsNotASplitTunnel() {
        // Everything through the VPN, only our own package excluded. Reporting that as a
        // split tunnel would put "some apps bypass" on every ordinary session.
        assertFalse(splitTunnelInUse(include = false, packages = emptySet(), selfPackage = self))
    }

    @Test
    fun excludingOnlyOurselvesIsStillNotASplitTunnel() {
        assertFalse(splitTunnelInUse(include = false, packages = setOf(self), selfPackage = self))
    }

    @Test
    fun excludingAnyOtherAppIsASplitTunnel() {
        assertTrue(splitTunnelInUse(include = false, packages = setOf("com.example.app"), selfPackage = self))
    }

    @Test
    fun includeModeIsASplitTunnelEvenWithOneApp() {
        // Include mode is the sharper case: everything the user did *not* list goes out
        // directly, so a one-entry list leaves the whole device outside the tunnel.
        assertTrue(splitTunnelInUse(include = true, packages = setOf("com.example.app"), selfPackage = self))
    }

    @Test
    fun anIncludeListThatNamesNothingUsableFallsBackToTheDefault() {
        // An include list holding only our own package resolves to nothing to allow, and
        // PerAppRouting documents that as the default plan rather than as "tunnel nothing".
        assertFalse(splitTunnelInUse(include = true, packages = setOf(self), selfPackage = self))
        assertFalse(splitTunnelInUse(include = true, packages = emptySet(), selfPackage = self))
    }
}
