package com.twoshare.app.data

import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
private data class FeedNotificationRow(
    val id: String,
    @SerialName("recipient_user_id") val recipientUserId: String,
    @SerialName("actor_user_id") val actorUserId: String,
    val type: String,
    @SerialName("post_id") val postId: String,
    @SerialName("comment_id") val commentId: String? = null,
    val emoji: String? = null,
    @SerialName("is_read") val isRead: Boolean = false,
    @SerialName("created_at") val createdAt: String
)

data class FeedNotification(
    val id: String,
    val actorName: String,
    val type: String,
    val postId: String,
    val commentId: String?,
    val emoji: String?,
    val isRead: Boolean,
    val createdAt: String
) {
    val text: String get() = when (type) {
        "post_reaction" -> "$actorName ${emoji ?: "❤️"} reacted to your post"
        "post_comment" -> "$actorName commented on your post"
        "comment_reply" -> "$actorName replied to your comment"
        "comment_reaction" -> "$actorName ${emoji ?: "❤️"} reacted to your comment"
        else -> "$actorName interacted with your post"
    }
    val opensComments: Boolean get() = type != "post_reaction"
}

class FeedNotificationRepository(private val base: TwoShareRepository) {
    private val supabase = SupabaseProvider.client

    suspend fun load(): List<FeedNotification> {
        val uid = base.currentUserId() ?: return emptyList()
        val rows = base.retryNetworkRead {
            supabase.from("feed_notifications").select {
                filter { eq("recipient_user_id", uid) }
                order("created_at", Order.DESCENDING)
                limit(100)
            }.decodeList<FeedNotificationRow>()
        }
        val names = runCatching {
            base.retryNetworkRead { supabase.from("profiles").select().decodeList<ProfileRow>() }
                .associate { it.id to it.displayName }
        }.getOrDefault(emptyMap())
        return rows.map { row ->
            FeedNotification(row.id, names[row.actorUserId] ?: "Your TwoShare partner", row.type, row.postId, row.commentId, row.emoji, row.isRead, row.createdAt)
        }
    }

    suspend fun unreadCount(): Int = load().count { !it.isRead }

    suspend fun markRead(id: String) {
        val uid = base.currentUserId() ?: return
        supabase.from("feed_notifications").update({ set("is_read", true) }) {
            filter { eq("id", id); eq("recipient_user_id", uid) }
        }
    }

    suspend fun markAllRead() {
        val uid = base.currentUserId() ?: return
        supabase.from("feed_notifications").update({ set("is_read", true) }) {
            filter { eq("recipient_user_id", uid); eq("is_read", false) }
        }
    }
}
