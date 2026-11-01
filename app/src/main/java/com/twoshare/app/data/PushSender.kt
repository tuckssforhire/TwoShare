package com.twoshare.app.data

import com.twoshare.app.model.ProfileRow
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.functions.functions
import io.github.jan.supabase.postgrest.from
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

object PushSender {
    private val supabase = SupabaseProvider.client

    suspend fun notifyOther(type: String, messageId: String? = null, callId: String? = null) {
        runCatching {
            val current = supabase.auth.currentUserOrNull()?.id ?: return
            val recipients = supabase.from("profiles").select().decodeList<ProfileRow>()
                .map { it.id }
                .filter { it != current }
            recipients.forEach { recipientId ->
                supabase.functions.invoke(
                    function = "send-push",
                    body = buildJsonObject {
                        put("recipient_user_id", recipientId)
                        put("type", type)
                        messageId?.let { put("message_id", it) }
                        callId?.let { put("call_id", it) }
                    }
                )
            }
        }
    }
}
