package dev.yukaribox.vpn.ui

import dev.yukaribox.vpn.proxy.ProxyType

/**
 * Short protocol tag — the node editor's protocol chips and its per-protocol section
 * heading.
 *
 * Three of the nine names are abbreviated because they are the ones that do not fit a
 * chip; the rest are already short enough that shortening them would only make them
 * harder to recognise. Not localised: these are the protocol names as their own
 * specifications spell them. The flag plate takes [protocolPlate] instead — it has room
 * for two glyphs, not five.
 */
internal fun protocolLabel(type: ProxyType): String = when (type) {
    ProxyType.SHADOWSOCKS -> "SS"
    ProxyType.HYSTERIA2 -> "HY2"
    ProxyType.WIREGUARD -> "WG"
    else -> type.name
}

/**
 * Two-letter tag for the flag plate, used when a node's name and host name no country.
 *
 * The plate is a **two**-letter slot — 37 x 25 dp measured, sized for an ISO 3166-1
 * alpha-2 code — so the fallback has to be two characters as well. [protocolLabel]
 * truncated into it gave `SOC`, `VLE`, `HTT`: three glyphs in a two-glyph plate, wider
 * than anything a country code puts there. These are the conventional short forms
 * instead — `S5` for SOCKS5, `H2` for Hysteria2 — and they are not localised, for the
 * same reason as [protocolLabel]: a protocol is spelled the way its own specification
 * spells it.
 */
internal fun protocolPlate(type: ProxyType): String = when (type) {
    ProxyType.VLESS -> "VL"
    ProxyType.VMESS -> "VM"
    ProxyType.TROJAN -> "TR"
    ProxyType.SHADOWSOCKS -> "SS"
    ProxyType.HYSTERIA2 -> "H2"
    ProxyType.SOCKS -> "S5"
    ProxyType.HTTP -> "HT"
    ProxyType.WIREGUARD -> "WG"
    ProxyType.TUIC -> "TU"
}
