package dev.yukaribox.vpn.core

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Bounds that keep a bad number from disarming the kill switch: an MTU the kernel
 * rejects fails `VpnService.Builder.establish()`, including the blocking fail-closed
 * TUN, which would leave traffic unprotected while the notification claimed otherwise.
 */
class SettingsGuardTest {

    @Test
    fun mtuIsClampedIntoTheAcceptedRange() {
        assertEquals(SettingsGuard.MTU_MIN, SettingsGuard.mtu(0))
        assertEquals(SettingsGuard.MTU_MIN, SettingsGuard.mtu(-1))
        assertEquals(SettingsGuard.MTU_MIN, SettingsGuard.mtu(Int.MIN_VALUE))
        assertEquals(SettingsGuard.MTU_MAX, SettingsGuard.mtu(100_000))
        assertEquals(SettingsGuard.MTU_MAX, SettingsGuard.mtu(Int.MAX_VALUE))
    }

    @Test
    fun mtuInRangeIsUntouched() {
        assertEquals(1280, SettingsGuard.mtu(1280))
        assertEquals(SettingsGuard.MTU_MIN, SettingsGuard.mtu(SettingsGuard.MTU_MIN))
        assertEquals(SettingsGuard.MTU_MAX, SettingsGuard.mtu(SettingsGuard.MTU_MAX))
    }

    @Test
    fun sanitizeFixesEveryOutOfRangeFieldAtOnce() {
        // The shape a hostile or hand-edited settings.json / restored backup can have.
        val hostile = SettingsData(
            mtu = 0,
            autoUpdateInterval = 0,
            logLevel = "not-a-level",
        )
        val safe = SettingsGuard.sanitize(hostile)
        assertEquals(SettingsGuard.MTU_MIN, safe.mtu)
        assertEquals(15, safe.autoUpdateInterval)
        assertEquals("info", safe.logLevel)
    }

    @Test
    fun sanitizeKeepsAValidSnapshotIntact() {
        val good = SettingsData(mtu = 9000, autoUpdateInterval = 1440, logLevel = "warn")
        assertEquals(good, SettingsGuard.sanitize(good))
    }

    @Test
    fun sanitizeAcceptsEveryLevelTheLoggerKnows() {
        for (level in listOf("trace", "debug", "info", "warn", "error", "panic")) {
            assertEquals(level, SettingsGuard.sanitize(SettingsData(logLevel = level)).logLevel)
        }
    }

    // ---- DNS: the core dials its own sockets, so cleartext DoH is not governed by
    // ---- network_security_config and has to be refused here.

    @Test
    fun cleartextDohUrlsAreReplacedByTheDefault() {
        val fallback = "https://1.1.1.1/dns-query"
        assertEquals(fallback, SettingsGuard.dnsAddress("http://dns.example/dns-query", fallback))
        // Scheme comparison is case-insensitive and tolerates padding.
        assertEquals(fallback, SettingsGuard.dnsAddress("HTTP://dns.example/dns-query", fallback))
        assertEquals(fallback, SettingsGuard.dnsAddress("  http://dns.example/dns-query  ", fallback))
    }

    @Test
    fun theOtherFormsTheCoreUnderstandsAreLeftAlone() {
        val fallback = "https://1.1.1.1/dns-query"
        for (address in listOf(
            "https://dns.adguard.com/dns-query",
            "tls://dns.google",
            "quic://dns.adguard.com",
            "h3://8.8.8.8/dns-query",
            "8.8.8.8",
            "2606:4700:4700::1111",
            "local",
        )) {
            assertEquals(address, SettingsGuard.dnsAddress(address, fallback))
        }
    }

    @Test
    fun blankDnsFallsBackInsteadOfEmittingAnEmptyServer() {
        val fallback = "https://223.5.5.5/dns-query"
        assertEquals(fallback, SettingsGuard.dnsAddress("", fallback))
        assertEquals(fallback, SettingsGuard.dnsAddress("   ", fallback))
    }

    @Test
    fun sanitizeSwapsBothCleartextResolversForTheDefaults() {
        val defaults = SettingsData()
        val hostile = SettingsData(
            remoteDns = "http://watcher.example/dns-query",
            directDns = "http://watcher.example/dns-query",
        )
        val safe = SettingsGuard.sanitize(hostile)
        assertEquals(defaults.remoteDns, safe.remoteDns)
        assertEquals(defaults.directDns, safe.directDns)
    }

    // ---- restore: a foreign backup's proxy password is a password its author knows

    @Test
    fun restoreDropsTheProxyOnlyPassword() {
        val foreign = SettingsData(proxyPassword = "password-the-author-knows")
        assertEquals("", SettingsGuard.sanitizeRestored(foreign, SettingsData()).proxyPassword)
    }

    @Test
    fun loadingFromDiskKeepsTheProxyPasswordSoClientsStayConfigured() {
        // Only the restore path clears it; a normal load must not invalidate the
        // credential the user already put in their browser.
        val own = SettingsData(proxyPassword = "our-own-generated-token")
        assertEquals("our-own-generated-token", SettingsGuard.sanitize(own).proxyPassword)
    }

    @Test
    fun restoreStillAppliesEveryOtherBound() {
        val hostile = SettingsData(mtu = 0, proxyPassword = "x", remoteDns = "http://watcher.example/dns-query")
        val safe = SettingsGuard.sanitizeRestored(hostile, SettingsData())
        assertEquals(SettingsGuard.MTU_MIN, safe.mtu)
        assertEquals(SettingsData().remoteDns, safe.remoteDns)
        assertEquals("", safe.proxyPassword)
    }

    @Test
    fun theAuthOptOutSurvivesRestoreBecauseItIsAUserChoiceNotACredential() {
        assertEquals(
            true,
            SettingsGuard.sanitizeRestored(SettingsData(proxyAuthDisabled = true), SettingsData()).proxyAuthDisabled,
        )
    }
}
