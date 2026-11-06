package com.twoshare.app.ui

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.twoshare.app.BuildConfig

data class VersionNote(val version:String,val items:List<String>)

val twoShareVersionHistory=listOf(
 VersionNote("1.0.35",listOf("Now Together captures the photo before asking for a caption","Caption screen previews the captured photo with Retake, Cancel and Share","Works for both starting and completing a Now Together moment")),
 VersionNote("1.0.34",listOf("Store five recent Feed posts locally for instant return navigation","Cache shared photos, chat images and profile photos on the device using stable keys","Keep comment threads and reactions locally with periodic background refresh","Reduce unnecessary Supabase egress while preserving new posts and comments")),
 VersionNote("1.0.33",listOf("Prevent cancelled screen tasks from being reported as errors when navigating","Preserve genuine network and login error messages","Retain v1.0.32 egress optimizations and existing app data")),
 VersionNote("1.0.32",listOf("Reduced Supabase egress: optimized photo uploads across Feed, Now Together, Messenger and profile pictures","Fewer unnecessary read/delivered updates and call-history downloads","Lower feed/comment polling and longer avatar URL caching")),
 VersionNote("1.0.26",listOf("New shared Calendar & Schedule for both TwoShare users","Month and Agenda views with upcoming schedules","Create, edit and delete shared events with notes and categories","All-day and repeating schedules with reminder settings","Messenger reactions stay synchronized without requiring a relogin")),
 VersionNote("1.0.25",listOf("Messenger reaction picker is scrollable","Expanded Unicode emoji reactions are accepted by Messenger","Reaction handling no longer depends on the original six-emoji restriction","What's New now follows the installed app version")),
 VersionNote("1.0.24",listOf("Expanded Messenger reaction picker","Messenger reaction and update stability improvements","Startup update/version handling improvements")),
 VersionNote("1.0.23",listOf("Partner profile badge for seen messages","More reaction picker in Messenger","Smoother message appearance and feed reaction updates")),
 VersionNote("1.0.18",listOf("Profile display photo in Account & settings","Tap a quoted reply to scroll back to the original message","Messenger keeps cached messages visible while reconnecting")),
 VersionNote("1.0.17",listOf("Offline-friendly feed: keeps the latest 3 posts ready","Messenger keeps the latest 20 messages ready","Connecting / Online status and automatic refresh after reconnection","Version progress history and What's new after updates")),
 VersionNote("1.0.16",listOf("Large emoji-only messages","Gallery and camera photos in Messenger","Camera photo posting in the feed")),
 VersionNote("1.0.15",listOf("Tap a message to show its sent time","Unread messages divider","Expanded emoji reactions with Android emoji picker","Instant optimistic reactions")),
 VersionNote("1.0.14",listOf("Five-minute biometric background grace period","Message notifications suppressed while TwoShare is active","Incoming TwoLink call notifications remain active","Theme and login refinements")),
 VersionNote("1.0.13",listOf("Together, Rose and Midnight themes in Settings","Random couple photo login background")),
 VersionNote("Earlier builds",listOf("Private two-person feed and Messenger","Photo sharing, comments and reactions","Voice/video TwoLink calls","Push notifications and trusted-device sign-in"))
)

private fun currentVersionNote():VersionNote=twoShareVersionHistory.firstOrNull{it.version==BuildConfig.VERSION_NAME}?:VersionNote(BuildConfig.VERSION_NAME,listOf("TwoShare has been updated with the latest fixes and improvements."))

fun shouldShowWhatsNew(context:Context,versionCode:Int):Boolean=context.getSharedPreferences("twoshare_progress",0).getInt("whats_new_seen",0)<versionCode
fun markWhatsNewSeen(context:Context,versionCode:Int){context.getSharedPreferences("twoshare_progress",0).edit().putInt("whats_new_seen",versionCode).apply()}

@Composable fun VersionHistoryDialog(onDismiss:()->Unit){AlertDialog(onDismissRequest=onDismiss,title={Text("TwoShare progress")},text={Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)){twoShareVersionHistory.forEach{note->Column{Text("Version ${note.version}",style=MaterialTheme.typography.titleMedium,color=MaterialTheme.colorScheme.primary);note.items.forEach{Text("• $it",style=MaterialTheme.typography.bodyMedium)}}}}},confirmButton={TextButton(onClick=onDismiss){Text("Done")}})}

@Composable fun WhatsNewDialog(onDismiss:()->Unit){val current=currentVersionNote();AlertDialog(onDismissRequest=onDismiss,title={Text("What's new in TwoShare ${current.version}")},text={Column(verticalArrangement=Arrangement.spacedBy(8.dp)){current.items.forEach{Text("• $it")};Spacer(Modifier.height(4.dp));Text("You can review the full app progress anytime in Account & settings.",style=MaterialTheme.typography.bodySmall)}},confirmButton={Button(onClick=onDismiss){Text("Got it")}})}
