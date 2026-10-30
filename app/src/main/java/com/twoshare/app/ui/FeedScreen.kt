package com.twoshare.app.ui

import android.content.Intent
import android.net.Uri
import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
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
import com.twoshare.app.data.AuthManager
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
    authManager: AuthManager,
    onOpenMessenger: () -> Unit,
    onSignedOut: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var posts by remember { mutableStateOf(emptyList<FeedPost>()) }
    var unreadCount by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var uploading by remember { mutableStateOf(false) }
    var uploadProgress by remember { mutableStateOf<String?>(null) }
    var pendingUploadUris by remember { mutableStateOf(emptyList<Uri>()) }
    var composerError by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var commentsPost by remember { mutableStateOf<FeedPost?>(null) }
    var viewerPost by remember { mutableStateOf<FeedPost?>(null) }
    var viewerIndex by remember { mutableStateOf(0) }
    var editPost by remember { mutableStateOf<FeedPost?>(null) }
    var showAccountSettings by remember { mutableStateOf(false) }

    fun friendlyError(throwable: Throwable, fallback: String): String {
        val raw = throwable.localizedMessage.orEmpty()
        return when {
            raw.isBlank() -> fallback
            "url=https://" in raw.lowercase() || raw.length > 220 -> fallback
            else -> raw.lineSequence().firstOrNull()?.take(180) ?: fallback
        }
    }

    suspend fun refresh() {
        runCatching { repository.loadFeed() }
            .onSuccess {
                posts = it
                error = null
            }
            .onFailure {
                if (!repository.isTransientNetworkError(it)) {
                    error = friendlyError(it, "Unable to refresh photos.")
                }
            }

        runCatching { repository.unreadMessageCount() }
            .onSuccess { unreadCount = it }

        loading = false
    }

    LaunchedEffect(Unit) {
        refresh()
        while (isActive) {
            delay(3_000)
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
            composerError = null
            pendingUploadUris = uris
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        modifier = Modifier.clickable { showAccountSettings = true },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Image(
                            painter = painterResource(R.drawable.twoshare_icon),
                            contentDescription = "TwoShare account settings",
                            modifier = Modifier.size(34.dp)
                        )
                        Text("TwoShare")
                    }
                },
                actions = {
                    BadgedBox(
                        badge = {
                            if (unreadCount > 0) {
                                Badge {
                                    Text(if (unreadCount > 99) "99+" else unreadCount.toString())
                                }
                            }
                        }
                    ) {
                        TextButton(onClick = onOpenMessenger) { Text("Chat") }
                    }
                    TextButton(onClick = {
                        scope.launch {
                            runCatching { repository.signOut() }
                            onSignedOut()
                        }
                    }) { Text("Sign out") }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    if (!uploading && pendingUploadUris.isEmpty()) {
                        picker.launch(arrayOf("image/*"))
                    }
                },
                modifier = Modifier.padding(bottom = 4.dp)
            ) {
                if (uploading) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(uploadProgress ?: "Uploading")
                    }
                } else {
                    Text("+ Photos")
                }
            }
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            error?.let {
                Text(
                    it,
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
                                        .onFailure {
                                            error = friendlyError(it, "Like failed.")
                                        }
                                }
                            },
                            onComments = { commentsPost = post },
                            onEdit = { editPost = post }
                        )
                    }
                }
            }
        }
    }

    if (pendingUploadUris.isNotEmpty()) {
        NewPostDialog(
            photoCount = pendingUploadUris.size,
            uploading = uploading,
            error = composerError,
            onDismiss = {
                if (!uploading) {
                    pendingUploadUris = emptyList()
                    composerError = null
                }
            },
            onPost = { caption ->
                val selectedUris = pendingUploadUris
                scope.launch {
                    uploading = true
                    composerError = null
                    uploadProgress = if (selectedUris.size == 1) {
                        "Uploading photo"
                    } else {
                        "Uploading ${selectedUris.size} photos"
                    }

                    runCatching {
                        repository.uploadPhotos(
                            context = context,
                            imageUris = selectedUris,
                            caption = caption
                        )
                    }.onSuccess {
                        pendingUploadUris = emptyList()
                        composerError = null
                        refresh()
                    }.onFailure {
                        composerError = friendlyError(it, "Upload failed.")
                    }

                    uploadProgress = null
                    uploading = false
                }
            }
        )
    }

    if (showAccountSettings) {
        AccountSettingsDialog(
            authManager = authManager,
            onDismiss = { showAccountSettings = false }
        )
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
    editPost?.let { post ->
        PostEditorDialog(
            post = post,
            repository = repository,
            onDismiss = { editPost = null },
            onChanged = { scope.launch { refresh() } },
            onDeleted = { scope.launch { refresh() } }
        )
    }
}

@Composable
private fun NewPostDialog(
    photoCount: Int,
    uploading: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onPost: (String) -> Unit
) {
    var caption by remember(photoCount) { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New post") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    if (photoCount == 1) "1 photo selected" else "$photoCount photos selected",
                    style = MaterialTheme.typography.bodyMedium
                )
                OutlinedTextField(
                    value = caption,
                    onValueChange = { if (it.length <= 2000) caption = it },
                    enabled = !uploading,
                    label = { Text("Caption (optional)") },
                    placeholder = { Text("Write something about these photos…") },
                    minLines = 3,
                    maxLines = 6,
                    supportingText = {
                        Text("${caption.length} / 2000")
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                error?.let {
                    Text(
                        it,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onPost(caption) },
                enabled = !uploading
            ) {
                if (uploading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Posting")
                } else {
                    Text("Post")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !uploading) {
                Text("Cancel")
            }
        }
    )
}

@Composable
private fun PostCard(
    post: FeedPost,
    currentUid: String?,
    onImageClick: (Int) -> Unit,
    onLike: () -> Unit,
    onComments: () -> Unit,
    onEdit: () -> Unit
) {
    val liked = currentUid != null && post.likedBy.contains(currentUid)
    val ownPost = currentUid != null && currentUid == post.userId
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

            GalleryPreview(post.imageUrls, onImageClick)

            post.caption?.takeIf { it.isNotBlank() }?.let { caption ->
                Text(
                    text = caption,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                TextButton(onClick = onLike) {
                    Text(if (liked) "♥" else "♡")
                    Spacer(Modifier.width(6.dp))
                    Text(if (post.likedBy.isEmpty()) "Like" else "${post.likedBy.size}")
                }
                TextButton(onClick = onComments) { Text("Comments") }
                if (ownPost) {
                    TextButton(onClick = onEdit) { Text("Edit") }
                }
            }
        }
    }
}

@Composable
private fun GalleryPreview(urls: List<String>, onImageClick: (Int) -> Unit) {
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PhotoViewerDialog(
    post: FeedPost,
    initialIndex: Int,
    onDismiss: () -> Unit
) {
    if (post.imageUrls.isEmpty()) return
    val pagerState = rememberPagerState(
        initialPage = initialIndex.coerceIn(0, post.imageUrls.lastIndex),
        pageCount = { post.imageUrls.size }
    )

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize()
            ) { page ->
                AsyncImage(
                    model = post.imageUrls[page],
                    contentDescription = "Photo ${page + 1} of ${post.imageUrls.size}",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit
                )
            }

            Button(
                onClick = onDismiss,
                modifier = Modifier.align(Alignment.TopStart).padding(16.dp)
            ) { Text("← Back") }

            Surface(
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 24.dp),
                color = Color.Black.copy(alpha = 0.65f),
                shape = RoundedCornerShape(16.dp)
            ) {
                Text(
                    "${pagerState.currentPage + 1} / ${post.imageUrls.size}",
                    color = Color.White,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }

            if (post.imageUrls.size > 1) {
                Surface(
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 18.dp),
                    color = Color.Black.copy(alpha = 0.55f),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Text(
                        "Swipe left or right",
                        color = Color.White,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp)
                    )
                }
            }
        }
    }
}
