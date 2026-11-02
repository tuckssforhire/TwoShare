package com.twoshare.app.data

import io.github.jan.supabase.postgrest.from
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
private data class FeedReactionRow(
    @SerialName("post_id") val postId: String,
    @SerialName("user_id") val userId: String,
    val emoji: String
)

@Serializable
private data class FeedReactionInsert(
    @SerialName("post_id") val postId: String,
    @SerialName("user_id") val userId: String,
    val emoji: String
)

class FeedReactionRepository(private val base: TwoShareRepository) {
    private val supabase = SupabaseProvider.client
    val choices = listOf("❤️", "👍", "😂", "😍", "😮", "😢", "🔥", "🥰", "👏", "🎉")

    suspend fun load(): Map<String, Map<String, Set<String>>> = runCatching {
        base.retryNetworkRead {
            supabase.from("likes").select().decodeList<FeedReactionRow>()
        }.groupBy { it.postId }.mapValues { (_, rows) ->
            rows.groupBy { it.emoji }.mapValues { (_, reactions) -> reactions.map { it.userId }.toSet() }
        }
    }.getOrDefault(emptyMap())

    suspend fun react(postId: String, emoji: String) {
        require(emoji in choices)
        val uid = base.currentUserId() ?: error("Session expired")
        val current = base.retryNetworkRead {
            supabase.from("likes").select {
                filter { eq("post_id", postId); eq("user_id", uid) }
            }.decodeList<FeedReactionRow>().firstOrNull()
        }
        if (current?.emoji == emoji) {
            supabase.from("likes").delete { filter { eq("post_id", postId); eq("user_id", uid) } }
        } else {
            if (current != null) supabase.from("likes").delete { filter { eq("post_id", postId); eq("user_id", uid) } }
            supabase.from("likes").insert(FeedReactionInsert(postId, uid, emoji))
        }
    }
}
