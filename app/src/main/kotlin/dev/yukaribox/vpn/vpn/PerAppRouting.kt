package dev.yukaribox.vpn.vpn

/**
 * The set of packages to allow into / disallow from the tunnel, as Android's
 * VpnService.Builder consumes them. [allowed] -> addAllowedApplication (ONLY these
 * are tunneled); [disallowed] -> addDisallowedApplication (these bypass the tunnel,
 * everything else is tunneled). Exactly one list is non-empty per call (the modes
 * are mutually exclusive), except the all-apps default which only disallows self.
 */
data class PerAppPlan(val allowed: List<String>, val disallowed: List<String>)

/**
 * Pure per-app split-tunnel decision, independent of [android.net.VpnService.Builder]
 * so it can be unit-tested. Our own package is never routed through the tunnel
 * (avoids a loop). Lives outside [YukariVpnService] so the service class stays under
 * the detekt function-count budget.
 */
object PerAppRouting {

    /**
     * Compute the routing plan for the per-app split tunnel.
     *
     * - No packages selected (the default): all apps go through the VPN; only our own
     *   app is excluded. This is the "all apps via VPN by default" guarantee.
     * - [include] = true: only the listed apps are tunneled (self bypasses by omission).
     * - [include] = false: the listed apps plus our own app bypass the tunnel;
     *   everything else is tunneled (the exclusion mode).
     *
     * A selection that contains *only* our own package is treated as no selection at
     * all. Include mode would otherwise filter self out and leave both lists empty,
     * and `VpnService.Builder` reads two empty lists as "tunnel everything" —
     * including us, which is the routing loop the self-exclusion exists to prevent.
     * Android cannot express "tunnel nothing", so the documented default is the only
     * safe reading of a request that resolves to an empty app set.
     */
    fun plan(include: Boolean, packages: Set<String>, selfPackage: String): PerAppPlan {
        val others = packages.filter { it != selfPackage }
        if (others.isEmpty()) {
            return PerAppPlan(allowed = emptyList(), disallowed = listOf(selfPackage))
        }
        return if (include) {
            PerAppPlan(allowed = others, disallowed = emptyList())
        } else {
            PerAppPlan(allowed = emptyList(), disallowed = listOf(selfPackage) + others)
        }
    }
}
