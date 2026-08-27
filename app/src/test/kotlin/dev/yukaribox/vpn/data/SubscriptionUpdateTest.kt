package dev.yukaribox.vpn.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubscriptionUpdateTest {

    @Test
    fun emptyFetchWithWorkingNodesIsFailure_keepsOld() {
        // The failure path: a fetch returned no nodes but the user has working ones.
        assertTrue(SubscriptionUpdate.isResilientFailure(freshCount = 0, priorCount = 5))
    }

    @Test
    fun nonEmptyFetchApplies() {
        assertFalse(SubscriptionUpdate.isResilientFailure(freshCount = 3, priorCount = 5))
    }

    @Test
    fun firstImportOfEmptyIsAllowed() {
        // No prior nodes: an empty result is a legitimate (if empty) first import,
        // not a failure that would wipe anything.
        assertFalse(SubscriptionUpdate.isResilientFailure(freshCount = 0, priorCount = 0))
    }

    @Test
    fun firstImportWithNodesApplies() {
        assertFalse(SubscriptionUpdate.isResilientFailure(freshCount = 5, priorCount = 0))
    }

    // ---- group naming: the old "last path segment" rule published the access token

    @Test
    fun nameIsTheHostNotTheAccessToken() {
        assertEquals(
            "sub.example.com",
            SubscriptionUpdate.deriveName("https://sub.example.com/sub/9f3a1c7b-secret-token"),
        )
    }

    @Test
    fun trailingSlashesAndPortsDoNotLeakIntoTheName() {
        assertEquals("sub.example.com", SubscriptionUpdate.deriveName("https://sub.example.com/sub/token/"))
        assertEquals("sub.example.com", SubscriptionUpdate.deriveName("https://sub.example.com:8443/x"))
        assertEquals("sub.example.com", SubscriptionUpdate.deriveName("  https://sub.example.com/a  "))
    }

    @Test
    fun aQueryStringTokenDoesNotBecomeTheName() {
        assertEquals("host.tld", SubscriptionUpdate.deriveName("https://host.tld/link?token=abcdef123456"))
    }

    @Test
    fun unparseableUrlFallsBackInsteadOfThrowing() {
        // Evaluated as a default argument, before any validation runs.
        assertEquals("subscription", SubscriptionUpdate.deriveName("not a url"))
        assertEquals("subscription", SubscriptionUpdate.deriveName(""))
        assertEquals("subscription", SubscriptionUpdate.deriveName("file:///etc/passwd"))
    }
}
