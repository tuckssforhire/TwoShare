package com.twoshare.app.push

import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
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
        PushNotificationManager.ensureNotificationChannels(applicationContext)
        scope.launch { runCatching { PushNotificationManager.registerToken(applicationContext, token) } }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        val type = message.data["type"].orEmpty()
        val isCall = type == "call" || type == "incoming_call"
        if (!isCall && isAppInForeground()) return
        val title = message.notification?.title ?: message.data["title"] ?: if (isCall) "Incoming TwoLink" else "TwoShare"
        val body = message.notification?.body ?: message.data["body"] ?: if (isCall) "Your partner is calling." else "You have a new notification."
        showNotification(title, body, isCall, message.data["call_id"])
    }

    private fun isAppInForeground(): Boolean {
        val manager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return manager.runningAppProcesses?.any {
            it.processName == packageName && it.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
        } == true
    }

    private fun showNotification(title: String, body: String, isCall: Boolean, callId: String?) {
        PushNotificationManager.ensureNotificationChannels(this)
        val manager = getSystemService(NotificationManager::class.java)
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("open_messenger", !isCall)
            if (isCall) {
                putExtra("incoming_call", true)
                callId?.let { putExtra("incoming_call_id", it) }
            }
        }
        val requestCode = if (isCall) 200 else 100
        val pending = PendingIntent.getActivity(this, requestCode, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val channel = if (isCall) PushNotificationManager.CHANNEL_CALLS else PushNotificationManager.CHANNEL_MESSAGES
        val builder = NotificationCompat.Builder(this, channel)
            .setSmallIcon(R.drawable.twoshare_icon)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(pending)
            .setAutoCancel(!isCall)
            .setOngoing(isCall)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(if (isCall) NotificationCompat.CATEGORY_CALL else NotificationCompat.CATEGORY_MESSAGE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)

        if (isCall) builder.setFullScreenIntent(pending, true).setTimeoutAfter(120_000)
        val notification = builder.build().apply {
            if (isCall) flags = flags or Notification.FLAG_INSISTENT
        }
        manager.notify(if (isCall) INCOMING_CALL_NOTIFICATION_ID else (System.currentTimeMillis() and 0x7fffffff).toInt(), notification)
    }

    companion object {
        private const val INCOMING_CALL_NOTIFICATION_ID = 22001
    }
}
