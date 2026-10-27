package com.twoshare.app.ui

import android.text.format.DateUtils
import android.widget.MediaController
import android.widget.VideoView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.viewinterop.AndroidView
import coil.compose.AsyncImage
import com.twoshare.app.data.TwoShareRepository
import com.twoshare.app.model.UiChatMessage
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.OffsetDateTime

private val quickReactions = listOf("👍", "❤️", "😂", "😮", "😢", "🔥")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessengerScreen(
    repository: TwoShareRepository,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    var messages by remember { mutableStateOf(emptyList<UiChatMessage>()) }
    var draft by remember { mutableStateOf("") }
    var attachment by remember { mutableStateOf<android.net.Uri?>(null) }
    var loading by remember { mutableStateOf(true) }
    var sending by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    suspend fun refresh() {
        runCatching { repository.loadMessages() }
            .onSuccess {
                messages = it
                error = null
            }
            .onFailure { error = it.localizedMessage ?: "Unable to load messages" }
        loading = false
    }

    LaunchedEffect(Unit) {
        refresh()
        while (isActive) {
            delay(2_000)
            refresh()
        }
    }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.lastIndex)
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
                    Column {
                        Text("Messenger")
                        Text(
                            "Private chat for two",
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text("‹ Back") }
                },
                actions = {
                    TextButton(onClick = { scope.launch { refresh() } }) {
                        Text("Refresh")
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
                                        error = it.localizedMessage ?: "Message failed"
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
                    color = MaterialTheme.colorScheme.error,
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

                messages.isEmpty() -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text("No messages yet. Say hello 👋")
                }

                else -> LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(messages, key = { it.id }) { message ->
                        MessageBubble(
                            message = message,
                            currentUid = repository.currentUserId(),
                            onReaction = { emoji ->
                                scope.launch {
                                    runCatching {
                                        repository.toggleMessageReaction(message, emoji)
                                    }.onSuccess {
                                        refresh()
                                    }.onFailure {
                                        error = it.localizedMessage ?: "Reaction failed"
                                    }
                                }
                            }
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

    val timeText = remember(message.createdAt) {
        runCatching {
            val millis = OffsetDateTime.parse(message.createdAt).toInstant().toEpochMilli()
            DateUtils.getRelativeTimeSpanString(
                millis,
                System.currentTimeMillis(),
                DateUtils.MINUTE_IN_MILLIS
            ).toString()
        }.getOrDefault("Recently")
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
            Text(timeText, style = MaterialTheme.typography.labelSmall)

            if (message.reactions.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
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

            Spacer(Modifier.height(5.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                quickReactions.forEach { emoji ->
                    TextButton(
                        onClick = { onReaction(emoji) },
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                        modifier = Modifier.height(34.dp)
                    ) {
                        Text(emoji)
                    }
                }
            }
        }
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
