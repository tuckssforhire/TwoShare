package com.twoshare.app.ui

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil.compose.AsyncImage
import coil.imageLoader
import coil.request.CachePolicy
import coil.request.ImageRequest
import com.twoshare.app.data.SupabaseProvider
import com.twoshare.app.model.FeedPost
import io.github.jan.supabase.auth.auth

/** Stable disk keys avoid refetching private images when signed URL tokens rotate. */
object LocalImageCache {
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
  posts.take(5).forEach{post->post.imageUrls.firstOrNull{it.isNotBlank()}?.let{url->
   context.imageLoader.enqueue(request(context,url,"feed"))
  }}
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
