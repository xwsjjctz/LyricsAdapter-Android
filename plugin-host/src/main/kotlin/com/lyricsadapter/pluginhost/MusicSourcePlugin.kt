package com.lyricsadapter.pluginhost

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement

/**
 * A running music source, whichever API it was written for: a legacy
 * `createPlugin(host)` plugin or a `music.source` provider on the plugin
 * platform. Both answer the calls of desktop's plugin handle.
 */
interface MusicSourcePlugin : AutoCloseable {
    val id: String

    /** A provider method, or `validateCookie`. */
    suspend fun call(method: String, args: JsonArray): JsonElement

    /** A plugin action, such as the QR login steps. */
    suspend fun invoke(action: String, args: JsonArray): JsonElement

    suspend fun streamHeaders(cookie: String): Map<String, String>

    suspend fun requiresCookie(): Boolean
}
