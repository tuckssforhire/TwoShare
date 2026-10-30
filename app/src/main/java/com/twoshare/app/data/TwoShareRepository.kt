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
    private val allowedEmails = setOf("eddierodwards@gmail.com", "urban.needs.ph@gmail.com")
    private val allowedReactions = setOf("👍", "❤️", "😂", "😮", "😢", "🔥")

    fun currentUserId(): String? = supabase.auth.currentUserOrNull()?.id
    fun isSignedIn(): Boolean = currentUserId() != null

    fun isTransientNetworkError(throwable: Throwable): Boolean {
        val text = generateSequence(throwable) { it.cause }.joinToString(" ") {
            "${it::class.simpleName.orEmpty()} ${it.localizedMessage.orEmpty()}"
        }.lowercase()
        return listOf("timeout", "timed out", "failed to connect", "connection reset", "connection closed", "connection abort", "network is unreachable", "unable to resolve host", "broken pipe", "socketexception", "connectexception", "request timeout has expired").any(text::contains)
    }

    internal suspend fun <T> retryNetworkRead(maxAttempts: Int = 5, block: suspend () -> T): T {
        var lastFailure: Throwable? = null
        repeat(maxAttempts) { attempt ->
            try { return block() }
            catch (cancel: CancellationException) { throw cancel }
            catch (t: Throwable) {
                lastFailure = t
                if (!isTransientNetworkError(t) || attempt == maxAttempts - 1) throw t
                delay(750L * (attempt + 1))
            }
        }
        throw lastFailure ?: IllegalStateException("Network request failed")
    }

    suspend fun sendSignInEmail(email: String) {
        val normalized = email.trim().lowercase(); require(normalized in allowedEmails) { "This email is not authorized for TwoShare." }
        supabase.auth.signInWith(OTP) { this.email = normalized; createUser = false }
    }
    suspend fun verifyEmailCode(email: String, code: String) {
        val normalized = email.trim().lowercase(); require(normalized in allowedEmails); require(code.trim().isNotEmpty())
        supabase.auth.verifyEmailOtp(type = OtpType.Email.EMAIL, email = normalized, token = code.trim()); requireAllowedUser()
    }
    suspend fun signOut() { supabase.auth.signOut() }

    suspend fun requireAllowedUser(): ProfileRow {
        val uid = currentUserId() ?: error("Not signed in")
        return retryNetworkRead { supabase.from("profiles").select { filter { eq("id", uid) } }.decodeList<ProfileRow>().firstOrNull() }
            ?: error("This account is not one of the two allowed users.")
    }

    suspend fun loadFeed(): List<FeedPost> {
        requireAllowedUser()
        val posts = retryNetworkRead { supabase.from("posts").select { order("created_at", Order.DESCENDING) }.decodeList<PostRow>() }
        if (posts.isEmpty()) return emptyList()
        val profiles = loadProfiles()
        val likes = retryNetworkRead { supabase.from("likes").select().decodeList<LikeRow>().groupBy { it.postId } }
        val bucket = supabase.storage.from("twoshare")
        return posts.map { post ->
            val paths = post.imagePaths.filter { it.isNotBlank() }.ifEmpty { listOf(post.imagePath) }.distinct()
            val urls = paths.map { path -> signedUrlCache[path] ?: retryNetworkRead { bucket.createSignedUrl(path, 24.hours) }.also { signedUrlCache[path] = it } }
            FeedPost(post.id, post.userId, profiles[post.userId]?.displayName ?: "User", paths, urls, post.createdAt, likes[post.id].orEmpty().map { it.userId }.toSet())
        }
    }

    suspend fun uploadPhoto(context: Context, imageUri: Uri) = uploadPhotos(context, listOf(imageUri))
    suspend fun uploadPhotos(context: Context, imageUris: List<Uri>) {
        val uid = currentUserId() ?: error("Not signed in"); requireAllowedUser(); require(imageUris.isNotEmpty())
        data class PreparedPhoto(val bytes: ByteArray, val extension: String)
        val prepared = imageUris.mapIndexed { index, uri ->
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: error("Unable to read selected image ${index + 1}.")
            require(bytes.size <= 15 * 1024 * 1024) { "Photo ${index + 1} must be 15 MB or smaller." }
            PreparedPhoto(bytes, fileExtension(context.contentResolver.getType(uri), "jpg"))
        }
        val bucket = supabase.storage.from("twoshare"); val paths = mutableListOf<String>()
        prepared.forEach { p -> val path = "$uid/posts/${UUID.randomUUID()}.${p.extension}"; bucket.upload(path, p.bytes) { upsert = false }; paths += path }
        supabase.from("posts").insert(PostInsert(uid, paths.first(), paths))
    }

    suspend fun toggleLike(post: FeedPost) {
        val uid = currentUserId() ?: error("Not signed in"); requireAllowedUser()
        if (uid in post.likedBy) supabase.from("likes").delete { filter { eq("post_id", post.id); eq("user_id", uid) } }
        else supabase.from("likes").insert(LikeInsert(post.id, uid))
    }
    suspend fun loadComments(postId: String): List<UiComment> {
        requireAllowedUser(); val rows = retryNetworkRead { supabase.from("comments").select { filter { eq("post_id", postId) }; order("created_at", Order.ASCENDING) }.decodeList<CommentRow>() }; val profiles = loadProfiles()
        return rows.map { UiComment(it.id, it.userId, profiles[it.userId]?.displayName ?: "User", it.text, it.createdAt) }
    }
    suspend fun addComment(postId: String, text: String) { val uid=currentUserId()?:error("Not signed in"); requireAllowedUser(); val cleaned=text.trim(); require(cleaned.isNotEmpty()); require(cleaned.length<=1000); supabase.from("comments").insert(CommentInsert(postId,uid,cleaned)) }

    suspend fun loadMessages(): List<UiChatMessage> {
        requireAllowedUser(); val rows=retryNetworkRead { supabase.from("messages").select { order("created_at",Order.ASCENDING) }.decodeList<MessageRow>() }; val profiles=loadProfiles(); val reactions=retryNetworkRead { supabase.from("message_reactions").select().decodeList<MessageReactionRow>().groupBy { it.messageId } }; val bucket=supabase.storage.from("twoshare")
        return rows.map { row ->
            val mediaUrl=row.mediaPath?.let { path -> signedUrlCache[path] ?: retryNetworkRead { bucket.createSignedUrl(path,24.hours) }.also { signedUrlCache[path]=it } }
            val grouped=reactions[row.id].orEmpty().groupBy { it.emoji }.mapValues { e -> e.value.map { it.userId }.toSet() }
            UiChatMessage(row.id,row.senderId,profiles[row.senderId]?.displayName?:"User",row.body,row.mediaType,mediaUrl,row.createdAt,row.readAt,grouped)
        }
    }
    suspend fun unreadMessageCount(): Int { val uid=currentUserId()?:return 0; requireAllowedUser(); return retryNetworkRead { supabase.from("messages").select { filter { neq("sender_id",uid); exact("read_at",null) } }.decodeList<MessageRow>().size } }
    suspend fun markIncomingMessagesRead() { val uid=currentUserId()?:return; requireAllowedUser(); retryNetworkRead { supabase.from("messages").update({ set("read_at",Instant.now().toString()) }) { filter { neq("sender_id",uid); exact("read_at",null) } } } }
    suspend fun sendMessage(context: Context,text:String,attachmentUri:Uri?) {
        val uid=currentUserId()?:error("Not signed in"); requireAllowedUser(); val cleaned=text.trim().takeIf { it.isNotEmpty() }; require(cleaned==null||cleaned.length<=2000); require(cleaned!=null||attachmentUri!=null)
        var mediaPath:String?=null; var mediaType:String?=null; var mediaMime:String?=null
        if(attachmentUri!=null){ val mime=context.contentResolver.getType(attachmentUri)?.lowercase()?:error("Unable to determine the attachment type."); val type=when{mime.startsWith("image/")->"image";mime.startsWith("video/")->"video";else->error("Only photos and videos can be sent.")}; val bytes=context.contentResolver.openInputStream(attachmentUri)?.use{it.readBytes()}?:error("Unable to read the selected attachment."); require(bytes.size<=25*1024*1024); val path="$uid/chat/${UUID.randomUUID()}.${fileExtension(mime,if(type=="video")"mp4" else "jpg")}"; supabase.storage.from("twoshare").upload(path,bytes){upsert=false}; mediaPath=path;mediaType=type;mediaMime=mime }
        supabase.from("messages").insert(MessageInsert(uid,cleaned,mediaPath,mediaType,mediaMime))
    }
    suspend fun toggleMessageReaction(message:UiChatMessage,emoji:String){val uid=currentUserId()?:error("Not signed in");requireAllowedUser();require(emoji in allowedReactions);if(message.reactions[emoji]?.contains(uid)==true)supabase.from("message_reactions").delete{filter{eq("message_id",message.id);eq("user_id",uid);eq("emoji",emoji)}}else supabase.from("message_reactions").insert(MessageReactionInsert(message.id,uid,emoji))}

    suspend fun createOutgoingCall(isVideo:Boolean):CallRow{val uid=currentUserId()?:error("Not signed in");requireAllowedUser();val other=loadProfiles().values.firstOrNull{it.id!=uid}?:error("The second TwoShare account is not available.");return supabase.from("calls").insert(CallInsert(uid,other.id,isVideo)){select()}.decodeSingle()}
    suspend fun findIncomingCall():CallRow?{val uid=currentUserId()?:return null;val cutoff=Instant.now().minusSeconds(120);return retryNetworkRead{supabase.from("calls").select{filter{eq("callee_id",uid);eq("status","ringing")};order("created_at",Order.DESCENDING)}.decodeList<CallRow>().firstOrNull{runCatching{Instant.parse(it.createdAt).isAfter(cutoff)}.getOrDefault(false)}}}
    suspend fun getCall(callId:String):CallRow?{requireAllowedUser();return retryNetworkRead{supabase.from("calls").select{filter{eq("id",callId)}}.decodeList<CallRow>().firstOrNull()}}
    suspend fun partnerName(call:CallRow):String{val uid=currentUserId()?:return "TwoShare";return loadProfiles()[if(call.callerId==uid)call.calleeId else call.callerId]?.displayName?:"TwoShare"}
    suspend fun setCallOffer(callId:String,sdp:String){requireAllowedUser();supabase.from("calls").update({set("offer_sdp",sdp)}){filter{eq("id",callId)}}}
    suspend fun acceptCall(callId:String,answerSdp:String){requireAllowedUser();supabase.from("calls").update({set("answer_sdp",answerSdp);set("status","accepted");set("answered_at",Instant.now().toString())}){filter{eq("id",callId)}}}
    suspend fun setCallAnswer(callId:String,answerSdp:String){requireAllowedUser();supabase.from("calls").update({set("answer_sdp",answerSdp);set("status","accepted")}){filter{eq("id",callId)}}}
    suspend fun rejectCall(callId:String)=setCallStatus(callId,"rejected")
    suspend fun cancelCall(callId:String)=setCallStatus(callId,"cancelled")
    suspend fun endCall(callId:String)=setCallStatus(callId,"ended")
    private suspend fun setCallStatus(callId:String,status:String){requireAllowedUser();supabase.from("calls").update({set("status",status);if(status in setOf("rejected","cancelled","ended"))set("ended_at",Instant.now().toString())}){filter{eq("id",callId)}}}
    private suspend fun loadProfiles():Map<String,ProfileRow> = retryNetworkRead{supabase.from("profiles").select().decodeList<ProfileRow>().associateBy{it.id}}
    private fun fileExtension(mime:String?,fallback:String):String=MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)?.lowercase()?.takeIf{it.matches(Regex("[a-z0-9]{2,5}"))}?:fallback
}
