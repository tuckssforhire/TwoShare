package com.twoshare.app.data

import android.content.Context
import android.net.Uri
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.storage.storage
import kotlin.time.Duration.Companion.hours

object ProfilePhotoStore {
    private val supabase get() = SupabaseProvider.client
    private fun path(uid:String) = "profiles/$uid/avatar.jpg"
    private data class CachedPhoto(val url:String?, val loadedAt:Long)
    private val cache = mutableMapOf<String,CachedPhoto>()
    private const val CACHE_MS = 6 * 60 * 60 * 1000L

    fun currentUserId():String? = supabase.auth.currentUserOrNull()?.id
    suspend fun currentPhotoUrl():String? { val uid=currentUserId() ?: return null; return photoUrl(uid) }

    suspend fun photoUrl(userId:String, forceRefresh:Boolean=false):String? {
        val now=System.currentTimeMillis()
        if(!forceRefresh) cache[userId]?.let { if(now-it.loadedAt<CACHE_MS) return it.url }
        val url=runCatching {
            val signed=supabase.storage.from("twoshare").createSignedUrl(path(userId),24.hours)
            val separator=if('?' in signed)'&' else '?'
            "$signed${separator}avatar=${now/300_000}"
        }.getOrNull()
        cache[userId]=CachedPhoto(url,now)
        return url
    }

    suspend fun uploadCurrent(context:Context,uri:Uri):String {
        val uid=currentUserId() ?: error("Your session has expired. Please sign in again.")
        val mime=context.contentResolver.getType(uri)?.lowercase().orEmpty();require(mime.startsWith("image/")){"Please choose an image."}
        val photo = PhotoOptimizer.prepare(context, uri, maxInputBytes = 8 * 1024 * 1024)
        require(photo.extension == "jpg" || photo.extension == "jpeg") { "Please choose a supported still photo." }
        supabase.storage.from("twoshare").upload(path(uid), photo.bytes) { upsert = true }
        cache.remove(uid)
        return photoUrl(uid,true) ?: error("Unable to load profile photo.")
    }
}