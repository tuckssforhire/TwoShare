package com.twoshare.app.data

import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.functions.functions
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

object PushSender {
    private val supabase = SupabaseProvider.client

    // TwoShare currently has exactly two authorized accounts. Resolve the peer
    // deterministically instead of depending on the order returned by profiles.
    private const val FIRST_USER_ID = "2fb3dad9-e2e0-45dd-a862-72fcfca7fc86"
    private const val SECOND_USER_ID = "8ec63d77-5a1c-4efc-8568-c7e3b9d053a4"

    suspend fun notifyOther(type: String, messageId: String? = null, callId: String? = null) {
        val current = supabase.auth.currentUserOrNull()?.id ?: return
        val recipient = when (current) {
            FIRST_USER_ID -> SECOND_USER_ID
            SECOND_USER_ID -> FIRST_USER_ID
            else -> return
        }

        // Do not silently swallow invocation failures. Message sending can still
        // succeed, but this makes push failures visible to the caller/logs.
        supabase.functions.invoke(
            function = "send-push",
            body = buildJsonObject {
                put("recipient_user_id", recipient)
                put("type", type)
                messageId?.let { put("message_id", it) }
                callId?.let { put("call_id", it) }
            }
        )
    }
}
