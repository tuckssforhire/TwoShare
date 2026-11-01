package com.twoshare.app.data

import com.twoshare.app.model.ProfileRow
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.from
import java.time.Duration
import java.time.Instant

/** Lightweight best-effort presence using the existing profiles.last_active_at field. */
class PresenceRepository {
    private val supabase get() = SupabaseProvider.client

    suspend fun heartbeat(): Boolean = runCatching {
        val uid = supabase.auth.currentUserOrNull()?.id ?: return false
        supabase.from("profiles").update({
            set("last_active_at", Instant.now().toString())
        }) { filter { eq("id", uid) } }
        true
    }.getOrDefault(false)

    suspend fun partner(): ProfileRow? = runCatching {
        val uid = supabase.auth.currentUserOrNull()?.id ?: return null
        supabase.from("profiles").select {
            filter { neq("id", uid) }
        }.decodeList<ProfileRow>().firstOrNull()
    }.getOrNull()

    fun label(lastActiveAt: String?): String {
        val last = lastActiveAt?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: return "Offline"
        val seconds = Duration.between(last, Instant.now()).seconds.coerceAtLeast(0)
        return when {
            seconds <= 45 -> "Online"
            seconds < 120 -> "Last active just now"
            seconds < 3600 -> "Last active ${seconds / 60} min ago"
            seconds < 86400 -> "Last active ${seconds / 3600} hr ago"
            else -> "Last active ${seconds / 86400} d ago"
        }
    }
}
