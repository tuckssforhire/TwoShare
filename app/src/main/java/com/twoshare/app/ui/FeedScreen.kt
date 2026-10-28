package com.twoshare.app.ui

import android.content.Intent
import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.twoshare.app.R
import com.twoshare.app.data.TwoShareRepository
import com.twoshare.app.model.FeedPost
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.OffsetDateTime

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeedScreen(
    repository: TwoShareRepository,
    onOpenMessenger: () -> Unit,
    onSignedOut: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var posts by remember { mutableStateOf(emptyList<FeedPost>()) }
    var loading by remember { mutableStateOf(true) }
    var uploading by remember { mutableStateOf(false) }
    var uploadProgress by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var commentsPost by remember { mutableStateOf<FeedPost?>(null) }

    suspend fun refresh() {
        runCatching { repository.loadFeed() }
            .onSuccess {
                posts = it
                error = null
            }
            .onFailure { error = it.localizedMessage ?: "Unable to load photos" }
        loading = false
    }

    LaunchedEffect(Unit) {
        refresh()
        while (isActive) {
            delay(5_000)
            refresh()
        }
    }

    val picker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            uris.forEach { uri ->
                runCatching {
                    context.contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                }
            }

            scope.launch {
                uploading = true
                error = null
                var failed = 0
                var firstFailure: String? = null

                uris.forEachIndexed { index, uri ->
                    uploadProgress = "Uploading ${index + 1} of ${uris.size}"
                    runCatching { repository.uploadPhoto(context, uri) }
                        .onFailure {
                            failed += 1
                            if (firstFailure == null) {
                                firstFailure = it.localizedMessage ?: "Upload failed"
                            }
                        }
                }

                refresh()
                if (failed > 0) {
                    error = if (failed == uris.size) {
                        firstFailure ?: "Upload failed"
                    } else {
                        "$failed of ${uris.size} photos failed to upload. ${firstFailure.orEmpty()}".trim()
                    }
                }
                uploadProgress = null
                uploading = false
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
                            modifier = Modifier.size(34.dp)
                        )
                        Text("TwoShare")
                    }
                },
                actions = {
                    TextButton(onClick = onOpenMessenger) {
                        Text("Chat")
                    }
                    TextButton(onClick = { scope.launch { refresh() } }) {
                        Text("Refresh")
                    }
                    TextButton(onClick = {
                        scope.launch {
                            runCatching { repository.signOut() }
                            onSignedOut()
                        }
                    }) {
                        Text("Sign out")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    if (!uploading) picker.launch(arrayOf("image/*"))
                },
                modifier = Modifier.padding(bottom = 4.dp)
            ) {
                if (uploading) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(uploadProgress ?: "Uploading")
                    }
                } else {
                    Text("+ Photos")
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding)
        ) {
            error?.let {
                Text(
                    text = it,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    color = MaterialTheme.colorScheme.error
                )
            }

            when {
                loading -> Box(Modifier.fillMaxSize().padding(32.dp)) {
                    CircularProgressIndicator()
                }
                posts.isEmpty() -> Box(Modifier.fillMaxSize().padding(32.dp)) {
                    Text("No photos yet. Tap + Photos to share one or several photos.")
                }
                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 100.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(posts, key = { it.id }) { post ->
                        PostCard(
                            post = post,
                            currentUid = repository.currentUserId(),
                            onLike = {
                                scope.launch {
                                    runCatching { repository.toggleLike(post) }
                                        .onSuccess { refresh() }
                                        .onFailure { error = it.localizedMessage ?: "Like failed" }
                                }
                            },
                            onComments = { commentsPost = post }
                        )
                    }
                }
            }
        }
    }

    commentsPost?.let { post ->
        CommentsDialog(
            post = post,
            repository = repository,
            onDismiss = { commentsPost = null }
        )
    }
}

@Composable
private fun PostCard(
    post: FeedPost,
    currentUid: String?,
    onLike: () -> Unit,
    onComments: () -> Unit
) {
    val liked = currentUid != null && post.likedBy.contains(currentUid)
    val timeText = remember(post.createdAt) {
        runCatching {
            val millis = OffsetDateTime.parse(post.createdAt).toInstant().toEpochMilli()
            DateUtils.getRelativeTimeSpanString(
                millis,
                System.currentTimeMillis(),
                DateUtils.MINUTE_IN_MILLIS
            ).toString()
        }.getOrDefault("Recently")
    }

    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        shape = RoundedCornerShape(18.dp)
    ) {
        Column {
            Column(Modifier.padding(14.dp)) {
                Text(post.userName, fontWeight = FontWeight.SemiBold)
                Text(timeText, style = MaterialTheme.typography.labelSmall)
            }

            AsyncImage(
                model = post.imageUrl,
                contentDescription = "Shared photo by ${post.userName}",
                modifier = Modifier.fillMaxWidth().heightIn(min = 220.dp, max = 520.dp),
                contentScale = ContentScale.Crop
            )

            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                TextButton(onClick = onLike) {
                    Text(if (liked) "♥" else "♡")
                    Spacer(Modifier.width(6.dp))
                    Text(if (post.likedBy.isEmpty()) "Like" else "${post.likedBy.size}")
                }

                TextButton(onClick = onComments) {
                    Text("Comments")
                }
            }
        }
    }
}
