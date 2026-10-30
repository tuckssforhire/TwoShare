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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.hours

class TwoShareRepository {
    private val supabase = SupabaseProvider.client
    private val signedUrlCache = mutableMapOf<String, String>()

    private val allowedEmails = setOf(
        "eddierodwards@gmail.com",
        "urban.needs.ph@gmail.com"
    )

    private val allowedReactions = setOf("👍", "❤️", "😂", "😮", "😢", "🔥")

    fun currentUserId(): String? = supabase.auth.currentUserOrNull()?.id

    fun isSignedIn(): Boolean = currentUserId() != null

    fun isTransientNetworkError(throwable: Throwable): Boolean {
        val chain = generateSequence(throwable) { it.cause }.toList()
        val text = chain.joinToString(" ") {
            "${it::class.simpleName.orEmpty()} ${it.localizedMessage.orEmpty()}"
        }.lowercase()

        return listOf(
            "timeout",
            "timed out",
            "failed to connect",
            "connection reset",
            "connection closed",
            "connection abort",
            "network is unreachable",
            "unable to resolve host",
            "broken pipe",
            "socketexception",
            "connectexception"
        ).any(text::contains)
    }

    internal suspend fun <T> retryNetworkRead(
        maxAttempts: Int = 3,
        block: suspend () -> T
    ): T {
        var lastFailure: Throwable? = null
        repeat(maxAttempts) { attempt ->
            try {
                return block()
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (t: Throwable) {
                lastFailure = t
                if (!isTransientNetworkError(t) || attempt == maxAttempts - 1) {
                    throw t
                }
                delay(600L * (attempt + 1))
            }
        }
        throw lastFailure ?: IllegalStateException("Network request failed")
    }

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
        val profile = retryNetworkRead {
            supabase.from("profiles")
                .select {
                    filter { eq("id", uid) }
                }
                .decodeList<ProfileRow>()
                .firstOrNull()
        }
        return profile ?: error("This account is not one of the two allowed users.")
    }

    suspend fun loadFeed(): List<FeedPost> {
        requireAllowedUser()

        val posts = retryNetworkRead {
            supabase.from("posts")
                .select {
                    order("created_at", Order.DESCENDING)
                }
                .decodeList<PostRow>()
        }

        if (posts.isEmpty()) return emptyList()

        val profiles = loadProfiles()
        val likes = retryNetworkRead {
            supabase.from("likes")
                .select()
                .decodeList<LikeRow>()
                .groupBy { it.postId }
        }

        val bucket = supabase.storage.from("twoshare")

        return posts.map { post ->
            val paths = post.imagePaths
                .filter { it.isNotBlank() }
                .ifEmpty { listOf(post.imagePath) }
                .distinct()

            val urls = paths.map { path ->
                signedUrlCache[path] ?: retryNetworkRead {
                    bucket.createSignedUrl(path, 24.hours)
                }.also { signedUrlCache[path] = it }
            }

            FeedPost(
                id = post.id,
                userId = post.userId,
                userName = profiles[post.userId]?.displayName ?: "User",
                imagePaths = paths,
                imageUrls = urls,
                createdAt = post.createdAt,
                likedBy = likes[post.id].orEmpty().map { it.userId }.toSet()
            )
        }
    }

    suspend fun uploadPhoto(context: Context, imageUri: Uri) {
        uploadPhotos(context, listOf(imageUri))
    }

    suspend fun uploadPhotos(context: Context, imageUris: List<Uri>) {
        val uid = currentUserId() ?: error("Not signed in")
        requireAllowedUser()
        require(imageUris.isNotEmpty()) { "Select at least one photo." }

        data class PreparedPhoto(val bytes: ByteArray, val extension: String)

        val prepared = imageUris.mapIndexed { index, imageUri ->
            val bytes = context.contentResolver.openInputStream(imageUri)?.use { it.readBytes() }
                ?: error("Unable to read selected image ${index + 1}.")
            require(bytes.size <= 15 * 1024 * 1024) {
                "Photo ${index + 1} must be 15 MB or smaller."
            }
            val mime = context.contentResolver.getType(imageUri)
            PreparedPhoto(
                bytes = bytes,
                extension = fileExtension(mime, "jpg")
            )
        }

        val bucket = supabase.storage.from("twoshare")
        val paths = mutableListOf<String>()

        prepared.forEach { photo ->
            val path = "$uid/posts/${UUID.randomUUID()}.${photo.extension}"
            bucket.upload(path, photo.bytes) {
                upsert = false
            }
            paths += path
        }

        supabase.from("posts").insert(
            PostInsert(
                userId = uid,
                imagePath = paths.first(),
                imagePaths = paths
            )
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

        val rows = retryNetworkRead {
            supabase.from("comments")
                .select {
                    filter { eq("post_id", postId) }
                    order("created_at", Order.ASCENDING)
                }
                .decodeList<CommentRow>()
        }

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

        val rows = retryNetworkRead {
            supabase.from("messages")
                .select {
                    order("created_at", Order.ASCENDING)
                }
                .decodeList<MessageRow>()
        }

        val profiles = loadProfiles()
        val reactions = retryNetworkRead {
            supabase.from("message_reactions")
                .select()
                .decodeList<MessageReactionRow>()
                .groupBy { it.messageId }
        }

        val bucket = supabase.storage.from("twoshare")

        return rows.map { row ->
            val mediaUrl = row.mediaPath?.let { path ->
                signedUrlCache[path] ?: retryNetworkRead {
                    bucket.createSignedUrl(path, 24.hours)
                }.also { signedUrlCache[path] = it }
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
                readAt = row.readAt,
                reactions = groupedReactions
            )
        }
    }

    suspend fun unreadMessageCount(): Int {
        val uid = currentUserId() ?: return 0
        requireAllowedUser()
        return retryNetworkRead {
            supabase.from("messages")
                .select {
                    filter {
                        neq("sender_id", uid)
                        exact("read_at", null)
                    }
                }
                .decodeList<MessageRow>()
                .size
        }
    }

    suspend fun markIncomingMessagesRead() {
        val uid = currentUserId() ?: return
        requireAllowedUser()
        retryNetworkRead {
            supabase.from("messages").update({
                set("read_at", Instant.now().toString())
            }) {
                filter {
                    neq("sender_id", uid)
                    exact("read_at", null)
                }
            }
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
        val cutoff = Instant.now().minusSeconds(120)
        return retryNetworkRead {
            supabase.from("calls")
                .select {
                    filter {
                        eq("callee_id", uid)
                        eq("status", "ringing")
                    }
                    order("created_at", Order.DESCENDING)
                }
                .decodeList<CallRow>()
                .firstOrNull { call ->
                    runCatching { Instant.parse(call.createdAt).isAfter(cutoff) }
                        .getOrDefault(false)
                }
        }
    }

    suspend fun getCall(callId: String): CallRow? {
        requireAllowedUser()
        return retryNetworkRead {
            supabase.from("calls")
                .select {
                    filter { eq("id", callId) }
                }
                .decodeList<CallRow>()
                .firstOrNull()
        }
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

    suspend fun setCallAnswer(callId: String, answerSdp: String) {
        requireAllowedUser()
        supabase.from("calls").update({
            set("answer_sdp", answerSdp)
            set("status", "accepted")
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
        retryNetworkRead {
            supabase.from("profiles")
                .select()
                .decodeList<ProfileRow>()
                .associateBy { it.id }
        }

    private fun fileExtension(mime: String?, fallback: String): String =
        MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)
            ?.lowercase()
            ?.takeIf { it.matches(Regex("[a-z0-9]{2,5}")) }
            ?: fallback
}
