package dev.yukaribox.vpn.data

/** Pure (Android-free) decision logic for resilient subscription updates. */
object SubscriptionUpdate {

    /**
     * Whether a freshly-fetched node set must be rejected to protect the user's
     * working nodes. A fetch that yields zero nodes while working nodes already
     * exist is treated as a failed update — the body was empty, an error page or
     * otherwise unparseable — so the old, working nodes are kept rather than
     * wiped. A first import (no prior nodes) accepts an empty result so the import
     * still completes; a non-empty result always applies.
     */
    fun isResilientFailure(freshCount: Int, priorCount: Int): Boolean =
        freshCount == 0 && priorCount > 0

    /**
     * Default group name for a subscription URL: its host.
     *
     * It used to be the last path segment, which for the common
     * `https://host/sub/<token>` shape *is* the access token — a credential that
     * then appeared as the group's name in the UI, in log lines and in exported
     * backups. The host is both non-secret and the more useful label. Never throws:
     * it is evaluated as a default argument, before any validation.
     */
    fun deriveName(url: String): String {
        val host = runCatching { java.net.URL(url.trim()).host }.getOrNull()
        return host?.takeIf { it.isNotBlank() } ?: FALLBACK_NAME
    }

    private const val FALLBACK_NAME = "subscription"
}
