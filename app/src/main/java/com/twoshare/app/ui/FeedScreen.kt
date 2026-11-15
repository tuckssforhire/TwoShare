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
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import com.twoshare.app.R
import com.twoshare.app.data.*
import com.twoshare.app.model.FeedPost
import com.twoshare.app.model.NowTogetherSessionRow
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.OffsetDateTime

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun FeedScreen(repository:TwoShareRepository,authManager:AuthManager,selectedTheme:String,onThemeSelected:(String)->Unit,onOpenMessenger:()->Unit,onOpenCalendar:()->Unit,onSignedOut:()->Unit){
 val context=LocalContext.current;val scope=rememberCoroutineScope();val presence=remember{PresenceRepository()};val nowRepo=remember(repository){NowTogetherRepository(repository)};val reactionRepo=remember(repository){FeedReactionRepository(repository)};val feedNotificationRepo=remember(repository){FeedNotificationRepository(repository)};val feedListState=rememberLazyListState();var posts by remember{mutableStateOf(OfflineCache.loadPosts())};var reactions by remember{mutableStateOf(OfflineCache.loadFeedReactions())};var unreadCount by remember{mutableIntStateOf(0)};var loading by remember{mutableStateOf(posts.isEmpty())};var uploading by remember{mutableStateOf(false)};var uploadProgress by remember{mutableStateOf<String?>(null)};var pendingUploadUris by remember{mutableStateOf(emptyList<Uri>())};var composerError by remember{mutableStateOf<String?>(null)};var error by remember{mutableStateOf<String?>(null)};var commentsPost by remember{mutableStateOf<FeedPost?>(null)};var viewerPost by remember{mutableStateOf<FeedPost?>(null)};var viewerIndex by remember{mutableIntStateOf(0)};var editPost by remember{mutableStateOf<FeedPost?>(null)};var showAccountSettings by remember{mutableStateOf(false)};var menuOpen by remember{mutableStateOf(false)};var addMenuOpen by remember{mutableStateOf(false)};var pendingCameraUri by remember{mutableStateOf<Uri?>(null)};var cameraMode by remember{mutableStateOf("post")};var nowCaption by remember{mutableStateOf("")};var nowCaptionOpen by remember{mutableStateOf(false)};var nowCapturedUri by remember{mutableStateOf<Uri?>(null)};var nowCaptureMode by remember{mutableStateOf("now_start")};var nowSubmitError by remember{mutableStateOf<String?>(null)};var nowPending by remember{mutableStateOf<NowTogetherSessionRow?>(null)};var nowBusy by remember{mutableStateOf(false)};var nowMessage by remember{mutableStateOf<String?>(null)};var avatarRefresh by remember{mutableLongStateOf(0L)};var feedNotifications by remember{mutableStateOf(emptyList<FeedNotification>())};var showFeedNotifications by remember{mutableStateOf(false)}
 fun friendlyError(t:Throwable,fallback:String):String{val raw=t.localizedMessage.orEmpty();return when{raw.isBlank()->fallback;"url=https://" in raw.lowercase()||raw.length>220->fallback;else->raw.lineSequence().firstOrNull()?.take(180)?:fallback}}
 suspend fun refresh(force:Boolean=false){runCatchingActive{repository.loadFeed()}.onSuccess{fresh->if(force||posts!=fresh)posts=fresh;OfflineCache.savePosts(fresh);LocalImageCache.warmRecent(context,fresh);ConnectionStatus.online();error=null}.onFailure{if(repository.isTransientNetworkError(it)){ConnectionStatus.connecting();if(posts.isEmpty())posts=OfflineCache.loadPosts()}else if(posts.isEmpty())error=friendlyError(it,"Unable to refresh photos.") else ConnectionStatus.connecting()};reactions=reactionRepo.load();OfflineCache.saveFeedReactions(reactions);runCatchingActive{repository.unreadMessageCount()}.onSuccess{unreadCount=it};loading=false}
 LaunchedEffect(Unit){if(posts.isEmpty()||!OfflineCache.feedFresh())refresh(true)else{loading=false;LocalImageCache.warmRecent(context,posts)};while(isActive){delay(45_000);runCatchingActive{repository.unreadMessageCount()}.onSuccess{unreadCount=it};val latestReactions=reactionRepo.load();if(latestReactions!=reactions){reactions=latestReactions;OfflineCache.saveFeedReactions(reactions)};if(reactionRepo.hasNewPost(posts.firstOrNull()?.id))refresh(true)else if(!OfflineCache.feedFresh(180_000L))refresh()}}
 LaunchedEffect(Unit){while(isActive){runCatchingActive{presence.heartbeat()};delay(30000)}}
 LaunchedEffect(Unit){while(isActive){delay(60000);avatarRefresh++}}
 LaunchedEffect(Unit){while(isActive){runCatchingActive{nowRepo.pendingForMe()}.onSuccess{if(it!=null&&nowPending?.id!=it.id)nowPending=it};delay(20000)}}
 val picker=rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()){uris->if(uris.isNotEmpty()){uris.forEach{uri->runCatchingActive{context.contentResolver.takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION)}};composerError=null;pendingUploadUris=uris}}
 val camera=rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()){saved->
  val uri=pendingCameraUri
  val mode=cameraMode
  pendingCameraUri=null
  if(saved&&uri!=null){
   if(mode=="now_start"||mode=="now_reply"){
    nowCapturedUri?.takeIf{it!=uri}?.let{old->runCatchingActive{context.contentResolver.delete(old,null,null)}}
    nowCapturedUri=uri
    nowCaptureMode=mode
    nowCaptionOpen=true
    nowSubmitError=null
   }else{
    composerError=null
    pendingUploadUris=listOf(uri)
   }
  }else{
   uri?.let{runCatchingActive{context.contentResolver.delete(it,null,null)}}
   if(mode.startsWith("now_")&&nowCapturedUri!=null)nowCaptionOpen=true
  }
  cameraMode="post"
 }
 fun launchFullCamera(mode:String){
  cameraMode=mode
  runCatchingActive{
   val values=ContentValues().apply{
    put(MediaStore.Images.Media.DISPLAY_NAME,"TwoShare_"+System.currentTimeMillis()+".jpg")
    put(MediaStore.Images.Media.MIME_TYPE,"image/jpeg")
    put(MediaStore.Images.Media.RELATIVE_PATH,"Pictures/TwoShare")
   }
   context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,values)
  }.onSuccess{uri->
   if(uri!=null){pendingCameraUri=uri;camera.launch(uri)}
   else if(mode.startsWith("now_")){nowSubmitError="Unable to prepare camera photo.";nowCaptionOpen=nowCapturedUri!=null}
   else composerError="Unable to prepare camera photo."
  }.onFailure{
   if(mode.startsWith("now_")){nowSubmitError=friendlyError(it,"Unable to open camera.");nowCaptionOpen=nowCapturedUri!=null}
   else composerError=friendlyError(it,"Unable to open camera.")
  }
 }
 val cameraPermission=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()){granted->
  if(granted)launchFullCamera(cameraMode)
  else if(cameraMode.startsWith("now_")){nowSubmitError="Camera permission is needed to take a photo.";nowCaptionOpen=nowCapturedUri!=null}
  else composerError="Camera permission is needed to take a photo."
 }
 fun openCamera(mode:String="post"){
  cameraMode=mode
  if(ContextCompat.checkSelfPermission(context,Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED)launchFullCamera(mode)
  else cameraPermission.launch(Manifest.permission.CAMERA)
 }
 fun react(post:FeedPost,emoji:String){scope.launch{runCatchingActive{reactionRepo.react(post.id,emoji)}.onSuccess{reactions=reactionRepo.load();OfflineCache.saveFeedReactions(reactions)}.onFailure{error=friendlyError(it,"Reaction failed.")}}}
 Scaffold(modifier=Modifier.imePadding(),containerColor=MaterialTheme.colorScheme.surfaceContainerLowest,
  topBar={TopAppBar(colors=TopAppBarDefaults.topAppBarColors(containerColor=MaterialTheme.colorScheme.surface),title={Row(Modifier.clickable{showAccountSettings=true},verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)){Surface(shape=CircleShape,color=MaterialTheme.colorScheme.primaryContainer){Image(painterResource(R.drawable.twoshare_icon),"TwoShare account",Modifier.padding(4.dp).size(30.dp))};Column{Text("TwoShare",style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold);Text("Our moments",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.primary)}}},actions={BadgedBox(badge={val n=feedNotifications.count{!it.isRead};if(n>0)Badge{Text(if(n>99)"99+" else n.toString())}}){FilledTonalIconButton(onClick={showFeedNotifications=true}){Icon(Icons.Default.Notifications,"Feed notifications")}};Spacer(Modifier.width(4.dp));Box{IconButton(onClick={menuOpen=true}){Icon(Icons.Default.MoreVert,"More")};DropdownMenu(menuOpen,{menuOpen=false}){DropdownMenuItem(text={Text("Account settings")},onClick={menuOpen=false;showAccountSettings=true});DropdownMenuItem(text={Text("Sign out")},onClick={menuOpen=false;scope.launch{runCatchingActive{repository.signOut()};onSignedOut()}})}};Spacer(Modifier.width(4.dp))})},
  floatingActionButton={Box{FloatingActionButton(onClick={if(!uploading&&!nowBusy&&pendingUploadUris.isEmpty())addMenuOpen=true}){if(uploading||nowBusy)CircularProgressIndicator(Modifier.size(22.dp),strokeWidth=2.dp)else Icon(Icons.Default.Add,"Add")};DropdownMenu(addMenuOpen,{addMenuOpen=false}){DropdownMenuItem(text={Text("Upload image")},leadingIcon={Icon(Icons.Default.AddPhotoAlternate,null)},onClick={addMenuOpen=false;picker.launch(arrayOf("image/*"))});DropdownMenuItem(text={Text("Camera")},leadingIcon={Icon(Icons.Default.CameraAlt,null)},onClick={addMenuOpen=false;openCamera()});DropdownMenuItem(text={Text("Now Together")},leadingIcon={Text("❤️")},onClick={addMenuOpen=false;nowCaption="";nowSubmitError=null;nowCapturedUri=null;nowCaptureMode="now_start";openCamera("now_start")})}}}
 ){padding->Column(Modifier.fillMaxSize().padding(padding)){Card(Modifier.fillMaxWidth().padding(start=12.dp,end=12.dp,top=10.dp,bottom=2.dp),shape=RoundedCornerShape(24.dp),colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.primaryContainer.copy(alpha=.6f))){Row(Modifier.padding(horizontal=16.dp,vertical=14.dp),verticalAlignment=Alignment.CenterVertically){Surface(Modifier.size(42.dp),shape=CircleShape,color=MaterialTheme.colorScheme.surface.copy(alpha=.85f)){Box(contentAlignment=Alignment.Center){Text("❤️",style=MaterialTheme.typography.titleMedium)}};Spacer(Modifier.width(12.dp));Column(Modifier.weight(1f)){Text("Just us",style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.Bold,color=MaterialTheme.colorScheme.onPrimaryContainer);Text("Our private place for messages, memories and the moments we keep together.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha=.8f))}}};error?.let{Text(it,Modifier.padding(horizontal=16.dp,vertical=8.dp),color=MaterialTheme.colorScheme.error)};when{loading->Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center){CircularProgressIndicator()};posts.isEmpty()->Box(Modifier.fillMaxSize().padding(32.dp),contentAlignment=Alignment.Center){Column(horizontalAlignment=Alignment.CenterHorizontally){Surface(Modifier.size(88.dp),shape=CircleShape,color=MaterialTheme.colorScheme.primaryContainer){Box(contentAlignment=Alignment.Center){Icon(Icons.Default.AddPhotoAlternate,null,Modifier.size(40.dp),tint=MaterialTheme.colorScheme.onPrimaryContainer)}};Spacer(Modifier.height(18.dp));Text("No moments yet",style=MaterialTheme.typography.titleLarge);Spacer(Modifier.height(6.dp));Text("Tap + to add your first moment.",style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant,textAlign=androidx.compose.ui.text.style.TextAlign.Center)}};else->LazyColumn(state=feedListState,modifier=Modifier.fillMaxSize(),contentPadding=PaddingValues(top=10.dp,bottom=130.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){items(posts,key={it.id}){post->LaunchedEffect(post.id){LocalImageCache.warmAhead(context,posts,post.id,3)};PostCard(post,repository,repository.currentUserId(),avatarRefresh,reactions[post.id].orEmpty(),reactionRepo.choices,{i->viewerPost=post;viewerIndex=i},{emoji->react(post,emoji)},{commentsPost=post},{editPost=post})}}}}}
 if(pendingUploadUris.isNotEmpty())NewPostDialog(pendingUploadUris.size,uploading,composerError,{if(!uploading){pendingUploadUris=emptyList();composerError=null}},{caption->val selected=pendingUploadUris;scope.launch{uploading=true;composerError=null;uploadProgress=if(selected.size==1)"Uploading photo" else "Uploading ${selected.size} photos";runCatchingActive{repository.uploadPhotos(context,selected,caption)}.onSuccess{pendingUploadUris=emptyList();refresh(true)}.onFailure{composerError=friendlyError(it,"Upload failed.")};uploadProgress=null;uploading=false}})
 nowCapturedUri?.let{photoUri->if(nowCaptionOpen){
  val discardNow={nowCaptionOpen=false;nowCapturedUri=null;nowCaption="";nowSubmitError=null;runCatchingActive{context.contentResolver.delete(photoUri,null,null)};Unit}
  // Full-screen caption sheet instead of an AlertDialog. The photo fills whatever room is
  // left above the caption and keyboard (weight), so typing never resizes the layout and
  // the window never has to pan. The old AlertDialog switched sizes on keyboard state and
  // panned on each keystroke when it did not fit (e.g. the longer reply title wrapped),
  // which made the preview shake for the invited partner.
  Dialog(onDismissRequest={if(!nowBusy)discardNow()},properties=DialogProperties(usePlatformDefaultWidth=false,decorFitsSystemWindows=false)){
   Surface(Modifier.fillMaxSize(),color=MaterialTheme.colorScheme.surface){
    Column(Modifier.fillMaxSize().systemBarsPadding().imePadding().padding(horizontal=20.dp,vertical=16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
     Column{
      Text(if(nowCaptureMode=="now_reply")"Complete Now Together ❤️" else "Start Now Together ❤️",
       style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.SemiBold,maxLines=1,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis)
      Text(if(nowCaptureMode=="now_reply")"Your photo completes your shared moment." else "Your partner will be asked to capture theirs.",
       style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=1,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis)
     }
     Box(Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(22.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh),contentAlignment=Alignment.Center){
      AsyncImage(model=photoUri,contentDescription="Your captured Now Together photo",modifier=Modifier.fillMaxSize(),contentScale=ContentScale.Fit)
     }
     OutlinedTextField(nowCaption,{if(it.length<=500)nowCaption=it},
      label={Text("Caption (optional)")},placeholder={Text("Write about this moment…")},
      supportingText={Text(nowCaption.length.toString()+" / 500")},minLines=2,maxLines=3,
      shape=RoundedCornerShape(18.dp),modifier=Modifier.fillMaxWidth(),enabled=!nowBusy)
     nowSubmitError?.let{Text(it,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)}
     Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
      TextButton(enabled=!nowBusy,onClick={nowCaptionOpen=false;openCamera(nowCaptureMode)}){Text("Retake")}
      TextButton(enabled=!nowBusy,onClick={discardNow()}){Text("Cancel")}
      Spacer(Modifier.weight(1f))
      Button(enabled=!nowBusy,onClick={
       if(!nowBusy)scope.launch{
        nowBusy=true
        nowSubmitError=null
        val mode=nowCaptureMode
        runCatchingActive{
         if(mode=="now_reply"){
          val session=nowPending?:error("The Now Together request is no longer available.")
          nowRepo.complete(context,session,photoUri,nowCaption)
         }else nowRepo.create(context,photoUri,nowCaption)
        }.onSuccess{
         nowCaptionOpen=false
         nowCapturedUri=null
         nowCaption=""
         if(mode=="now_reply"){nowPending=null;nowMessage="Now Together · Just now";refresh(true)}
         else nowMessage="Waiting for your partner to capture their moment…"
        }.onFailure{nowSubmitError=friendlyError(it,"Unable to share Now Together.")}
        nowBusy=false
       }
      }){if(nowBusy)CircularProgressIndicator(Modifier.size(18.dp),strokeWidth=2.dp)else Text("Share photo")}
     }
    }
   }
  }
 }}
 nowMessage?.let{msg->AlertDialog(onDismissRequest={nowMessage=null},title={Text("Now Together")},text={Text(msg)},confirmButton={TextButton({nowMessage=null}){Text("OK")}})}
 if(!nowCaptionOpen)nowPending?.let{session->AlertDialog(
  onDismissRequest={},
  title={Text("Now Together ❤️")},
  text={Text("Your partner wants a Now Together with you. Take your photo first, then preview it and add your caption.")},
  confirmButton={Button(enabled=!nowBusy,onClick={
   scope.launch{
    nowBusy=true
    runCatchingActive{nowRepo.markOpened(session)}.onSuccess{
     nowCaption="";nowSubmitError=null;nowCapturedUri=null;nowCaptureMode="now_reply";openCamera("now_reply")
    }.onFailure{nowMessage=friendlyError(it,"Now Together expired.");nowPending=null}
    nowBusy=false
   }
  }){Text(if(nowBusy)"Opening…" else "Take photo")}},
  dismissButton={TextButton(onClick={nowPending=null}){Text("Not now")}}
 )}
 if(showFeedNotifications)FeedNotificationsDialog(feedNotifications,{showFeedNotifications=false},{notification->scope.launch{runCatching{feedNotificationRepo.markRead(notification.id)};feedNotifications=runCatching{feedNotificationRepo.load()}.getOrDefault(feedNotifications);showFeedNotifications=false;val index=posts.indexOfFirst{it.id==notification.postId};if(index>=0){if(notification.opensComments)FeedCommentExpansion.expand(notification.postId);feedListState.animateScrollToItem(index)}}},{scope.launch{runCatching{feedNotificationRepo.markAllRead()};feedNotifications=runCatching{feedNotificationRepo.load()}.getOrDefault(feedNotifications)}});if(showAccountSettings)AccountSettingsDialog(authManager,selectedTheme,onThemeSelected){showAccountSettings=false}
 commentsPost?.let{CommentsDialog(it,repository){commentsPost=null}}
 viewerPost?.let{PhotoViewerDialog(it,viewerIndex){viewerPost=null}}
 editPost?.let{PostEditorDialog(it,repository,{editPost=null},{scope.launch{refresh(true)}},{scope.launch{refresh(true)}})}
}

@Composable private fun NewPostDialog(photoCount:Int,uploading:Boolean,error:String?,onDismiss:()->Unit,onPost:(String)->Unit){var caption by remember(photoCount){mutableStateOf("")};AlertDialog(modifier=Modifier.imePadding(),onDismissRequest=onDismiss,title={Text("Share a moment")},text={Column(verticalArrangement=Arrangement.spacedBy(10.dp)){Text(if(photoCount==1)"1 photo selected" else "$photoCount photos selected");OutlinedTextField(caption,{if(it.length<=2000)caption=it},enabled=!uploading,label={Text("Caption (optional)")},placeholder={Text("Write something about this moment…")},minLines=3,maxLines=6,supportingText={Text("${caption.length} / 2000")},modifier=Modifier.fillMaxWidth());error?.let{Text(it,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)}}},confirmButton={Button({onPost(caption)},enabled=!uploading){Text(if(uploading)"Posting…" else "Share")}},dismissButton={TextButton(onDismiss,enabled=!uploading){Text("Cancel")}})}
@OptIn(ExperimentalFoundationApi::class)
@Composable private fun PostCard(post:FeedPost,repository:TwoShareRepository,currentUid:String?,avatarRefresh:Long,reactions:Map<String,Set<String>>,choices:List<String>,onImageClick:(Int)->Unit,onReact:(String)->Unit,onComments:()->Unit,onEdit:()->Unit){val own=currentUid!=null&&currentUid==post.userId;var reactionMenu by remember(post.id){mutableStateOf(false)};val mine=reactions.entries.firstOrNull{currentUid in it.value}?.key;val total=reactions.values.sumOf{it.size};val time=remember(post.createdAt){runCatchingActive{DateUtils.getRelativeTimeSpanString(OffsetDateTime.parse(post.createdAt).toInstant().toEpochMilli(),System.currentTimeMillis(),DateUtils.MINUTE_IN_MILLIS).toString()}.getOrDefault("Recently")};val avatar by produceState<String?>(ProfilePhotoStore.cachedUrl(post.userId),post.userId,avatarRefresh){value=runCatchingActive{ProfilePhotoStore.photoUrl(post.userId)}.getOrNull()};Card(Modifier.fillMaxWidth().padding(horizontal=12.dp),shape=RoundedCornerShape(22.dp),colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surface),elevation=CardDefaults.cardElevation(defaultElevation=1.dp)){Column{Row(Modifier.fillMaxWidth().padding(14.dp),verticalAlignment=Alignment.CenterVertically){Surface(Modifier.size(38.dp),shape=CircleShape,color=MaterialTheme.colorScheme.primaryContainer){if(avatar!=null)CachedRemoteImage(url=avatar,contentDescription="${post.userName} profile photo",modifier=Modifier.fillMaxSize(),contentScale=ContentScale.Crop)else Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center){Text(post.userName.take(1).uppercase().ifBlank{"?"},fontWeight=FontWeight.Bold,color=MaterialTheme.colorScheme.primary)}};Spacer(Modifier.width(10.dp));Column(Modifier.weight(1f)){Text(post.userName,fontWeight=FontWeight.SemiBold);Text(time,style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)};if(post.imageUrls.size>1)Surface(shape=RoundedCornerShape(12.dp),color=MaterialTheme.colorScheme.secondaryContainer){Text("${post.imageUrls.size} photos",Modifier.padding(horizontal=9.dp,vertical=4.dp),style=MaterialTheme.typography.labelSmall)}};GalleryPreview(post.imageUrls,onImageClick);FeedPostCaption(post,repository);if(reactions.isNotEmpty())Text(reactions.entries.joinToString("  "){"${it.key} ${it.value.size}"},Modifier.padding(horizontal=14.dp,vertical=5.dp),style=MaterialTheme.typography.labelMedium);FeedCommentPreview(post,repository,onComments);HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant.copy(alpha=.45f));Row(Modifier.fillMaxWidth().padding(horizontal=7.dp,vertical=3.dp)){FeedReactionButton(mine,total,choices,onReact);TextButton(onClick={FeedCommentExpansion.toggle(post.id)}){Text("Comment")};Spacer(Modifier.weight(1f));if(own)TextButton(onClick=onEdit){Text("Edit")}}}}}
@Composable private fun GalleryPreview(urls:List<String>,onImageClick:(Int)->Unit){if(urls.isEmpty())return;if(urls.size==1){CachedRemoteImage(urls.first(),"Shared photo",Modifier.fillMaxWidth().heightIn(min=240.dp,max=520.dp).clickable{onImageClick(0)},contentScale=ContentScale.Crop);return};val visible=urls.take(4);Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(2.dp)){visible.chunked(2).forEachIndexed{r,row->Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(2.dp)){row.forEachIndexed{c,url->val i=r*2+c;val extra=if(i==3)urls.size-4 else 0;Box(Modifier.weight(1f).aspectRatio(1f).clickable{onImageClick(i)}){CachedRemoteImage(url,"Gallery photo ${i+1}",Modifier.fillMaxSize(),contentScale=ContentScale.Crop);if(extra>0)Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha=.55f)),contentAlignment=Alignment.Center){Text("+$extra",color=Color.White,style=MaterialTheme.typography.headlineMedium)}}};if(row.size==1)Spacer(Modifier.weight(1f).aspectRatio(1f))}}}}
@OptIn(ExperimentalFoundationApi::class)
@Composable private fun PhotoViewerDialog(post:FeedPost,initialIndex:Int,onDismiss:()->Unit){if(post.imageUrls.isEmpty())return;val pager=rememberPagerState(initialPage=initialIndex.coerceIn(0,post.imageUrls.lastIndex),pageCount={post.imageUrls.size});Dialog(onDismissRequest=onDismiss,properties=DialogProperties(usePlatformDefaultWidth=false)){Box(Modifier.fillMaxSize().background(Color.Black)){HorizontalPager(pager,Modifier.fillMaxSize()){page->CachedRemoteImage(post.imageUrls[page],"Photo ${page+1} of ${post.imageUrls.size}",Modifier.fillMaxSize(),contentScale=ContentScale.Fit)};Box(Modifier.fillMaxWidth().align(Alignment.TopCenter).background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color.Black.copy(alpha=.6f),Color.Transparent))).statusBarsPadding().padding(horizontal=12.dp,vertical=10.dp)){Surface(onClick=onDismiss,modifier=Modifier.align(Alignment.CenterStart).size(44.dp),shape=CircleShape,color=Color.White.copy(alpha=.16f),contentColor=Color.White){Box(contentAlignment=Alignment.Center){Icon(Icons.Default.Close,"Close photo")}};Column(Modifier.align(Alignment.Center),horizontalAlignment=Alignment.CenterHorizontally){Text(post.userName,color=Color.White,style=MaterialTheme.typography.titleSmall);if(post.imageUrls.size>1)Text("${pager.currentPage+1} of ${post.imageUrls.size}",color=Color.White.copy(alpha=.75f),style=MaterialTheme.typography.labelMedium)}};if(post.imageUrls.size>1)Row(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom=20.dp),horizontalArrangement=Arrangement.spacedBy(6.dp)){repeat(post.imageUrls.size.coerceAtMost(10)){i->val active=i==pager.currentPage.coerceAtMost(9);Box(Modifier.size(if(active)8.dp else 6.dp).background(if(active)Color.White else Color.White.copy(alpha=.45f),CircleShape))}}}}}
private fun savePostCameraBitmap(context:Context,bitmap:Bitmap):Uri?=runCatchingActive{val values=ContentValues().apply{put(MediaStore.Images.Media.DISPLAY_NAME,"TwoShare_${System.currentTimeMillis()}.jpg");put(MediaStore.Images.Media.MIME_TYPE,"image/jpeg")};val uri=context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,values)?:return@runCatchingActive null;context.contentResolver.openOutputStream(uri)?.use{bitmap.compress(Bitmap.CompressFormat.JPEG,92,it)}?:return@runCatchingActive null;uri}.getOrNull()
