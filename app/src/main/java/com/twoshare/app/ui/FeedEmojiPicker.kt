package com.twoshare.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

private val feedEmojiPickerChoices = listOf(
    "😀","😃","😄","😁","😆","😅","😂","🤣","😊","😇","🙂","🙃","😉","😍","🥰","😘",
    "😋","😎","🤩","🥳","😏","😢","😭","😤","😡","🤯","😱","😮","🤔","🫡","👍","👎",
    "👏","🙌","🙏","💪","👌","✌️","🤞","🫶","❤️","🩷","🧡","💛","💚","💙","💜","🤍",
    "💔","💕","💞","💓","💗","💖","💘","💝","🔥","✨","⭐","🎉","🎊","💯","🌹","😘",
    "🥹","🤗","😴","🤭","😜","🤪","😌"
)

@Composable
fun FeedEmojiPickerDialog(onDismiss: () -> Unit, onEmoji: (String) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Choose a reaction") },
        text = {
            LazyVerticalGrid(
                columns = GridCells.Fixed(6),
                modifier = Modifier.fillMaxWidth().heightIn(max = 330.dp),
                contentPadding = PaddingValues(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(feedEmojiPickerChoices) { emoji ->
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.aspectRatio(1f).clickable { onEmoji(emoji) }
                    ) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(emoji, style = MaterialTheme.typography.headlineSmall)
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
