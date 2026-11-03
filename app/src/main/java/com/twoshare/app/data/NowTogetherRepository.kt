package com.twoshare.app.data

import android.content.Context
import android.net.Uri
import com.twoshare.app.model.NowTogetherSessionInsert
import com.twoshare.app.model.NowTogetherSessionRow
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Order
import io.github.jan.supabase.storage.storage
import java.time.Instant
import java.util.UUID

class NowTogetherRepository(private val repository: TwoShareRepository) {
    private val supabase = SupabaseProvider.client
    private val bucket = supabase.storage.from("twoshare")

    private fun uid(): String = supabase.auth.currentUserOrNull()?.id
        ?: error("Your session has expired. Please sign in again.")

    suspend fun partnerId(): String {
        val me = uid()
        return supabase.from("profiles").select().decodeList<com.twoshare.app.model.ProfileRow>()
            .firstOrNull { it.id != me }?.id ?: error("Partner account was not found.")
    }

    suspend fun create(context: Context, photoUri: Uri, caption: String?): NowTogetherSessionRow {
        repository.requireAllowedUser()
        val me = uid()
        val partner = partnerId()
        val bytes = context.contentResolver.openInputStream(photoUri)?.use { it.readBytes() }
            ?: error("Unable to read the Now Together photo.")
        require(bytes.size <= 15 * 1024 * 1024) { "Photo must be 15 MB or smaller." }
        val path = "$me/now-together/${UUID.randomUUID()}.jpg"
        bucket.upload(path, bytes) { upsert = false }
        return try {
            supabase.from("now_together_sessions").insert(
                NowTogetherSessionInsert(me, partner, path, caption?.trim()?.takeIf { it.isNotEmpty() }, Instant.now().toString())
            ) { select() }.decodeSingle<NowTogetherSessionRow>()
        } catch (t: Throwable) {
            runCatching { bucket.delete(path) }
            throw t
        }
    }

    suspend fun pendingForMe(): NowTogetherSessionRow? {
        val me = uid()
        val now = Instant.now()
        return supabase.from("now_together_sessions").select {
            filter { eq("partner_id", me); or { eq("status", "waiting_partner"); eq("status", "partner_opened") } }
            order("created_at", Order.DESCENDING)
            limit(10)
        }.decodeList<NowTogetherSessionRow>().firstOrNull { session ->
            val deadline = if (session.status == "partner_opened") {
                session.graceExpiresAt?.let { runCatching { Instant.parse(it) }.getOrNull() }
                    ?: runCatching { Instant.parse(session.expiresAt) }.getOrNull()
            } else runCatching { Instant.parse(session.expiresAt) }.getOrNull()
            deadline != null && now.isBefore(deadline)
        }
    }

    suspend fun markOpened(session: NowTogetherSessionRow) {
        val me = uid(); require(session.partnerId == me)
        val now = Instant.now()
        if (now.isAfter(Instant.parse(session.expiresAt))) error("Now Together expired.")
        supabase.from("now_together_sessions").update({
            set("status", "partner_opened")
            set("partner_opened_at", now.toString())
            set("grace_expires_at", now.plusSeconds(120).toString())
        }) { filter { eq("id", session.id); eq("partner_id", me) } }
    }

    suspend fun complete(context: Context, session: NowTogetherSessionRow, photoUri: Uri, caption: String?) {
        repository.requireAllowedUser()
        val me = uid(); require(session.partnerId == me)
        val now = Instant.now()
        val deadline = session.graceExpiresAt?.let(Instant::parse) ?: Instant.parse(session.expiresAt)
        if (now.isAfter(deadline)) error("Now Together expired.")
        val bytes = context.contentResolver.openInputStream(photoUri)?.use { it.readBytes() }
            ?: error("Unable to read your Now Together photo.")
        require(bytes.size <= 15 * 1024 * 1024) { "Photo must be 15 MB or smaller." }
        val partnerPath = "$me/now-together/${UUID.randomUUID()}.jpg"
        bucket.upload(partnerPath, bytes) { upsert = false }
        try {
            val post = supabase.from("posts").insert(
                com.twoshare.app.model.PostInsert(
                    userId = session.initiatorId,
                    imagePath = session.initiatorPhotoPath ?: error("The first photo is missing."),
                    imagePaths = listOf(session.initiatorPhotoPath, partnerPath),
                    caption = listOfNotNull(session.initiatorCaption, caption?.trim()?.takeIf { it.isNotEmpty() }).joinToString(" · ").takeIf { it.isNotBlank() }
                )
            ) { select() }.decodeSingle<com.twoshare.app.model.PostRow>()
            supabase.from("now_together_sessions").update({
                set("partner_photo_path", partnerPath)
                set("partner_caption", caption?.trim()?.takeIf { it.isNotEmpty() })
                set("partner_captured_at", now.toString())
                set("status", "completed")
                set("completed_at", now.toString())
                set("final_post_id", post.id)
            }) { filter { eq("id", session.id); eq("partner_id", me) } }
        } catch (t: Throwable) {
            runCatching { bucket.delete(partnerPath) }
            throw t
        }
    }
}
