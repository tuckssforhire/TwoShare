package com.twoshare.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.twoshare.app.data.TwoShareRepository
import com.twoshare.app.model.FeedPost
import com.twoshare.app.model.UiComment
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@Composable
fun CommentsDialog(
    post: FeedPost,
    repository: TwoShareRepository,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var comments by remember(post.id) { mutableStateOf(emptyList<UiComment>()) }
    var text by remember(post.id) { mutableStateOf("") }
    var error by remember(post.id) { mutableStateOf<String?>(null) }
    var sending by remember(post.id) { mutableStateOf(false) }

    suspend fun refreshComments() {
        runCatching { repository.loadComments(post.id) }
            .onSuccess {
                comments = it
                error = null
            }
            .onFailure { error = it.localizedMessage ?: "Unable to load comments" }
    }

    LaunchedEffect(post.id) {
        refreshComments()
        while (isActive) {
            delay(3_000)
            refreshComments()
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Comments") },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.height(8.dp))
                }

                if (comments.isEmpty()) {
                    Text("No comments yet.", style = MaterialTheme.typography.bodyMedium)
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(comments, key = { it.id }) { comment ->
                            Column {
                                Text(comment.userName, fontWeight = FontWeight.SemiBold)
                                Text(comment.text)
                            }
                        }
                    }
                }

                Spacer(Modifier.height(14.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { if (it.length <= 500) text = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Add a comment…") },
                    maxLines = 4
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    scope.launch {
                        sending = true
                        error = null
                        runCatching { repository.addComment(post.id, text) }
                            .onSuccess {
                                text = ""
                                refreshComments()
                            }
                            .onFailure { error = it.localizedMessage ?: "Comment failed" }
                        sending = false
                    }
                },
                enabled = text.isNotBlank() && !sending
            ) {
                Text(if (sending) "Sending…" else "Send")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}
