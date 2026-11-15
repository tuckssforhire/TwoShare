package com.twoshare.app.data

import io.github.jan.supabase.realtime.PostgresAction
import io.github.jan.supabase.realtime.RealtimeChannel
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.postgresChangeFlow
import io.github.jan.supabase.realtime.realtime
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

/**
 * One shared Supabase Realtime connection for the whole app.
 *
 * Screens never take data from here. They only learn *that* a table changed and then run
 * their normal RLS-protected load, so the existing loaders stay the single source of truth.
 * Polling remains as a backup and speeds up automatically while the live connection is down.
 *
 * Lifecycle (driven by MainActivity):
 *  - start()/stop(): user signed in and unlocked / signed out or locked.
 *  - pause()/resume(): activity hidden / visible. In the background the socket is closed and
 *    foreground polling loops wait; incoming calls still arrive through Firebase push.
 *
 * Usage in a refresh loop:
 *   var seen = LiveUpdates.version(tables)
 *   while (isActive) { seen = LiveUpdates.awaitChange(tables, seen, liveMs, fallbackMs); refresh() }
 */
object LiveUpdates {
    val CHAT_TABLES = setOf("messages", "message_reactions", "message_edits")
    val CALL_TABLES = setOf("calls")
    val LIST_TABLES = setOf("shared_lists", "shared_list_items")
    val SETTINGS_TABLES = setOf("shared_settings")
    private val ALL_TABLES = CHAT_TABLES + CALL_TABLES + LIST_TABLES + SETTINGS_TABLES

    private const val SUBSCRIBE_TIMEOUT_MS = 15_000L
    private const val MIN_RETRY_MS = 1_000L
    private const val MAX_RETRY_MS = 30_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val versions = MutableStateFlow<Map<String, Long>>(emptyMap())
    private val _live = MutableStateFlow(false)
    private val _foreground = MutableStateFlow(true)

    /** True only while the realtime channel is subscribed and delivering changes. */
    val live: StateFlow<Boolean> = _live.asStateFlow()
    /** False while the app is in the background. */
    val foreground: StateFlow<Boolean> = _foreground.asStateFlow()

    private var wanted = false
    @Volatile private var connectionJob: Job? = null

    /** Signed in and unlocked: keep a live connection while the app is visible. */
    @Synchronized fun start() {
        wanted = true
        if (_foreground.value) ensureRunning()
    }

    /** Signed out or locked: close the connection and stop reconnecting. */
    @Synchronized fun stop() {
        wanted = false
        ensureStopped()
    }

    /** Activity hidden: close the socket to save battery; polling loops wait for [resume]. */
    @Synchronized fun pause() {
        _foreground.value = false
        ensureStopped()
    }

    /**
     * Activity visible again. Every screen refreshes once to catch up on anything missed while
     * hidden. Pass [connect] = false when a biometric unlock is about to be required; [start]
     * is called again after unlocking.
     */
    @Synchronized fun resume(connect: Boolean = true) {
        _foreground.value = true
        if (wanted && connect) ensureRunning()
        bump(ALL_TABLES)
    }

    fun version(tables: Set<String>): Long = versions.value.let { map -> tables.sumOf { map[it] ?: 0L } }

    /**
     * Suspends until one of [tables] changes after [seen], the live connection goes up or down,
     * or the timeout elapses ([liveTimeoutMs] while live, [fallbackTimeoutMs] otherwise).
     * While the app is in the background it waits until the app is visible again.
     * Returns the version to pass next time; changes made during the caller's refresh are not
     * lost because the caller passes the version it saw before refreshing.
     */
    suspend fun awaitChange(tables: Set<String>, seen: Long, liveTimeoutMs: Long, fallbackTimeoutMs: Long): Long {
        _foreground.first { it }
        val wasLive = _live.value
        val changed = withTimeoutOrNull(if (wasLive) liveTimeoutMs else fallbackTimeoutMs) {
            combine(versions, _live, _foreground) { map, isLive, visible -> Triple(tables.sumOf { map[it] ?: 0L }, isLive, visible) }
                .first { (sum, isLive, visible) -> sum != seen || isLive != wasLive || !visible }
        }
        // Several rows often change together (message + reaction); wait briefly to batch them.
        if (changed != null) delay(250)
        _foreground.first { it }
        return version(tables)
    }

    private fun bump(tables: Set<String>) {
        versions.update { map -> map.toMutableMap().apply { tables.forEach { put(it, (get(it) ?: 0L) + 1L) } } }
    }

    private fun ensureRunning() {
        if (connectionJob?.isActive == true) return
        // Assign before starting so the job can recognise itself as the current attempt.
        val job = scope.launch(start = CoroutineStart.LAZY) { superviseConnection() }
        connectionJob = job
        job.start()
    }

    private fun ensureStopped() {
        connectionJob?.cancel()
        connectionJob = null
        _live.value = false
    }

    /**
     * Keeps one channel subscribed, recreating it (with backoff) whenever the initial
     * subscription fails or times out, or an established subscription drops (network switch,
     * server restart). Each attempt uses a fresh channel with a unique topic and removes it
     * afterwards, so a stale channel can never block reconnection.
     */
    private suspend fun superviseConnection() {
        val client = SupabaseProvider.client
        val self = currentCoroutineContext()[Job]
        // A cancelled attempt finishing late must not overwrite the state of a newer attempt.
        fun setLive(value: Boolean) { if (connectionJob === self) _live.value = value }
        var retryMs = MIN_RETRY_MS
        while (true) {
            currentCoroutineContext().ensureActive()
            var channel: RealtimeChannel? = null
            try {
                val ch = client.channel("twoshare-live-" + UUID.randomUUID().toString().take(8))
                channel = ch
                coroutineScope {
                    // Change listeners must be registered before subscribing.
                    ALL_TABLES.forEach { table ->
                        ch.postgresChangeFlow<PostgresAction>(schema = "public") { this.table = table }
                            .onEach { bump(setOf(table)) }
                            .launchIn(this)
                    }
                    val subscribed = CompletableDeferred<Unit>()
                    ch.status.onEach { status ->
                        val isLive = status == RealtimeChannel.Status.SUBSCRIBED
                        if (isLive && !_live.value) bump(ALL_TABLES) // recover changes missed while down
                        setLive(isLive)
                        if (isLive) subscribed.complete(Unit)
                    }.launchIn(this)
                    ch.subscribe(blockUntilSubscribed = false)
                    withTimeout(SUBSCRIBE_TIMEOUT_MS) { subscribed.await() }
                    retryMs = MIN_RETRY_MS
                    ch.status.first { it != RealtimeChannel.Status.SUBSCRIBED }
                    error("Realtime subscription dropped")
                }
            } catch (t: Throwable) {
                // stop()/pause() cancel this job: leave. Timeouts and failures: retry below.
                currentCoroutineContext().ensureActive()
            } finally {
                setLive(false)
                channel?.let { ch -> withContext(NonCancellable) { runCatching { client.realtime.removeChannel(ch) } } }
            }
            delay(retryMs)
            retryMs = (retryMs * 2).coerceAtMost(MAX_RETRY_MS)
        }
    }
}
