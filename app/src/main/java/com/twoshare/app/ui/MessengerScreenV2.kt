package com.twoshare.app.ui

import android.text.format.DateUtils
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.VideoCall
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.twoshare.app.data.ChatFeaturesRepository
import com.twoshare.app.data.TwoShareRepository
import com.twoshare.app.data.loadCallHistory
import com.twoshare.app.model.CallRow
import com.twoshare.app.model.UiChatMessage
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private val reactionChoices = listOf("❤️", "😂", "😮", "😢", "👍", "🔥")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessengerScreenV2(repository: TwoShareRepository, onBack: () -> Unit, onStartCall: (CallRow, Boolean) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val chat = remember(repository) { ChatFeaturesRepository(repository) }
    val listState = rememberLazyListState()
    var messages by remember { mutableStateOf(emptyList<UiChatMessage>()) }
    var calls by remember { mutableStateOf(emptyList<CallRow>()) }
    var draft by remember { mutableStateOf("") }
    var replying by remember { mutableStateOf<UiChatMessage?>(null) }
    var editing by remember { mutableStateOf<UiChatMessage?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    val timeline = remember(messages, calls) {
        (messages.map { Pair(epoch(it.createdAt), it as Any) } + calls.map { Pair(epoch(it.createdAt), it as Any) }).sortedBy { it.first }
    }

    suspend fun refresh() {
        runCatching { chat.markIncomingRead() }
        runCatching { chat.loadMessages() to repository.loadCallHistory() }.onSuccess { (server, history) ->
            val pending = messages.filter { it.localState != "sent" }
            val ids = server.mapNotNull { it.clientId }.toSet()
            messages = server + pending.filter { it.clientId !in ids }
            calls = history
            error = null
        }.onFailure { error = it.localizedMessage ?: "Unable to refresh chat." }
    }

    LaunchedEffect(Unit) {
        refresh()
        while (isActive) { delay(1500); refresh() }
    }

    LaunchedEffect(timeline.size) {
        if (timeline.isNotEmpty()) {
            delay(100)
            val count = listState.layoutInfo.totalItemsCount
            if (count > 0) runCatching { listState.scrollToItem((timeline.lastIndex).coerceAtMost(count - 1)) }
        }
    }

    fun beginReply(message: UiChatMessage) { replying = message; editing = null }
    fun react(message: UiChatMessage, emoji: String) { scope.launch { runCatching { repository.toggleMessageReaction(message, emoji) }.onSuccess { refresh() }.onFailure { error = "Reaction failed." } } }
    fun send() {
        val edit = editing
        if (edit != null) { val text = draft; if (text.isBlank()) return; draft = ""; editing = null; scope.launch { runCatching { chat.editMessage(edit, text) }.onSuccess { refresh() }.onFailure { error = "Edit failed: ${it.localizedMessage}" } }; return }
        if (draft.isBlank()) return
        val uid = repository.currentUserId() ?: return; val text = draft; val reply = replying; val clientId = UUID.randomUUID().toString()
        val local = UiChatMessage("local-$clientId", uid, "You", text.trim(), null, null, Instant.now().toString(), null, emptyMap(), clientId, reply?.id, reply?.body?.take(120), null, null, emptyList(), "sending")
        messages = messages + local; draft = ""; replying = null
        scope.launch { runCatching { chat.sendMessage(context, text, null, clientId, reply?.id) }.onSuccess { sent -> messages = messages.map { if (it.clientId == clientId) sent.copy(replyPreview = local.replyPreview) else it }; refresh() }.onFailure { t -> messages = messages.map { if (it.clientId == clientId) it.copy(localState = "failed") else it }; error = t.localizedMessage ?: "Message failed." } }
    }

    Scaffold(containerColor = MaterialTheme.colorScheme.background,
        topBar = { TopAppBar(colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface), title = { Column { Text("TwoShare", style = MaterialTheme.typography.titleLarge); Text("Private Messenger", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary) } }, navigationIcon = { TextButton(onClick = onBack) { Text("‹ Feed") } }, actions = { FilledTonalIconButton(onClick = { scope.launch { runCatching { repository.createOutgoingCall(false) }.onSuccess { onStartCall(it, false) } } }) { Icon(Icons.Default.Call, "Voice call") }; Spacer(Modifier.width(5.dp)); FilledTonalIconButton(onClick = { scope.launch { runCatching { repository.createOutgoingCall(true) }.onSuccess { onStartCall(it, false) } } }) { Icon(Icons.Default.VideoCall, "Video call") }; Spacer(Modifier.width(8.dp)) }) },
        bottomBar = { Surface(shadowElevation = 8.dp) { Column(Modifier.fillMaxWidth().padding(10.dp)) { replying?.let { Banner("Replying to ${it.senderName}", it.body.orEmpty()) { replying = null } }; editing?.let { Banner("Editing message", it.body.orEmpty()) { editing = null; draft = "" } }; Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) { OutlinedTextField(draft, { draft = it.take(2000) }, Modifier.weight(1f), placeholder = { Text(if (editing == null) "Message…" else "Edit message…") }, maxLines = 4, shape = RoundedCornerShape(26.dp)); FilledIconButton(onClick = ::send, enabled = draft.isNotBlank(), modifier = Modifier.size(50.dp)) { Icon(Icons.Default.Send, "Send") } } } } }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            error?.let { Text(it, Modifier.padding(10.dp), color = MaterialTheme.colorScheme.error) }
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                // Do not use database IDs as Compose keys here. Message and call history can contain
                // duplicate/legacy IDs or optimistic/server copies during refresh, which makes LazyColumn
                // throw an IllegalArgumentException and closes the app as soon as Messenger is opened.
                itemsIndexed(timeline) { _, entry ->
                    val item = entry.second
                    if (item is UiChatMessage) MessageRow(item, repository.currentUserId(), { beginReply(item) }, { editing = item; replying = null; draft = item.body.orEmpty() }, { emoji -> react(item, emoji) }, { failed -> messages = messages.filterNot { it.id == failed.id }; draft = failed.body.orEmpty() })
                    else CallLine(item as CallRow, repository.currentUserId())
                }
            }
        }
    }
}

@Composable private fun Banner(title: String, text: String, close: () -> Unit) { Surface(color = MaterialTheme.colorScheme.primaryContainer.copy(alpha=.65f), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth().padding(bottom=6.dp)) { Row(Modifier.padding(9.dp), verticalAlignment=Alignment.CenterVertically) { Box(Modifier.width(3.dp).height(30.dp).background(MaterialTheme.colorScheme.primary, RoundedCornerShape(4.dp))); Spacer(Modifier.width(8.dp)); Column(Modifier.weight(1f)) { Text(title, style=MaterialTheme.typography.labelMedium, color=MaterialTheme.colorScheme.primary); Text(text.take(90), maxLines=1, style=MaterialTheme.typography.bodySmall) }; TextButton(onClick=close) { Text("×") } } } }

@OptIn(ExperimentalFoundationApi::class)
@Composable private fun MessageRow(message: UiChatMessage, uid: String?, reply: () -> Unit, edit: () -> Unit, react: (String) -> Unit, retry: (UiChatMessage) -> Unit) {
    val mine = message.senderId == uid; var actions by remember(message.id) { mutableStateOf(false) }; var edits by remember(message.id) { mutableStateOf(false) }; var dragX by remember(message.id) { mutableFloatStateOf(0f) }; val x by animateFloatAsState(dragX, label="reply")
    val receipt = when { message.localState == "sending" -> "Sending…"; message.localState == "failed" -> "Failed"; message.readAt != null -> "Seen"; message.deliveredAt != null -> "Delivered"; else -> "Sent" }
    val bubbleColor = if (mine) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
    val textColor = if (mine) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
    Box(Modifier.fillMaxWidth()) {
        if (dragX > 18f) Text("↩", modifier=Modifier.align(Alignment.CenterStart).padding(start=8.dp), color=MaterialTheme.colorScheme.primary)
        Column(Modifier.fillMaxWidth().offset { IntOffset(x.roundToInt(),0) }.pointerInput(message.id) { detectHorizontalDragGestures(onHorizontalDrag={_,a -> dragX=(dragX+a).coerceIn(0f,100f)}, onDragEnd={if(dragX>=58f) reply(); dragX=0f}, onDragCancel={dragX=0f}) }, horizontalAlignment=if(mine) Alignment.End else Alignment.Start) {
            Surface(color=bubbleColor, shape=if(mine) RoundedCornerShape(20.dp,20.dp,5.dp,20.dp) else RoundedCornerShape(20.dp,20.dp,20.dp,5.dp), modifier=Modifier.widthIn(max=300.dp).combinedClickable(onClick={}, onLongClick={actions=true}), tonalElevation=if(mine) 0.dp else 1.dp) {
                Column(Modifier.padding(horizontal=12.dp, vertical=8.dp)) {
                    message.replyPreview?.let { Surface(color=if(mine) Color.White.copy(alpha=.14f) else MaterialTheme.colorScheme.surface, shape=RoundedCornerShape(9.dp), modifier=Modifier.padding(bottom=5.dp)) { Text("↩ $it", Modifier.padding(horizontal=8.dp, vertical=5.dp), maxLines=2, style=MaterialTheme.typography.bodySmall, color=textColor) } }
                    message.mediaUrl?.let { if(message.mediaType=="image") AsyncImage(it,"Shared photo",Modifier.widthIn(max=280.dp).heightIn(max=300.dp),contentScale=ContentScale.Crop) }
                    message.body?.let { Text(it, style=MaterialTheme.typography.bodyLarge, color=textColor) }
                    if(message.editedAt!=null) TextButton(onClick={edits=!edits}, contentPadding=PaddingValues(0.dp), modifier=Modifier.height(22.dp), colors=ButtonDefaults.textButtonColors(contentColor=textColor.copy(alpha=.72f))) { Text(if(edits) "Hide edits" else "Edited · Show edits", style=MaterialTheme.typography.labelSmall) }
                    if(edits) message.editHistory.asReversed().forEach { Text("Previous: ${it.body}", style=MaterialTheme.typography.bodySmall, color=textColor.copy(alpha=.7f)) }
                }
            }
            if(message.reactions.isNotEmpty()) Surface(shape=RoundedCornerShape(14.dp), color=MaterialTheme.colorScheme.surface, shadowElevation=1.dp, modifier=Modifier.padding(top=(-5).dp, end=5.dp)) { Text(message.reactions.entries.joinToString(" ") { "${it.key} ${it.value.size}" }, Modifier.padding(horizontal=7.dp,vertical=2.dp), style=MaterialTheme.typography.labelMedium) }
            Row(verticalAlignment=Alignment.CenterVertically) { Text(relative(message.createdAt)+(if(mine) " · $receipt" else ""), style=MaterialTheme.typography.labelSmall, color=MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha=.68f)); TextButton(onClick={actions=true}, contentPadding=PaddingValues(horizontal=5.dp), modifier=Modifier.height(26.dp)) { Text("•••") } }
            if(message.localState=="failed") TextButton(onClick={retry(message)}) { Text("Retry") }
        }
        if(actions) AlertDialog(onDismissRequest={actions=false}, title={Text("Message")}, text={ Column { Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.SpaceEvenly) { reactionChoices.forEach { emoji -> Text(emoji, style=MaterialTheme.typography.headlineSmall, modifier=Modifier.combinedClickable(onClick={react(emoji);actions=false},onLongClick={})) } }; Spacer(Modifier.height(10.dp)); TextButton(onClick={reply();actions=false}, modifier=Modifier.fillMaxWidth()) { Text("Reply") }; if(mine && message.localState=="sent" && message.body!=null) TextButton(onClick={edit();actions=false}, modifier=Modifier.fillMaxWidth()) { Text("Edit") } } }, confirmButton={})
    }
}

@Composable private fun CallLine(call: CallRow, uid: String?) { Row(Modifier.fillMaxWidth().padding(vertical=5.dp), horizontalArrangement=Arrangement.Center) { Surface(shape=RoundedCornerShape(16.dp), color=MaterialTheme.colorScheme.secondaryContainer) { Text("${if(call.callerId==uid) "Outgoing" else "Incoming"} ${if(call.isVideo) "video" else "voice"} TwoLink · ${relative(call.createdAt)}", Modifier.padding(horizontal=12.dp,vertical=7.dp), style=MaterialTheme.typography.labelMedium) } } }
private fun epoch(value:String)=runCatching{OffsetDateTime.parse(value).toInstant().toEpochMilli()}.recoverCatching{Instant.parse(value).toEpochMilli()}.getOrDefault(0L)
private fun relative(value:String)=DateUtils.getRelativeTimeSpanString(epoch(value),System.currentTimeMillis(),DateUtils.MINUTE_IN_MILLIS).toString()