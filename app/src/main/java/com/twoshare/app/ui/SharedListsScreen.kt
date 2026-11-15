package com.twoshare.app.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.twoshare.app.data.LiveUpdates
import com.twoshare.app.data.SharedList
import com.twoshare.app.data.SharedListItem
import com.twoshare.app.data.SharedListsRepository
import com.twoshare.app.data.TwoShareRepository
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SharedListsScreen(repository: TwoShareRepository) {
    val scope = rememberCoroutineScope()
    val repo = remember(repository) { SharedListsRepository(repository) }
    val cached = SharedListsRepository.lastSnapshot
    var lists by remember { mutableStateOf(cached?.lists.orEmpty()) }
    var items by remember { mutableStateOf(cached?.items.orEmpty()) }
    var names by remember { mutableStateOf(cached?.names.orEmpty()) }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(cached == null) }
    var error by remember { mutableStateOf<String?>(null) }
    var draft by remember { mutableStateOf("") }
    var showNewList by remember { mutableStateOf(false) }
    var listMenu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<SharedList?>(null) }
    var showCompleted by rememberSaveable { mutableStateOf(false) }
    val uid = repo.currentUserId()

    suspend fun refresh() {
        runCatchingActive { repo.load() }
            .onSuccess { snap -> lists = snap.lists; items = snap.items; names = snap.names; error = null }
            .onFailure { if (!repository.isTransientNetworkError(it) || lists.isEmpty()) error = friendlyListError(it) }
        loading = false
    }

    // Live: refresh as soon as either of you changes a list; slow poll as a backup.
    LaunchedEffect(Unit) {
        var seen = LiveUpdates.version(LiveUpdates.LIST_TABLES)
        refresh()
        while (isActive) {
            seen = LiveUpdates.awaitChange(LiveUpdates.LIST_TABLES, seen, 120_000L, 30_000L)
            refresh()
        }
    }

    val selected = lists.firstOrNull { it.id == selectedId } ?: lists.firstOrNull()
    val listItems = items.filter { it.listId == selected?.id }
    val open = listItems.filter { !it.done }
    val done = listItems.filter { it.done }.sortedByDescending { it.doneAt ?: it.createdAt }

    fun act(rollback: () -> Unit, block: suspend () -> Unit) {
        scope.launch {
            runCatchingActive { block() }
                .onSuccess { refresh() }
                .onFailure { rollback(); error = friendlyListError(it) }
        }
    }

    fun addDraft() {
        val list = selected ?: return
        val text = draft.trim()
        if (text.isEmpty()) return
        val temp = SharedListItem(id = "local-" + UUID.randomUUID(), listId = list.id, text = text.take(200), addedBy = uid ?: "", createdAt = java.time.Instant.now().toString())
        val before = items
        items = items + temp
        draft = ""
        act({ items = before }) { repo.addItem(list.id, text) }
    }

    fun toggle(item: SharedListItem) {
        if (item.id.startsWith("local-")) return
        val before = items
        val now = java.time.Instant.now().toString()
        items = items.map { if (it.id == item.id) it.copy(done = !item.done, doneBy = if (!item.done) uid else null, doneAt = if (!item.done) now else null) else it }
        act({ items = before }) { repo.setDone(item, !item.done) }
    }

    fun remove(item: SharedListItem) {
        if (item.id.startsWith("local-")) return
        val before = items
        items = items.filterNot { it.id == item.id }
        act({ items = before }) { repo.deleteItem(item) }
    }

    Scaffold(
        modifier = Modifier.imePadding(),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
                title = {
                    Column {
                        Text("Our lists", fontWeight = FontWeight.Bold)
                        Text(
                            if (selected == null) "Shared with your partner" else "${open.size} to do" + if (done.isNotEmpty()) " · ${done.size} done" else "",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                },
                actions = {
                    if (selected != null) Box {
                        IconButton(onClick = { listMenu = true }) { Icon(Icons.Default.MoreVert, "List options") }
                        DropdownMenu(listMenu, { listMenu = false }) {
                            DropdownMenuItem(text = { Text("Clear completed") }, enabled = done.isNotEmpty(), onClick = {
                                listMenu = false
                                val before = items
                                items = items.filterNot { it.listId == selected.id && it.done }
                                act({ items = before }) { repo.clearCompleted(selected.id) }
                            })
                            DropdownMenuItem(text = { Text("Delete list", color = MaterialTheme.colorScheme.error) }, onClick = { listMenu = false; confirmDelete = selected })
                        }
                    }
                }
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            // List picker
            LazyRow(
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(lists, key = { it.id }) { list ->
                    val remaining = items.count { it.listId == list.id && !it.done }
                    FilterChip(
                        selected = list.id == selected?.id,
                        onClick = { selectedId = list.id },
                        label = { Text("${list.emoji} ${list.title}" + if (remaining > 0) "  $remaining" else "", maxLines = 1) },
                        shape = RoundedCornerShape(50)
                    )
                }
                item(key = "new") {
                    AssistChip(onClick = { showNewList = true }, label = { Text("New list") }, leadingIcon = { Icon(Icons.Default.Add, null, Modifier.size(18.dp)) }, shape = RoundedCornerShape(50))
                }
            }

            error?.let {
                Surface(Modifier.fillMaxWidth().padding(horizontal = 12.dp), shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.errorContainer) {
                    Row(Modifier.padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(it, Modifier.weight(1f), color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodySmall)
                        IconButton(onClick = { error = null }) { Icon(Icons.Default.Close, "Dismiss", tint = MaterialTheme.colorScheme.onErrorContainer) }
                    }
                }
            }

            when {
                loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                selected == null -> EmptyLists { showNewList = true }
                else -> {
                    LazyColumn(
                        Modifier.weight(1f).fillMaxWidth(),
                        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        if (open.isEmpty() && done.isEmpty()) item(key = "empty") {
                            Text(
                                "Nothing here yet. Add the first item below.",
                                Modifier.fillMaxWidth().padding(vertical = 40.dp),
                                textAlign = TextAlign.Center,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        items(open, key = { it.id }) { item ->
                            ListItemRow(item, names, uid, onToggle = { toggle(item) }, onDelete = { remove(item) })
                        }
                        if (done.isNotEmpty()) {
                            item(key = "done-header") {
                                Row(
                                    Modifier.fillMaxWidth().clickable { showCompleted = !showCompleted }.padding(horizontal = 4.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("Completed (${done.size})", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                                    Icon(if (showCompleted) Icons.Default.ExpandLess else Icons.Default.ExpandMore, if (showCompleted) "Hide completed" else "Show completed", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            if (showCompleted) items(done, key = { it.id }) { item ->
                                ListItemRow(item, names, uid, onToggle = { toggle(item) }, onDelete = { remove(item) })
                            }
                        }
                    }

                    // Add bar
                    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = draft,
                                onValueChange = { if (it.length <= 200) draft = it },
                                modifier = Modifier.weight(1f),
                                placeholder = { Text("Add to ${selected.title}…", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                singleLine = true,
                                shape = RoundedCornerShape(24.dp),
                                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                                keyboardActions = KeyboardActions(onDone = { addDraft() })
                            )
                            Spacer(Modifier.width(8.dp))
                            FilledIconButton(onClick = { addDraft() }, enabled = draft.isNotBlank(), modifier = Modifier.size(52.dp)) { Icon(Icons.Default.Add, "Add item") }
                        }
                    }
                }
            }
        }
    }

    if (showNewList) NewListDialog(onDismiss = { showNewList = false }) { title, emoji ->
        showNewList = false
        scope.launch {
            runCatchingActive { repo.createList(title, emoji) }
                .onSuccess { created -> lists = lists + created; selectedId = created.id; refresh() }
                .onFailure { error = friendlyListError(it) }
        }
    }

    confirmDelete?.let { list ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete ${list.emoji} ${list.title}?") },
            text = { Text("This removes the list and all its items for both of you.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = null
                    val beforeLists = lists
                    val beforeItems = items
                    lists = lists.filterNot { it.id == list.id }
                    items = items.filterNot { it.listId == list.id }
                    selectedId = null
                    act({ lists = beforeLists; items = beforeItems }) { repo.deleteList(list) }
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun ListItemRow(item: SharedListItem, names: Map<String, String>, uid: String?, onToggle: () -> Unit, onDelete: () -> Unit) {
    val pending = item.id.startsWith("local-")
    Surface(
        Modifier.fillMaxWidth().animateContentSize(),
        shape = RoundedCornerShape(16.dp),
        color = if (item.done) MaterialTheme.colorScheme.surfaceContainerLow else MaterialTheme.colorScheme.surface
    ) {
        Row(Modifier.clickable(enabled = !pending, onClick = onToggle).padding(start = 4.dp, end = 4.dp, top = 2.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = item.done, onCheckedChange = { onToggle() }, enabled = !pending)
            Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                Text(
                    item.text,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (item.done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                    textDecoration = if (item.done) TextDecoration.LineThrough else null
                )
                val who = if (item.done) item.doneBy else item.addedBy
                val label = when {
                    pending -> "Adding…"
                    who == null -> null
                    item.done -> "Done by " + personName(who, names, uid)
                    else -> "Added by " + personName(who, names, uid)
                }
                label?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            IconButton(onClick = onDelete, enabled = !pending) { Icon(Icons.Default.Close, "Remove ${item.text}", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

private fun personName(id: String, names: Map<String, String>, uid: String?): String =
    if (id == uid) "you" else names[id] ?: "your partner"

@Composable
private fun EmptyLists(onCreate: () -> Unit) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(88.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape), contentAlignment = Alignment.Center) {
                Text("📝", style = MaterialTheme.typography.headlineLarge)
            }
            Spacer(Modifier.height(18.dp))
            Text("No lists yet", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(6.dp))
            Text("Groceries, a bucket list, movies to watch: make a list you both can tick off.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            Spacer(Modifier.height(18.dp))
            Button(onClick = onCreate) { Icon(Icons.Default.Add, null); Spacer(Modifier.width(6.dp)); Text("Create a list") }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NewListDialog(onDismiss: () -> Unit, onCreate: (String, String) -> Unit) {
    var title by remember { mutableStateOf("") }
    var emoji by remember { mutableStateOf("📝") }
    AlertDialog(
        modifier = Modifier.imePadding(),
        onDismissRequest = onDismiss,
        title = { Text("New list") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { if (it.length <= 60) title = it },
                    label = { Text("List name") },
                    placeholder = { Text("e.g. Weekend trip") },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { if (title.isNotBlank()) onCreate(title, emoji) }),
                    modifier = Modifier.fillMaxWidth()
                )
                Text("Icon", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    SharedListsRepository.emojiChoices.forEach { choice ->
                        val chosen = choice == emoji
                        Surface(
                            onClick = { emoji = choice },
                            shape = CircleShape,
                            color = if (chosen) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                            modifier = Modifier.size(42.dp)
                        ) { Box(contentAlignment = Alignment.Center) { Text(choice, style = MaterialTheme.typography.titleMedium) } }
                    }
                }
            }
        },
        confirmButton = { Button(onClick = { onCreate(title, emoji) }, enabled = title.isNotBlank()) { Text("Create") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

private fun friendlyListError(t: Throwable): String {
    val raw = t.localizedMessage.orEmpty()
    return when {
        "shared_list" in raw && ("does not exist" in raw || "relation" in raw || "schema cache" in raw) ->
            "Lists aren't set up in Supabase yet. Run supabase/shared_lists_and_realtime.sql once."
        raw.isBlank() || raw.length > 200 || "url=https://" in raw.lowercase() -> "Something went wrong. Please try again."
        else -> raw.lineSequence().firstOrNull()?.take(180) ?: "Something went wrong. Please try again."
    }
}
