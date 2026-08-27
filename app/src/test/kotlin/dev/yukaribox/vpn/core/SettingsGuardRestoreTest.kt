package dev.yukaribox.vpn.core

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A backup file is untrusted input even when the user picked it themselves, and the fields
 * that decide *whether traffic is tunnelled at all* are the ones that matter most here.
 *
 * The restore dialog defaults to restoring settings, so a file someone was talked into
 * importing needed no attacker beyond that: `serviceMode = ProxyOnly` skips the VPN consent
 * dialog, owns no TUN and captures nothing, while Home still read Connected and said the
 * connection was protected. A one-package include list is the same outcome with a real TUN.
 */
class SettingsGuardRestoreTest {

    private val mine = SettingsData(
        serviceMode = ServiceMode.Vpn,
        perAppProxyInclude = false,
        perAppPackages = emptySet(),
        bypassLan = false,
        allowInsecure = false,
    )

    @Test
    fun aFileCannotTurnThisDeviceIntoProxyOnlyMode() {
        val hostile = SettingsData(serviceMode = ServiceMode.ProxyOnly)
        assertEquals(ServiceMode.Vpn, SettingsGuard.sanitizeRestored(hostile, mine).serviceMode)
    }

    @Test
    fun aFileCannotRewriteThePerAppPlan() {
        val hostile = SettingsData(
            perAppProxyInclude = true,
            perAppPackages = setOf("com.example.only.this.one"),
        )
        val safe = SettingsGuard.sanitizeRestored(hostile, mine)
        assertEquals(false, safe.perAppProxyInclude)
        assertEquals(emptySet<String>(), safe.perAppPackages)
    }

    @Test
    fun aFileCannotOpenLanBypassOrDisableCertificateChecks() {
        val hostile = SettingsData(bypassLan = true, allowInsecure = true)
        val safe = SettingsGuard.sanitizeRestored(hostile, mine)
        assertEquals(false, safe.bypassLan)
        assertEquals(false, safe.allowInsecure)
    }

    @Test
    fun theDevicesOwnPostureIsKeptEvenWhenItIsTheUnusualOne() {
        // Held back means "this device's value stands", not "the default stands": a user who
        // deliberately runs proxy-only must not be switched to VPN mode by restoring a file.
        val mineIsProxyOnly = mine.copy(serviceMode = ServiceMode.ProxyOnly, bypassLan = true)
        val incoming = SettingsData(serviceMode = ServiceMode.Vpn, bypassLan = false)
        val safe = SettingsGuard.sanitizeRestored(incoming, mineIsProxyOnly)
        assertEquals(ServiceMode.ProxyOnly, safe.serviceMode)
        assertEquals(true, safe.bypassLan)
    }

    @Test
    fun everythingThatIsNotAboutProtectionStillComesAcross() {
        val incoming = SettingsData(
            nickname = "Vasya",
            themeMode = ThemeMode.Dark,
            mtu = 1400,
            sniffing = false,
            autoUpdate = true,
            proxyAuthDisabled = true,
        )
        val safe = SettingsGuard.sanitizeRestored(incoming, mine)
        assertEquals("Vasya", safe.nickname)
        assertEquals(ThemeMode.Dark, safe.themeMode)
        assertEquals(1400, safe.mtu)
        assertEquals(false, safe.sniffing)
        assertEquals(true, safe.autoUpdate)
        assertEquals(true, safe.proxyAuthDisabled)
    }

    // ---- DNS: the restore path demands an encrypted transport, the edit path does not

    @Test
    fun plaintextDnsInAFileIsRejectedWhateverSchemeItWears() {
        // `dnsAddress` only rejects `http://`, and the threat its own KDoc names is a tampered
        // backup watching every domain the user resolves. udp/tcp/bare-IP are the same threat.
        val planted = listOf(
            "udp://198.51.100.7",
            "tcp://198.51.100.7",
            "198.51.100.7",
            "http://watcher.example/dns-query",
        )
        for (value in planted) {
            assertEquals(
                "rejected: $value",
                mine.directDns,
                SettingsGuard.dnsAddressRestored(value, mine.directDns),
            )
        }
    }

    @Test
    fun everyEncryptedTransportTheCoreUnderstandsIsAccepted() {
        val encrypted = listOf(
            "https://dns.example/dns-query",
            "tls://dns.example",
            "quic://dns.example",
            "h3://dns.example",
        )
        for (ok in encrypted) {
            assertEquals(ok, SettingsGuard.dnsAddressRestored(ok, mine.directDns))
        }
    }

    @Test
    fun everyPresetThisAppCanWriteSurvivesItsOwnBackup() {
        // If the rule rejected the app's own values, restoring your own backup would silently
        // reset your DNS choice.
        for (preset in DohPresets.remote + DohPresets.direct) {
            assertEquals(preset.url, SettingsGuard.dnsAddressRestored(preset.url, mine.remoteDns))
        }
    }

    @Test
    fun theRestoredDnsFieldsGoThroughThatRule() {
        val hostile = SettingsData(
            remoteDns = "udp://198.51.100.7",
            directDns = "https://dns.example/dns-query",
        )
        val safe = SettingsGuard.sanitizeRestored(hostile, mine)
        assertEquals(mine.remoteDns, safe.remoteDns)
        assertEquals("https://dns.example/dns-query", safe.directDns)
    }

    @Test
    fun aValueTheUserTypedIsStillTheirOwnChoice() {
        // The load path is unchanged: a bare resolver is a legitimate, if less private, thing
        // to type into the settings screen.
        assertEquals("198.51.100.7", SettingsGuard.dnsAddress("198.51.100.7", mine.directDns))
    }
}
