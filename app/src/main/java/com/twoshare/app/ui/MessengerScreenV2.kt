package com.twoshare.app.ui

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
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
        topBar = { TopAppBar(title = { Text("Messenger") }, navigationIcon = { TextButton(onClick = onBack) { Text("← Back") } }, actions = { TextButton(onClick = { scope.launch { runCatching { repository.createOutgoingCall(false) }.onSuccess { onStartCall(it, false) } } }) { Text("Call") }; TextButton(onClick = { scope.launch { runCatching { repository.createOutgoingCall(true) }.onSuccess { onStartCall(it, false) } } }) { Text("Video") } }) },
        bottomBar = { Surface(shadowElevation = 8.dp) { Column(Modifier.fillMaxWidth().padding(10.dp)) {
            replying?.let { Banner("Replying", it.body.orEmpty()) { replying = null } }
            editing?.let { Banner("Editing", it.body.orEmpty()) { editing = null; draft = "" } }
            Row(verticalAlignment = Alignment.Bottom) { OutlinedTextField(draft, { draft = it.take(2000) }, Modifier.weight(1f), placeholder = { Text(if (editing == null) "Message" else "Edit message") }, maxLines = 4); Spacer(Modifier.width(8.dp)); Button(onClick = ::send, enabled = draft.isNotBlank()) { Text(if (editing == null) "Send" else "Save") } }
        } } }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(8.dp)) }
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val timeline = (messages.map { Pair(epoch(it.createdAt), it as Any) } + calls.map { Pair(epoch(it.createdAt), it as Any) }).sortedBy { it.first }
                items(timeline, key = { (_, item) -> if (item is UiChatMessage) "m-${item.id}" else "c-${(item as CallRow).id}" }) { (_, item) ->
                    if (item is UiChatMessage) ModernBubble(item, repository.currentUserId(), { replying = item; editing = null }, { editing = item; replying = null; draft = item.body.orEmpty() }, { failed -> messages = messages.filterNot { it.id == failed.id }; draft = failed.body.orEmpty() })
                    else CallLine(item as CallRow, repository.currentUserId())
                }
            }
        }
    }
}

@Composable private fun Banner(title: String, text: String, close: () -> Unit) { Surface(tonalElevation = 2.dp, shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)) { Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text(title, style = MaterialTheme.typography.labelMedium); Text(text.take(100), maxLines = 1, style = MaterialTheme.typography.bodySmall) }; TextButton(onClick = close) { Text("×") } } } }

@Composable private fun ModernBubble(message: UiChatMessage, uid: String?, reply: () -> Unit, edit: () -> Unit, retry: (UiChatMessage) -> Unit) {
    val mine = message.senderId == uid
    var menu by remember(message.id) { mutableStateOf(false) }
    var edits by remember(message.id) { mutableStateOf(false) }
    val receipt = when { message.localState == "sending" -> "Sending…"; message.localState == "failed" -> "Failed"; message.readAt != null -> "Seen"; message.deliveredAt != null -> "Delivered"; else -> "Sent" }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) { Column(Modifier.widthIn(max = 330.dp).background(if (mine) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(18.dp)).padding(10.dp)) {
        if (!mine) Text(message.senderName, style = MaterialTheme.typography.labelMedium)
        message.replyPreview?.let { Surface(shape = RoundedCornerShape(8.dp), tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) { Text("↩ $it", Modifier.padding(7.dp), maxLines = 2, style = MaterialTheme.typography.bodySmall) } }
        message.mediaUrl?.let { if (message.mediaType == "image") AsyncImage(it, "Shared photo", Modifier.fillMaxWidth().heightIn(max = 320.dp), contentScale = ContentScale.Crop) }
        message.body?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }
        if (message.editedAt != null) TextButton(onClick = { edits = !edits }, contentPadding = PaddingValues(0.dp), modifier = Modifier.height(28.dp)) { Text(if (edits) "Hide edits" else "Edited · Show edits", style = MaterialTheme.typography.labelSmall) }
        if (edits) message.editHistory.asReversed().forEach { Text("Previous: ${it.body} · ${relative(it.editedAt)}", style = MaterialTheme.typography.bodySmall) }
        Row(verticalAlignment = Alignment.CenterVertically) { Text(relative(message.createdAt) + if (mine) " · $receipt" else "", style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f)); TextButton(onClick = { menu = !menu }, contentPadding = PaddingValues(4.dp)) { Text("⋯") } }
        if (menu) Row { TextButton(onClick = { reply(); menu = false }) { Text("Reply") }; if (mine && message.localState == "sent" && message.body != null) TextButton(onClick = { edit(); menu = false }) { Text("Edit") } }
        if (message.localState == "failed") TextButton(onClick = { retry(message) }) { Text("Retry") }
        if (message.reactions.isNotEmpty()) Text(message.reactions.entries.joinToString("  ") { "${it.key} ${it.value.size}" }, style = MaterialTheme.typography.labelMedium)
    } }
}

@Composable private fun CallLine(call: CallRow, uid: String?) { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) { Surface(shape = RoundedCornerShape(16.dp), tonalElevation = 1.dp) { Text("${if (call.callerId == uid) "Outgoing" else "Incoming"} ${if (call.isVideo) "video" else "voice"} TwoLink · ${relative(call.createdAt)}", Modifier.padding(10.dp), style = MaterialTheme.typography.labelMedium) } } }
private fun epoch(value: String) = runCatching { OffsetDateTime.parse(value).toInstant().toEpochMilli() }.recoverCatching { Instant.parse(value).toEpochMilli() }.getOrDefault(0L)
private fun relative(value: String): String = DateUtils.getRelativeTimeSpanString(epoch(value), System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()
