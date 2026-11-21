package com.twoshare.app.ui

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
 /**
  * Saves the original shared photo to Pictures/TwoShare. Uses the copy already cached on the
  * phone when there is one, so saving a photo you've viewed costs no extra download.
  */
 suspend fun saveToGallery(context:Context,url:String):Unit=withContext(Dispatchers.IO){
  val loader=context.imageLoader
  val key=diskKey(url)
  fun cached():ByteArray?=loader.diskCache?.openSnapshot(key)?.use{snapshot->snapshot.data.toFile().readBytes()}
  val bytes=cached()?:run{loader.execute(request(context,url,"save"));cached()}?:error("Couldn't download the photo. Check your connection and try again.")
  val (mime,ext)=when{
   bytes.size>3&&bytes[0]==0x89.toByte()&&bytes[1]=='P'.code.toByte()->"image/png" to "png"
   bytes.size>11&&String(bytes,8,4,Charsets.US_ASCII)=="WEBP"->"image/webp" to "webp"
   else->"image/jpeg" to "jpg"
  }
  val resolver=context.contentResolver
  val values=ContentValues().apply{
   put(MediaStore.Images.Media.DISPLAY_NAME,"TwoShare_${System.currentTimeMillis()}.$ext")
   put(MediaStore.Images.Media.MIME_TYPE,mime)
   if(Build.VERSION.SDK_INT>=29){put(MediaStore.Images.Media.RELATIVE_PATH,"Pictures/TwoShare");put(MediaStore.Images.Media.IS_PENDING,1)}
  }
  val uri=resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,values)?:error("Couldn't save the photo.")
  try{
   resolver.openOutputStream(uri)?.use{it.write(bytes)}?:error("Couldn't save the photo.")
   if(Build.VERSION.SDK_INT>=29)resolver.update(uri,ContentValues().apply{put(MediaStore.Images.Media.IS_PENDING,0)},null,null)
  }catch(t:Throwable){resolver.delete(uri,null,null);throw t}
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
