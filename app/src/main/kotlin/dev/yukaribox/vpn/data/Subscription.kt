package dev.yukaribox.vpn.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * A saved subscription (NekoBox-style group): a named, URL-backed set of nodes
 * that persists across restarts. [selectedNodeId] remembers the active node
 * within this subscription.
 */
@Serializable
data class Subscription(
    val id: String,
    val name: String,
    val url: String,
    val updatedAt: Long,
    val nodes: List<NodeEntry>,
    val selectedNodeId: Int = -1,
)

/** Pure (Android-free) serialization of the saved-subscription list. */
object SubscriptionCodec {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = true
    }
    private val serializer = ListSerializer(Subscription.serializer())

    fun encode(subscriptions: List<Subscription>): String =
        json.encodeToString(serializer, subscriptions)

    fun decode(text: String): List<Subscription> =
        if (text.isBlank()) emptyList() else json.decodeFromString(serializer, text)
}
