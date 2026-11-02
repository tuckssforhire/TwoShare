package com.twoshare.app
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Environment
import androidx.core.content.ContextCompat
fun downloadAndInstallUpdate(context:Context,url:String,version:String){val manager=context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager;val request=DownloadManager.Request(Uri.parse(url)).setTitle("TwoShare $version").setDescription("Downloading TwoShare update…").setMimeType("application/vnd.android.package-archive").setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED).setDestinationInExternalFilesDir(context,Environment.DIRECTORY_DOWNLOADS,"TwoShare-$version.apk");val id=manager.enqueue(request);val receiver=object:BroadcastReceiver(){override fun onReceive(c:Context,intent:Intent){if(intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID,-1L)!=id)return;runCatching{val apk=manager.getUriForDownloadedFile(id)?:return@runCatching;c.startActivity(Intent(Intent.ACTION_VIEW).apply{setDataAndType(apk,"application/vnd.android.package-archive");addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)})};runCatching{c.unregisterReceiver(this)}}};ContextCompat.registerReceiver(context,receiver,IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),ContextCompat.RECEIVER_EXPORTED)}
