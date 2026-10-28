package com.twoshare.app.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ProfileRow(
    val id: String,
    val email: String,
    @SerialName("display_name") val displayName: String
)

@Serializable
data class PostRow(
    val id: String,
    @SerialName("user_id") val userId: String,
    @SerialName("image_path") val imagePath: String,
    @SerialName("created_at") val createdAt: String
)

@Serializable
data class LikeRow(
    @SerialName("post_id") val postId: String,
    @SerialName("user_id") val userId: String
)

@Serializable
data class CommentRow(
    val id: String,
    @SerialName("post_id") val postId: String,
    @SerialName("user_id") val userId: String,
    @SerialName("body") val text: String,
    @SerialName("created_at") val createdAt: String
)

@Serializable
data class PostInsert(
    @SerialName("user_id") val userId: String,
    @SerialName("image_path") val imagePath: String
)

@Serializable
data class LikeInsert(
    @SerialName("post_id") val postId: String,
    @SerialName("user_id") val userId: String
)

@Serializable
data class CommentInsert(
    @SerialName("post_id") val postId: String,
    @SerialName("user_id") val userId: String,
    @SerialName("body") val text: String
)

@Serializable
data class MessageRow(
    val id: String,
    @SerialName("sender_id") val senderId: String,
    val body: String? = null,
    @SerialName("media_path") val mediaPath: String? = null,
    @SerialName("media_type") val mediaType: String? = null,
    @SerialName("media_mime") val mediaMime: String? = null,
    @SerialName("created_at") val createdAt: String
)

@Serializable
data class MessageInsert(
    @SerialName("sender_id") val senderId: String,
    val body: String? = null,
    @SerialName("media_path") val mediaPath: String? = null,
    @SerialName("media_type") val mediaType: String? = null,
    @SerialName("media_mime") val mediaMime: String? = null
)

@Serializable
data class MessageReactionRow(
    @SerialName("message_id") val messageId: String,
    @SerialName("user_id") val userId: String,
    val emoji: String
)

@Serializable
data class MessageReactionInsert(
    @SerialName("message_id") val messageId: String,
    @SerialName("user_id") val userId: String,
    val emoji: String
)

@Serializable
data class CallRow(
    val id: String,
    @SerialName("caller_id") val callerId: String,
    @SerialName("callee_id") val calleeId: String,
    @SerialName("is_video") val isVideo: Boolean,
    val status: String,
    @SerialName("offer_sdp") val offerSdp: String? = null,
    @SerialName("answer_sdp") val answerSdp: String? = null,
    @SerialName("created_at") val createdAt: String,
    @SerialName("answered_at") val answeredAt: String? = null,
    @SerialName("ended_at") val endedAt: String? = null
)

@Serializable
data class CallInsert(
    @SerialName("caller_id") val callerId: String,
    @SerialName("callee_id") val calleeId: String,
    @SerialName("is_video") val isVideo: Boolean
)

data class FeedPost(
    val id: String,
    val userId: String,
    val userName: String,
    val imagePath: String,
    val imageUrl: String,
    val createdAt: String,
    val likedBy: Set<String>
)

data class UiComment(
    val id: String,
    val userId: String,
    val userName: String,
    val text: String,
    val createdAt: String
)

data class UiChatMessage(
    val id: String,
    val senderId: String,
    val senderName: String,
    val body: String?,
    val mediaType: String?,
    val mediaUrl: String?,
    val createdAt: String,
    val reactions: Map<String, Set<String>>
)
