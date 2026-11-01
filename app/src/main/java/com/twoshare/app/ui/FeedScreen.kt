package com.twoshare.app.ui

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import com.twoshare.app.R
import com.twoshare.app.data.AuthManager
import com.twoshare.app.data.TwoShareRepository
import com.twoshare.app.model.FeedPost
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.OffsetDateTime

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeedScreen(repository:TwoShareRepository,authManager:AuthManager,selectedTheme:String,onThemeSelected:(String)->Unit,onOpenMessenger:()->Unit,onSignedOut:()->Unit){
 val context=LocalContext.current;val scope=rememberCoroutineScope();var posts by remember{mutableStateOf(emptyList<FeedPost>())};var unreadCount by remember{mutableIntStateOf(0)};var loading by remember{mutableStateOf(true)};var uploading by remember{mutableStateOf(false)};var uploadProgress by remember{mutableStateOf<String?>(null)};var pendingUploadUris by remember{mutableStateOf(emptyList<Uri>())};var composerError by remember{mutableStateOf<String?>(null)};var error by remember{mutableStateOf<String?>(null)};var commentsPost by remember{mutableStateOf<FeedPost?>(null)};var viewerPost by remember{mutableStateOf<FeedPost?>(null)};var viewerIndex by remember{mutableIntStateOf(0)};var editPost by remember{mutableStateOf<FeedPost?>(null)};var showAccountSettings by remember{mutableStateOf(false)};var menuOpen by remember{mutableStateOf(false)}
 fun friendlyError(t:Throwable,fallback:String):String{val raw=t.localizedMessage.orEmpty();return when{raw.isBlank()->fallback;"url=https://" in raw.lowercase()||raw.length>220->fallback;else->raw.lineSequence().firstOrNull()?.take(180)?:fallback}}
 suspend fun refresh(){runCatching{repository.loadFeed()}.onSuccess{posts=it;error=null}.onFailure{if(!repository.isTransientNetworkError(it))error=friendlyError(it,"Unable to refresh photos.")};runCatching{repository.unreadMessageCount()}.onSuccess{unreadCount=it};loading=false}
 LaunchedEffect(Unit){refresh();while(isActive){delay(3000);refresh()}}
 val picker=rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()){uris->if(uris.isNotEmpty()){uris.forEach{uri->runCatching{context.contentResolver.takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION)}};composerError=null;pendingUploadUris=uris}}
 val camera=rememberLauncherForActivityResult(ActivityResultContracts.TakePicturePreview()){bitmap->if(bitmap!=null){val uri=savePostCameraBitmap(context,bitmap);if(uri!=null){composerError=null;pendingUploadUris=listOf(uri)}else composerError="Unable to save camera photo."}}
 val cameraPermission=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()){granted->if(granted)camera.launch(null)else composerError="Camera permission is needed to take a photo."}
 fun openCamera(){if(ContextCompat.checkSelfPermission(context,Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED)camera.launch(null)else cameraPermission.launch(Manifest.permission.CAMERA)}
 Scaffold(containerColor=MaterialTheme.colorScheme.surfaceContainerLowest,
  topBar={TopAppBar(colors=TopAppBarDefaults.topAppBarColors(containerColor=MaterialTheme.colorScheme.surface),title={Row(Modifier.clickable{showAccountSettings=true},verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)){Surface(shape=CircleShape,color=MaterialTheme.colorScheme.primaryContainer){Image(painterResource(R.drawable.twoshare_icon),"TwoShare account",Modifier.padding(4.dp).size(30.dp))};Column{Text("TwoShare",style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold);Text("Our moments",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.primary)}}},actions={BadgedBox(badge={if(unreadCount>0)Badge{Text(if(unreadCount>99)"99+" else unreadCount.toString())}}){FilledTonalIconButton(onClick=onOpenMessenger){Icon(Icons.Default.ChatBubbleOutline,"Chat")}};Box{IconButton(onClick={menuOpen=true}){Icon(Icons.Default.MoreVert,"More")};DropdownMenu(menuOpen,{menuOpen=false}){DropdownMenuItem(text={Text("Account settings")},onClick={menuOpen=false;showAccountSettings=true});DropdownMenuItem(text={Text("Sign out")},onClick={menuOpen=false;scope.launch{runCatching{repository.signOut()};onSignedOut()}})}};Spacer(Modifier.width(4.dp))})},
  floatingActionButton={Column(horizontalAlignment=Alignment.End,verticalArrangement=Arrangement.spacedBy(10.dp)){SmallFloatingActionButton(onClick={if(!uploading&&pendingUploadUris.isEmpty())openCamera()}){Icon(Icons.Default.CameraAlt,"Take photo")};ExtendedFloatingActionButton(onClick={if(!uploading&&pendingUploadUris.isEmpty())picker.launch(arrayOf("image/*"))},icon={if(uploading)CircularProgressIndicator(Modifier.size(18.dp),strokeWidth=2.dp) else Icon(Icons.Default.AddPhotoAlternate,"Add photos")},text={Text(if(uploading)uploadProgress?:"Uploading" else "Share photos")})}}
 ){padding->Column(Modifier.fillMaxSize().padding(padding)){error?.let{Text(it,Modifier.padding(horizontal=16.dp,vertical=8.dp),color=MaterialTheme.colorScheme.error)};when{loading->Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center){CircularProgressIndicator()};posts.isEmpty()->Box(Modifier.fillMaxSize().padding(32.dp),contentAlignment=Alignment.Center){Text("Your shared moments will appear here.\nTap Share photos or the camera to add the first one.",style=MaterialTheme.typography.bodyLarge)};else->LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(top=10.dp,bottom=130.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){items(posts,key={it.id}){post->PostCard(post,repository.currentUserId(),{i->viewerPost=post;viewerIndex=i},{scope.launch{runCatching{repository.toggleLike(post)}.onSuccess{refresh()}.onFailure{error=friendlyError(it,"Like failed.")}}},{commentsPost=post},{editPost=post})}}}}}
 if(pendingUploadUris.isNotEmpty())NewPostDialog(pendingUploadUris.size,uploading,composerError,{if(!uploading){pendingUploadUris=emptyList();composerError=null}},{caption->val selected=pendingUploadUris;scope.launch{uploading=true;composerError=null;uploadProgress=if(selected.size==1)"Uploading photo" else "Uploading ${selected.size} photos";runCatching{repository.uploadPhotos(context,selected,caption)}.onSuccess{pendingUploadUris=emptyList();refresh()}.onFailure{composerError=friendlyError(it,"Upload failed.")};uploadProgress=null;uploading=false}})
 if(showAccountSettings)AccountSettingsDialog(authManager,selectedTheme,onThemeSelected){showAccountSettings=false}
 commentsPost?.let{CommentsDialog(it,repository){commentsPost=null}}
 viewerPost?.let{PhotoViewerDialog(it,viewerIndex){viewerPost=null}}
 editPost?.let{PostEditorDialog(it,repository,{editPost=null},{scope.launch{refresh()}},{scope.launch{refresh()}})}
}

@Composable private fun NewPostDialog(photoCount:Int,uploading:Boolean,error:String?,onDismiss:()->Unit,onPost:(String)->Unit){var caption by remember(photoCount){mutableStateOf("")};AlertDialog(onDismissRequest=onDismiss,title={Text("Share a moment")},text={Column(verticalArrangement=Arrangement.spacedBy(10.dp)){Text(if(photoCount==1)"1 photo selected" else "$photoCount photos selected");OutlinedTextField(caption,{if(it.length<=2000)caption=it},enabled=!uploading,label={Text("Caption (optional)")},placeholder={Text("Write something about this moment…")},minLines=3,maxLines=6,supportingText={Text("${caption.length} / 2000")},modifier=Modifier.fillMaxWidth());error?.let{Text(it,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)}}},confirmButton={Button({onPost(caption)},enabled=!uploading){Text(if(uploading)"Posting…" else "Share")}},dismissButton={TextButton(onDismiss,enabled=!uploading){Text("Cancel")}})}
@Composable private fun PostCard(post:FeedPost,currentUid:String?,onImageClick:(Int)->Unit,onLike:()->Unit,onComments:()->Unit,onEdit:()->Unit){val liked=currentUid!=null&&post.likedBy.contains(currentUid);val own=currentUid!=null&&currentUid==post.userId;val time=remember(post.createdAt){runCatching{DateUtils.getRelativeTimeSpanString(OffsetDateTime.parse(post.createdAt).toInstant().toEpochMilli(),System.currentTimeMillis(),DateUtils.MINUTE_IN_MILLIS).toString()}.getOrDefault("Recently")};Card(Modifier.fillMaxWidth().padding(horizontal=12.dp),shape=RoundedCornerShape(22.dp),colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surface),elevation=CardDefaults.cardElevation(defaultElevation=1.dp)){Column{Row(Modifier.fillMaxWidth().padding(14.dp),verticalAlignment=Alignment.CenterVertically){Surface(Modifier.size(38.dp),shape=CircleShape,color=MaterialTheme.colorScheme.primaryContainer){Box(contentAlignment=Alignment.Center){Text(post.userName.take(1).uppercase(),fontWeight=FontWeight.Bold,color=MaterialTheme.colorScheme.primary)}};Spacer(Modifier.width(10.dp));Column(Modifier.weight(1f)){Text(post.userName,fontWeight=FontWeight.SemiBold);Text(time,style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)};if(post.imageUrls.size>1)Surface(shape=RoundedCornerShape(12.dp),color=MaterialTheme.colorScheme.secondaryContainer){Text("${post.imageUrls.size} photos",Modifier.padding(horizontal=9.dp,vertical=4.dp),style=MaterialTheme.typography.labelSmall)}};GalleryPreview(post.imageUrls,onImageClick);post.caption?.takeIf{it.isNotBlank()}?.let{Text(it,Modifier.padding(horizontal=14.dp,vertical=11.dp),style=MaterialTheme.typography.bodyMedium)};HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant.copy(alpha=.45f));Row(Modifier.fillMaxWidth().padding(horizontal=7.dp,vertical=3.dp)){TextButton(onClick=onLike){Text(if(liked)"♥" else "♡",color=if(liked)MaterialTheme.colorScheme.primary else LocalContentColor.current);Spacer(Modifier.width(6.dp));Text(if(post.likedBy.isEmpty())"Like" else "${post.likedBy.size}")};TextButton(onClick=onComments){Text("Comment")};Spacer(Modifier.weight(1f));if(own)TextButton(onClick=onEdit){Text("Edit")}}}}}
@Composable private fun GalleryPreview(urls:List<String>,onImageClick:(Int)->Unit){if(urls.isEmpty())return;if(urls.size==1){AsyncImage(urls.first(),"Shared photo",Modifier.fillMaxWidth().heightIn(min=240.dp,max=520.dp).clickable{onImageClick(0)},contentScale=ContentScale.Crop);return};val visible=urls.take(4);Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(2.dp)){visible.chunked(2).forEachIndexed{r,row->Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(2.dp)){row.forEachIndexed{c,url->val i=r*2+c;val extra=if(i==3)urls.size-4 else 0;Box(Modifier.weight(1f).aspectRatio(1f).clickable{onImageClick(i)}){AsyncImage(url,"Gallery photo ${i+1}",Modifier.fillMaxSize(),contentScale=ContentScale.Crop);if(extra>0)Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha=.55f)),contentAlignment=Alignment.Center){Text("+$extra",color=Color.White,style=MaterialTheme.typography.headlineMedium)}}};if(row.size==1)Spacer(Modifier.weight(1f).aspectRatio(1f))}}}}
@OptIn(ExperimentalFoundationApi::class)
@Composable private fun PhotoViewerDialog(post:FeedPost,initialIndex:Int,onDismiss:()->Unit){if(post.imageUrls.isEmpty())return;val pager=rememberPagerState(initialPage=initialIndex.coerceIn(0,post.imageUrls.lastIndex),pageCount={post.imageUrls.size});Dialog(onDismissRequest=onDismiss,properties=DialogProperties(usePlatformDefaultWidth=false)){Box(Modifier.fillMaxSize().background(Color.Black)){HorizontalPager(pager,Modifier.fillMaxSize()){page->AsyncImage(post.imageUrls[page],"Photo ${page+1} of ${post.imageUrls.size}",Modifier.fillMaxSize(),contentScale=ContentScale.Fit)};Button(onDismiss,Modifier.align(Alignment.TopStart).padding(16.dp)){Text("← Back")};Surface(Modifier.align(Alignment.TopCenter).padding(top=24.dp),color=Color.Black.copy(alpha=.65f),shape=RoundedCornerShape(16.dp)){Text("${pager.currentPage+1} / ${post.imageUrls.size}",color=Color.White,modifier=Modifier.padding(horizontal=12.dp,vertical=6.dp))};if(post.imageUrls.size>1)Surface(Modifier.align(Alignment.BottomCenter).padding(bottom=18.dp),color=Color.Black.copy(alpha=.55f),shape=RoundedCornerShape(16.dp)){Text("Swipe left or right",color=Color.White,style=MaterialTheme.typography.labelMedium,modifier=Modifier.padding(horizontal=14.dp,vertical=7.dp))}}}}

private fun savePostCameraBitmap(context:Context,bitmap:Bitmap):Uri?=runCatching{val values=ContentValues().apply{put(MediaStore.Images.Media.DISPLAY_NAME,"TwoShare_${System.currentTimeMillis()}.jpg");put(MediaStore.Images.Media.MIME_TYPE,"image/jpeg")};val uri=context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,values)?:return@runCatching null;context.contentResolver.openOutputStream(uri)?.use{bitmap.compress(Bitmap.CompressFormat.JPEG,92,it)}?:return@runCatching null;uri}.getOrNull()
