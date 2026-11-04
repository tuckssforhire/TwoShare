package com.twoshare.app.data

import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
private data class ThreadCommentRow(
    val id: String,
    @SerialName("post_id") val postId: String,
    @SerialName("user_id") val userId: String,
    @SerialName("body") val body: String,
    @SerialName("created_at") val createdAt: String,
    @SerialName("parent_comment_id") val parentCommentId: String? = null
)

@Serializable
private data class ThreadCommentInsert(
    @SerialName("post_id") val postId: String,
    @SerialName("user_id") val userId: String,
    @SerialName("body") val body: String,
    @SerialName("parent_comment_id") val parentCommentId: String? = null
)

@Serializable
data class ThreadComment(
    val id: String,
    val userId: String,
    val userName: String,
    val text: String,
    val createdAt: String,
    val parentCommentId: String?
)

class CommentThreadRepository {
    private val supabase = SupabaseProvider.client
    companion object {
        @Volatile private var cachedNames: Map<String, String> = emptyMap()
        @Volatile private var namesFetchedAtMs: Long = 0L
    }

    suspend fun load(postId: String): List<ThreadComment> {
        val rows = supabase.from("comments").select {
            filter { eq("post_id", postId) }
            order("created_at", Order.ASCENDING)
        }.decodeList<ThreadCommentRow>()
        val nowMs = System.currentTimeMillis()
        if (cachedNames.isEmpty() || nowMs - namesFetchedAtMs > 300_000L) {
            cachedNames = supabase.from("profiles").select()
                .decodeList<com.twoshare.app.model.ProfileRow>()
                .associate { it.id to it.displayName }
            namesFetchedAtMs = nowMs
        }
        return rows.map { row ->
            ThreadComment(
                row.id,
                row.userId,
                cachedNames[row.userId] ?: "User",
                row.body,
                row.createdAt,
                row.parentCommentId
            )
        }
    }

    suspend fun add(postId: String, text: String, parentCommentId: String? = null) {
        val uid = supabase.auth.currentUserOrNull()?.id ?: error("Please sign in again.")
        val cleaned = text.trim()
        require(cleaned.isNotEmpty())
        require(cleaned.length <= 1000)
        supabase.from("comments").insert(ThreadCommentInsert(postId, uid, cleaned, parentCommentId))
    }
}
