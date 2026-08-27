package dev.yukaribox.vpn.core

import dev.yukaribox.vpn.data.RouteRule
import dev.yukaribox.vpn.data.RuleOutbound
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A malformed rule used to reach the core verbatim, where it either stopped the tunnel
 * from starting or — with every matcher dropped — became a rule that matches everything.
 */
class RouteRuleValidationTest {

    private fun rule(
        domains: List<String> = emptyList(),
        ipCidrs: List<String> = emptyList(),
        ports: List<String> = emptyList(),
        packages: List<String> = emptyList(),
    ) = RouteRule(
        id = "r",
        domains = domains,
        ipCidrs = ipCidrs,
        ports = ports,
        packages = packages,
        outbound = RuleOutbound.Direct,
    )

    @Test
    fun validCidrsAreAccepted() {
        for (cidr in listOf("1.2.3.4", "10.0.0.0/8", "192.168.1.0/24", "::1", "fc00::/7", "2001:db8::/32")) {
            assertTrue(cidr, isValidCidr(cidr))
        }
    }

    @Test
    fun malformedCidrsAreRejected() {
        val malformed =
            listOf("", "1.2.3", "1.2.3.4.5", "256.1.1.1", "10.0.0.0/33", "::1/129", "not-an-ip", "1.2.3.4/x")
        for (cidr in malformed) {
            assertFalse(cidr, isValidCidr(cidr))
        }
    }

    @Test
    fun validPortSpecsAreAccepted() {
        for (port in listOf("1", "53", "443", "65535", "1000:2000", "1000-2000", "443:443")) {
            assertTrue(port, isValidPortSpec(port))
        }
    }

    @Test
    fun malformedPortSpecsAreRejected() {
        for (port in listOf("", "0", "65536", "-1", "80-", ":80", "2000:1000", "http", "80,443")) {
            assertFalse(port, isValidPortSpec(port))
        }
    }

    @Test
    fun packageNamesAreCheckedConservatively() {
        assertTrue(isValidPackageName("com.example.app"))
        assertTrue(isValidPackageName("dev.yukaribox.vpn"))
        assertFalse(isValidPackageName(""))
        assertFalse(isValidPackageName("1com.example"))
        assertFalse(isValidPackageName("com example"))
        assertFalse(isValidPackageName("com/example"))
    }

    @Test
    fun invalidConditionsAreStrippedAndValidOnesKept() {
        val cleaned = rule(
            ipCidrs = listOf("10.0.0.0/8", "garbage"),
            ports = listOf("443", "80-", " 8080 "),
            packages = listOf("com.example.app", "not a package"),
        ).sanitizedForConfig()
        assertEquals(listOf("10.0.0.0/8"), cleaned?.ipCidrs)
        assertEquals(listOf("443", "8080"), cleaned?.ports)
        assertEquals(listOf("com.example.app"), cleaned?.packages)
    }

    @Test
    fun aRuleLeftWithNoConditionIsDroppedEntirely() {
        // Would otherwise emit a condition-less sing-box rule = "match everything",
        // routing all traffic to this rule's outbound.
        assertNull(rule(ipCidrs = listOf("garbage")).sanitizedForConfig())
        assertNull(rule(ports = listOf("nope")).sanitizedForConfig())
        assertNull(rule(packages = listOf("!!!")).sanitizedForConfig())
        assertNull(rule().sanitizedForConfig())
    }

    @Test
    fun domainMatchersSurviveUntouched() {
        val cleaned = rule(domains = listOf(" example.com ", "keyword:ads", "")).sanitizedForConfig()
        assertEquals(listOf("example.com", "keyword:ads"), cleaned?.domains)
    }

    // ---- IPv6 literals: a shape sing-box cannot parse must not reach the config

    @Test
    fun aTruncatedIpv6LiteralIsRejected() {
        // The old check only counted colons and validated hex groups, so this passed. What
        // followed was not a rejected field but a rejected config: sing-box could not parse
        // it, every connect attempt threw, and the session ended in the fail-closed TUN with
        // all traffic on the device blocked until the user found the rule.
        assertFalse(isValidCidr("2001:db8"))
        assertFalse(isValidCidr("2001:db8/32"))
        assertFalse(isValidCidr("1:2:3:4:5:6:7"))
    }

    @Test
    fun onlyOneElisionIsAllowedAndOnlyItMayBeEmpty() {
        assertFalse(isValidCidr("1::2::3"))
        assertFalse(isValidCidr(":1:2:3:4:5:6:7"))
        assertFalse(isValidCidr("1:2:3:4:5:6:7:"))
        assertFalse(isValidCidr("1:::2"))
    }

    @Test
    fun everyRealFormStillParses() {
        assertTrue(isValidCidr("::"))
        assertTrue(isValidCidr("::1"))
        assertTrue(isValidCidr("2001:db8::"))
        assertTrue(isValidCidr("2001:db8::/32"))
        assertTrue(isValidCidr("2001:0db8:0000:0000:0000:ff00:0042:8329"))
        // The TUN's own v6 address, which this validator must never start rejecting.
        assertTrue(isValidCidr("fdfe:dcba:9876::1"))
        // A trailing dotted quad fills the last two words.
        assertTrue(isValidCidr("::ffff:192.0.2.1"))
        assertTrue(isValidCidr("1:2:3:4:5:6:7.8.9.10"))
    }

    @Test
    fun tooManyWordsIsRejectedWithOrWithoutAnElision() {
        assertFalse(isValidCidr("1:2:3:4:5:6:7:8:9"))
        assertFalse(isValidCidr("1:2:3:4:5:6:7:8::"))
        assertFalse(isValidCidr("1:2:3:4:5:6:7:8.9.10.11"))
        assertFalse(isValidCidr("::ffff:192.0.2.999"))
        assertFalse(isValidCidr("2001:db8::gggg"))
        assertFalse(isValidCidr("2001:db8::12345"))
    }

    @Test
    fun theIpv4PathIsUnchanged() {
        assertTrue(isValidCidr("1.2.3.4"))
        assertTrue(isValidCidr("10.0.0.0/8"))
        assertFalse(isValidCidr("1.2.3.256"))
        assertFalse(isValidCidr("1.2.3"))
        assertFalse(isValidCidr("10.0.0.0/33"))
    }
}
