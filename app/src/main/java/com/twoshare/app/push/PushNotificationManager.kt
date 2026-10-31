package com.twoshare.app.push

import android.content.Context
import android.provider.Settings
import com.google.firebase.messaging.FirebaseMessaging
import com.twoshare.app.data.SupabaseProvider
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.from
import kotlinx.coroutines.tasks.await
import kotlinx.serialization.Serializable

@Serializable
private data class DevicePushTokenRow(val user_id: String, val token: String, val device_id: String, val platform: String = "android")

object PushNotificationManager {
    suspend fun registerCurrentDevice(context: Context) {
        val user = SupabaseProvider.client.auth.currentUserOrNull() ?: return
        val token = FirebaseMessaging.getInstance().token.await()
        SupabaseProvider.client.from("device_push_tokens").upsert(
            DevicePushTokenRow(user.id, token, deviceId(context)), onConflict = "user_id,device_id"
        )
    }

    suspend fun registerToken(context: Context, token: String) {
        val user = SupabaseProvider.client.auth.currentUserOrNull() ?: return
        SupabaseProvider.client.from("device_push_tokens").upsert(
            DevicePushTokenRow(user.id, token, deviceId(context)), onConflict = "user_id,device_id"
        )
    }

    suspend fun unregisterCurrentDevice(context: Context) {
        val user = SupabaseProvider.client.auth.currentUserOrNull() ?: return
        SupabaseProvider.client.from("device_push_tokens").delete {
            filter { eq("user_id", user.id); eq("device_id", deviceId(context)) }
        }
    }

    private fun deviceId(context: Context): String = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID).orEmpty().ifBlank { context.packageName }
}
