package com.twoshare.app.data

import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.from
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class CommentReactionRow(
    @SerialName("comment_id") val commentId: String,
    @SerialName("user_id") val userId: String,
    val emoji: String
)

class CommentReactionRepository {
    private val client = SupabaseProvider.client

    suspend fun load(commentIds: List<String>): Map<String, Map<String, Set<String>>> {
        if (commentIds.isEmpty()) return emptyMap()
        val rows = client.from("comment_reactions").select {
            filter { isIn("comment_id", commentIds) }
        }.decodeList<CommentReactionRow>()
        return rows.groupBy { it.commentId }.mapValues { (_, values) ->
            values.groupBy { it.emoji }.mapValues { (_, reactions) -> reactions.map { it.userId }.toSet() }
        }
    }

    suspend fun react(commentId: String, emoji: String) {
        require(emoji.isNotBlank() && emoji.codePointCount(0, emoji.length) <= 16)
        val uid = client.auth.currentUserOrNull()?.id ?: error("Not signed in")
        val existing = client.from("comment_reactions").select {
            filter { eq("comment_id", commentId); eq("user_id", uid) }
        }.decodeList<CommentReactionRow>().firstOrNull()
        when {
            existing == null -> client.from("comment_reactions").insert(CommentReactionRow(commentId, uid, emoji))
            existing.emoji == emoji -> client.from("comment_reactions").delete { filter { eq("comment_id", commentId); eq("user_id", uid) } }
            else -> client.from("comment_reactions").update({ set("emoji", emoji) }) { filter { eq("comment_id", commentId); eq("user_id", uid) } }
        }
    }
}
