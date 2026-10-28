package com.twoshare.app.data

import android.content.Context
import android.net.Uri
import android.webkit.MimeTypeMap
import com.twoshare.app.model.*
import io.github.jan.supabase.auth.OtpType
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.builtin.OTP
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Order
import io.github.jan.supabase.storage.storage
import java.time.Instant
import java.util.UUID
import kotlin.time.Duration.Companion.hours

class TwoShareRepository {
    private val supabase = SupabaseProvider.client
    private val signedUrlCache = mutableMapOf<String, String>()

    private val allowedEmails = setOf(
        "eddierodwards@gmail.com",
        "misamitchlailarene@gmail.com"
    )

    private val allowedReactions = setOf("👍", "❤️", "😂", "😮", "😢", "🔥")

    fun currentUserId(): String? = supabase.auth.currentUserOrNull()?.id

    fun isSignedIn(): Boolean = currentUserId() != null

    suspend fun sendSignInEmail(email: String) {
        val normalized = email.trim().lowercase()
        require(normalized in allowedEmails) { "This email is not authorized for TwoShare." }

        supabase.auth.signInWith(OTP) {
            this.email = normalized
            createUser = false
        }
    }

    suspend fun verifyEmailCode(email: String, code: String) {
        val normalized = email.trim().lowercase()
        require(normalized in allowedEmails) { "This email is not authorized for TwoShare." }
        require(code.trim().isNotEmpty()) { "Enter the code from your email." }

        supabase.auth.verifyEmailOtp(
            type = OtpType.Email.EMAIL,
            email = normalized,
            token = code.trim()
        )
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

        val profiles = loadProfiles()
        val likes = supabase.from("likes")
            .select()
            .decodeList<LikeRow>()
            .groupBy { it.postId }

        val bucket = supabase.storage.from("twoshare")

        return posts.map { post ->
            val signedUrl = signedUrlCache.getOrPut(post.imagePath) {
                bucket.createSignedUrl(post.imagePath, 24.hours)
            }
            FeedPost(
                id = post.id,
                userId = post.userId,
                userName = profiles[post.userId]?.displayName ?: "User",
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
        val extension = fileExtension(mime, "jpg")

        val path = "$uid/${UUID.randomUUID()}.$extension"
        val bucket = supabase.storage.from("twoshare")
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

        val profiles = loadProfiles()

        return rows.map { row ->
            UiComment(
                id = row.id,
                userId = row.userId,
                userName = profiles[row.userId]?.displayName ?: "User",
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
        require(cleaned.length <= 1000) { "Comment must be 1000 characters or less." }

        supabase.from("comments").insert(
            CommentInsert(postId = postId, userId = uid, text = cleaned)
        )
    }

    suspend fun loadMessages(): List<UiChatMessage> {
        requireAllowedUser()

        val rows = supabase.from("messages")
            .select {
                order("created_at", Order.ASCENDING)
            }
            .decodeList<MessageRow>()

        val profiles = loadProfiles()
        val reactions = supabase.from("message_reactions")
            .select()
            .decodeList<MessageReactionRow>()
            .groupBy { it.messageId }

        val bucket = supabase.storage.from("twoshare")

        return rows.map { row ->
            val mediaUrl = row.mediaPath?.let { path ->
                signedUrlCache.getOrPut(path) {
                    bucket.createSignedUrl(path, 24.hours)
                }
            }

            val groupedReactions = reactions[row.id]
                .orEmpty()
                .groupBy { it.emoji }
                .mapValues { (_, values) -> values.map { it.userId }.toSet() }

            UiChatMessage(
                id = row.id,
                senderId = row.senderId,
                senderName = profiles[row.senderId]?.displayName ?: "User",
                body = row.body,
                mediaType = row.mediaType,
                mediaUrl = mediaUrl,
                createdAt = row.createdAt,
                reactions = groupedReactions
            )
        }
    }

    suspend fun sendMessage(context: Context, text: String, attachmentUri: Uri?) {
        val uid = currentUserId() ?: error("Not signed in")
        requireAllowedUser()

        val cleaned = text.trim().takeIf { it.isNotEmpty() }
        require(cleaned == null || cleaned.length <= 2000) { "Message must be 2000 characters or less." }
        require(cleaned != null || attachmentUri != null) { "Type a message or attach a photo/video." }

        var mediaPath: String? = null
        var mediaType: String? = null
        var mediaMime: String? = null

        if (attachmentUri != null) {
            val mime = context.contentResolver.getType(attachmentUri)?.lowercase()
                ?: error("Unable to determine the attachment type.")
            val type = when {
                mime.startsWith("image/") -> "image"
                mime.startsWith("video/") -> "video"
                else -> error("Only photos and videos can be sent.")
            }

            val bytes = context.contentResolver.openInputStream(attachmentUri)?.use { it.readBytes() }
                ?: error("Unable to read the selected attachment.")
            require(bytes.size <= 25 * 1024 * 1024) {
                "Attachments must be 25 MB or smaller to conserve your Supabase Free storage."
            }

            val extension = fileExtension(mime, if (type == "video") "mp4" else "jpg")
            val path = "$uid/chat/${UUID.randomUUID()}.$extension"
            supabase.storage.from("twoshare").upload(path, bytes) {
                upsert = false
            }

            mediaPath = path
            mediaType = type
            mediaMime = mime
        }

        supabase.from("messages").insert(
            MessageInsert(
                senderId = uid,
                body = cleaned,
                mediaPath = mediaPath,
                mediaType = mediaType,
                mediaMime = mediaMime
            )
        )
    }

    suspend fun toggleMessageReaction(message: UiChatMessage, emoji: String) {
        val uid = currentUserId() ?: error("Not signed in")
        requireAllowedUser()
        require(emoji in allowedReactions) { "Unsupported reaction." }

        val alreadyReacted = message.reactions[emoji]?.contains(uid) == true
        if (alreadyReacted) {
            supabase.from("message_reactions").delete {
                filter {
                    eq("message_id", message.id)
                    eq("user_id", uid)
                    eq("emoji", emoji)
                }
            }
        } else {
            supabase.from("message_reactions").insert(
                MessageReactionInsert(
                    messageId = message.id,
                    userId = uid,
                    emoji = emoji
                )
            )
        }
    }

    suspend fun createOutgoingCall(isVideo: Boolean): CallRow {
        val uid = currentUserId() ?: error("Not signed in")
        requireAllowedUser()
        val other = loadProfiles().values.firstOrNull { it.id != uid }
            ?: error("The second TwoShare account is not available.")

        return supabase.from("calls").insert(
            CallInsert(
                callerId = uid,
                calleeId = other.id,
                isVideo = isVideo
            )
        ) {
            select()
        }.decodeSingle<CallRow>()
    }

    suspend fun findIncomingCall(): CallRow? {
        val uid = currentUserId() ?: return null
        return supabase.from("calls")
            .select {
                filter {
                    eq("callee_id", uid)
                    eq("status", "ringing")
                }
                order("created_at", Order.DESCENDING)
            }
            .decodeList<CallRow>()
            .firstOrNull()
    }

    suspend fun getCall(callId: String): CallRow? {
        requireAllowedUser()
        return supabase.from("calls")
            .select {
                filter { eq("id", callId) }
            }
            .decodeList<CallRow>()
            .firstOrNull()
    }

    suspend fun partnerName(call: CallRow): String {
        val uid = currentUserId() ?: return "TwoShare"
        val partnerId = if (call.callerId == uid) call.calleeId else call.callerId
        return loadProfiles()[partnerId]?.displayName ?: "TwoShare"
    }

    suspend fun setCallOffer(callId: String, sdp: String) {
        requireAllowedUser()
        supabase.from("calls").update({
            set("offer_sdp", sdp)
        }) {
            filter { eq("id", callId) }
        }
    }

    suspend fun acceptCall(callId: String, answerSdp: String) {
        requireAllowedUser()
        supabase.from("calls").update({
            set("answer_sdp", answerSdp)
            set("status", "accepted")
            set("answered_at", Instant.now().toString())
        }) {
            filter { eq("id", callId) }
        }
    }

    suspend fun rejectCall(callId: String) {
        setCallStatus(callId, "rejected")
    }

    suspend fun cancelCall(callId: String) {
        setCallStatus(callId, "cancelled")
    }

    suspend fun endCall(callId: String) {
        setCallStatus(callId, "ended")
    }

    private suspend fun setCallStatus(callId: String, status: String) {
        requireAllowedUser()
        supabase.from("calls").update({
            set("status", status)
            if (status in setOf("rejected", "cancelled", "ended")) {
                set("ended_at", Instant.now().toString())
            }
        }) {
            filter { eq("id", callId) }
        }
    }

    private suspend fun loadProfiles(): Map<String, ProfileRow> =
        supabase.from("profiles")
            .select()
            .decodeList<ProfileRow>()
            .associateBy { it.id }

    private fun fileExtension(mime: String?, fallback: String): String =
        MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)
            ?.lowercase()
            ?.takeIf { it.matches(Regex("[a-z0-9]{2,5}")) }
            ?: fallback
}
