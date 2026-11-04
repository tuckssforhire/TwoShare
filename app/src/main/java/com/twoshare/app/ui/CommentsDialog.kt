package com.twoshare.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
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
fun CommentsDialog(post: FeedPost, repository: TwoShareRepository, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var comments by remember(post.id) { mutableStateOf(emptyList<UiComment>()) }
    var text by remember(post.id) { mutableStateOf("") }
    var error by remember(post.id) { mutableStateOf<String?>(null) }
    var sending by remember(post.id) { mutableStateOf(false) }

    suspend fun refreshComments() {
        runCatching { repository.loadComments(post.id) }
            .onSuccess { comments = it; error = null }
            .onFailure { error = it.localizedMessage ?: "Unable to load comments" }
    }
    LaunchedEffect(post.id) { refreshComments(); while (isActive) { delay(3_000); refreshComments() } }

    AlertDialog(
        modifier = Modifier.imePadding(),
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(28.dp),
        title = {
            Column {
                Text("Comments", fontWeight = FontWeight.Bold)
                Text(post.userName, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            }
        },
        text = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                error?.let { Surface(shape=RoundedCornerShape(12.dp), color=MaterialTheme.colorScheme.errorContainer){Text(it,Modifier.padding(10.dp),color=MaterialTheme.colorScheme.onErrorContainer,style=MaterialTheme.typography.bodySmall)} }
                if (comments.isEmpty()) {
                    Surface(Modifier.fillMaxWidth(),shape=RoundedCornerShape(18.dp),color=MaterialTheme.colorScheme.surfaceContainerLow){
                        Column(Modifier.padding(22.dp),horizontalAlignment=Alignment.CenterHorizontally){Text("💬",style=MaterialTheme.typography.headlineMedium);Spacer(Modifier.height(6.dp));Text("Start the conversation",fontWeight=FontWeight.SemiBold);Text("No comments yet.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
                    }
                } else {
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 320.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(comments, key = { it.id }) { comment ->
                            Surface(shape=RoundedCornerShape(16.dp),color=MaterialTheme.colorScheme.surfaceContainerLow){
                                Column(Modifier.fillMaxWidth().padding(horizontal=12.dp,vertical=9.dp)){Text(comment.userName,fontWeight=FontWeight.SemiBold,style=MaterialTheme.typography.labelLarge,color=MaterialTheme.colorScheme.primary);Text(comment.text,style=MaterialTheme.typography.bodyMedium)}
                            }
                        }
                    }
                }
                OutlinedTextField(value=text,onValueChange={if(it.length<=500)text=it},modifier=Modifier.fillMaxWidth(),placeholder={Text("Write a comment…")},maxLines=4,shape=RoundedCornerShape(18.dp),supportingText={Text("${text.length} / 500")},trailingIcon={FilledIconButton(onClick={scope.launch{sending=true;error=null;runCatching{repository.addComment(post.id,text)}.onSuccess{text="";refreshComments()}.onFailure{error=it.localizedMessage?:"Comment failed"};sending=false}},enabled=text.isNotBlank()&&!sending){if(sending)CircularProgressIndicator(Modifier.size(18.dp),strokeWidth=2.dp)else Icon(Icons.Default.Send,"Send")}})
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Done") } }
    )
}
