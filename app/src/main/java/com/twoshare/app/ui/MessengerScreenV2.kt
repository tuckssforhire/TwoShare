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
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.VideoCall
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import com.twoshare.app.data.ChatFeaturesRepository
import com.twoshare.app.data.TwoShareRepository
import com.twoshare.app.data.loadCallHistory
import com.twoshare.app.model.CallRow
import com.twoshare.app.model.UiChatMessage
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
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
    var attachment by remember { mutableStateOf<Uri?>(null) }
    var replying by remember { mutableStateOf<UiChatMessage?>(null) }
    var editing by remember { mutableStateOf<UiChatMessage?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var unreadDividerId by remember { mutableStateOf<String?>(null) }
    var initialUnreadCaptured by remember { mutableStateOf(false) }

    val timeline = remember(messages, calls) {
        (messages.map { Pair(epoch(it.createdAt), it as Any) } + calls.map { Pair(epoch(it.createdAt), it as Any) }).sortedBy { it.first }
    }

    suspend fun refresh() {
        val result = runCatching { chat.loadMessages() }
        val history = runCatching { repository.loadCallHistory() }.getOrDefault(emptyList())
        result.onSuccess { server ->
            if (!initialUnreadCaptured) {
                val uid = repository.currentUserId()
                unreadDividerId = server.firstOrNull { it.senderId != uid && it.readAt == null }?.id
                initialUnreadCaptured = true
            }
            val pending = messages.filter { it.localState != "sent" }
            val ids = server.mapNotNull { it.clientId }.toSet()
            messages = server + pending.filter { it.clientId !in ids }
            calls = history
            error = null
            runCatching { chat.markIncomingRead() }
        }.onFailure { calls = history; error = it.localizedMessage ?: "Unable to load messages." }
    }

    LaunchedEffect(Unit) { refresh(); while (isActive) { delay(1500); refresh() } }
    LaunchedEffect(timeline.size) {
        if (timeline.isNotEmpty()) { delay(100); val count = listState.layoutInfo.totalItemsCount; if (count > 0) runCatching { listState.scrollToItem(timeline.lastIndex.coerceAtMost(count - 1)) } }
    }
    LaunchedEffect(replying?.id, editing?.id) {
        if ((replying != null || editing != null) && timeline.isNotEmpty()) { delay(100); runCatching { listState.animateScrollToItem(timeline.lastIndex) } }
    }

    val mediaPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        attachment = uri
        if (uri != null) runCatching { context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicturePreview()) { bitmap ->
        if (bitmap != null) attachment = saveCameraBitmap(context, bitmap)
    }
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) camera.launch(null) else error = "Camera permission is needed to take a photo."
    }
    fun openCamera() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) camera.launch(null)
        else cameraPermission.launch(Manifest.permission.CAMERA)
    }

    fun beginReply(message: UiChatMessage) { replying = message; editing = null }
    fun react(message: UiChatMessage, emoji: String) {
        if (message.localState != "sent" || emoji.isBlank()) return
        val uid = repository.currentUserId() ?: return
        val before = message.reactions
        val users = before[emoji].orEmpty()
        val updated = before.toMutableMap()
        if (uid in users) { val remaining = users - uid; if (remaining.isEmpty()) updated.remove(emoji) else updated[emoji] = remaining } else updated[emoji] = users + uid
        messages = messages.map { if (it.id == message.id) it.copy(reactions = updated.toMap()) else it }
        scope.launch { runCatching { repository.toggleMessageReaction(message, emoji) }.onFailure { messages = messages.map { current -> if (current.id == message.id) current.copy(reactions = before) else current }; error = "Reaction failed." } }
    }

    fun send() {
        val edit = editing
        if (edit != null) {
            val text = draft; if (text.isBlank()) return; draft = ""; editing = null
            scope.launch { runCatching { chat.editMessage(edit, text) }.onSuccess { refresh() }.onFailure { error = "Edit failed: ${it.localizedMessage}" } }
            return
        }
        if (draft.isBlank() && attachment == null) return
        val uid = repository.currentUserId() ?: return
        val text = draft
        val selected = attachment
        val reply = replying
        val clientId = UUID.randomUUID().toString()
        val local = UiChatMessage("local-$clientId", uid, "You", text.trim().ifBlank { null }, if (selected != null) "image" else null, selected?.toString(), Instant.now().toString(), null, emptyMap(), clientId, reply?.id, reply?.body?.take(120), null, null, emptyList(), "sending")
        messages = messages + local; draft = ""; attachment = null; replying = null
        scope.launch {
            runCatching { chat.sendMessage(context, text, selected, clientId, reply?.id) }
                .onSuccess { sent -> messages = messages.map { if (it.clientId == clientId) sent.copy(replyPreview = local.replyPreview) else it }; refresh() }
                .onFailure { throwable -> messages = messages.map { if (it.clientId == clientId) it.copy(localState = "failed") else it }; error = throwable.localizedMessage ?: "Message failed." }
        }
    }

    Scaffold(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
        topBar = { TopAppBar(colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface), title = { Column { Text("TwoShare", style = MaterialTheme.typography.titleLarge); Text("Just the two of you", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary) } }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back to feed") } }, actions = {
            FilledTonalIconButton(onClick = { scope.launch { runCatching { repository.createOutgoingCall(false) }.onSuccess { onStartCall(it, false) } } }) { Icon(Icons.Default.Call, "Voice call") }
            Spacer(Modifier.width(5.dp)); FilledTonalIconButton(onClick = { scope.launch { runCatching { repository.createOutgoingCall(true) }.onSuccess { onStartCall(it, false) } } }) { Icon(Icons.Default.VideoCall, "Video call") }; Spacer(Modifier.width(8.dp))
        }) },
        bottomBar = { Surface(shadowElevation = 6.dp, color = MaterialTheme.colorScheme.surface) { Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp)) {
            replying?.let { Banner("Replying to ${it.senderName}", it.body.orEmpty()) { replying = null } }
            editing?.let { Banner("Editing message", it.body.orEmpty()) { editing = null; draft = "" } }
            attachment?.let { uri -> Row(Modifier.fillMaxWidth().padding(bottom = 5.dp), verticalAlignment = Alignment.CenterVertically) { AsyncImage(uri, "Selected image", Modifier.size(54.dp), contentScale = ContentScale.Crop); Spacer(Modifier.width(8.dp)); Text("Photo ready", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium); TextButton(onClick = { attachment = null }) { Text("Remove") } } }
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                IconButton(onClick = { mediaPicker.launch(arrayOf("image/*")) }, enabled = editing == null) { Icon(Icons.Default.Photo, "Choose image") }
                IconButton(onClick = ::openCamera, enabled = editing == null) { Icon(Icons.Default.CameraAlt, "Take photo") }
                OutlinedTextField(value = draft, onValueChange = { draft = it.take(2000) }, modifier = Modifier.weight(1f), placeholder = { Text(if (editing == null) "Message…" else "Edit message…") }, maxLines = 4, shape = RoundedCornerShape(24.dp))
                FilledIconButton(onClick = ::send, enabled = draft.isNotBlank() || (attachment != null && editing == null), modifier = Modifier.size(48.dp)) { Icon(Icons.Default.Send, "Send") }
            }
        } } }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            error?.let { Text(it, Modifier.padding(10.dp), color = MaterialTheme.colorScheme.error) }
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                itemsIndexed(timeline) { _, entry ->
                    val item = entry.second
                    if (item is UiChatMessage) {
                        if (item.id == unreadDividerId) UnreadDivider()
                        MessageRow(item, repository.currentUserId(), { beginReply(item) }, { editing = item; replying = null; draft = item.body.orEmpty() }, { emoji -> react(item, emoji) }, { failed -> messages = messages.filterNot { it.id == failed.id }; draft = failed.body.orEmpty() })
                    } else CallLine(item as CallRow, repository.currentUserId())
                }
            }
        }
    }
}

@Composable private fun UnreadDivider() { Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) { HorizontalDivider(Modifier.weight(1f), color = MaterialTheme.colorScheme.primary.copy(alpha = .45f)); Text("Unread messages", Modifier.padding(horizontal = 10.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary); HorizontalDivider(Modifier.weight(1f), color = MaterialTheme.colorScheme.primary.copy(alpha = .45f)) } }

@Composable private fun Banner(title: String, text: String, close: () -> Unit) { Surface(color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = .55f), shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)) { Row(Modifier.padding(9.dp), verticalAlignment = Alignment.CenterVertically) { Box(Modifier.width(3.dp).height(30.dp).background(MaterialTheme.colorScheme.primary, RoundedCornerShape(4.dp))); Spacer(Modifier.width(8.dp)); Column(Modifier.weight(1f)) { Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary); Text(text.take(90), maxLines = 1, style = MaterialTheme.typography.bodySmall) }; TextButton(onClick = close) { Text("×") } } } }

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageRow(message: UiChatMessage, uid: String?, reply: () -> Unit, edit: () -> Unit, react: (String) -> Unit, retry: (UiChatMessage) -> Unit) {
    val mine = message.senderId == uid
    var actions by remember(message.id) { mutableStateOf(false) }
    var edits by remember(message.id) { mutableStateOf(false) }
    var showTime by remember(message.id) { mutableStateOf(false) }
    var showEmojiPicker by remember(message.id) { mutableStateOf(false) }
    var dragX by remember(message.id) { mutableFloatStateOf(0f) }
    val x by animateFloatAsState(dragX, label = "reply")
    val receipt = when { message.localState == "sending" -> "Sending…"; message.localState == "failed" -> "Failed"; message.readAt != null -> "Seen"; message.deliveredAt != null -> "Delivered"; else -> "Sent" }
    val bubbleColor = if (mine) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh
    val textColor = if (mine) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
    val body = message.body.orEmpty()
    val pureEmojiCount = remember(body) { pureEmojiCount(body) }

    Box(Modifier.fillMaxWidth()) {
        if (dragX > 18f) Text("↩", modifier = Modifier.align(Alignment.CenterStart).padding(start = 8.dp), color = MaterialTheme.colorScheme.primary)
        Column(Modifier.fillMaxWidth().offset { IntOffset(x.roundToInt(), 0) }.pointerInput(message.id) { detectHorizontalDragGestures(onHorizontalDrag = { _, amount -> dragX = (dragX + amount).coerceIn(0f, 100f) }, onDragEnd = { if (dragX >= 58f) reply(); dragX = 0f }, onDragCancel = { dragX = 0f }) }, horizontalAlignment = if (mine) Alignment.End else Alignment.Start) {
            Surface(color = bubbleColor, shape = if (mine) RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp) else RoundedCornerShape(20.dp, 20.dp, 20.dp, 6.dp), modifier = Modifier.widthIn(max = 310.dp).combinedClickable(onClick = { showTime = !showTime }, onDoubleClick = { react("❤️") }, onLongClick = { actions = true })) {
                Column(Modifier.padding(horizontal = 13.dp, vertical = 8.dp)) {
                    message.replyPreview?.let { Surface(color = MaterialTheme.colorScheme.surface.copy(alpha = .55f), shape = RoundedCornerShape(9.dp), modifier = Modifier.padding(bottom = 5.dp)) { Text("↩ $it", Modifier.padding(horizontal = 8.dp, vertical = 5.dp), maxLines = 2, style = MaterialTheme.typography.bodySmall, color = textColor) } }
                    message.mediaUrl?.let { if (message.mediaType == "image") AsyncImage(it, "Shared photo", Modifier.widthIn(max = 280.dp).heightIn(max = 300.dp), contentScale = ContentScale.Crop) }
                    if (body.isNotBlank()) {
                        if (pureEmojiCount > 0) Text(body, fontSize = when { pureEmojiCount <= 3 -> 44.sp; pureEmojiCount <= 6 -> 34.sp; pureEmojiCount <= 12 -> 27.sp; else -> 22.sp }, lineHeight = when { pureEmojiCount <= 3 -> 50.sp; pureEmojiCount <= 6 -> 40.sp; else -> 31.sp }, color = textColor)
                        else Text(body, style = MaterialTheme.typography.bodyLarge, color = textColor)
                    }
                    if (message.editedAt != null) TextButton(onClick = { edits = !edits }, contentPadding = PaddingValues(0.dp), modifier = Modifier.height(22.dp), colors = ButtonDefaults.textButtonColors(contentColor = textColor.copy(alpha = .65f))) { Text(if (edits) "Hide edits" else "Edited", style = MaterialTheme.typography.labelSmall) }
                    if (edits) message.editHistory.asReversed().forEach { Text("Previous: ${it.body}", style = MaterialTheme.typography.bodySmall, color = textColor.copy(alpha = .65f)) }
                }
            }
            if (message.reactions.isNotEmpty()) Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surface, shadowElevation = 1.dp, modifier = Modifier.offset(y = (-5).dp).padding(end = 5.dp)) { Text(message.reactions.entries.joinToString(" ") { "${it.key} ${it.value.size}" }, Modifier.padding(horizontal = 7.dp, vertical = 2.dp), style = MaterialTheme.typography.labelMedium) }
            if (showTime || message.localState != "sent") Text((if (showTime) absoluteTime(message.createdAt) else "") + if (mine) { if (showTime) " · $receipt" else receipt } else "", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .62f), modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp))
            if (message.localState == "failed") TextButton(onClick = { retry(message) }) { Text("Retry") }
        }
        if (actions) AlertDialog(onDismissRequest = { actions = false }, title = { Text("React or message action") }, text = { Column { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) { reactionChoices.forEach { emoji -> Text(emoji, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.combinedClickable(onClick = { react(emoji); actions = false }, onLongClick = {})) }; TextButton(onClick = { actions = false; showEmojiPicker = true }, contentPadding = PaddingValues(horizontal = 7.dp)) { Text("+", style = MaterialTheme.typography.headlineSmall) } }; Spacer(Modifier.height(10.dp)); TextButton(onClick = { reply(); actions = false }, modifier = Modifier.fillMaxWidth()) { Text("Reply") }; if (mine && message.localState == "sent" && message.body != null) TextButton(onClick = { edit(); actions = false }, modifier = Modifier.fillMaxWidth()) { Text("Edit") } } }, confirmButton = {})
        if (showEmojiPicker) EmojiPickerDialog(onDismiss = { showEmojiPicker = false }, onEmoji = { react(it); showEmojiPicker = false })
    }
}

@Composable private fun EmojiPickerDialog(onDismiss: () -> Unit, onEmoji: (String) -> Unit) { var emoji by remember { mutableStateOf("") }; val focusRequester = remember { FocusRequester() }; AlertDialog(onDismissRequest = onDismiss, title = { Text("Choose any emoji") }, text = { Column { Text("Use your Android emoji keyboard to choose any emoji supported by your phone.", style = MaterialTheme.typography.bodySmall); Spacer(Modifier.height(10.dp)); OutlinedTextField(value = emoji, onValueChange = { emoji = it.take(16) }, modifier = Modifier.fillMaxWidth().focusRequester(focusRequester), singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text), placeholder = { Text("😀") }); LaunchedEffect(Unit) { delay(150); focusRequester.requestFocus() } } }, confirmButton = { TextButton(onClick = { if (emoji.isNotBlank()) onEmoji(emoji) }, enabled = emoji.isNotBlank()) { Text("React") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }) }

@Composable private fun CallLine(call: CallRow, uid: String?) { Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.Center) { Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = .7f)) { Text("${if (call.callerId == uid) "Outgoing" else "Incoming"} ${if (call.isVideo) "video" else "voice"} TwoLink · ${relative(call.createdAt)}", Modifier.padding(horizontal = 12.dp, vertical = 7.dp), style = MaterialTheme.typography.labelMedium) } } }

private fun saveCameraBitmap(context: Context, bitmap: Bitmap): Uri? = runCatching {
    val values = ContentValues().apply { put(MediaStore.Images.Media.DISPLAY_NAME, "TwoShare_${System.currentTimeMillis()}.jpg"); put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg") }
    val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return@runCatching null
    context.contentResolver.openOutputStream(uri)?.use { bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it) } ?: return@runCatching null
    uri
}.getOrNull()

private fun pureEmojiCount(text: String): Int {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return 0
    var count = 0
    var i = 0
    while (i < trimmed.length) {
        val cp = Character.codePointAt(trimmed, i)
        i += Character.charCount(cp)
        if (Character.isWhitespace(cp) || cp == 0xFE0F || cp == 0x200D || cp == 0x20E3 || cp in 0x1F3FB..0x1F3FF) continue
        val emoji = cp in 0x1F000..0x1FAFF || cp in 0x2600..0x27BF || cp in 0x2300..0x23FF || cp in 0x2B00..0x2BFF || cp in 0x1F1E6..0x1F1FF || cp == 0x00A9 || cp == 0x00AE || cp == 0x203C || cp == 0x2049 || cp == 0x2122 || cp == 0x2139 || cp == 0x3030 || cp == 0x303D || cp == 0x3297 || cp == 0x3299 || cp in 0x30..0x39 || cp == 0x23 || cp == 0x2A
        if (!emoji) return 0
        count++
    }
    return count
}

private fun epoch(value: String) = runCatching { OffsetDateTime.parse(value).toInstant().toEpochMilli() }.recoverCatching { Instant.parse(value).toEpochMilli() }.getOrDefault(0L)
private fun relative(value: String) = DateUtils.getRelativeTimeSpanString(epoch(value), System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()
private fun absoluteTime(value: String): String = runCatching { Instant.ofEpochMilli(epoch(value)).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("MMM d, h:mm a")) }.getOrDefault("")
