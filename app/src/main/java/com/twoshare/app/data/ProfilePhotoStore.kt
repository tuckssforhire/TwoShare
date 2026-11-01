package com.twoshare.app.data

import android.content.Context
import android.net.Uri
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.storage.storage
import kotlin.time.Duration.Companion.hours

object ProfilePhotoStore {
    private val supabase get() = SupabaseProvider.client
    private fun path(uid:String) = "profiles/$uid/avatar.jpg"
    fun currentUserId():String? = supabase.auth.currentUserOrNull()?.id
    suspend fun currentPhotoUrl():String? { val uid=currentUserId() ?: return null; return photoUrl(uid) }
    suspend fun photoUrl(userId:String):String? = runCatching { supabase.storage.from("twoshare").createSignedUrl(path(userId),24.hours) }.getOrNull()
    suspend fun uploadCurrent(context:Context,uri:Uri):String {
        val uid=currentUserId() ?: error("Your session has expired. Please sign in again.")
        val mime=context.contentResolver.getType(uri)?.lowercase().orEmpty();require(mime.startsWith("image/")){"Please choose an image."}
        val bytes=context.contentResolver.openInputStream(uri)?.use{it.readBytes()} ?: error("Unable to read selected photo.");require(bytes.size<=8*1024*1024){"Profile photo must be 8 MB or smaller."}
        supabase.storage.from("twoshare").upload(path(uid),bytes){upsert=true}
        return photoUrl(uid)?.plus("&v=${System.currentTimeMillis()}") ?: error("Unable to load profile photo.")
    }
}