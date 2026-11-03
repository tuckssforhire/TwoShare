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

class TwoShareFirebaseMessagingService:FirebaseMessagingService(){
 private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
 override fun onNewToken(token:String){super.onNewToken(token);PushNotificationManager.ensureNotificationChannels(applicationContext);scope.launch{runCatching{PushNotificationManager.registerToken(applicationContext,token)}}}
 override fun onMessageReceived(message:RemoteMessage){
  super.onMessageReceived(message);val type=message.data["type"].orEmpty();val isCall=type=="call"||type=="incoming_call";val isCalendar=type=="calendar_reminder";val isNowTogether=type=="now_together"
  if(!isCall&&!isCalendar&&!isNowTogether&&isAppInForeground())return
  val title=message.notification?.title?:message.data["title"]?:when{isCall->"Incoming TwoLink";isCalendar->"Calendar reminder";isNowTogether->"Now Together ❤️";else->"TwoShare"}
  val body=message.notification?.body?:message.data["body"]?:when{isCall->"Your partner is calling.";isCalendar->"You have an upcoming shared schedule.";isNowTogether->"Your partner wants a Now Together with you. Tap to capture your moment.";else->"You have a new notification."}
  showNotification(title,body,isCall,isCalendar,isNowTogether,message.data["call_id"],message.data["event_id"],message.data["session_id"])
 }
 private fun isAppInForeground():Boolean{val manager=getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager;return manager.runningAppProcesses?.any{it.processName==packageName&&it.importance==ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND}==true}
 private fun showNotification(title:String,body:String,isCall:Boolean,isCalendar:Boolean,isNowTogether:Boolean,callId:String?,eventId:String?,sessionId:String?){
  PushNotificationManager.ensureNotificationChannels(this);val manager=getSystemService(NotificationManager::class.java)
  val intent=Intent(this,MainActivity::class.java).apply{flags=Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP;putExtra("open_messenger",!isCall&&!isCalendar&&!isNowTogether);putExtra("open_calendar",isCalendar);eventId?.let{putExtra("calendar_event_id",it)};if(isNowTogether){putExtra("open_now_together",true);sessionId?.let{putExtra("now_together_session_id",it)}};if(isCall){putExtra("incoming_call",true);callId?.let{putExtra("incoming_call_id",it)}}}
  val requestCode=when{isCall->200;isCalendar->300;isNowTogether->400;else->100};val pending=PendingIntent.getActivity(this,requestCode,intent,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
  val channel=when{isCall->PushNotificationManager.CHANNEL_CALLS;isCalendar->PushNotificationManager.CHANNEL_CALENDAR;else->PushNotificationManager.CHANNEL_MESSAGES}
  val builder=NotificationCompat.Builder(this,channel).setSmallIcon(R.drawable.twoshare_icon).setContentTitle(title).setContentText(body).setStyle(NotificationCompat.BigTextStyle().bigText(body)).setContentIntent(pending).setAutoCancel(!isCall).setOngoing(isCall).setPriority(NotificationCompat.PRIORITY_MAX).setCategory(if(isCall)NotificationCompat.CATEGORY_CALL else if(isCalendar)NotificationCompat.CATEGORY_REMINDER else NotificationCompat.CATEGORY_MESSAGE).setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
  if(isCall)builder.setFullScreenIntent(pending,true).setTimeoutAfter(120_000);val notification=builder.build().apply{if(isCall)flags=flags or Notification.FLAG_INSISTENT};manager.notify(if(isCall)22001 else (System.currentTimeMillis() and 0x7fffffff).toInt(),notification)
 }
}
