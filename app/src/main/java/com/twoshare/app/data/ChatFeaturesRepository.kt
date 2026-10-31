package com.twoshare.app.data

import android.content.Context
import android.net.Uri
import android.webkit.MimeTypeMap
import com.twoshare.app.model.*
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Order
import io.github.jan.supabase.storage.storage
import java.time.Instant
import java.util.UUID
import kotlin.time.Duration.Companion.hours
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
private data class ChatMessageRow(
    val id: String,
    @SerialName("sender_id") val senderId: String,
    val body: String? = null,
    @SerialName("media_path") val mediaPath: String? = null,
    @SerialName("media_type") val mediaType: String? = null,
    @SerialName("media_mime") val mediaMime: String? = null,
    @SerialName("created_at") val createdAt: String,
    @SerialName("read_at") val readAt: String? = null,
    @SerialName("client_id") val clientId: String? = null,
    @SerialName("reply_to_id") val replyToId: String? = null,
    @SerialName("edited_at") val editedAt: String? = null,
    @SerialName("delivered_at") val deliveredAt: String? = null
)

@Serializable
private data class ChatMessageInsert(
    @SerialName("sender_id") val senderId: String,
    val body: String? = null,
    @SerialName("media_path") val mediaPath: String? = null,
    @SerialName("media_type") val mediaType: String? = null,
    @SerialName("media_mime") val mediaMime: String? = null,
    @SerialName("client_id") val clientId: String,
    @SerialName("reply_to_id") val replyToId: String? = null
)

@Serializable
private data class EditRow(
    val id: Long,
    @SerialName("message_id") val messageId: String,
    @SerialName("editor_id") val editorId: String,
    @SerialName("old_body") val oldBody: String? = null,
    @SerialName("edited_at") val editedAt: String
)

@Serializable
private data class EditInsert(
    @SerialName("message_id") val messageId: String,
    @SerialName("editor_id") val editorId: String,
    @SerialName("old_body") val oldBody: String?
)

class ChatFeaturesRepository(private val base: TwoShareRepository) {
    private val supabase = SupabaseProvider.client
    private val urlCache = mutableMapOf<String, String>()

    suspend fun loadMessages(): List<UiChatMessage> {
        base.requireAllowedUser()
        val rows = base.retryNetworkRead {
            supabase.from("messages").select { order("created_at", Order.ASCENDING) }.decodeList<ChatMessageRow>()
        }
        val profiles = base.retryNetworkRead {
            supabase.from("profiles").select().decodeList<ProfileRow>().associateBy { it.id }
        }
        val reactions = base.retryNetworkRead {
            supabase.from("message_reactions").select().decodeList<MessageReactionRow>().groupBy { it.messageId }
        }
        val edits = base.retryNetworkRead {
            supabase.from("message_edits").select { order("edited_at", Order.ASCENDING) }.decodeList<EditRow>().groupBy { it.messageId }
        }
        val byId = rows.associateBy { it.id }
        val bucket = supabase.storage.from("twoshare")
        return rows.map { row ->
            val mediaUrl = row.mediaPath?.let { path ->
                urlCache[path] ?: base.retryNetworkRead { bucket.createSignedUrl(path, 24.hours) }.also { urlCache[path] = it }
            }
            val reply = row.replyToId?.let(byId::get)
            UiChatMessage(
                id = row.id,
                senderId = row.senderId,
                senderName = profiles[row.senderId]?.displayName ?: "User",
                body = row.body,
                mediaType = row.mediaType,
                mediaUrl = mediaUrl,
                createdAt = row.createdAt,
                readAt = row.readAt,
                reactions = reactions[row.id].orEmpty().groupBy { it.emoji }.mapValues { e -> e.value.map { it.userId }.toSet() },
                clientId = row.clientId,
                replyToId = row.replyToId,
                replyPreview = reply?.body?.take(120) ?: if (reply?.mediaType != null) "${reply.mediaType} attachment" else null,
                editedAt = row.editedAt,
                deliveredAt = row.deliveredAt,
                editHistory = edits[row.id].orEmpty().map { MessageEditVersion(it.oldBody.orEmpty(), it.editedAt) },
                localState = "sent"
            )
        }
    }

    suspend fun sendMessage(context: Context, text: String, attachmentUri: Uri?, clientId: String, replyToId: String?): UiChatMessage {
        val uid = base.currentUserId() ?: error("Your session has expired. Please sign in again.")
        base.requireAllowedUser()
        val cleaned = text.trim().takeIf { it.isNotEmpty() }
        require(cleaned == null || cleaned.length <= 2000)
        require(cleaned != null || attachmentUri != null)
        var mediaPath: String? = null
        var mediaType: String? = null
        var mediaMime: String? = null
        if (attachmentUri != null) {
            val mime = context.contentResolver.getType(attachmentUri)?.lowercase() ?: error("Unable to determine attachment type.")
            val type = when { mime.startsWith("image/") -> "image"; mime.startsWith("video/") -> "video"; else -> error("Only photos and videos can be sent.") }
            val bytes = context.contentResolver.openInputStream(attachmentUri)?.use { it.readBytes() } ?: error("Unable to read attachment.")
            require(bytes.size <= 25 * 1024 * 1024) { "Attachment must be 25 MB or smaller." }
            val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) ?: if (type == "video") "mp4" else "jpg"
            mediaPath = "$uid/chat/${UUID.randomUUID()}.$ext"
            supabase.storage.from("twoshare").upload(mediaPath, bytes) { upsert = false }
            mediaType = type
            mediaMime = mime
        }
        val row = supabase.from("messages").insert(ChatMessageInsert(uid, cleaned, mediaPath, mediaType, mediaMime, clientId, replyToId)) { select() }.decodeSingle<ChatMessageRow>()
        return UiChatMessage(row.id, uid, "You", row.body, row.mediaType, attachmentUri?.toString(), row.createdAt, row.readAt, emptyMap(), row.clientId, row.replyToId, null, row.editedAt, row.deliveredAt, emptyList(), "sent")
    }

    suspend fun markIncomingDelivered() {
        val uid = base.currentUserId() ?: return
        base.requireAllowedUser()
        supabase.from("messages").update({ set("delivered_at", Instant.now().toString()) }) {
            filter { neq("sender_id", uid); exact("delivered_at", null) }
        }
    }

    suspend fun markIncomingRead() {
        val uid = base.currentUserId() ?: return
        base.requireAllowedUser()
        supabase.from("messages").update({ set("read_at", Instant.now().toString()); set("delivered_at", Instant.now().toString()) }) {
            filter { neq("sender_id", uid); exact("read_at", null) }
        }
    }

    suspend fun editMessage(message: UiChatMessage, newText: String) {
        val uid = base.currentUserId() ?: error("Session expired")
        require(message.senderId == uid) { "You can only edit your own message." }
        val cleaned = newText.trim()
        require(cleaned.isNotEmpty() && cleaned.length <= 2000)
        if (cleaned == message.body) return
        supabase.from("message_edits").insert(EditInsert(message.id, uid, message.body))
        supabase.from("messages").update({ set("body", cleaned); set("edited_at", Instant.now().toString()) }) {
            filter { eq("id", message.id); eq("sender_id", uid) }
        }
    }
}
