package com.twoshare.app.push

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.media.AudioAttributes
import android.os.Build
import androidx.core.app.NotificationCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.twoshare.app.MainActivity
import com.twoshare.app.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class TwoShareFirebaseMessagingService : FirebaseMessagingService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        scope.launch { runCatching { PushNotificationManager.registerToken(applicationContext, token) } }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        val type = message.data["type"].orEmpty()
        val isCall = type == "call" || type == "incoming_call"
        val title = message.notification?.title ?: message.data["title"] ?: if (isCall) "Incoming TwoLink" else "TwoShare"
        val body = message.notification?.body ?: message.data["body"] ?: if (isCall) "Your partner is calling." else "You have a new notification."
        showNotification(title, body, isCall)
    }

    private fun showNotification(title: String, body: String, isCall: Boolean) {
        val manager = getSystemService(NotificationManager::class.java)
        ensureChannels(manager)
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("open_messenger", !isCall)
        }
        val pending = PendingIntent.getActivity(this, 100, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val channel = if (isCall) CHANNEL_CALLS else CHANNEL_MESSAGES
        val notification = NotificationCompat.Builder(this, channel)
            .setSmallIcon(R.drawable.twoshare_icon)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setPriority(if (isCall) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(if (isCall) NotificationCompat.CATEGORY_CALL else NotificationCompat.CATEGORY_MESSAGE)
            .build()
        manager.notify((System.currentTimeMillis() and 0x7fffffff).toInt(), notification)
    }

    private fun ensureChannels(manager: NotificationManager) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        manager.createNotificationChannel(NotificationChannel(CHANNEL_MESSAGES, "Messages & activity", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "TwoShare messages, replies and reactions"
        })
        manager.createNotificationChannel(NotificationChannel(CHANNEL_CALLS, "TwoLink calls", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Incoming TwoLink voice and video calls"
            setSound(android.provider.Settings.System.DEFAULT_RINGTONE_URI, AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE).build())
            enableVibration(true)
        })
    }

    companion object {
        const val CHANNEL_MESSAGES = "twoshare_messages"
        const val CHANNEL_CALLS = "twoshare_calls"
    }
}
