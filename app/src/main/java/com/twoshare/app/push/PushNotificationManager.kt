package com.twoshare.app.push

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.google.firebase.messaging.FirebaseMessaging
import com.twoshare.app.R
import com.twoshare.app.data.SupabaseProvider
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.from
import kotlinx.coroutines.tasks.await
import kotlinx.serialization.Serializable

@Serializable
private data class DevicePushTokenRow(val user_id:String,val token:String,val device_id:String,val platform:String="android")

object PushNotificationManager {
 const val CHANNEL_MESSAGES="twoshare_messages"
 const val CHANNEL_CALLS="twolink_calls_jazz_v2"
 const val CHANNEL_CALENDAR="twoshare_calendar"
 fun ensureNotificationChannels(context:Context){
  if(Build.VERSION.SDK_INT<Build.VERSION_CODES.O)return
  val manager=context.getSystemService(NotificationManager::class.java)
  manager.createNotificationChannel(NotificationChannel(CHANNEL_MESSAGES,"Messages & activity",NotificationManager.IMPORTANCE_HIGH).apply{description="TwoShare messages, replies and reactions";enableVibration(true)})
  manager.createNotificationChannel(NotificationChannel(CHANNEL_CALENDAR,"Calendar reminders",NotificationManager.IMPORTANCE_HIGH).apply{description="Shared TwoShare calendar and schedule reminders";enableVibration(true)})
  val ringtoneUri=Uri.parse("android.resource://${context.packageName}/${R.raw.twolink_smooth_jazz}")
  manager.createNotificationChannel(NotificationChannel(CHANNEL_CALLS,"TwoLink calls",NotificationManager.IMPORTANCE_HIGH).apply{description="Incoming TwoLink voice and video calls";setSound(ringtoneUri,AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE).build());enableVibration(true)})
 }
 suspend fun registerCurrentDevice(context:Context){ensureNotificationChannels(context);val user=SupabaseProvider.client.auth.currentUserOrNull()?:return;val token=FirebaseMessaging.getInstance().token.await();SupabaseProvider.client.from("device_push_tokens").upsert(DevicePushTokenRow(user.id,token,deviceId(context))){onConflict="user_id,device_id"}}
 suspend fun registerToken(context:Context,token:String){ensureNotificationChannels(context);val user=SupabaseProvider.client.auth.currentUserOrNull()?:return;SupabaseProvider.client.from("device_push_tokens").upsert(DevicePushTokenRow(user.id,token,deviceId(context))){onConflict="user_id,device_id"}}
 suspend fun unregisterCurrentDevice(context:Context){val user=SupabaseProvider.client.auth.currentUserOrNull()?:return;SupabaseProvider.client.from("device_push_tokens").delete{filter{eq("user_id",user.id);eq("device_id",deviceId(context))}}}
 private fun deviceId(context:Context)=Settings.Secure.getString(context.contentResolver,Settings.Secure.ANDROID_ID).orEmpty().ifBlank{context.packageName}
}
