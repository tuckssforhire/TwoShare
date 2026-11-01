package com.twoshare.app.data

import android.content.Context
import android.net.Uri
import android.webkit.MimeTypeMap
import com.twoshare.app.model.*
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Order
import io.github.jan.supabase.storage.storage
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.util.UUID
import kotlin.time.Duration.Companion.hours
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable private data class ChatMessageRow(val id:String,@SerialName("sender_id") val senderId:String,val body:String?=null,@SerialName("media_path") val mediaPath:String?=null,@SerialName("media_type") val mediaType:String?=null,@SerialName("media_mime") val mediaMime:String?=null,@SerialName("created_at") val createdAt:String,@SerialName("read_at") val readAt:String?=null,@SerialName("client_id") val clientId:String?=null,@SerialName("reply_to_id") val replyToId:String?=null,@SerialName("edited_at") val editedAt:String?=null,@SerialName("delivered_at") val deliveredAt:String?=null)
@Serializable private data class ChatMessageInsert(@SerialName("sender_id") val senderId:String,val body:String?=null,@SerialName("media_path") val mediaPath:String?=null,@SerialName("media_type") val mediaType:String?=null,@SerialName("media_mime") val mediaMime:String?=null,@SerialName("client_id") val clientId:String,@SerialName("reply_to_id") val replyToId:String?=null)
@Serializable private data class EditRow(val id:Long,@SerialName("message_id") val messageId:String,@SerialName("editor_id") val editorId:String,@SerialName("old_body") val oldBody:String?=null,@SerialName("edited_at") val editedAt:String)
@Serializable private data class EditInsert(@SerialName("message_id") val messageId:String,@SerialName("editor_id") val editorId:String,@SerialName("old_body") val oldBody:String?)

class ChatFeaturesRepository(private val base:TwoShareRepository){
 private val supabase=SupabaseProvider.client
 private val urlCache=mutableMapOf<String,String>()
 private var profileCache:Map<String,ProfileRow> = emptyMap()
 private var profileCacheAt=0L

 private suspend fun profiles():Map<String,ProfileRow>{
  val now=System.currentTimeMillis()
  if(profileCache.isNotEmpty() && now-profileCacheAt<300_000L)return profileCache
  val loaded=runCatching{base.retryNetworkRead{supabase.from("profiles").select().decodeList<ProfileRow>().associateBy{it.id}}}.getOrDefault(profileCache)
  if(loaded.isNotEmpty()){profileCache=loaded;profileCacheAt=now}
  return profileCache
 }

 suspend fun loadMessages(pageSize:Int=60):List<UiChatMessage>{
  return try{
   check(base.currentUserId()!=null){"Your session has expired. Please sign in again."}
   val rows=base.retryNetworkRead{supabase.from("messages").select{order("created_at",Order.DESCENDING);limit(pageSize.toLong())}.decodeList<ChatMessageRow>()}.asReversed()
   val profiles=profiles()
   val ids=rows.map{it.id}.toSet()
   val reactions=if(ids.isEmpty()) emptyMap() else runCatching{base.retryNetworkRead{supabase.from("message_reactions").select().decodeList<MessageReactionRow>().filter{it.messageId in ids}.groupBy{it.messageId}}}.getOrDefault(emptyMap())
   val edits=if(ids.isEmpty()) emptyMap() else runCatching{base.retryNetworkRead{supabase.from("message_edits").select{order("edited_at",Order.ASCENDING)}.decodeList<EditRow>().filter{it.messageId in ids}.groupBy{it.messageId}}}.getOrDefault(emptyMap())
   val byId=rows.associateBy{it.id};val bucket=supabase.storage.from("twoshare")
   val result=rows.mapNotNull{row->runCatching{val mediaUrl=row.mediaPath?.let{path->urlCache[path]?:runCatching{base.retryNetworkRead{bucket.createSignedUrl(path,24.hours)}}.getOrNull()?.also{urlCache[path]=it}};val reply=row.replyToId?.let(byId::get);UiChatMessage(row.id,row.senderId,profiles[row.senderId]?.displayName?:if(row.senderId==base.currentUserId())"You" else "User",row.body,row.mediaType,mediaUrl,row.createdAt,row.readAt,reactions[row.id].orEmpty().groupBy{it.emoji}.mapValues{e->e.value.map{it.userId}.toSet()},row.clientId,row.replyToId,reply?.body?.take(120)?:if(reply?.mediaType!=null)"${reply.mediaType} attachment" else null,row.editedAt,row.deliveredAt,edits[row.id].orEmpty().map{MessageEditVersion(it.oldBody.orEmpty(),it.editedAt)},"sent")}.getOrNull()}
   OfflineCache.saveMessages(result);ConnectionStatus.online();result
  }catch(t:Throwable){if(base.isTransientNetworkError(t)){ConnectionStatus.connecting();OfflineCache.loadMessages().takeLast(pageSize)}else throw t}
 }

 private fun readAttachmentBounded(context:Context,uri:Uri,maxBytes:Int):ByteArray{
  context.contentResolver.openAssetFileDescriptor(uri,"r")?.use{fd->if(fd.length>maxBytes)error("Attachment must be ${maxBytes/1024/1024} MB or smaller.")}
  val input=context.contentResolver.openInputStream(uri)?:error("Unable to read attachment.")
  input.use{stream->
   val out=ByteArrayOutputStream(minOf(maxBytes,1024*1024));val buffer=ByteArray(64*1024);var total=0
   while(true){val n=stream.read(buffer);if(n<0)break;total+=n;if(total>maxBytes)error("Attachment must be ${maxBytes/1024/1024} MB or smaller.");out.write(buffer,0,n)}
   return out.toByteArray()
  }
 }

 suspend fun sendMessage(context:Context,text:String,attachmentUri:Uri?,clientId:String,replyToId:String?):UiChatMessage{val uid=base.currentUserId()?:error("Your session has expired. Please sign in again.");val cleaned=text.trim().takeIf{it.isNotEmpty()};require(cleaned==null||cleaned.length<=2000);require(cleaned!=null||attachmentUri!=null);var mediaPath:String?=null;var mediaType:String?=null;var mediaMime:String?=null;if(attachmentUri!=null){val mime=context.contentResolver.getType(attachmentUri)?.lowercase()?:error("Unable to determine attachment type.");val type=when{mime.startsWith("image/")->"image";mime.startsWith("video/")->"video";else->error("Only photos and videos can be sent.")};val maxBytes=15*1024*1024;val bytes=readAttachmentBounded(context,attachmentUri,maxBytes);val ext=MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)?:if(type=="video")"mp4" else "jpg";mediaPath="$uid/chat/${UUID.randomUUID()}.$ext";supabase.storage.from("twoshare").upload(mediaPath,bytes){upsert=false};mediaType=type;mediaMime=mime};val row=supabase.from("messages").insert(ChatMessageInsert(uid,cleaned,mediaPath,mediaType,mediaMime,clientId,replyToId)){select()}.decodeSingle<ChatMessageRow>();runCatching{PushSender.notifyOther(if(replyToId==null)"message" else "reply",messageId=row.id)};return UiChatMessage(row.id,uid,"You",row.body,row.mediaType,attachmentUri?.toString(),row.createdAt,row.readAt,emptyMap(),row.clientId,row.replyToId,null,row.editedAt,row.deliveredAt,emptyList(),"sent")}
 suspend fun markIncomingDelivered(){val uid=base.currentUserId()?:return;supabase.from("messages").update({set("delivered_at",Instant.now().toString())}){filter{neq("sender_id",uid);exact("delivered_at",null)}}}
 suspend fun markIncomingRead(){val uid=base.currentUserId()?:return;supabase.from("messages").update({set("read_at",Instant.now().toString());set("delivered_at",Instant.now().toString())}){filter{neq("sender_id",uid);exact("read_at",null)}}}
 suspend fun editMessage(message:UiChatMessage,newText:String){val uid=base.currentUserId()?:error("Session expired");require(message.senderId==uid){"You can only edit your own message."};val cleaned=newText.trim();require(cleaned.isNotEmpty()&&cleaned.length<=2000);if(cleaned==message.body)return;supabase.from("message_edits").insert(EditInsert(message.id,uid,message.body));supabase.from("messages").update({set("body",cleaned);set("edited_at",Instant.now().toString())}){filter{eq("id",message.id);eq("sender_id",uid)}}}
}
