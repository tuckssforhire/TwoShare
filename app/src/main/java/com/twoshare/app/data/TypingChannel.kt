package com.twoshare.app.data

import io.github.jan.supabase.realtime.RealtimeChannel
import io.github.jan.supabase.realtime.broadcast
import io.github.jan.supabase.realtime.broadcastFlow
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.realtime
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class TypingEvent(@SerialName("user_id") val userId: String, val typing: Boolean)

/**
 * "typing…" in chat over a private Supabase Realtime broadcast channel.
 * Nothing is written to the database (no storage, almost no egress). The channel is private:
 * Realtime only lets the two TwoShare accounts join it (policy in supabase/typing_indicator.sql).
 * Best effort: if it can't connect, chat works exactly as before without the indicator.
 */
class TypingChannel(private val myId: String) {
    private val client = SupabaseProvider.client
    @Volatile private var channel: RealtimeChannel? = null
    private var typingSent = false
    private var lastTypingSentAt = 0L

    /** Runs while the chat screen is open; reconnects after drops and pauses in the background. */
    suspend fun listen(onPartnerTyping: (Boolean) -> Unit) {
        while (true) {
            currentCoroutineContext().ensureActive()
            LiveUpdates.foreground.first { it }
            var ch: RealtimeChannel? = null
            try {
                val created = client.channel(TOPIC) { isPrivate = true }
                ch = created
                coroutineScope {
                    created.broadcastFlow<TypingEvent>(event = EVENT)
                        .onEach { if (it.userId != myId) onPartnerTyping(it.typing) }
                        .launchIn(this)
                    val subscribed = CompletableDeferred<Unit>()
                    created.status.onEach { if (it == RealtimeChannel.Status.SUBSCRIBED) subscribed.complete(Unit) }.launchIn(this)
                    created.subscribe(blockUntilSubscribed = false)
                    withTimeout(15_000) { subscribed.await() }
                    channel = created
                    // Leave when the subscription drops or the app goes to the background.
                    launch { LiveUpdates.foreground.first { !it }; created.unsubscribe() }
                    created.status.first { it != RealtimeChannel.Status.SUBSCRIBED }
                    error("Typing channel closed")
                }
            } catch (t: Throwable) {
                currentCoroutineContext().ensureActive()
            } finally {
                channel = null
                onPartnerTyping(false)
                ch?.let { c ->
                    withContext(NonCancellable) {
                        if (typingSent) runCatching { c.broadcast(event = EVENT, message = TypingEvent(myId, false)) }
                        typingSent = false
                        runCatching { client.realtime.removeChannel(c) }
                    }
                }
            }
            delay(3_000)
        }
    }

    /** Call as the draft changes. "typing" is sent at most every 2.5 s; "stopped" once. */
    suspend fun update(typing: Boolean) {
        val ch = channel ?: return
        val now = System.currentTimeMillis()
        if (typing) {
            if (typingSent && now - lastTypingSentAt < 2_500) return
            lastTypingSentAt = now
            typingSent = true
        } else {
            if (!typingSent) return
            typingSent = false
        }
        runCatching { ch.broadcast(event = EVENT, message = TypingEvent(myId, typing)) }
    }

    companion object {
        const val TOPIC = "twoshare-typing"
        private const val EVENT = "typing"
    }
}
