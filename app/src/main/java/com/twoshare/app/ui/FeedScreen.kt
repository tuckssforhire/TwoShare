package com.twoshare.app.ui

import android.content.Intent
import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
    var viewerPost by remember { mutableStateOf<FeedPost?>(null) }
    var viewerIndex by remember { mutableStateOf(0) }

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
                uploadProgress = if (uris.size == 1) "Uploading photo" else "Uploading ${uris.size} photos"

                runCatching { repository.uploadPhotos(context, uris) }
                    .onSuccess { refresh() }
                    .onFailure { error = it.localizedMessage ?: "Upload failed" }

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
                            onImageClick = { index ->
                                viewerPost = post
                                viewerIndex = index
                            },
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

    viewerPost?.let { post ->
        PhotoViewerDialog(
            post = post,
            initialIndex = viewerIndex,
            onDismiss = { viewerPost = null }
        )
    }
}

@Composable
private fun PostCard(
    post: FeedPost,
    currentUid: String?,
    onImageClick: (Int) -> Unit,
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
                if (post.imageUrls.size > 1) {
                    Text(
                        "${post.imageUrls.size} photos",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            GalleryPreview(
                urls = post.imageUrls,
                onImageClick = onImageClick
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

@Composable
private fun GalleryPreview(
    urls: List<String>,
    onImageClick: (Int) -> Unit
) {
    if (urls.isEmpty()) return

    if (urls.size == 1) {
        AsyncImage(
            model = urls.first(),
            contentDescription = "Shared photo",
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 220.dp, max = 520.dp)
                .clickable { onImageClick(0) },
            contentScale = ContentScale.Crop
        )
        return
    }

    val visible = urls.take(4)
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        visible.chunked(2).forEachIndexed { rowIndex, rowUrls ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                rowUrls.forEachIndexed { columnIndex, url ->
                    val index = rowIndex * 2 + columnIndex
                    val extraCount = if (index == 3) urls.size - 4 else 0
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .aspectRatio(1f)
                            .clickable { onImageClick(index) }
                    ) {
                        AsyncImage(
                            model = url,
                            contentDescription = "Gallery photo ${index + 1}",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                        if (extraCount > 0) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(Color.Black.copy(alpha = 0.55f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    "+$extraCount",
                                    color = Color.White,
                                    style = MaterialTheme.typography.headlineMedium
                                )
                            }
                        }
                    }
                }
                if (rowUrls.size == 1) {
                    Spacer(Modifier.weight(1f).aspectRatio(1f))
                }
            }
        }
    }
}

@Composable
private fun PhotoViewerDialog(
    post: FeedPost,
    initialIndex: Int,
    onDismiss: () -> Unit
) {
    if (post.imageUrls.isEmpty()) return
    var index by remember(post.id, initialIndex) {
        mutableStateOf(initialIndex.coerceIn(0, post.imageUrls.lastIndex))
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            AsyncImage(
                model = post.imageUrls[index],
                contentDescription = "Photo ${index + 1} of ${post.imageUrls.size}",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit
            )

            Button(
                onClick = onDismiss,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(16.dp)
            ) {
                Text("← Back")
            }

            Surface(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 24.dp),
                color = Color.Black.copy(alpha = 0.65f),
                shape = RoundedCornerShape(16.dp)
            ) {
                Text(
                    "${index + 1} / ${post.imageUrls.size}",
                    color = Color.White,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }

            if (post.imageUrls.size > 1) {
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(18.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = { if (index > 0) index-- },
                        enabled = index > 0,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Previous")
                    }
                    OutlinedButton(
                        onClick = { if (index < post.imageUrls.lastIndex) index++ },
                        enabled = index < post.imageUrls.lastIndex,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Next")
                    }
                }
            }
        }
    }
}
