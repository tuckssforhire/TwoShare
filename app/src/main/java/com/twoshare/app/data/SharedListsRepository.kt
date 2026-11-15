package com.twoshare.app.data

import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class SharedList(
    val id: String,
    val title: String,
    val emoji: String = "📝",
    @SerialName("created_by") val createdBy: String? = null,
    @SerialName("created_at") val createdAt: String
)

@Serializable
data class SharedListItem(
    val id: String,
    @SerialName("list_id") val listId: String,
    val text: String,
    val done: Boolean = false,
    @SerialName("added_by") val addedBy: String,
    @SerialName("done_by") val doneBy: String? = null,
    @SerialName("created_at") val createdAt: String,
    @SerialName("done_at") val doneAt: String? = null
)

// Attribution (created_by, added_by, done_by, timestamps) is set by database triggers;
// the API only accepts these columns.
@Serializable private data class SharedListInsert(val title: String, val emoji: String)
@Serializable private data class SharedListItemInsert(@SerialName("list_id") val listId: String, val text: String)

data class SharedListsSnapshot(
    val lists: List<SharedList>,
    val items: List<SharedListItem>,
    val names: Map<String, String>
)

class SharedListsRepository(private val base: TwoShareRepository) {
    private val supabase = SupabaseProvider.client

    fun currentUserId(): String? = base.currentUserId()

    suspend fun load(): SharedListsSnapshot {
        base.requireAllowedUser()
        val lists = base.retryNetworkRead {
            supabase.from("shared_lists").select { order("created_at", Order.ASCENDING) }.decodeList<SharedList>()
        }
        val items = base.retryNetworkRead {
            supabase.from("shared_list_items").select { order("created_at", Order.ASCENDING) }.decodeList<SharedListItem>()
        }
        val names = runCatching { base.loadProfiles().mapValues { it.value.displayName } }.getOrDefault(lastSnapshot?.names.orEmpty())
        return SharedListsSnapshot(lists, items, names).also { lastSnapshot = it }
    }

    suspend fun createList(title: String, emoji: String): SharedList {
        base.currentUserId() ?: error("Your session has expired. Please sign in again.")
        val clean = title.trim()
        require(clean.isNotEmpty() && clean.length <= 60) { "List name must be 1–60 characters." }
        return supabase.from("shared_lists")
            .insert(SharedListInsert(clean, emoji.ifBlank { "📝" }.take(16))) { select() }
            .decodeSingle<SharedList>()
    }

    suspend fun deleteList(list: SharedList) {
        base.requireAllowedUser()
        supabase.from("shared_lists").delete { filter { eq("id", list.id) } }
    }

    suspend fun addItem(listId: String, text: String): SharedListItem {
        base.currentUserId() ?: error("Your session has expired. Please sign in again.")
        val clean = text.trim()
        require(clean.isNotEmpty() && clean.length <= 200) { "Items must be 1–200 characters." }
        return supabase.from("shared_list_items")
            .insert(SharedListItemInsert(listId, clean)) { select() }
            .decodeSingle<SharedListItem>()
    }

    /** done_by and done_at are filled in by the database. */
    suspend fun setDone(item: SharedListItem, done: Boolean) {
        base.requireAllowedUser()
        supabase.from("shared_list_items").update({ set("done", done) }) { filter { eq("id", item.id) } }
    }

    suspend fun deleteItem(item: SharedListItem) {
        base.requireAllowedUser()
        supabase.from("shared_list_items").delete { filter { eq("id", item.id) } }
    }

    suspend fun clearCompleted(listId: String) {
        base.requireAllowedUser()
        supabase.from("shared_list_items").delete { filter { eq("list_id", listId); eq("done", true) } }
    }

    companion object {
        /** Last loaded data, so switching tabs shows lists instantly instead of a spinner. */
        @Volatile var lastSnapshot: SharedListsSnapshot? = null
            private set
        /** Called on sign-out so another account never sees the previous account's lists. */
        fun clearCache() { lastSnapshot = null }
        val emojiChoices = listOf("📝", "🛒", "✨", "🎬", "📍", "🎁", "🏠", "✈️", "🍽️", "📚", "💪", "❤️")
    }
}
