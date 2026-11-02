package com.twoshare.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun MessageTemplateDialog(
    templates: List<String>,
    onUse: (String) -> Unit,
    onSave: (List<String>) -> Unit,
    onDismiss: () -> Unit
) {
    var working by remember(templates) { mutableStateOf(templates) }
    var editingIndex by remember { mutableIntStateOf(-1) }
    var editorText by remember { mutableStateOf("") }
    var showEditor by remember { mutableStateOf(false) }

    fun openNew() { editingIndex = -1; editorText = ""; showEditor = true }
    fun openEdit(index: Int) { editingIndex = index; editorText = working[index]; showEditor = true }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(28.dp),
        title = { Text("Message templates") },
        text = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Tap a template to put it in the message box. You can edit it before sending.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (working.isEmpty()) Text("No saved templates yet.", style = MaterialTheme.typography.bodyMedium)
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    itemsIndexed(working) { index, text ->
                        Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth().clickable { onUse(text) }) {
                            Row(Modifier.padding(start = 14.dp, top = 8.dp, bottom = 8.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(text, Modifier.weight(1f), maxLines = 3)
                                IconButton(onClick = { openEdit(index) }) { Icon(Icons.Default.Edit, "Edit template") }
                                IconButton(onClick = { working = working.toMutableList().also { it.removeAt(index) }; onSave(working) }) { Icon(Icons.Default.Delete, "Delete template") }
                            }
                        }
                    }
                }
                if (working.size < 10) FilledTonalButton(onClick = { openNew() }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.Add, null); Spacer(Modifier.width(6.dp)); Text("Add template") }
                Text("${working.size}/10 saved", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } }
    )

    if (showEditor) AlertDialog(
        onDismissRequest = { showEditor = false },
        title = { Text(if (editingIndex >= 0) "Edit template" else "New template") },
        text = { OutlinedTextField(editorText, { editorText = it.take(500) }, modifier = Modifier.fillMaxWidth(), minLines = 2, maxLines = 6, placeholder = { Text("Type your reusable message…") }) },
        confirmButton = {
            TextButton(enabled = editorText.isNotBlank(), onClick = {
                val clean = editorText.trim()
                working = if (editingIndex >= 0) working.toMutableList().also { it[editingIndex] = clean } else (working + clean).take(10)
                onSave(working)
                showEditor = false
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = { showEditor = false }) { Text("Cancel") } }
    )
}
