package com.twoshare.app.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.twoshare.app.data.TwoShareRepository
import com.twoshare.app.model.FeedPost
import kotlinx.coroutines.launch

@Composable
fun PostEditorDialog(
    post: FeedPost,
    repository: TwoShareRepository,
    onDismiss: () -> Unit,
    onChanged: () -> Unit,
    onDeleted: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var caption by remember(post.id) { mutableStateOf(post.caption.orEmpty()) }
    var removedPaths by remember(post.id) { mutableStateOf(emptySet<String>()) }
    var pendingUris by remember(post.id) { mutableStateOf(emptyList<Uri>()) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }

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
            pendingUris = (pendingUris + uris).distinct()
        }
    }

    val keptExisting = post.imagePaths.count { it !in removedPaths }
    val finalCount = keptExisting + pendingUris.size
    val captionChanged = caption.trim() != post.caption.orEmpty().trim()
    val hasChanges = removedPaths.isNotEmpty() || pendingUris.isNotEmpty() || captionChanged

    Dialog(
        onDismissRequest = { if (!saving) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .fillMaxHeight(0.9f),
            shape = RoundedCornerShape(24.dp),
            tonalElevation = 6.dp
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                Text("Edit post", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "Change the caption, add or remove photos, or delete the whole post.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                error?.let {
                    Text(
                        it,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }

                Spacer(Modifier.height(12.dp))

                OutlinedTextField(
                    value = caption,
                    onValueChange = { if (it.length <= 2000) caption = it },
                    enabled = !saving,
                    label = { Text("Caption (optional)") },
                    placeholder = { Text("Write something about these photos…") },
                    minLines = 2,
                    maxLines = 5,
                    supportingText = { Text("${caption.length} / 2000") },
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(12.dp))

                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    item {
                        Text("Current photos", style = MaterialTheme.typography.titleMedium)
                    }

                    itemsIndexed(post.imagePaths, key = { _, path -> path }) { index, path ->
                        val removed = path in removedPaths
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            AsyncImage(
                                model = post.imageUrls.getOrNull(index),
                                contentDescription = "Post photo ${index + 1}",
                                modifier = Modifier.size(88.dp),
                                contentScale = ContentScale.Crop,
                                alpha = if (removed) 0.35f else 1f
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Photo ${index + 1}")
                                if (removed) {
                                    Text(
                                        "Will be removed",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                            TextButton(
                                enabled = !saving,
                                onClick = {
                                    removedPaths = if (removed) {
                                        removedPaths - path
                                    } else {
                                        removedPaths + path
                                    }
                                }
                            ) {
                                Text(if (removed) "Restore" else "Remove")
                            }
                        }
                    }

                    if (pendingUris.isNotEmpty()) {
                        item {
                            Text(
                                "New photos",
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.padding(top = 8.dp)
                            )
                        }
                    }

                    itemsIndexed(pendingUris, key = { _, uri -> uri.toString() }) { index, uri ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            AsyncImage(
                                model = uri,
                                contentDescription = "New photo ${index + 1}",
                                modifier = Modifier.size(88.dp),
                                contentScale = ContentScale.Crop
                            )
                            Text("New photo ${index + 1}", modifier = Modifier.weight(1f))
                            TextButton(
                                enabled = !saving,
                                onClick = { pendingUris = pendingUris - uri }
                            ) {
                                Text("Remove")
                            }
                        }
                    }
                }

                if (finalCount == 0) {
                    Text(
                        "A post needs at least one photo. Use Delete post if you want to remove it completely.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(vertical = 6.dp)
                    )
                }

                OutlinedButton(
                    onClick = { picker.launch(arrayOf("image/*")) },
                    enabled = !saving,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("+ Add photos")
                }

                Spacer(Modifier.height(8.dp))

                Button(
                    onClick = {
                        scope.launch {
                            saving = true
                            error = null
                            runCatching {
                                repository.editPostPhotos(
                                    context = context,
                                    post = post,
                                    removedPaths = removedPaths,
                                    addedUris = pendingUris,
                                    caption = caption
                                )
                            }.onSuccess {
                                onChanged()
                                onDismiss()
                            }.onFailure {
                                error = it.localizedMessage ?: "Unable to update the post."
                            }
                            saving = false
                        }
                    },
                    enabled = !saving && finalCount > 0 && hasChanges,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (saving) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        Text("Save changes")
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(
                        enabled = !saving,
                        onClick = { confirmDelete = true }
                    ) {
                        Text("Delete post", color = MaterialTheme.colorScheme.error)
                    }
                    TextButton(enabled = !saving, onClick = onDismiss) {
                        Text("Cancel")
                    }
                }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { if (!saving) confirmDelete = false },
            title = { Text("Delete this post?") },
            text = {
                Text(
                    "The post, its likes, comments, caption, and uploaded photos will be removed. This cannot be undone."
                )
            },
            confirmButton = {
                TextButton(
                    enabled = !saving,
                    onClick = {
                        scope.launch {
                            saving = true
                            error = null
                            runCatching { repository.deletePost(post) }
                                .onSuccess {
                                    confirmDelete = false
                                    onDeleted()
                                    onDismiss()
                                }
                                .onFailure {
                                    error = it.localizedMessage ?: "Unable to delete the post."
                                    confirmDelete = false
                                }
                            saving = false
                        }
                    }
                ) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(
                    enabled = !saving,
                    onClick = { confirmDelete = false }
                ) { Text("Cancel") }
            }
        )
    }
}
