package com.twoshare.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.text.format.DateUtils
import android.widget.MediaController
import android.widget.VideoView
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.VideoCall
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import com.twoshare.app.R
import com.twoshare.app.data.TwoShareRepository
import com.twoshare.app.data.loadCallHistory
import com.twoshare.app.model.CallRow
import com.twoshare.app.model.UiChatMessage
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.OffsetDateTime

private val quickReactions = listOf("👍", "❤️", "😂", "😮", "😢", "🔥")

private sealed interface MessengerTimelineItem {
    val key: String
    val createdAt: String

    data class MessageEntry(val message: UiChatMessage) : MessengerTimelineItem {
        override val key: String = "message-${message.id}"
        override val createdAt: String = message.createdAt
    }

    data class CallEntry(val call: CallRow) : MessengerTimelineItem {
        override val key: String = "call-${call.id}"
        override val createdAt: String = call.createdAt
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessengerScreen(
    repository: TwoShareRepository,
    onBack: () -> Unit,
    onStartCall: (CallRow, Boolean) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    var messages by remember { mutableStateOf(emptyList<UiChatMessage>()) }
    var calls by remember { mutableStateOf(emptyList<CallRow>()) }
    var draft by remember { mutableStateOf("") }
    var attachment by remember { mutableStateOf<android.net.Uri?>(null) }
    var loading by remember { mutableStateOf(true) }
    var sending by remember { mutableStateOf(false) }
    var startingCall by remember { mutableStateOf(false) }
    var pendingCallVideo by remember { mutableStateOf<Boolean?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    val timeline = remember(messages, calls) {
        (messages.map { MessengerTimelineItem.MessageEntry(it) } +
            calls.map { MessengerTimelineItem.CallEntry(it) })
            .sortedBy { timelineEpochMillis(it.createdAt) }
    }

    fun friendlyError(throwable: Throwable, fallback: String): String {
        if (repository.isTransientNetworkError(throwable)) {
            return "Connection changed. Reconnecting…"
        }
        val raw = throwable.localizedMessage.orEmpty()
        return when {
            raw.isBlank() -> fallback
            "url=https://" in raw.lowercase() || raw.length > 220 -> fallback
            else -> raw.lineSequence().firstOrNull()?.take(180) ?: fallback
        }
    }

    fun requiredCallPermissions(video: Boolean): List<String> = buildList {
        add(Manifest.permission.RECORD_AUDIO)
        if (video) add(Manifest.permission.CAMERA)
    }

    fun beginOutgoingCall(video: Boolean) {
        if (startingCall) return
        scope.launch {
            startingCall = true
            error = null
            runCatching { repository.createOutgoingCall(video) }
                .onSuccess { call -> onStartCall(call, false) }
                .onFailure { error = friendlyError(it, "Unable to start TwoLink.") }
            startingCall = false
        }
    }

    val callPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val video = pendingCallVideo
        pendingCallVideo = null
        if (video != null) {
            val required = requiredCallPermissions(video)
            val granted = required.all { permission ->
                results[permission] == true ||
                    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
            }
            if (granted) {
                beginOutgoingCall(video)
            } else {
                error = if (video) {
                    "Allow microphone and camera access before starting a video TwoLink."
                } else {
                    "Allow microphone access before starting a voice TwoLink."
                }
            }
        }
    }

    fun startCall(video: Boolean) {
        if (startingCall || pendingCallVideo != null) return
        val missing = requiredCallPermissions(video).filter { permission ->
            ContextCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            beginOutgoingCall(video)
        } else {
            error = null
            pendingCallVideo = video
            callPermissionLauncher.launch(missing.toTypedArray())
        }
    }

    BackHandler { onBack() }

    suspend fun refresh() {
        runCatching { repository.markIncomingMessagesRead() }

        runCatching {
            val loadedMessages = repository.loadMessages()
            val loadedCalls = repository.loadCallHistory()
            loadedMessages to loadedCalls
        }.onSuccess { (loadedMessages, loadedCalls) ->
            messages = loadedMessages
            calls = loadedCalls
            error = null
        }.onFailure {
            error = friendlyError(it, "Unable to refresh Messenger.")
        }
        loading = false
    }

    LaunchedEffect(Unit) {
        refresh()
        while (isActive) {
            delay(2_000)
            refresh()
        }
    }

    LaunchedEffect(timeline.size) {
        if (timeline.isNotEmpty()) {
            listState.animateScrollToItem(timeline.lastIndex)
        }
    }

    val attachmentPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        attachment = uri
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Image(
                            painter = painterResource(R.drawable.twoshare_icon),
                            contentDescription = "TwoShare logo",
                            modifier = Modifier.size(32.dp)
                        )
                        Column {
                            Text("Messenger")
                            Text(
                                "Private chat for two",
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }
                },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text("← Back") }
                },
                actions = {
                    IconButton(onClick = { startCall(false) }, enabled = !startingCall && pendingCallVideo == null) {
                        Icon(Icons.Default.Call, contentDescription = "Start voice TwoLink")
                    }
                    IconButton(onClick = { startCall(true) }, enabled = !startingCall && pendingCallVideo == null) {
                        Icon(Icons.Default.VideoCall, contentDescription = "Start video TwoLink")
                    }
                    TextButton(onClick = { scope.launch { refresh() } }) {
                        Text("↻")
                    }
                }
            )
        },
        bottomBar = {
            Surface(shadowElevation = 8.dp) {
                Column(Modifier.fillMaxWidth().padding(10.dp)) {
                    if (attachment != null) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "Photo/video attached",
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.labelMedium
                            )
                            TextButton(onClick = { attachment = null }) {
                                Text("Remove")
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.Bottom
                    ) {
                        TextButton(
                            onClick = {
                                attachmentPicker.launch(arrayOf("image/*", "video/*"))
                            },
                            enabled = !sending
                        ) {
                            Text("＋ Media")
                        }

                        OutlinedTextField(
                            value = draft,
                            onValueChange = { draft = it.take(2000) },
                            modifier = Modifier.weight(1f),
                            placeholder = { Text("Message") },
                            maxLines = 4
                        )

                        Spacer(Modifier.width(8.dp))

                        Button(
                            onClick = {
                                scope.launch {
                                    sending = true
                                    error = null
                                    val selected = attachment
                                    runCatching {
                                        repository.sendMessage(context, draft, selected)
                                    }.onSuccess {
                                        draft = ""
                                        attachment = null
                                        refresh()
                                    }.onFailure {
                                        error = friendlyError(it, "Message failed.")
                                    }
                                    sending = false
                                }
                            },
                            enabled = !sending && (draft.isNotBlank() || attachment != null)
                        ) {
                            if (sending) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Text("Send")
                            }
                        }
                    }
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            error?.let {
                Text(
                    text = it,
                    color = if (it.startsWith("Connection changed")) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }

            when {
                loading -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }

                timeline.isEmpty() -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text("No messages or TwoLinks yet. Say hello 👋")
                }

                else -> LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(timeline, key = { it.key }) { item ->
                        when (item) {
                            is MessengerTimelineItem.MessageEntry -> {
                                MessageBubble(
                                    message = item.message,
                                    currentUid = repository.currentUserId(),
                                    onReaction = { emoji ->
                                        scope.launch {
                                            runCatching {
                                                repository.toggleMessageReaction(item.message, emoji)
                                            }.onSuccess {
                                                refresh()
                                            }.onFailure {
                                                error = friendlyError(it, "Reaction failed.")
                                            }
                                        }
                                    }
                                )
                            }

                            is MessengerTimelineItem.CallEntry -> {
                                CallHistoryCard(
                                    call = item.call,
                                    currentUid = repository.currentUserId()
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CallHistoryCard(
    call: CallRow,
    currentUid: String?
) {
    val outgoing = call.callerId == currentUid
    val mode = if (call.isVideo) "Video" else "Voice"
    val title = when {
        call.answeredAt != null -> "${if (outgoing) "Outgoing" else "Incoming"} TwoLink • $mode"
        call.status == "rejected" && outgoing -> "TwoLink declined • $mode"
        call.status == "rejected" -> "Declined TwoLink • $mode"
        call.status == "cancelled" && outgoing -> "Cancelled TwoLink • $mode"
        call.status == "cancelled" -> "Missed TwoLink • $mode"
        else -> "${if (outgoing) "Outgoing" else "Incoming"} TwoLink • $mode"
    }
    val duration = callDurationText(call)
    val timeText = relativeTimeText(call.createdAt)
    val detail = listOfNotNull(duration, timeText).joinToString(" • ")

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center
    ) {
        Surface(
            shape = RoundedCornerShape(18.dp),
            tonalElevation = 1.dp,
            modifier = Modifier.widthIn(max = 310.dp)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(
                    imageVector = if (call.isVideo) Icons.Default.VideoCall else Icons.Default.Call,
                    contentDescription = "$mode TwoLink",
                    tint = if (call.answeredAt == null && !outgoing) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.primary
                    }
                )
                Column {
                    Text(title, style = MaterialTheme.typography.labelLarge)
                    if (detail.isNotBlank()) {
                        Text(
                            detail,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MessageBubble(
    message: UiChatMessage,
    currentUid: String?,
    onReaction: (String) -> Unit
) {
    val mine = message.senderId == currentUid
    val bubbleColor = if (mine) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    var reactionPickerOpen by remember(message.id) { mutableStateOf(false) }

    val timeText = remember(message.createdAt) {
        relativeTimeText(message.createdAt)
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 330.dp)
                .background(bubbleColor, RoundedCornerShape(18.dp))
                .padding(10.dp)
        ) {
            if (!mine) {
                Text(
                    message.senderName,
                    style = MaterialTheme.typography.labelMedium
                )
                Spacer(Modifier.height(4.dp))
            }

            when (message.mediaType) {
                "image" -> message.mediaUrl?.let { url ->
                    AsyncImage(
                        model = url,
                        contentDescription = "Shared photo",
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 160.dp, max = 360.dp),
                        contentScale = ContentScale.Crop
                    )
                    Spacer(Modifier.height(8.dp))
                }

                "video" -> message.mediaUrl?.let { url ->
                    ChatVideo(url)
                    Spacer(Modifier.height(8.dp))
                }
            }

            message.body?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodyLarge)
            }

            Spacer(Modifier.height(5.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    timeText,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.weight(1f)
                )
                TextButton(
                    onClick = { reactionPickerOpen = !reactionPickerOpen },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                    modifier = Modifier.height(32.dp)
                ) {
                    Text(if (reactionPickerOpen) "Close" else "React")
                }
            }

            if (message.reactions.isNotEmpty()) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.padding(top = 4.dp)
                ) {
                    message.reactions.entries
                        .sortedBy { quickReactions.indexOf(it.key).let { i -> if (i < 0) 99 else i } }
                        .forEach { (emoji, users) ->
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                tonalElevation = if (currentUid in users) 3.dp else 0.dp
                            ) {
                                Text(
                                    "$emoji ${users.size}",
                                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                                    style = MaterialTheme.typography.labelMedium
                                )
                            }
                        }
                }
            }

            if (reactionPickerOpen) {
                Surface(
                    modifier = Modifier.padding(top = 6.dp),
                    shape = RoundedCornerShape(18.dp),
                    tonalElevation = 2.dp
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                        horizontalArrangement = Arrangement.spacedBy(1.dp)
                    ) {
                        quickReactions.forEach { emoji ->
                            TextButton(
                                onClick = {
                                    onReaction(emoji)
                                    reactionPickerOpen = false
                                },
                                contentPadding = PaddingValues(horizontal = 5.dp, vertical = 0.dp),
                                modifier = Modifier.height(36.dp)
                            ) {
                                Text(emoji)
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun timelineEpochMillis(value: String): Long = runCatching {
    OffsetDateTime.parse(value).toInstant().toEpochMilli()
}.getOrDefault(0L)

private fun relativeTimeText(value: String): String = runCatching {
    val millis = OffsetDateTime.parse(value).toInstant().toEpochMilli()
    DateUtils.getRelativeTimeSpanString(
        millis,
        System.currentTimeMillis(),
        DateUtils.MINUTE_IN_MILLIS
    ).toString()
}.getOrDefault("Recently")

private fun callDurationText(call: CallRow): String? {
    val answered = call.answeredAt ?: return null
    val ended = call.endedAt ?: return "In progress"
    val seconds = runCatching {
        Duration.between(
            OffsetDateTime.parse(answered).toInstant(),
            OffsetDateTime.parse(ended).toInstant()
        ).seconds.coerceAtLeast(0)
    }.getOrNull() ?: return null

    val minutes = seconds / 60
    val remainingSeconds = seconds % 60
    return if (minutes > 0) {
        "%d:%02d".format(minutes, remainingSeconds)
    } else {
        "$seconds sec"
    }
}

@Composable
private fun ChatVideo(url: String) {
    AndroidView(
        factory = { context ->
            VideoView(context).apply {
                val controls = MediaController(context)
                controls.setAnchorView(this)
                setMediaController(controls)
                setVideoPath(url)
                setOnPreparedListener { player ->
                    player.isLooping = false
                    seekTo(1)
                }
            }
        },
        update = { view ->
            if (view.tag != url) {
                view.tag = url
                view.setVideoPath(url)
            }
        },
        modifier = Modifier
            .fillMaxWidth()
            .height(220.dp)
    )
}
