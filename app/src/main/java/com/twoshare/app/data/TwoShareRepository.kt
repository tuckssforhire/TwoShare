package com.twoshare.app.data

import android.content.Context
import android.net.Uri
import android.webkit.MimeTypeMap
import com.twoshare.app.model.*
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Order
import io.github.jan.supabase.storage.storage
import java.util.UUID
import kotlin.time.Duration.Companion.hours

class TwoShareRepository {
    private val supabase = SupabaseProvider.client
    private val signedUrlCache = mutableMapOf<String, String>()

    fun currentUserId(): String? = supabase.auth.currentUserOrNull()?.id

    fun isSignedIn(): Boolean = currentUserId() != null

    suspend fun signIn(email: String, password: String) {
        supabase.auth.signInWith(Email) {
            this.email = email.trim()
            this.password = password
        }
        requireAllowedUser()
    }

    suspend fun signOut() {
        supabase.auth.signOut()
    }

    suspend fun requireAllowedUser(): ProfileRow {
        val uid = currentUserId() ?: error("Not signed in")
        val profile = supabase.from("profiles")
            .select {
                filter { eq("id", uid) }
            }
            .decodeList<ProfileRow>()
            .firstOrNull()
        return profile ?: error("This account is not one of the two allowed users.")
    }

    suspend fun loadFeed(): List<FeedPost> {
        requireAllowedUser()

        val posts = supabase.from("posts")
            .select {
                order("created_at", Order.DESCENDING)
            }
            .decodeList<PostRow>()

        if (posts.isEmpty()) return emptyList()

        val profiles = supabase.from("profiles")
            .select()
            .decodeList<ProfileRow>()
            .associateBy { it.id }

        val likes = supabase.from("likes")
            .select()
            .decodeList<LikeRow>()
            .groupBy { it.postId }

        val bucket = supabase.storage.from("photos")

        return posts.map { post ->
            val signedUrl = signedUrlCache.getOrPut(post.imagePath) {
                bucket.createSignedUrl(post.imagePath, 24.hours)
            }
            FeedPost(
                id = post.id,
                userId = post.userId,
                userName = profiles[post.userId]?.name ?: "User",
                imagePath = post.imagePath,
                imageUrl = signedUrl,
                createdAt = post.createdAt,
                likedBy = likes[post.id].orEmpty().map { it.userId }.toSet()
            )
        }
    }

    suspend fun uploadPhoto(context: Context, imageUri: Uri) {
        val uid = currentUserId() ?: error("Not signed in")
        requireAllowedUser()

        val bytes = context.contentResolver.openInputStream(imageUri)?.use { it.readBytes() }
            ?: error("Unable to read selected image")
        require(bytes.size <= 15 * 1024 * 1024) { "Image must be 15 MB or smaller." }

        val mime = context.contentResolver.getType(imageUri)
        val extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)
            ?.lowercase()
            ?.takeIf { it.matches(Regex("[a-z0-9]{2,5}")) }
            ?: "jpg"

        val path = "$uid/${UUID.randomUUID()}.$extension"
        val bucket = supabase.storage.from("photos")
        bucket.upload(path, bytes) {
            upsert = false
        }

        supabase.from("posts").insert(
            PostInsert(userId = uid, imagePath = path)
        )
    }

    suspend fun toggleLike(post: FeedPost) {
        val uid = currentUserId() ?: error("Not signed in")
        requireAllowedUser()

        if (post.likedBy.contains(uid)) {
            supabase.from("likes").delete {
                filter {
                    eq("post_id", post.id)
                    eq("user_id", uid)
                }
            }
        } else {
            supabase.from("likes").insert(
                LikeInsert(postId = post.id, userId = uid)
            )
        }
    }

    suspend fun loadComments(postId: String): List<UiComment> {
        requireAllowedUser()

        val rows = supabase.from("comments")
            .select {
                filter { eq("post_id", postId) }
                order("created_at", Order.ASCENDING)
            }
            .decodeList<CommentRow>()

        val profiles = supabase.from("profiles")
            .select()
            .decodeList<ProfileRow>()
            .associateBy { it.id }

        return rows.map { row ->
            UiComment(
                id = row.id,
                userId = row.userId,
                userName = profiles[row.userId]?.name ?: "User",
                text = row.text,
                createdAt = row.createdAt
            )
        }
    }

    suspend fun addComment(postId: String, text: String) {
        val uid = currentUserId() ?: error("Not signed in")
        requireAllowedUser()

        val cleaned = text.trim()
        require(cleaned.isNotEmpty()) { "Comment cannot be empty." }
        require(cleaned.length <= 500) { "Comment must be 500 characters or less." }

        supabase.from("comments").insert(
            CommentInsert(postId = postId, userId = uid, text = cleaned)
        )
    }
}
