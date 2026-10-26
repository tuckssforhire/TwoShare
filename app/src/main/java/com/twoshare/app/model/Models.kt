package com.twoshare.app.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ProfileRow(
    val id: String,
    val name: String
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
    val text: String,
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
    val text: String
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
