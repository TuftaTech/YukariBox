package dev.yukaribox.vpn.ui

import dev.yukaribox.vpn.core.NodeGeo

/**
 * A group's or subscription's name as it may be **drawn**.
 *
 * Feeds and the people who mirror their naming both put a regional-indicator flag emoji
 * at the front of a name, and the system paints that in full colour whatever the text
 * style says — the one chromatic element a label can inject into an interface that is
 * monochrome by construction (`SKILL.md` §1, which admits no exception). Node names are
 * stripped with [NodeGeo.plainName] at every site that draws one; a group name is a name,
 * so it goes through the same function.
 *
 * The second line is for the case that function deliberately does not cover. `plainName`
 * hands back the original when stripping would leave nothing, because for a node row an
 * empty title is worse than a coloured one — that row still has a country plate and a
 * host under it. A group card has neither: its own name is the only thing identifying it,
 * so `🇯🇵` alone would keep painting the flag. The country the flag names is what the
 * plate beside a node *from that group* shows, so that is what is drawn instead: `🇯🇵`
 * reads `JP`. A lone half of a pair decodes to nothing and keeps its (monochrome) glyph.
 *
 * Display only, at every one of the six sites a group name reaches a `Text`. The stored
 * name, the edit field, the export and the search index all keep the author's text.
 */
internal fun groupLabel(name: String): String {
    val plain = NodeGeo.plainName(name)
    if (!plain.hasRegionalIndicator()) return plain
    return NodeGeo.codeFor(name) ?: plain
}

/** True when a regional indicator survived the strip, i.e. `plainName` fell back. */
private fun String.hasRegionalIndicator(): Boolean {
    // Every one of them is a surrogate pair whose high half is U+D83C, so a name without
    // that char cannot hold one — which is every name but a handful.
    if (indexOf(FLAG_HIGH_SURROGATE) < 0) return false
    var index = 0
    while (index < length) {
        val point = codePointAt(index)
        if (point in FLAG_FIRST..FLAG_LAST) return true
        index += Character.charCount(point)
    }
    return false
}

/** The regional-indicator block, and the high surrogate every one of them starts with. */
private const val FLAG_FIRST = 0x1F1E6
private const val FLAG_LAST = 0x1F1FF
private const val FLAG_HIGH_SURROGATE = '\uD83C'
