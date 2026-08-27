package dev.yukaribox.vpn.core

import dev.yukaribox.vpn.data.RuleOutbound
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit tests for the opt-in routing rule presets (US-012). */
class RoutePresetsTest {

    @Test
    fun noPresetsEnabledByDefault() {
        // Both preset toggles default OFF -> no rules injected.
        assertTrue(RoutePresets.enabledRules(SettingsData()).isEmpty())
    }

    @Test
    fun ruBypassRoutesLocalDomainsDirect() {
        val rules = RoutePresets.enabledRules(SettingsData(presetRuBypass = true))
        assertEquals(1, rules.size)
        val ru = rules.single()
        assertEquals(RuleOutbound.Direct, ru.outbound)
        assertTrue(ru.domains.contains("ru"))
        assertTrue(ru.domains.contains("рф"))
    }

    @Test
    fun adBlockBlocksTrackerDomains() {
        val rules = RoutePresets.enabledRules(SettingsData(presetAdBlock = true))
        assertEquals(1, rules.size)
        val ads = rules.single()
        assertEquals(RuleOutbound.Block, ads.outbound)
        assertTrue(ads.domains.contains("doubleclick.net"))
        assertTrue(ads.domains.isNotEmpty())
    }

    @Test
    fun bothPresetsEnabledKeepPriorityOrder() {
        val rules = RoutePresets.enabledRules(SettingsData(presetRuBypass = true, presetAdBlock = true))
        assertEquals(2, rules.size)
        // RU bypass before ad-block (declared priority order).
        assertEquals(RuleOutbound.Direct, rules[0].outbound)
        assertEquals(RuleOutbound.Block, rules[1].outbound)
    }

    @Test
    fun presetRulesAreNonEmptyMatchers() {
        // A preset rule with no matchers would never fire (isEmpty) — guard against it.
        listOf(
            SettingsData(presetRuBypass = true),
            SettingsData(presetAdBlock = true),
        ).flatMap { RoutePresets.enabledRules(it) }.forEach { assertTrue(!it.isEmpty) }
    }
}
