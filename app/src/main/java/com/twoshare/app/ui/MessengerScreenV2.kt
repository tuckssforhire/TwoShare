package com.twoshare.app.ui

import android.text.format.DateUtils
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.VideoCall
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
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

    suspend fun refresh() {
        runCatching { chat.markIncomingRead() }
        runCatching { chat.loadMessages() to repository.loadCallHistory() }.onSuccess { (server, history) ->
            val pending = messages.filter { it.localState != "sent" }
            val ids = server.mapNotNull { it.clientId }.toSet()
            messages = server + pending.filter { it.clientId !in ids }
            calls = history
        }.onFailure { error = it.localizedMessage ?: "Unable to refresh chat." }
    }
    LaunchedEffect(Unit) { refresh(); while (isActive) { delay(1500); refresh() } }
    LaunchedEffect(messages.size, calls.size) { if (messages.isNotEmpty() || calls.isNotEmpty()) listState.animateScrollToItem((messages.size + calls.size - 1).coerceAtLeast(0)) }

    fun beginReply(message: UiChatMessage) { replying = message; editing = null }
    fun send() {
        val edit = editing
        if (edit != null) {
            val text = draft; if (text.isBlank()) return
            draft = ""; editing = null
            scope.launch { runCatching { chat.editMessage(edit, text) }.onSuccess { refresh() }.onFailure { error = "Edit failed: ${it.localizedMessage}" } }
            return
        }
        if (draft.isBlank()) return
        val uid = repository.currentUserId() ?: return
        val text = draft; val reply = replying; val clientId = UUID.randomUUID().toString()
        val local = UiChatMessage("local-$clientId", uid, "You", text.trim(), null, null, Instant.now().toString(), null, emptyMap(), clientId, reply?.id, reply?.body?.take(120), null, null, emptyList(), "sending")
        messages = messages + local; draft = ""; replying = null
        scope.launch {
            runCatching { chat.sendMessage(context, text, null, clientId, reply?.id) }
                .onSuccess { sent -> messages = messages.map { if (it.clientId == clientId) sent.copy(replyPreview = local.replyPreview) else it }; refresh() }
                .onFailure { t -> messages = messages.map { if (it.clientId == clientId) it.copy(localState = "failed") else it }; error = t.localizedMessage ?: "Message failed." }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = .97f)),
                title = { Column { Text("TwoShare", style = MaterialTheme.typography.titleLarge); Text("Private Messenger", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary) } },
                navigationIcon = { TextButton(onClick = onBack) { Text("‹ Feed") } },
                actions = {
                    FilledTonalIconButton(onClick = { scope.launch { runCatching { repository.createOutgoingCall(false) }.onSuccess { onStartCall(it, false) } } }) { Icon(Icons.Default.Call, "Voice call") }
                    Spacer(Modifier.width(4.dp))
                    FilledTonalIconButton(onClick = { scope.launch { runCatching { repository.createOutgoingCall(true) }.onSuccess { onStartCall(it, false) } } }) { Icon(Icons.Default.VideoCall, "Video call") }
                    Spacer(Modifier.width(8.dp))
                }
            )
        },
        bottomBar = {
            Surface(shadowElevation = 12.dp, color = MaterialTheme.colorScheme.surface) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
                    replying?.let { Banner("Replying to ${it.senderName}", it.body.orEmpty()) { replying = null } }
                    editing?.let { Banner("Editing message", it.body.orEmpty()) { editing = null; draft = "" } }
                    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = draft, onValueChange = { draft = it.take(2000) }, modifier = Modifier.weight(1f),
                            placeholder = { Text(if (editing == null) "Message…" else "Edit message…") }, maxLines = 4,
                            shape = RoundedCornerShape(24.dp),
                            colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = .35f))
                        )
                        FilledIconButton(onClick = ::send, enabled = draft.isNotBlank(), modifier = Modifier.size(52.dp)) { Icon(Icons.Default.Send, if (editing == null) "Send" else "Save") }
                    }
                }
            }
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            error?.let { Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth()) { Text(it, color = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.padding(10.dp)) } }
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                val timeline = (messages.map { Pair(epoch(it.createdAt), it as Any) } + calls.map { Pair(epoch(it.createdAt), it as Any) }).sortedBy { it.first }
                items(timeline, key = { (_, item) -> if (item is UiChatMessage) "m-${item.id}" else "c-${(item as CallRow).id}" }) { (_, item) ->
                    if (item is UiChatMessage) ModernBubble(item, repository.currentUserId(), { beginReply(item) }, { editing = item; replying = null; draft = item.body.orEmpty() }, { failed -> messages = messages.filterNot { it.id == failed.id }; draft = failed.body.orEmpty() })
                    else CallLine(item as CallRow, repository.currentUserId())
                }
            }
        }
    }
}

@Composable private fun Banner(title: String, text: String, close: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth().padding(bottom = 7.dp)) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(3.dp).height(34.dp).background(MaterialTheme.colorScheme.primary, RoundedCornerShape(4.dp)))
            Spacer(Modifier.width(9.dp)); Column(Modifier.weight(1f)) { Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary); Text(text.take(100), maxLines = 1, style = MaterialTheme.typography.bodySmall) }; TextButton(onClick = close) { Text("×") }
        }
    }
}

@Composable private fun ModernBubble(message: UiChatMessage, uid: String?, reply: () -> Unit, edit: () -> Unit, retry: (UiChatMessage) -> Unit) {
    val mine = message.senderId == uid
    var menu by remember(message.id) { mutableStateOf(false) }
    var edits by remember(message.id) { mutableStateOf(false) }
    var dragX by remember(message.id) { mutableFloatStateOf(0f) }
    val animatedX by animateFloatAsState(dragX, label = "replySwipe")
    val receipt = when { message.localState == "sending" -> "Sending…"; message.localState == "failed" -> "Failed"; message.readAt != null -> "Seen"; message.deliveredAt != null -> "Delivered"; else -> "Sent" }
    val mineBrush = Brush.horizontalGradient(listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.tertiary))

    Box(Modifier.fillMaxWidth()) {
        if (dragX > 20f) {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.align(if (mine) Alignment.CenterEnd else Alignment.CenterStart).size(38.dp)) {
                Box(contentAlignment = Alignment.Center) { Text("↩", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleMedium) }
            }
        }
        Row(
            Modifier.fillMaxWidth().offset { IntOffset(animatedX.roundToInt(), 0) }
                .pointerInput(message.id) {
                    detectHorizontalDragGestures(
                        onHorizontalDrag = { _, amount -> dragX = (dragX + amount).coerceIn(0f, 120f) },
                        onDragEnd = { if (dragX >= 70f) reply(); dragX = 0f },
                        onDragCancel = { dragX = 0f }
                    )
                },
            horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start
        ) {
            Column(
                Modifier.widthIn(max = 330.dp)
                    .then(if (mine) Modifier.background(mineBrush, RoundedCornerShape(22.dp, 22.dp, 6.dp, 22.dp)) else Modifier.background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(22.dp, 22.dp, 22.dp, 6.dp)))
                    .shadow(1.dp, RoundedCornerShape(22.dp)).padding(horizontal = 12.dp, vertical = 10.dp)
            ) {
                val mainText = if (mine) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
                if (!mine) Text(message.senderName, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary)
                message.replyPreview?.let { Surface(shape = RoundedCornerShape(10.dp), color = if (mine) Color.White.copy(alpha=.15f) else MaterialTheme.colorScheme.surface.copy(alpha=.8f), modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) { Text("↩ $it", Modifier.padding(8.dp), maxLines = 2, style = MaterialTheme.typography.bodySmall, color = mainText) } }
                message.mediaUrl?.let { if (message.mediaType == "image") AsyncImage(it, "Shared photo", Modifier.fillMaxWidth().heightIn(max = 320.dp), contentScale = ContentScale.Crop) }
                message.body?.let { Text(it, style = MaterialTheme.typography.bodyLarge, color = mainText) }
                if (message.editedAt != null) TextButton(onClick = { edits = !edits }, contentPadding = PaddingValues(0.dp), modifier = Modifier.height(28.dp), colors = ButtonDefaults.textButtonColors(contentColor = if (mine) Color.White.copy(alpha=.85f) else MaterialTheme.colorScheme.primary)) { Text(if (edits) "Hide edits" else "Edited · Show edits", style = MaterialTheme.typography.labelSmall) }
                if (edits) message.editHistory.asReversed().forEach { Text("Previous: ${it.body} · ${relative(it.editedAt)}", style = MaterialTheme.typography.bodySmall, color = mainText.copy(alpha=.8f)) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(relative(message.createdAt) + if (mine) " · $receipt" else "", style = MaterialTheme.typography.labelSmall, color = mainText.copy(alpha=.75f), modifier = Modifier.weight(1f))
                    TextButton(onClick = { menu = !menu }, contentPadding = PaddingValues(4.dp), colors = ButtonDefaults.textButtonColors(contentColor = mainText)) { Text("•••") }
                }
                if (menu) Row { TextButton(onClick = { reply(); menu = false }, colors = ButtonDefaults.textButtonColors(contentColor = mainText)) { Text("Reply") }; if (mine && message.localState == "sent" && message.body != null) TextButton(onClick = { edit(); menu = false }, colors = ButtonDefaults.textButtonColors(contentColor = mainText)) { Text("Edit") } }
                if (message.localState == "failed") TextButton(onClick = { retry(message) }, colors = ButtonDefaults.textButtonColors(contentColor = if (mine) Color.White else MaterialTheme.colorScheme.error)) { Text("Retry") }
                if (message.reactions.isNotEmpty()) Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface, modifier = Modifier.padding(top=3.dp)) { Text(message.reactions.entries.joinToString("  ") { "${it.key} ${it.value.size}" }, Modifier.padding(horizontal=8.dp, vertical=3.dp), style = MaterialTheme.typography.labelMedium) }
            }
        }
    }
}

@Composable private fun CallLine(call: CallRow, uid: String?) { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) { Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.secondaryContainer, tonalElevation = 1.dp) { Text("${if (call.callerId == uid) "Outgoing" else "Incoming"} ${if (call.isVideo) "video" else "voice"} TwoLink · ${relative(call.createdAt)}", Modifier.padding(horizontal=13.dp, vertical=9.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSecondaryContainer) } } }
private fun epoch(value: String) = runCatching { OffsetDateTime.parse(value).toInstant().toEpochMilli() }.recoverCatching { Instant.parse(value).toEpochMilli() }.getOrDefault(0L)
private fun relative(value: String): String = DateUtils.getRelativeTimeSpanString(epoch(value), System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()
