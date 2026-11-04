package com.twoshare.app.data

import android.content.Context
import com.twoshare.app.model.FeedPost
import com.twoshare.app.model.UiChatMessage
import io.github.jan.supabase.auth.auth
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val cacheJson = Json { ignoreUnknownKeys = true }
@Serializable private data class CachedPost(
 val id:String,val userId:String,val userName:String,val imagePaths:List<String>,
 val imageUrls:List<String>,val caption:String?,val createdAt:String,val likedBy:List<String>,
 val nowTogetherPartnerName:String?=null,val nowTogetherInitiatorCaption:String?=null,
 val nowTogetherPartnerCaption:String?=null
)
@Serializable private data class CachedMessage(
 val id:String,val senderId:String,val senderName:String,val body:String?,
 val mediaType:String?,val mediaUrl:String?,val createdAt:String,val readAt:String?,
 val reactions:Map<String,List<String>>,val clientId:String?,val replyToId:String?,
 val replyPreview:String?,val editedAt:String?,val deliveredAt:String?
)
@Serializable private data class StoredThread(
 val savedAtMs:Long,val comments:List<ThreadComment>,
 val reactions:Map<String,Map<String,List<String>>>
)
data class CachedThread(
 val savedAtMs:Long,val comments:List<ThreadComment>,
 val reactions:Map<String,Map<String,Set<String>>>
)
@Serializable private data class StoredReactions(val values:Map<String,Map<String,List<String>>>)

object ConnectionStatus {
 private val _online=MutableStateFlow(true)
 val online=_online.asStateFlow()
 fun online(){_online.value=true}
 fun connecting(){_online.value=false}
}

/** Keep the complete feed in memory; persist only the five newest posts per account.
 * Cached comments and images can be shown without a Supabase request on navigation.
 */
object OfflineCache {
 private const val PREFS="twoshare_offline_cache"
 private var context:Context?=null
 private val memoryPosts=mutableMapOf<String,List<FeedPost>>()
 private val memoryThreads=mutableMapOf<String,CachedThread>()
 private val memoryReactions=mutableMapOf<String,Map<String,Map<String,Set<String>>>>()
 fun init(value:Context){context=value.applicationContext}
 private fun prefs()=context?.getSharedPreferences(PREFS,0)
 private fun uid():String?=SupabaseProvider.client.auth.currentUserOrNull()?.id
 private fun key(label:String,user:String)="$label:$user"
 private fun threadKey(user:String,postId:String)="thread:$user:$postId"
 private fun now()=System.currentTimeMillis()
 @Synchronized fun savePosts(posts:List<FeedPost>){
  val user=uid()?:return
  memoryPosts[user]=posts
  val rows=posts.take(5).map{CachedPost(it.id,it.userId,it.userName,it.imagePaths,it.imageUrls,
    it.caption,it.createdAt,it.likedBy.toList(),it.nowTogetherPartnerName,
    it.nowTogetherInitiatorCaption,it.nowTogetherPartnerCaption)}
  prefs()?.edit()?.putString(key("posts",user),cacheJson.encodeToString(rows))
   ?.putLong(key("feed_checked",user),now())?.apply()
 }
 @Synchronized fun loadPosts():List<FeedPost>{
  val user=uid()?:return emptyList()
  memoryPosts[user]?.let{return it}
  val raw=prefs()?.getString(key("posts",user),null)?:return emptyList()
  return runCatching{cacheJson.decodeFromString<List<CachedPost>>(raw).map{
   FeedPost(it.id,it.userId,it.userName,it.imagePaths,it.imageUrls,it.caption,it.createdAt,
    it.likedBy.toSet(),it.nowTogetherPartnerName,it.nowTogetherInitiatorCaption,it.nowTogetherPartnerCaption)
  }.also{memoryPosts[user]=it}}.getOrDefault(emptyList())
 }
 fun feedFresh(maxAgeMs:Long=120_000L):Boolean{
  val user=uid()?:return false
  val last=prefs()?.getLong(key("feed_checked",user),0L)?:0L
  return last>0&&now()-last in 0L until maxAgeMs
 }
 @Synchronized fun saveFeedReactions(value:Map<String,Map<String,Set<String>>>){
  val user=uid()?:return
  memoryReactions[user]=value
  val mapped=value.mapValues{(_,r)->r.mapValues{(_,uids)->uids.toList()}}
  prefs()?.edit()?.putString(key("feed_reactions",user),
   cacheJson.encodeToString(StoredReactions(mapped)))?.apply()
 }
 @Synchronized fun loadFeedReactions():Map<String,Map<String,Set<String>>>{
  val user=uid()?:return emptyMap()
  memoryReactions[user]?.let{return it}
  val raw=prefs()?.getString(key("feed_reactions",user),null)?:return emptyMap()
  return runCatching{cacheJson.decodeFromString<StoredReactions>(raw).values
   .mapValues{(_,r)->r.mapValues{(_,uids)->uids.toSet()}}
   .also{memoryReactions[user]=it}}.getOrDefault(emptyMap())
 }
 @Synchronized fun saveThread(postId:String,comments:List<ThreadComment>,
  reactions:Map<String,Map<String,Set<String>>>){
  val user=uid()?:return
  val snapshot=CachedThread(now(),comments,reactions)
  memoryThreads[threadKey(user,postId)]=snapshot
  if(loadPosts().take(5).none{it.id==postId})return
  val value=StoredThread(snapshot.savedAtMs,comments,
    reactions.mapValues{(_,r)->r.mapValues{(_,uids)->uids.toList()}})
  prefs()?.edit()?.putString(threadKey(user,postId),cacheJson.encodeToString(value))?.apply()
 }
 @Synchronized fun loadThread(postId:String):CachedThread?{
  val user=uid()?:return null
  val key=threadKey(user,postId)
  memoryThreads[key]?.let{return it}
  val raw=prefs()?.getString(key,null)?:return null
  return runCatching{cacheJson.decodeFromString<StoredThread>(raw).let{
   CachedThread(it.savedAtMs,it.comments,
    it.reactions.mapValues{(_,r)->r.mapValues{(_,uids)->uids.toSet()}})
  }.also{memoryThreads[key]=it}}.getOrNull()
 }
 fun threadFresh(postId:String,maxAgeMs:Long=120_000L):Boolean{
  val saved=loadThread(postId)?.savedAtMs?:return false
  return now()-saved in 0L until maxAgeMs
 }
 fun saveMessages(messages:List<UiChatMessage>){
  val user=uid()?:return
  val rows=messages.filter{it.localState=="sent"}.takeLast(20).map{
   CachedMessage(it.id,it.senderId,it.senderName,it.body,it.mediaType,it.mediaUrl,it.createdAt,
    it.readAt,it.reactions.mapValues{e->e.value.toList()},it.clientId,it.replyToId,
    it.replyPreview,it.editedAt,it.deliveredAt)}
  prefs()?.edit()?.putString(key("messages",user),cacheJson.encodeToString(rows))?.apply()
 }
 fun loadMessages():List<UiChatMessage>{
  val user=uid()?:return emptyList()
  val raw=prefs()?.getString(key("messages",user),null)?:return emptyList()
  return runCatching{cacheJson.decodeFromString<List<CachedMessage>>(raw).map{
   UiChatMessage(it.id,it.senderId,it.senderName,it.body,it.mediaType,it.mediaUrl,it.createdAt,
    it.readAt,it.reactions.mapValues{e->e.value.toSet()},it.clientId,it.replyToId,
    it.replyPreview,it.editedAt,it.deliveredAt,emptyList(),"sent")
  }}.getOrDefault(emptyList())
 }
}
