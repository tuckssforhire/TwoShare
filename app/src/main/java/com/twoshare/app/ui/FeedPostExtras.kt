package com.twoshare.app.ui

import android.text.format.DateUtils
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.twoshare.app.data.CommentThreadRepository
import com.twoshare.app.data.ThreadComment
import com.twoshare.app.data.TwoShareRepository
import com.twoshare.app.model.FeedPost
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.OffsetDateTime

object FeedCommentExpansion {
    private val expanded = mutableStateMapOf<String, Boolean>()
    fun isExpanded(postId: String): Boolean = expanded[postId] == true
    fun toggle(postId: String) { expanded[postId] = !(expanded[postId] == true) }
}

private fun commentTime(value: String): String = runCatching {
    DateUtils.getRelativeTimeSpanString(
        OffsetDateTime.parse(value).toInstant().toEpochMilli(),
        System.currentTimeMillis(),
        DateUtils.MINUTE_IN_MILLIS
    ).toString()
}.getOrDefault("")

@Composable
fun FeedCommentPreview(
    post: FeedPost,
    repository: TwoShareRepository,
    onComments: () -> Unit
) {
    val threadRepo = remember { CommentThreadRepository() }
    val scope = rememberCoroutineScope()
    var comments by remember(post.id) { mutableStateOf(emptyList<ThreadComment>()) }
    var draft by remember(post.id) { mutableStateOf("") }
    var replyTo by remember(post.id) { mutableStateOf<ThreadComment?>(null) }
    var sending by remember(post.id) { mutableStateOf(false) }
    var error by remember(post.id) { mutableStateOf<String?>(null) }
    val expanded = FeedCommentExpansion.isExpanded(post.id)

    suspend fun refresh() {
        runCatching { threadRepo.load(post.id) }
            .onSuccess { comments = it; error = null }
    }

    LaunchedEffect(post.id) {
        while (isActive) {
            refresh()
            delay(if (FeedCommentExpansion.isExpanded(post.id)) 4_000 else 10_000)
        }
    }

    if (comments.isNotEmpty() || expanded) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 7.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (!expanded && comments.size > 1) {
                Text(
                    "View all ${comments.size} comments",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.clickable { FeedCommentExpansion.toggle(post.id) }
                )
            }

            val shown = if (expanded) comments.filter { it.parentCommentId == null }
            else comments.takeLast(1)

            shown.forEach { comment ->
                CommentItem(comment, indent = false) {
                    replyTo = comment
                    if (!FeedCommentExpansion.isExpanded(post.id)) FeedCommentExpansion.toggle(post.id)
                }
                if (expanded) {
                    comments.filter { it.parentCommentId == comment.id }.forEach { reply ->
                        CommentItem(reply, indent = true) { replyTo = comment }
                    }
                }
            }

            if (expanded) {
                if (comments.isNotEmpty()) {
                    Text(
                        "Hide comments",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable { FeedCommentExpansion.toggle(post.id) }
                    )
                }
                replyTo?.let {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Replying to ${it.userName}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        Text("Cancel", style = MaterialTheme.typography.labelSmall, modifier = Modifier.clickable { replyTo = null })
                    }
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { if (it.length <= 1000) draft = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text(if (replyTo == null) "Write a comment…" else "Write a reply…") },
                        maxLines = 3,
                        shape = RoundedCornerShape(20.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    TextButton(
                        enabled = draft.isNotBlank() && !sending,
                        onClick = {
                            val text = draft
                            val parent = replyTo?.id
                            scope.launch {
                                sending = true
                                runCatching { threadRepo.add(post.id, text, parent) }
                                    .onSuccess { draft = ""; replyTo = null; refresh() }
                                    .onFailure { error = "Comment failed." }
                                sending = false
                            }
                        }
                    ) { Text(if (sending) "…" else "Post") }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall) }
            }
        }
    }
}

@Composable
private fun CommentItem(comment: ThreadComment, indent: Boolean, onReply: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = if (indent) 28.dp else 0.dp)) {
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            modifier = Modifier.weight(1f)
        ) {
            Column(Modifier.padding(horizontal = 11.dp, vertical = 8.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(comment.userName, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelLarge)
                    Text(commentTime(comment.createdAt), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(comment.text, style = MaterialTheme.typography.bodyMedium)
                Text("Reply", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 3.dp).clickable(onClick = onReply))
            }
        }
    }
}

@Composable
fun FeedReactionButton(
    currentReaction: String?,
    total: Int,
    choices: List<String>,
    onReact: (String) -> Unit
) {
    var quickOpen by remember { mutableStateOf(false) }
    var moreOpen by remember { mutableStateOf(false) }

    Box {
        TextButton(onClick = { quickOpen = true }) {
            Text(currentReaction ?: "♡")
            Spacer(Modifier.width(6.dp))
            Text(if (total == 0) "React" else total.toString())
        }
        DropdownMenu(expanded = quickOpen, onDismissRequest = { quickOpen = false }) {
            Row(Modifier.padding(horizontal = 8.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                choices.forEach { emoji ->
                    Text(emoji, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.clickable { quickOpen = false; onReact(emoji) }.padding(5.dp))
                }
                Text("＋", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.clickable { quickOpen = false; moreOpen = true }.padding(5.dp))
            }
        }
    }
    if (moreOpen) FeedEmojiPickerDialog({ moreOpen = false }) { emoji -> moreOpen = false; onReact(emoji) }
}
