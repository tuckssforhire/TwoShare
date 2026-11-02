package com.twoshare.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
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

@Composable
fun FeedCommentPreview(
    post: FeedPost,
    repository: TwoShareRepository,
    onComments: () -> Unit
) {
    var comments by remember(post.id) { mutableStateOf(emptyList<UiComment>()) }

    LaunchedEffect(post.id) {
        while (isActive) {
            runCatching { repository.loadComments(post.id) }
                .onSuccess { comments = it }
            delay(5_000)
        }
    }

    if (comments.isNotEmpty()) {
        val latest = comments.last()
        Column(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onComments)
                .padding(horizontal = 14.dp, vertical = 7.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            if (comments.size > 1) {
                Text(
                    "View all ${comments.size} comments",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow
            ) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 11.dp, vertical = 8.dp)) {
                    Text(
                        latest.userName,
                        fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.labelLarge
                    )
                    Text(latest.text, style = MaterialTheme.typography.bodyMedium)
                }
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

        DropdownMenu(
            expanded = quickOpen,
            onDismissRequest = { quickOpen = false }
        ) {
            Row(
                Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                choices.forEach { emoji ->
                    Text(
                        emoji,
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier
                            .clickable {
                                quickOpen = false
                                onReact(emoji)
                            }
                            .padding(5.dp)
                    )
                }
                Text(
                    "＋",
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier
                        .clickable {
                            quickOpen = false
                            moreOpen = true
                        }
                        .padding(5.dp)
                )
            }
        }
    }

    if (moreOpen) {
        FeedEmojiPickerDialog(
            onDismiss = { moreOpen = false },
            onEmoji = { emoji ->
                moreOpen = false
                onReact(emoji)
            }
        )
    }
}
