package dev.yukaribox.vpn.core

import dev.yukaribox.vpn.data.RouteRule

/**
 * Validation for user routing rules, applied where a rule is turned into sing-box JSON.
 *
 * Two failure modes made this necessary. A malformed CIDR or port ("1.2.3", "80-", a
 * pasted comment) is rejected by the core at *start* time, so one bad rule stopped the
 * whole tunnel from coming up with an opaque error — the domain matchers next door
 * already dropped invalid regexes for exactly that reason. And a rule whose only
 * condition was invalid used to emit a rule object with no conditions at all, which
 * sing-box treats as "match everything": a broken `direct` rule would quietly route the
 * user's entire traffic outside the proxy.
 *
 * Pure and Android-free so every case is unit-tested.
 */

/** Highest valid TCP/UDP port; both ends of a range are checked against it. */
private const val MAX_PORT = 65535

/**
 * Drop the invalid conditions from this rule and return it, or null when nothing usable
 * is left (in which case the rule must not be emitted at all).
 */
internal fun RouteRule.sanitizedForConfig(): RouteRule? {
    val cleaned = copy(
        domains = domains.map { it.trim() }.filter { it.isNotEmpty() },
        ipCidrs = ipCidrs.map { it.trim() }.filter { isValidCidr(it) },
        ports = ports.map { it.trim() }.filter { isValidPortSpec(it) },
        packages = packages.map { it.trim() }.filter { isValidPackageName(it) },
    )
    return if (cleaned.isEmpty) null else cleaned
}

/** Accepts `1.2.3.4`, `1.2.3.0/24`, `2001:db8::/32` — anything sing-box can parse. */
internal fun isValidCidr(value: String): Boolean {
    if (value.isEmpty()) return false
    val slash = value.indexOf('/')
    val address = if (slash < 0) value else value.substring(0, slash)
    val prefix = if (slash < 0) null else value.substring(slash + 1).toIntOrNull() ?: return false
    val v6 = address.contains(':')
    if (prefix != null && (prefix < 0 || prefix > if (v6) 128 else 32)) return false
    return if (v6) isValidIpv6(address) else isValidIpv4(address)
}

private fun isValidIpv4(address: String): Boolean {
    val parts = address.split('.')
    if (parts.size != 4) return false
    return parts.all { part ->
        part.isNotEmpty() && part.length <= 3 && part.all(Char::isDigit) &&
            (part.toIntOrNull() ?: return@all false) <= 255
    }
}

/**
 * Whether [address] is a well-formed IPv6 literal in any of RFC 4291's textual forms.
 *
 * Parsed rather than pattern-matched, because the previous version only counted colons and
 * checked that each group was hex: `2001:db8` passed it. That is a plausible hand-typed
 * value, and what followed was not a rejected field but a rejected *config* -- sing-box
 * cannot parse it, so every connect attempt threw, the retries ran out, and the session
 * ended in `enterFailClosed`, blocking all traffic on the device until the user found the
 * rule that caused it. The direction is safe; being right is the point of this file.
 *
 * The three shapes accepted are eight hextets, any single `::` elision, and a trailing
 * dotted quad occupying the last two 16-bit words. `java.net.InetAddress` is deliberately
 * not used: handed a malformed literal it treats the string as a *hostname* and performs a
 * blocking DNS lookup, which is not something a validator may do.
 */
private fun isValidIpv6(address: String): Boolean {
    val elision = elisionIndex(address) ?: return false
    val groups = ipv6Groups(address, elision) ?: return false
    val words = ipv6WordCount(groups) ?: return false
    // An elision stands for at least one omitted word; without one every word is spelt out.
    return if (elision >= 0) words <= IPV6_WORDS - 1 else words == IPV6_WORDS
}

/**
 * Index of the single `::` elision, -1 when the address has none, or null when the address
 * cannot be one at all. Split out of [isValidIpv6] to keep that function inside detekt's
 * complexity and return-count budgets.
 */
private fun elisionIndex(address: String): Int? {
    if (address.isEmpty() || address.length > MAX_IPV6_TEXT || address.contains(":::")) return null
    val first = address.indexOf("::")
    // At most one elision, and it is the only place an empty group may appear.
    return if (first >= 0 && address.indexOf("::", first + 2) >= 0) null else first
}

/** The groups spelt out either side of the elision, or null when one of them is empty. */
private fun ipv6Groups(address: String, elision: Int): List<String>? {
    val head = if (elision < 0) address else address.substring(0, elision)
    val tail = if (elision < 0) "" else address.substring(elision + 2)
    val groups = splitGroups(head) + splitGroups(tail)
    return groups.takeIf { list -> list.none(String::isEmpty) }
}

/** How many 16-bit words [groups] account for, or null when one of them is malformed. */
private fun ipv6WordCount(groups: List<String>): Int? {
    val dottedTail = groups.isNotEmpty() && groups.last().contains('.')
    if (dottedTail && !isValidIpv4(groups.last())) return null
    if (groups.dropLast(if (dottedTail) 1 else 0).any { !isHextet(it) }) return null
    // A trailing dotted quad fills two words rather than one.
    return groups.size + if (dottedTail) 1 else 0
}

private fun splitGroups(part: String): List<String> =
    if (part.isEmpty()) emptyList() else part.split(':')

private fun isHextet(group: String): Boolean =
    group.length in 1..4 && group.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }

/** Words in an IPv6 address, and the longest textual form one can take. */
private const val IPV6_WORDS = 8
private const val MAX_IPV6_TEXT = 45

/** Accepts a single port ("443") or an inclusive range ("1000:2000" / "1000-2000"). */
internal fun isValidPortSpec(value: String): Boolean {
    val separator = value.indexOfFirst { it == ':' || it == '-' }
    if (separator < 0) return value.toIntOrNull()?.let { it in 1..MAX_PORT } == true
    val from = value.substring(0, separator).toIntOrNull() ?: return false
    val to = value.substring(separator + 1).toIntOrNull() ?: return false
    return from in 1..MAX_PORT && to in 1..MAX_PORT && from <= to
}

/** Conservative Android package-name check; a bad entry would never match anyway. */
internal fun isValidPackageName(value: String): Boolean =
    value.isNotEmpty() && value.length <= 255 &&
        value.all { it.isLetterOrDigit() || it == '.' || it == '_' } &&
        value.first().isLetter()
