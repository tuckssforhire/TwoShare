package com.twoshare.app.ui

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil.compose.AsyncImage
import coil.Coil
import coil.ImageLoader
import coil.disk.DiskCache
import coil.imageLoader
import coil.memory.MemoryCache
import coil.request.CachePolicy
import coil.request.ImageRequest
import com.twoshare.app.data.SupabaseProvider
import com.twoshare.app.model.FeedPost
import io.github.jan.supabase.auth.auth

/** Stable disk keys avoid refetching private images when signed URL tokens rotate. */
object LocalImageCache {
 @Volatile private var installed=false
 /**
  * App-wide image loader. Supabase signed URLs come back with short cache headers, and
  * Coil's default loader honours them: after they expire it re-downloads every photo
  * (and shows nothing if that download fails offline). Shared photos never change at a
  * given storage path, so ignore the headers and always serve from disk once downloaded.
  */
 fun install(context:Context){
  if(installed)return
  val app=context.applicationContext
  Coil.setImageLoader(ImageLoader.Builder(app)
   .respectCacheHeaders(false)
   .memoryCache{MemoryCache.Builder(app).maxSizePercent(0.25).build()}
   // Same folder as Coil's default, so photos cached by earlier versions are reused.
   .diskCache{DiskCache.Builder().directory(app.cacheDir.resolve("image_cache")).maxSizeBytes(300L*1024*1024).build()}
   .crossfade(false)
   .build())
  installed=true
 }
 private fun diskKey(url:String):String{
  val uri=Uri.parse(url)
  val raw=uri.path?.substringAfter("/storage/v1/object/sign/","")?.takeIf{it.isNotBlank()}
   ?:url.substringBefore('?')
  val user=SupabaseProvider.client.auth.currentUserOrNull()?.id?:"signed-out"
  val revision=if(raw.contains("/profiles/")&&raw.endsWith("/avatar.jpg"))
   ":avatar:"+(uri.getQueryParameter("avatar_rev")?:"0") else ""
  return "twoshare:$user:$raw$revision"
 }
 fun request(context:Context,url:String,variant:String="feed"):ImageRequest{
  val key=diskKey(url)
  return ImageRequest.Builder(context).data(url)
   .diskCacheKey(key).memoryCacheKey("$key:$variant")
   .diskCachePolicy(CachePolicy.ENABLED).memoryCachePolicy(CachePolicy.ENABLED)
   .crossfade(false).build()
 }
 fun warmRecent(context:Context,posts:List<FeedPost>){
  posts.take(5).forEach{post->warmPost(context,post)}
 }
 /** Download the photos of the next [count] posts after [postId] so they are ready before you scroll to them. */
 fun warmAhead(context:Context,posts:List<FeedPost>,postId:String,count:Int=3){
  val index=posts.indexOfFirst{it.id==postId}
  if(index<0)return
  posts.drop(index+1).take(count).forEach{warmPost(context,it)}
 }
 private fun warmPost(context:Context,post:FeedPost){
  post.imageUrls.filter{it.isNotBlank()}.take(4).forEach{url->
   context.imageLoader.enqueue(request(context,url,"feed"))
  }
 }
}
@Composable
fun CachedRemoteImage(
 url:String?,contentDescription:String?,modifier:Modifier=Modifier,
 contentScale:ContentScale=ContentScale.Fit,variant:String="feed"
){
 val context=LocalContext.current
 val request=remember(context,url,variant){
  url?.takeIf{it.isNotBlank()}?.let{LocalImageCache.request(context,it,variant)}
 }
 AsyncImage(model=request,contentDescription=contentDescription,modifier=modifier,contentScale=contentScale)
}
