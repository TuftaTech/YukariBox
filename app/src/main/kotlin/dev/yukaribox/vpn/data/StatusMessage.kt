package dev.yukaribox.vpn.data

import androidx.annotation.PluralsRes
import androidx.annotation.StringRes

/**
 * An outcome the node store wants to tell the user about, carried as a resource id
 * rather than as prose.
 *
 * [NodeRepository] reports from an IO thread, a network thread and the main thread, none
 * of which has a `Context` to resolve a string against — so it used to build English
 * sentences inline, which then appeared verbatim in a Russian interface. Holding the id
 * and its arguments lets the screen that renders the notice resolve it in the current
 * locale, and keeps the store free of anything Android-shaped beyond the annotation.
 *
 * Two shapes because counts need real plural rules: "Обновлено 1 сервер" and "Обновлено 5
 * серверов" are not the same sentence with a different number in it.
 */
sealed interface StatusMessage {

    /**
     * A fixed sentence with as many substitutions as its resource declares — a name, an
     * error message, or the two halves of a progress count. A list rather than a single
     * argument because three of these messages (the reassigned selection, and the URL
     * test's progress and stopped-at lines) take two.
     */
    data class Text(@StringRes val res: Int, val args: List<Any> = emptyList()) : StatusMessage

    /** A sentence whose wording depends on [quantity], which is also its only argument. */
    data class Count(@PluralsRes val res: Int, val quantity: Int) : StatusMessage
}
