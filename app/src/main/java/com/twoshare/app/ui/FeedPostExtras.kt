package com.twoshare.app.ui

import android.text.format.DateUtils
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.twoshare.app.data.CommentReactionRepository
import com.twoshare.app.data.CommentThreadRepository
import com.twoshare.app.data.FeedNotification
import com.twoshare.app.data.ThreadComment
import com.twoshare.app.data.TwoShareRepository
import com.twoshare.app.model.FeedPost
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.OffsetDateTime

private val compactReactionChoices = listOf("❤️", "😂", "😮", "😢", "👍", "🔥")

object FeedCommentExpansion {
    private val expanded = mutableStateMapOf<String, Boolean>()
    fun isExpanded(postId: String): Boolean = expanded[postId] == true
    fun toggle(postId: String) { expanded[postId] = !(expanded[postId] == true) }
    fun expand(postId: String) { expanded[postId] = true }
}

private fun commentTime(value: String): String = runCatching {
    DateUtils.getRelativeTimeSpanString(OffsetDateTime.parse(value).toInstant().toEpochMilli(), System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()
}.getOrDefault("")

@Composable
fun FeedNotificationsDialog(notifications: List<FeedNotification>, onDismiss: () -> Unit, onOpen: (FeedNotification) -> Unit, onMarkAllRead: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("Notifications"); if (notifications.any { !it.isRead }) Text("Mark all read", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.clickable { onMarkAllRead() }) } },
        text = {
            if (notifications.isEmpty()) Text("No Feed notifications yet.") else Column(Modifier.fillMaxWidth().heightIn(max = 480.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                notifications.take(30).forEach { item ->
                    Surface(shape = RoundedCornerShape(14.dp), color = if (item.isRead) MaterialTheme.colorScheme.surfaceContainerLow else MaterialTheme.colorScheme.primaryContainer.copy(alpha = .45f), modifier = Modifier.fillMaxWidth().clickable { onOpen(item) }) {
                        Column(Modifier.padding(11.dp)) { Text(item.text, fontWeight = if (item.isRead) FontWeight.Normal else FontWeight.SemiBold); Text(commentTime(item.createdAt), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun FeedCommentPreview(post: FeedPost, repository: TwoShareRepository, onComments: () -> Unit) {
    val threadRepo = remember { CommentThreadRepository() }
    val reactionRepo = remember { CommentReactionRepository() }
    val scope = rememberCoroutineScope()
    var comments by remember(post.id) { mutableStateOf(emptyList<ThreadComment>()) }
    var reactions by remember(post.id) { mutableStateOf<Map<String, Map<String, Set<String>>>>(emptyMap()) }
    var draft by remember(post.id) { mutableStateOf("") }
    var replyTo by remember(post.id) { mutableStateOf<ThreadComment?>(null) }
    var sending by remember(post.id) { mutableStateOf(false) }
    var error by remember(post.id) { mutableStateOf<String?>(null) }
    val bringIntoViewRequester = remember { BringIntoViewRequester() }
    val expanded = FeedCommentExpansion.isExpanded(post.id)
    val currentUid = repository.currentUserId()

    suspend fun refresh() {
        runCatching { threadRepo.load(post.id) }.onSuccess { loaded ->
            comments = loaded
            reactions = runCatching { reactionRepo.load(loaded.map { it.id }) }.getOrDefault(emptyMap())
            error = null
        }
    }

    fun react(commentId: String, emoji: String) {
        scope.launch {
            runCatching { reactionRepo.react(commentId, emoji) }
                .onSuccess { reactions = reactionRepo.load(comments.map { it.id }) }
                .onFailure { error = "Reaction failed." }
        }
    }

    LaunchedEffect(post.id) {
        while (isActive) { refresh(); delay(if (FeedCommentExpansion.isExpanded(post.id)) 4_000 else 10_000) }
    }

    if (comments.isNotEmpty() || expanded) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 7.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (!expanded && comments.size > 1) Text("View all ${comments.size} comments", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.clickable { FeedCommentExpansion.toggle(post.id) })
            val shown = if (expanded) comments.filter { it.parentCommentId == null } else comments.takeLast(1)
            shown.forEach { comment ->
                CommentItem(comment, false, reactions[comment.id].orEmpty(), currentUid, { replyTo = comment; if (!FeedCommentExpansion.isExpanded(post.id)) FeedCommentExpansion.toggle(post.id) }, { react(comment.id, it) })
                if (expanded) comments.filter { it.parentCommentId == comment.id }.forEach { reply -> CommentItem(reply, true, reactions[reply.id].orEmpty(), currentUid, { replyTo = comment }, { react(reply.id, it) }) }
            }
            if (expanded) {
                if (comments.isNotEmpty()) Text("Hide comments", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.clickable { FeedCommentExpansion.toggle(post.id) })
                replyTo?.let { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("Replying to ${it.userName}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary); Text("Cancel", style = MaterialTheme.typography.labelSmall, modifier = Modifier.clickable { replyTo = null }) } }
                Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    OutlinedTextField(value = draft, onValueChange = { if (it.length <= 1000) draft = it }, modifier = Modifier.weight(1f).bringIntoViewRequester(bringIntoViewRequester).onFocusChanged { state -> if (state.isFocused) scope.launch { delay(300); bringIntoViewRequester.bringIntoView() } }, placeholder = { Text(if (replyTo == null) "Write a comment…" else "Write a reply…") }, maxLines = 3, shape = RoundedCornerShape(20.dp))
                    Spacer(Modifier.width(6.dp))
                    TextButton(enabled = draft.isNotBlank() && !sending, onClick = { val text = draft; val parent = replyTo?.id; scope.launch { sending = true; runCatching { threadRepo.add(post.id, text, parent) }.onSuccess { draft = ""; replyTo = null; refresh() }.onFailure { error = "Comment failed." }; sending = false } }) { Text(if (sending) "…" else "Post") }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall) }
            }
        }
    }
}

@Composable
private fun CommentItem(comment: ThreadComment, indent: Boolean, reactions: Map<String, Set<String>>, currentUid: String?, onReply: () -> Unit, onReact: (String) -> Unit) {
    var quickOpen by remember(comment.id) { mutableStateOf(false) }; var moreOpen by remember(comment.id) { mutableStateOf(false) }
    val mine = reactions.entries.firstOrNull { currentUid != null && currentUid in it.value }?.key
    Row(Modifier.fillMaxWidth().padding(start = if (indent) 28.dp else 0.dp)) {
        Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.weight(1f)) {
            Column(Modifier.padding(horizontal = 11.dp, vertical = 8.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(comment.userName, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelLarge); Text(commentTime(comment.createdAt), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                Text(comment.text, style = MaterialTheme.typography.bodyMedium)
                if (reactions.isNotEmpty()) Text(reactions.entries.joinToString("  ") { "${it.key} ${it.value.size}" }, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 3.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box { Text(mine ?: "React", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 3.dp).clickable { quickOpen = true }); DropdownMenu(expanded = quickOpen, onDismissRequest = { quickOpen = false }) { Row(Modifier.padding(horizontal = 6.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) { compactReactionChoices.forEach { emoji -> Text(emoji, style = MaterialTheme.typography.titleLarge, modifier = Modifier.clickable { quickOpen = false; onReact(emoji) }.padding(4.dp)) }; Text("＋", style = MaterialTheme.typography.titleLarge, modifier = Modifier.clickable { quickOpen = false; moreOpen = true }.padding(4.dp)) } } }
                    Text("Reply", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 3.dp).clickable(onClick = onReply))
                }
            }
        }
    }
    if (moreOpen) FeedEmojiPickerDialog({ moreOpen = false }) { emoji -> moreOpen = false; onReact(emoji) }
}

@Composable
fun FeedReactionButton(currentReaction: String?, total: Int, choices: List<String>, onReact: (String) -> Unit) {
    var quickOpen by remember { mutableStateOf(false) }; var moreOpen by remember { mutableStateOf(false) }
    Box { TextButton(onClick = { quickOpen = true }) { Text(currentReaction ?: "♡"); Spacer(Modifier.width(6.dp)); Text(if (total == 0) "React" else total.toString()) }; DropdownMenu(expanded = quickOpen, onDismissRequest = { quickOpen = false }) { Row(Modifier.padding(horizontal = 6.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) { compactReactionChoices.forEach { emoji -> Text(emoji, style = MaterialTheme.typography.titleLarge, modifier = Modifier.clickable { quickOpen = false; onReact(emoji) }.padding(4.dp)) }; Text("＋", style = MaterialTheme.typography.titleLarge, modifier = Modifier.clickable { quickOpen = false; moreOpen = true }.padding(4.dp)) } } }
    if (moreOpen) FeedEmojiPickerDialog({ moreOpen = false }) { emoji -> moreOpen = false; onReact(emoji) }
}
