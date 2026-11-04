package com.twoshare.app.ui

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.*
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import com.twoshare.app.data.*
import com.twoshare.app.model.*
import java.time.*
import java.util.UUID
import kotlin.math.roundToInt
import kotlinx.coroutines.*

private val reactionChoices=listOf("❤️","🥰","😘","😂","😮","😢","👍","🔥")
private val emojiPickerChoices=listOf("😀","😃","😄","😁","😆","😅","😂","🤣","😊","😇","🙂","🙃","😉","😍","🥰","😘","😋","😎","🤩","🥳","😏","😢","😭","😤","😡","🤯","😱","😮","🤔","🫡","👍","👎","👏","🙌","🙏","💪","👌","✌️","🤞","🫶","❤️","🩷","🧡","💛","💚","💙","💜","🤍","💔","💕","💞","💓","💗","💖","💘","💝","🔥","✨","⭐","🎉","🎊","💯","🌹","😘","🥹","🤗","😴","🤭","😜","🤪","😌")
private const val MESSAGE_PAGE=60
private const val MAX_MESSAGE_WINDOW=600

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun MessengerScreenV2(repository:TwoShareRepository,onBack:()->Unit,onStartCall:(CallRow,Boolean)->Unit){
 val context=LocalContext.current
 val scope=rememberCoroutineScope()
 val chat=remember(repository){ChatFeaturesRepository(repository)}
 val presence=remember{PresenceRepository()}
 val listState=rememberLazyListState()
 val uid=repository.currentUserId()
 val chatPrefs=remember{context.getSharedPreferences("twoshare_chat",Context.MODE_PRIVATE)}
 var chatTheme by remember{mutableIntStateOf(chatPrefs.getInt("theme",0))}
 var themePicker by remember{mutableStateOf(false)}
 var messages by remember{mutableStateOf(OfflineCache.loadMessages().takeLast(MESSAGE_PAGE))}
 var calls by remember{mutableStateOf(emptyList<CallRow>())}
 var lastCallHistoryFetchAtMs by remember{mutableLongStateOf(0L)}
 var draft by remember{mutableStateOf("")}
 var attachment by remember{mutableStateOf<Uri?>(null)}
 var replying by remember{mutableStateOf<UiChatMessage?>(null)}
 var editing by remember{mutableStateOf<UiChatMessage?>(null)}
 var error by remember{mutableStateOf<String?>(null)}
 var connected by remember{mutableStateOf(false)}
 var partner by remember{mutableStateOf<ProfileRow?>(null)}
 var presenceTick by remember{mutableLongStateOf(0L)}
 var unreadDividerId by remember{mutableStateOf<String?>(null)}
 var initialUnreadCaptured by remember{mutableStateOf(false)}
 var messageWindow by remember{mutableIntStateOf(MESSAGE_PAGE)}
 var avatarRefresh by remember{mutableLongStateOf(0L)}
 var initialScrollDone by remember{mutableStateOf(false)}
 var headerMenu by remember{mutableStateOf(false)}
 var heartBurst by remember{mutableIntStateOf(0)}
 var thinkingLocked by remember{mutableStateOf(false)}
 val myPhoto by produceState<String?>(null,uid,avatarRefresh){value=uid?.let{ProfilePhotoStore.photoUrl(it)}}
 val partnerPhoto by produceState<String?>(null,partner?.id,avatarRefresh){value=partner?.id?.let{ProfilePhotoStore.photoUrl(it)}}
 val timeline=remember(messages,calls){(messages.map{epoch(it.createdAt) to (it as Any)}+calls.map{epoch(it.createdAt) to (it as Any)}).sortedBy{it.first}}
 suspend fun refresh(){
  val result=runCatching{chat.loadMessages(messageWindow)}
  val nowMs=System.currentTimeMillis();val history=if(nowMs-lastCallHistoryFetchAtMs>=60_000L){runCatching{repository.loadCallHistory()}.getOrDefault(calls).also{lastCallHistoryFetchAtMs=nowMs}}else calls
  result.onSuccess{server->
   if(!initialUnreadCaptured){unreadDividerId=server.firstOrNull{it.senderId!=uid&&it.readAt==null}?.id;initialUnreadCaptured=true}
   val pending=messages.filter{it.localState!="sent"};val ids=server.mapNotNull{it.clientId}.toSet()
   messages=server+pending.filter{it.clientId !in ids};OfflineCache.saveMessages(server);calls=history;connected=true;error=null;if(server.any{it.senderId!=uid&&it.readAt==null})runCatching{chat.markIncomingRead()}
  }.onFailure{calls=history;connected=false;if(!repository.isTransientNetworkError(it))error=it.localizedMessage?:"Unable to load messages."}
 }
 fun jumpTo(id:String?){if(id==null)return;val index=timeline.indexOfFirst{(it.second as? UiChatMessage)?.id==id};if(index>=0)scope.launch{runCatching{listState.animateScrollToItem(index)}}}
 LaunchedEffect(messageWindow){refresh()}
 LaunchedEffect(Unit){while(isActive){delay(15000);refresh()}}
 LaunchedEffect(Unit){while(isActive){runCatching{presence.heartbeat()};partner=presence.partner()?:partner;presenceTick=System.currentTimeMillis();delay(30000)}}
 LaunchedEffect(Unit){while(isActive){delay(300000);avatarRefresh++}}
 LaunchedEffect(timeline.size){if(!initialScrollDone&&timeline.isNotEmpty()){delay(100);val count=listState.layoutInfo.totalItemsCount;if(count>0){runCatching{listState.scrollToItem(timeline.lastIndex.coerceAtMost(count-1))};initialScrollDone=true}}}
 val nearTop by remember{derivedStateOf{initialScrollDone&&listState.firstVisibleItemIndex<=2}}
 LaunchedEffect(nearTop){if(nearTop&&messageWindow<MAX_MESSAGE_WINDOW)messageWindow=(messageWindow+MESSAGE_PAGE).coerceAtMost(MAX_MESSAGE_WINDOW)}
 LaunchedEffect(replying?.id,editing?.id){if((replying!=null||editing!=null)&&timeline.isNotEmpty()){delay(100);runCatching{listState.animateScrollToItem(timeline.lastIndex)}}}
 val mediaPicker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){uri->attachment=uri;if(uri!=null)runCatching{context.contentResolver.takePersistableUriPermission(uri,android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)}}
 val camera=rememberLauncherForActivityResult(ActivityResultContracts.TakePicturePreview()){b->if(b!=null)attachment=saveCameraBitmap(context,b)}
 val cameraPermission=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()){if(it)camera.launch(null)else error="Camera permission is needed to take a photo."}
 fun openCamera(){if(ContextCompat.checkSelfPermission(context,Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED)camera.launch(null)else cameraPermission.launch(Manifest.permission.CAMERA)}
 fun react(m:UiChatMessage,emoji:String){if(m.localState!="sent"||emoji.isBlank())return;val current=uid?:return;val before=m.reactions;val users=before[emoji].orEmpty();val updated=before.toMutableMap();if(current in users){val left=users-current;if(left.isEmpty())updated.remove(emoji)else updated[emoji]=left}else updated[emoji]=users+current;messages=messages.map{if(it.id==m.id)it.copy(reactions=updated.toMap())else it};scope.launch{runCatching{repository.toggleMessageReaction(m,emoji)}.onFailure{messages=messages.map{cur->if(cur.id==m.id)cur.copy(reactions=before)else cur};error="Reaction failed."}}}
 fun send(){
  val edit=editing
  if(edit!=null){val text=draft;if(text.isBlank())return;draft="";editing=null;scope.launch{runCatching{chat.editMessage(edit,text)}.onSuccess{refresh()}.onFailure{error="Edit failed: ${it.localizedMessage}"}};return}
  if(draft.isBlank()&&attachment==null)return
  val current=uid?:return;val text=draft;val selected=attachment;val reply=replying;val clientId=UUID.randomUUID().toString()
  val local=UiChatMessage("local-$clientId",current,"You",text.trim().ifBlank{null},if(selected!=null)"image" else null,selected?.toString(),Instant.now().toString(),null,emptyMap(),clientId,reply?.id,reply?.body?.take(120),null,null,emptyList(),"sending")
  messages=messages+local;draft="";attachment=null;replying=null
  scope.launch{runCatching{chat.sendMessage(context,text,selected,clientId,reply?.id)}.onSuccess{sent->messages=messages.map{if(it.clientId==clientId)sent.copy(replyPreview=local.replyPreview)else it};refresh()}.onFailure{t->messages=messages.map{if(it.clientId==clientId)it.copy(localState="failed")else it};connected=false;error=t.localizedMessage?:"Message failed."}}
 }
 val partnerStatus=remember(partner?.lastActiveAt,presenceTick){if(presence.label(partner?.lastActiveAt)=="Online")"Online" else "Offline"}
 Scaffold(
  modifier=Modifier.imePadding(),
  containerColor=MaterialTheme.colorScheme.surfaceContainerLowest,
  topBar={TopAppBar(colors=TopAppBarDefaults.topAppBarColors(containerColor=MaterialTheme.colorScheme.surface),title={Column{Text(partner?.displayName?:"TwoShare",style=MaterialTheme.typography.titleLarge);Text(partnerStatus,style=MaterialTheme.typography.labelSmall,color=if(partnerStatus=="Online")MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)}},navigationIcon={IconButton(onClick=onBack){Icon(Icons.Default.ArrowBack,"Back")}},actions={FilledTonalIconButton(onClick={scope.launch{runCatching{repository.createOutgoingCall(false)}.onSuccess{onStartCall(it,false)}}}){Icon(Icons.Default.Call,"Voice call")};Spacer(Modifier.width(5.dp));FilledTonalIconButton(onClick={scope.launch{runCatching{repository.createOutgoingCall(true)}.onSuccess{onStartCall(it,false)}}}){Icon(Icons.Default.VideoCall,"Video call")};Spacer(Modifier.width(3.dp));Box{IconButton(onClick={headerMenu=true}){Icon(Icons.Default.MoreVert,"More")};DropdownMenu(expanded=headerMenu,onDismissRequest={headerMenu=false}){DropdownMenuItem(text={Column{Text("Thinking of you");Text(if(thinkingLocked)"Just sent · wait a moment" else "Send a little heart",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}},leadingIcon={Icon(Icons.Default.Favorite,null)},enabled=!thinkingLocked,onClick={headerMenu=false;if(!thinkingLocked){thinkingLocked=true;heartBurst++;draft="❤️ Thinking of you";send();scope.launch{delay(1000);thinkingLocked=false}}});DropdownMenuItem(text={Column{Text("Chat theme");Text("Personalize your conversation",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}},leadingIcon={Icon(Icons.Default.Palette,null)},onClick={headerMenu=false;themePicker=true})}};Spacer(Modifier.width(4.dp))})},
  bottomBar={Surface(shadowElevation=6.dp){Column(Modifier.fillMaxWidth().padding(horizontal=12.dp,vertical=9.dp)){replying?.let{Banner("Replying to ${it.senderName}",it.body.orEmpty()){replying=null}};editing?.let{Banner("Editing message",it.body.orEmpty()){editing=null;draft=""}};attachment?.let{uri->Row(Modifier.fillMaxWidth().padding(bottom=5.dp),verticalAlignment=Alignment.CenterVertically){AsyncImage(uri,"Selected image",Modifier.size(54.dp),contentScale=ContentScale.Crop);Spacer(Modifier.width(8.dp));Text("Photo ready",Modifier.weight(1f));TextButton(onClick={attachment=null}){Text("Remove")}}};Row(verticalAlignment=Alignment.Bottom){IconButton(onClick={mediaPicker.launch(arrayOf("image/*"))},enabled=editing==null){Icon(Icons.Default.Photo,"Photo")};IconButton(onClick=::openCamera,enabled=editing==null){Icon(Icons.Default.CameraAlt,"Camera")};OutlinedTextField(draft,{draft=it.take(2000)},Modifier.weight(1f),placeholder={Text(if(editing==null)"Message…" else "Edit message…")},maxLines=4,shape=RoundedCornerShape(24.dp));Spacer(Modifier.width(5.dp));FilledIconButton(onClick=::send,enabled=draft.isNotBlank()||(attachment!=null&&editing==null),modifier=Modifier.size(48.dp)){Icon(Icons.Default.Send,"Send")}}}}}
 ){padding->Column(Modifier.fillMaxSize().padding(padding)){error?.let{Text(it,Modifier.padding(10.dp),color=MaterialTheme.colorScheme.error)};val chatBackground=when(chatTheme){1->MaterialTheme.colorScheme.primaryContainer.copy(alpha=.18f);2->MaterialTheme.colorScheme.tertiaryContainer.copy(alpha=.20f);3->MaterialTheme.colorScheme.secondaryContainer.copy(alpha=.20f);else->MaterialTheme.colorScheme.surfaceContainerLowest};LazyColumn(state=listState,modifier=Modifier.fillMaxSize().background(chatBackground),contentPadding=PaddingValues(12.dp),verticalArrangement=Arrangement.spacedBy(2.dp)){itemsIndexed(timeline,key={_,entry->when(val item=entry.second){is UiChatMessage->"m-${item.id}";is CallRow->"c-${item.id}";else->entry.first.toString()}}){index,entry->val item=entry.second;val previous=timeline.getOrNull(index-1);if(previous==null||dayKey(previous.first)!=dayKey(entry.first))DateDivider(entry.first);if(item is UiChatMessage){if(item.id==unreadDividerId)UnreadDivider();val avatar=if(item.senderId==uid)myPhoto else partnerPhoto;MessageRow(item,uid,avatar,{replying=item;editing=null},{editing=item;replying=null;draft=item.body.orEmpty()},{e->react(item,e)},{failed->messages=messages.filterNot{it.id==failed.id};draft=failed.body.orEmpty()},{jumpTo(item.replyToId)})}else CallLine(item as CallRow,uid)}}}}
 }
 if(heartBurst>0)key(heartBurst){FloatingThinkingHeart()}
 if(themePicker)ThemePickerDialog(chatTheme,{i->chatTheme=i;chatPrefs.edit().putInt("theme",i).apply();themePicker=false},{themePicker=false})
}

@Composable private fun ThemePickerDialog(selected:Int,onSelect:(Int)->Unit,onDismiss:()->Unit){AlertDialog(onDismissRequest=onDismiss,shape=RoundedCornerShape(28.dp),title={Column{Text("Chat theme",style=MaterialTheme.typography.headlineSmall);Text("Choose the mood for your conversation",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}},text={Column(verticalArrangement=Arrangement.spacedBy(10.dp)){listOf("Default","Blush","Lavender","Ocean").forEachIndexed{i,label->val preview=when(i){1->MaterialTheme.colorScheme.primaryContainer;2->MaterialTheme.colorScheme.tertiaryContainer;3->MaterialTheme.colorScheme.secondaryContainer;else->MaterialTheme.colorScheme.surfaceContainerHigh};Surface(onClick={onSelect(i)},shape=RoundedCornerShape(18.dp),color=preview.copy(alpha=.62f),border=if(selected==i)BorderStroke(2.dp,MaterialTheme.colorScheme.primary)else BorderStroke(1.dp,MaterialTheme.colorScheme.outlineVariant),modifier=Modifier.fillMaxWidth()){Row(Modifier.padding(14.dp),verticalAlignment=Alignment.CenterVertically){Surface(Modifier.size(38.dp),shape=CircleShape,color=preview){Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center){Text(if(i==0)"Aa" else "♥")}};Spacer(Modifier.width(12.dp));Column(Modifier.weight(1f)){Text(label,style=MaterialTheme.typography.titleSmall);Text(if(i==0)"Clean and simple" else "Soft $label tones",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)};if(selected==i)Icon(Icons.Default.CheckCircle,"Selected",tint=MaterialTheme.colorScheme.primary)}}}}},confirmButton={TextButton(onClick=onDismiss){Text("Done")}})}

@Composable private fun FloatingThinkingHeart(){androidx.compose.ui.window.Popup(alignment=Alignment.Center){var go by remember{mutableStateOf(false)};LaunchedEffect(Unit){go=true};val rise by androidx.compose.animation.core.animateDpAsState(if(go)(-150).dp else 0.dp,animationSpec=androidx.compose.animation.core.tween(900),label="heartRise");val alpha by animateFloatAsState(if(go)0f else 1f,animationSpec=androidx.compose.animation.core.tween(900),label="heartFade");val scale by animateFloatAsState(if(go)1.65f else .75f,animationSpec=androidx.compose.animation.core.tween(420),label="heartScale");Text("❤️",fontSize=46.sp,modifier=Modifier.offset(y=rise).graphicsLayer{this.alpha=alpha;scaleX=scale;scaleY=scale})}}

private fun dayKey(ms:Long):LocalDate=Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalDate()
private fun dayLabel(ms:Long):String{val d=dayKey(ms);val today=LocalDate.now();return when(d){today->"Today";today.minusDays(1)->"Yesterday";else->d.format(java.time.format.DateTimeFormatter.ofPattern("MMMM d, yyyy"))}}
@Composable private fun DateDivider(ms:Long){Row(Modifier.fillMaxWidth().padding(vertical=10.dp),verticalAlignment=Alignment.CenterVertically){HorizontalDivider(Modifier.weight(1f));Surface(shape=RoundedCornerShape(16.dp),color=MaterialTheme.colorScheme.surfaceContainerHigh){Text(dayLabel(ms),Modifier.padding(horizontal=12.dp,vertical=5.dp),style=MaterialTheme.typography.labelMedium)};HorizontalDivider(Modifier.weight(1f))}}
@Composable private fun UnreadDivider(){Row(Modifier.fillMaxWidth().padding(vertical=10.dp),verticalAlignment=Alignment.CenterVertically){HorizontalDivider(Modifier.weight(1f));Text("Unread messages",Modifier.padding(horizontal=10.dp),color=MaterialTheme.colorScheme.primary);HorizontalDivider(Modifier.weight(1f))}}
@Composable private fun Banner(title:String,text:String,close:()->Unit){Surface(color=MaterialTheme.colorScheme.primaryContainer.copy(alpha=.55f),shape=RoundedCornerShape(14.dp),modifier=Modifier.fillMaxWidth().padding(bottom=6.dp)){Row(Modifier.padding(9.dp),verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text(title,color=MaterialTheme.colorScheme.primary);Text(text.take(90),maxLines=1)};TextButton(onClick=close){Text("×")}}}}
@Composable private fun MessageAvatar(photo:String?,name:String){Surface(Modifier.size(32.dp),shape=CircleShape,color=MaterialTheme.colorScheme.secondaryContainer){if(photo!=null)AsyncImage(photo,"$name profile photo",Modifier.fillMaxSize(),contentScale=ContentScale.Crop)else Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center){Text(name.take(1).uppercase().ifBlank{"?"})}}}

@OptIn(ExperimentalFoundationApi::class)
@Composable private fun MessageRow(message:UiChatMessage,uid:String?,avatar:String?,reply:()->Unit,edit:()->Unit,react:(String)->Unit,retry:(UiChatMessage)->Unit,jumpToReply:()->Unit){
 val mine=message.senderId==uid;var actions by remember(message.id){mutableStateOf(false)};var edits by remember(message.id){mutableStateOf(false)};var showTime by remember(message.id){mutableStateOf(false)};var emojiPicker by remember(message.id){mutableStateOf(false)};var fullPhoto by remember(message.id){mutableStateOf(false)};var dragX by remember(message.id){mutableFloatStateOf(0f)}
 val x by animateFloatAsState(dragX,label="reply")
 val receipt=when{message.localState=="sending"->"Sending…";message.localState=="failed"->"Failed";message.readAt!=null->"Seen";message.deliveredAt!=null->"Delivered";else->"Sent"}
 val bubble=if(mine)MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh;val text=if(mine)MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface;val body=message.body.orEmpty();val emojis=remember(body){pureEmojiCount(body)}
 Box(Modifier.fillMaxWidth()){
  Column(Modifier.fillMaxWidth().offset{IntOffset(x.roundToInt(),0)}.pointerInput(message.id){detectHorizontalDragGestures(onHorizontalDrag={_,a->dragX=(dragX+a).coerceIn(0f,100f)},onDragEnd={if(dragX>=58f)reply();dragX=0f},onDragCancel={dragX=0f})},horizontalAlignment=if(mine)Alignment.End else Alignment.Start){MessageAvatar(avatar,if(mine)"You" else message.senderName);Spacer(Modifier.height(3.dp));Surface(color=bubble,shape=if(mine)RoundedCornerShape(20.dp,20.dp,6.dp,20.dp)else RoundedCornerShape(20.dp,20.dp,20.dp,6.dp),modifier=Modifier.widthIn(max=310.dp).combinedClickable(onClick={showTime=!showTime},onDoubleClick={react("❤️")},onLongClick={actions=true})){Column(Modifier.padding(13.dp,8.dp)){message.replyPreview?.let{p->Surface(color=MaterialTheme.colorScheme.surface.copy(alpha=.55f),shape=RoundedCornerShape(9.dp),modifier=Modifier.padding(bottom=5.dp).clickable(enabled=message.replyToId!=null,onClick=jumpToReply)){Text("↩ $p",Modifier.padding(8.dp,5.dp),maxLines=2,color=text)}};message.mediaUrl?.let{url->if(message.mediaType=="image")Box(Modifier.requiredWidth(284.dp).heightIn(max=300.dp).clip(RoundedCornerShape(14.dp)).clickable{fullPhoto=true},contentAlignment=Alignment.Center){AsyncImage(url,"Shared photo",Modifier.fillMaxWidth().heightIn(max=300.dp),contentScale=ContentScale.Fit)}};if(body.isNotBlank())Text(body,fontSize=if(emojis in 1..3)44.sp else if(emojis in 4..6)34.sp else 16.sp,color=text);if(message.editedAt!=null)TextButton(onClick={edits=!edits}){Text(if(edits)"Hide edits" else "Edited")};if(edits)message.editHistory.asReversed().forEach{Text("Previous: ${it.body}",style=MaterialTheme.typography.bodySmall)}}};if(message.reactions.isNotEmpty())Surface(shape=RoundedCornerShape(14.dp),shadowElevation=1.dp,modifier=Modifier.offset(y=(-5).dp)){Text(message.reactions.entries.joinToString(" "){"${it.key} ${it.value.size}"},Modifier.padding(7.dp,2.dp))};if(showTime||message.localState!="sent")Text((if(showTime)absoluteTime(message.createdAt)else "")+if(mine){if(showTime)" · $receipt" else receipt}else "",style=MaterialTheme.typography.labelSmall);if(message.localState=="failed")TextButton(onClick={retry(message)}){Text("Retry")}}
  if(actions)MessageOptionsDialog(mine,message,{actions=false},{e->react(e);actions=false},{actions=false;emojiPicker=true},{reply();actions=false},{edit();actions=false})
  if(emojiPicker)EmojiPickerDialog({emojiPicker=false}){react(it);emojiPicker=false}
  if(fullPhoto)message.mediaUrl?.let{url->FullPhotoViewer(url){fullPhoto=false}}
 }
}

@Composable private fun MessageOptionsDialog(mine:Boolean,message:UiChatMessage,onDismiss:()->Unit,onReact:(String)->Unit,onMore:()->Unit,onReply:()->Unit,onEdit:()->Unit){AlertDialog(onDismissRequest=onDismiss,shape=RoundedCornerShape(28.dp),title={Text("Message options",style=MaterialTheme.typography.titleLarge)},text={Column(verticalArrangement=Arrangement.spacedBy(14.dp)){Surface(shape=RoundedCornerShape(22.dp),color=MaterialTheme.colorScheme.surfaceContainerHigh){Column(Modifier.padding(10.dp)){Text("Quick reaction",Modifier.padding(horizontal=6.dp,vertical=4.dp),style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant);LazyRow(horizontalArrangement=Arrangement.spacedBy(7.dp)){items(reactionChoices){e->Surface(onClick={onReact(e)},shape=CircleShape,color=MaterialTheme.colorScheme.surface,tonalElevation=2.dp){Text(e,fontSize=25.sp,modifier=Modifier.padding(9.dp))}}};TextButton(onClick=onMore,modifier=Modifier.align(Alignment.End)){Icon(Icons.Default.Add,null);Spacer(Modifier.width(5.dp));Text("More emoji")}}};Surface(onClick=onReply,shape=RoundedCornerShape(16.dp),color=MaterialTheme.colorScheme.surfaceContainer,modifier=Modifier.fillMaxWidth()){Row(Modifier.padding(14.dp),verticalAlignment=Alignment.CenterVertically){Icon(Icons.Default.Reply,null);Spacer(Modifier.width(12.dp));Text("Reply",style=MaterialTheme.typography.titleSmall)}};if(mine&&message.localState=="sent"&&message.body!=null)Surface(onClick=onEdit,shape=RoundedCornerShape(16.dp),color=MaterialTheme.colorScheme.surfaceContainer,modifier=Modifier.fillMaxWidth()){Row(Modifier.padding(14.dp),verticalAlignment=Alignment.CenterVertically){Icon(Icons.Default.Edit,null);Spacer(Modifier.width(12.dp));Text("Edit message",style=MaterialTheme.typography.titleSmall)}}}},confirmButton={})}

@Composable private fun FullPhotoViewer(url:String,onBack:()->Unit){androidx.compose.ui.window.Dialog(onDismissRequest=onBack,properties=androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth=false)){Box(Modifier.fillMaxSize().background(Color.Black)){AsyncImage(url,"Full screen photo",Modifier.fillMaxSize().padding(vertical=48.dp),contentScale=ContentScale.Fit);IconButton(onClick=onBack,modifier=Modifier.align(Alignment.TopStart).padding(12.dp).background(Color.Black.copy(alpha=.45f),CircleShape)){Icon(Icons.Default.ArrowBack,"Back to messages",tint=Color.White)}}}}

@Composable private fun EmojiPickerDialog(onDismiss:()->Unit,onEmoji:(String)->Unit){
 AlertDialog(onDismissRequest=onDismiss,shape=RoundedCornerShape(28.dp),title={Column{Text("Pick a reaction",style=MaterialTheme.typography.headlineSmall);Text("Tap an emoji to react instantly",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}},text={LazyVerticalGrid(columns=GridCells.Fixed(6),modifier=Modifier.fillMaxWidth().heightIn(max=330.dp),horizontalArrangement=Arrangement.spacedBy(6.dp),verticalArrangement=Arrangement.spacedBy(6.dp)){items(emojiPickerChoices){emoji->Surface(onClick={onEmoji(emoji)},shape=CircleShape,color=MaterialTheme.colorScheme.surfaceContainerHigh,modifier=Modifier.aspectRatio(1f)){Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center){Text(emoji,fontSize=26.sp)}}}}},confirmButton={},dismissButton={TextButton(onClick=onDismiss){Text("Close")}})
}

@Composable private fun CallLine(call:CallRow,uid:String?){Row(Modifier.fillMaxWidth().padding(vertical=6.dp),horizontalArrangement=Arrangement.Center){Surface(shape=RoundedCornerShape(16.dp),color=MaterialTheme.colorScheme.secondaryContainer){Text("${if(call.callerId==uid)"Outgoing" else "Incoming"} ${if(call.isVideo)"video" else "voice"} TwoLink · ${relative(call.createdAt)}",Modifier.padding(12.dp,7.dp))}}}
private fun saveCameraBitmap(context:Context,bitmap:Bitmap):Uri?=runCatching{val v=ContentValues().apply{put(MediaStore.Images.Media.DISPLAY_NAME,"TwoShare_${System.currentTimeMillis()}.jpg");put(MediaStore.Images.Media.MIME_TYPE,"image/jpeg")};val uri=context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,v)?:return@runCatching null;context.contentResolver.openOutputStream(uri)?.use{bitmap.compress(Bitmap.CompressFormat.JPEG,88,it)};uri}.getOrNull()
private fun epoch(v:String)=runCatching{Instant.parse(v).toEpochMilli()}.getOrDefault(0)
private fun relative(v:String):String{val t=epoch(v);return if(t==0L)"" else DateUtils.getRelativeTimeSpanString(t,System.currentTimeMillis(),DateUtils.MINUTE_IN_MILLIS).toString()}
