package com.twoshare.app.data

import android.content.Context
import com.twoshare.app.model.FeedPost
import com.twoshare.app.model.UiChatMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val cacheJson = Json { ignoreUnknownKeys = true }

@Serializable private data class CachedPost(val id:String,val userId:String,val userName:String,val imagePaths:List<String>,val imageUrls:List<String>,val caption:String?,val createdAt:String,val likedBy:List<String>)
@Serializable private data class CachedMessage(val id:String,val senderId:String,val senderName:String,val body:String?,val mediaType:String?,val mediaUrl:String?,val createdAt:String,val readAt:String?,val reactions:Map<String,List<String>>,val clientId:String?,val replyToId:String?,val replyPreview:String?,val editedAt:String?,val deliveredAt:String?)

object ConnectionStatus {
 private val _online=MutableStateFlow(true)
 val online=_online.asStateFlow()
 fun online(){_online.value=true}
 fun connecting(){_online.value=false}
}

object OfflineCache {
 private const val PREFS="twoshare_offline_cache"
 private var context:Context?=null
 fun init(value:Context){context=value.applicationContext}
 private fun prefs()=context?.getSharedPreferences(PREFS,0)
 fun savePosts(posts:List<FeedPost>){val rows=posts.take(3).map{CachedPost(it.id,it.userId,it.userName,it.imagePaths,it.imageUrls,it.caption,it.createdAt,it.likedBy.toList())};prefs()?.edit()?.putString("posts",cacheJson.encodeToString(rows))?.apply()}
 fun loadPosts():List<FeedPost>{val raw=prefs()?.getString("posts",null)?:return emptyList();return runCatching{cacheJson.decodeFromString<List<CachedPost>>(raw).map{FeedPost(it.id,it.userId,it.userName,it.imagePaths,it.imageUrls,it.caption,it.createdAt,it.likedBy.toSet())}}.getOrDefault(emptyList())}
 fun saveMessages(messages:List<UiChatMessage>){val rows=messages.filter{it.localState=="sent"}.takeLast(20).map{CachedMessage(it.id,it.senderId,it.senderName,it.body,it.mediaType,it.mediaUrl,it.createdAt,it.readAt,it.reactions.mapValues{e->e.value.toList()},it.clientId,it.replyToId,it.replyPreview,it.editedAt,it.deliveredAt)};prefs()?.edit()?.putString("messages",cacheJson.encodeToString(rows))?.apply()}
 fun loadMessages():List<UiChatMessage>{val raw=prefs()?.getString("messages",null)?:return emptyList();return runCatching{cacheJson.decodeFromString<List<CachedMessage>>(raw).map{UiChatMessage(it.id,it.senderId,it.senderName,it.body,it.mediaType,it.mediaUrl,it.createdAt,it.readAt,it.reactions.mapValues{e->e.value.toSet()},it.clientId,it.replyToId,it.replyPreview,it.editedAt,it.deliveredAt,emptyList(),"sent")}}.getOrDefault(emptyList())}
}
