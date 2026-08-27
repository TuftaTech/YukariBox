package dev.yukaribox.vpn.proxy

/**
 * Decodes subscription payloads into proxy nodes. Handles both plain
 * newline-separated link lists and the common base64-wrapped form, ignoring
 * blank lines, comments and entries that fail to parse.
 */
object SubscriptionDecoder {

    /**
     * Upper bound on nodes taken from one payload. The fetch itself is byte-bounded, but
     * 8 MB of minimal links is still tens of thousands of entries — enough to make the
     * node list, its JSON persistence and a "test all" run unusable. A feed that large is
     * broken or hostile, so the excess is dropped rather than imported.
     */
    const val MAX_NODES = 5000

    /**
     * Decode raw subscription [content] into the list of nodes it contains.
     *
     * Delegates rather than repeating [decodeReport]'s pipeline. The two used to unwrap,
     * split, trim, drop comments and parse independently, and they applied [MAX_NODES]
     * differently -- `take` here against counting the overflow as failures there -- so the
     * count the clipboard import showed the user could disagree with the list every other
     * import path produced.
     */
    fun decode(content: String): List<ProxyNode> = decodeReport(content).nodes

    /** Count of well-formed links in [content] regardless of protocol support. */
    fun decodeReport(content: String): Report {
        val text = unwrap(content)
        val lines = text.split('\n', '\r')
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("//") && !it.startsWith("#") }
        val nodes = ArrayList<ProxyNode>()
        var failed = 0
        for (line in lines) {
            val node = ProxyLinkParser.parseOrNull(line)
            when {
                node == null -> failed++
                nodes.size < MAX_NODES -> nodes.add(node)
                else -> failed++
            }
        }
        return Report(nodes, failed)
    }

    data class Report(val nodes: List<ProxyNode>, val failedCount: Int)

    /** If [content] is base64-wrapped (no scheme markers), decode it; else return as-is. */
    private fun unwrap(content: String): String {
        val trimmed = content.trim()
        if (trimmed.isEmpty()) return ""
        if (trimmed.contains("://")) return trimmed
        return try {
            val decoded = LinkCodec.base64DecodeToString(trimmed)
            if (decoded.contains("://")) decoded else trimmed
        } catch (_: Exception) {
            trimmed
        }
    }
}
