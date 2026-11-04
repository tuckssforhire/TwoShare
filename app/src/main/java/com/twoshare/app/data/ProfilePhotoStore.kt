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
    private var appContext: Context? = null
    fun init(context: Context) { appContext = context.applicationContext }
    private fun prefs() = appContext?.getSharedPreferences("twoshare_avatar_urls", 0)
    private fun stored(userId: String): CachedPhoto? {
        val p = prefs() ?: return null
        val at = p.getLong("at:$userId", 0L)
        val url = p.getString("url:$userId", null) ?: return null
        return if (at > 0L && System.currentTimeMillis() - at in 0L until CACHE_MS)
            CachedPhoto(url, at) else null
    }
    fun cachedUrl(userId: String?): String? {
        val id = userId ?: return null
        val now = System.currentTimeMillis()
        cache[id]?.takeIf { now - it.loadedAt in 0L until CACHE_MS }?.let { return it.url }
        return stored(id)?.also { cache[id] = it }?.url
    }

    fun currentUserId():String? = supabase.auth.currentUserOrNull()?.id
    suspend fun currentPhotoUrl():String? { val uid=currentUserId() ?: return null; return photoUrl(uid) }

    suspend fun photoUrl(userId:String, forceRefresh:Boolean=false):String? {
        if(!forceRefresh) cachedUrl(userId)?.let { return it }
        val now = System.currentTimeMillis()
        val url = runCatching {
            val signed = supabase.storage.from("twoshare").createSignedUrl(path(userId),24.hours)
            val revision = prefs()?.getLong("revision:$userId",0L) ?: 0L
            val separator = if('?' in signed) '&' else '?'
            signed + separator + "avatar_rev=" + (now / CACHE_MS) + "-" + revision
        }.getOrNull()
        if(url != null) {
            cache[userId] = CachedPhoto(url,now)
            prefs()?.edit()?.putString("url:$userId",url)
                ?.putLong("at:$userId",now)?.apply()
        }
        return url ?: cachedUrl(userId)
    }

    suspend fun uploadCurrent(context:Context,uri:Uri):String {
        val uid=currentUserId() ?: error("Your session has expired. Please sign in again.")
        val mime=context.contentResolver.getType(uri)?.lowercase().orEmpty();require(mime.startsWith("image/")){"Please choose an image."}
        val photo = PhotoOptimizer.prepare(context, uri, maxInputBytes = 8 * 1024 * 1024)
        require(photo.extension == "jpg" || photo.extension == "jpeg") { "Please choose a supported still photo." }
        supabase.storage.from("twoshare").upload(path(uid), photo.bytes) { upsert = true }
        cache.remove(uid)
        prefs()?.edit()?.remove("url:$uid")?.remove("at:$uid")
            ?.putLong("revision:$uid", System.currentTimeMillis())?.apply()
        return photoUrl(uid,true) ?: error("Unable to load profile photo.")
    }
}