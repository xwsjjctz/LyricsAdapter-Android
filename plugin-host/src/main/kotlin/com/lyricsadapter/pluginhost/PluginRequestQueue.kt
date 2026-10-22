package com.lyricsadapter.pluginhost

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Carries requests into the runtime's JavaScript event loop and replies back.
 * The loop takes requests with [next] and answers each with [complete].
 */
internal class PluginRequestQueue(private val pluginId: String) {
    private val requests = Channel<List<Any?>>(Channel.UNLIMITED)
    private val replies = ConcurrentHashMap<Long, CompletableDeferred<String>>()
    private val nextId = AtomicLong()

    @Volatile
    private var closedCause: Throwable? = null

    /** Sends [payload] (JSON) to the loop and returns the JSON reply. */
    suspend fun submit(operation: String, payload: String, timeoutMillis: Long): String {
        val id = nextId.incrementAndGet()
        val reply = CompletableDeferred<String>()
        replies[id] = reply
        try {
            if (requests.trySend(listOf(id, operation, payload)).isFailure) throw PluginUnavailableException(pluginId, closedCause)
            return withTimeoutOrNull(timeoutMillis) { reply.await() }
                ?: throw PluginTimeoutException(pluginId, operation, timeoutMillis)
        } finally {
            replies.remove(id)
        }
    }

    /** The next request for the loop as `[id, operation, payload]`, or null once closed. */
    suspend fun next(): List<Any?>? = requests.receiveCatching().getOrNull()

    fun complete(id: Long, ok: Boolean, payload: String) {
        val reply = replies.remove(id) ?: return
        if (ok) reply.complete(payload) else reply.completeExceptionally(PluginCallException(payload))
    }

    /** Stops the loop and fails every request still waiting for a reply. */
    fun close(cause: Throwable?) {
        closedCause = cause
        requests.close()
        val failure = PluginUnavailableException(pluginId, cause)
        replies.values.forEach { it.completeExceptionally(failure) }
    }
}
