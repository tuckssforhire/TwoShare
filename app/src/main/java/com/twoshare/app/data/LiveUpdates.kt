package com.twoshare.app.data

import io.github.jan.supabase.realtime.PostgresAction
import io.github.jan.supabase.realtime.RealtimeChannel
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.postgresChangeFlow
import io.github.jan.supabase.realtime.realtime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * One shared Supabase Realtime connection for the whole app.
 *
 * Screens never read data from here. They only learn *that* a table changed and then run
 * their normal (RLS-protected) load. This keeps the existing loading code as the single
 * source of truth, and polling stays as a slower backup in case the socket drops.
 *
 * Usage in a refresh loop:
 *   var seen = LiveUpdates.version(tables)
 *   while (isActive) { seen = LiveUpdates.awaitChange(tables, seen, liveMs, fallbackMs); refresh() }
 */
object LiveUpdates {
    val CHAT_TABLES = setOf("messages", "message_reactions", "message_edits")
    val CALL_TABLES = setOf("calls")
    val LIST_TABLES = setOf("shared_lists", "shared_list_items")
    private val ALL_TABLES = CHAT_TABLES + CALL_TABLES + LIST_TABLES

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val versions = MutableStateFlow<Map<String, Long>>(emptyMap())
    private val _live = MutableStateFlow(false)
    /** True while the realtime channel is subscribed and delivering changes. */
    val live: StateFlow<Boolean> = _live.asStateFlow()

    private var wanted = false
    private var paused = false
    private var channel: RealtimeChannel? = null
    private var job: Job? = null

    /** Call when a user is signed in and unlocked. */
    @Synchronized fun start() {
        wanted = true
        if (!paused) connect()
    }

    /** Call on sign out. */
    @Synchronized fun stop() {
        wanted = false
        disconnect()
    }

    /** App went to the background: drop the socket to save battery. */
    @Synchronized fun pause() {
        paused = true
        disconnect()
    }

    /** App is visible again: reconnect and make every screen catch up once. */
    @Synchronized fun resume() {
        paused = false
        if (wanted) {
            connect()
            bump(ALL_TABLES)
        }
    }

    fun version(tables: Set<String>): Long = versions.value.let { map -> tables.sumOf { map[it] ?: 0L } }

    /**
     * Suspends until one of [tables] changes after [seen], or until the timeout elapses
     * ([liveTimeoutMs] while connected, [fallbackTimeoutMs] otherwise). Returns the new
     * version to pass in next time. Changes that arrive during a refresh are not lost,
     * because the caller passes the version it saw before refreshing.
     */
    suspend fun awaitChange(tables: Set<String>, seen: Long, liveTimeoutMs: Long, fallbackTimeoutMs: Long): Long {
        val timeout = if (_live.value) liveTimeoutMs else fallbackTimeoutMs
        val changed = withTimeoutOrNull(timeout) { versions.first { map -> tables.sumOf { map[it] ?: 0L } != seen } }
        // Several rows often change together (message + reaction); wait a moment to batch them.
        if (changed != null) delay(250)
        return version(tables)
    }

    private fun bump(tables: Set<String>) {
        versions.update { map -> map.toMutableMap().apply { tables.forEach { put(it, (get(it) ?: 0L) + 1L) } } }
    }

    private fun connect() {
        if (channel != null) return
        val client = SupabaseProvider.client
        val ch = runCatching { client.channel("twoshare-live") }.getOrNull() ?: return
        channel = ch
        job = scope.launch {
            runCatching {
                // Flows must be registered before subscribing.
                ALL_TABLES.forEach { table ->
                    ch.postgresChangeFlow<PostgresAction>(schema = "public") { this.table = table }
                        .onEach { bump(setOf(table)) }
                        .launchIn(this)
                }
                ch.status.onEach { _live.value = it == RealtimeChannel.Status.SUBSCRIBED }.launchIn(this)
                ch.subscribe()
            }.onFailure { _live.value = false }
        }
    }

    private fun disconnect() {
        val ch = channel ?: return
        channel = null
        job?.cancel()
        job = null
        _live.value = false
        scope.launch { runCatching { SupabaseProvider.client.realtime.removeChannel(ch) } }
    }
}
