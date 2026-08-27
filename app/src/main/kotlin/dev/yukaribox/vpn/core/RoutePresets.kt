package dev.yukaribox.vpn.core

import dev.yukaribox.vpn.data.RouteRule
import dev.yukaribox.vpn.data.RuleOutbound

/**
 * Opt-in, ready-made routing rule presets (US-012). Each preset is OFF by
 * default; when enabled in [SettingsData] its rules are injected into the config
 * at build time (they are NOT stored in [dev.yukaribox.vpn.data.RouteRepository],
 * so they never clutter the user's editable rule list). User rules outrank these
 * — see [SettingsStore.configOptions] for the merge order.
 */
object RoutePresets {

    /** Local domains kept off the proxy: `.ru` / `.рф` resolve & route direct. */
    private val RU_BYPASS = RouteRule(
        id = "preset-ru-bypass",
        name = "RU bypass",
        domains = listOf("ru", "рф", "su"),
        outbound = RuleOutbound.Direct,
    )

    /** Well-known ad / tracker domain suffixes, dropped (no outbound). */
    private val AD_BLOCK = RouteRule(
        id = "preset-adblock",
        name = "Ad-block",
        domains = listOf(
            "doubleclick.net",
            "googlesyndication.com",
            "googleadservices.com",
            "google-analytics.com",
            "adservice.google.com",
            "ads.youtube.com",
            "adnxs.com",
            "adsrvr.org",
            "scorecardresearch.com",
            "moatads.com",
            "keyword:adservice",
        ),
        outbound = RuleOutbound.Block,
    )

    /**
     * Preset rules enabled by [data], in priority order (RU bypass before
     * ad-block). Empty when no preset is on — the default.
     */
    fun enabledRules(data: SettingsData): List<RouteRule> = buildList {
        if (data.presetRuBypass) add(RU_BYPASS)
        if (data.presetAdBlock) add(AD_BLOCK)
    }
}
