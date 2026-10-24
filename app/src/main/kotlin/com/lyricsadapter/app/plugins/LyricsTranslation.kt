package com.lyricsadapter.app.plugins

import com.lyricsadapter.app.ui.TranslationLanguages
import com.lyricsadapter.library.Track
import com.lyricsadapter.lyrics.LyricLine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.security.MessageDigest

/** The translation provider focus mode uses: the first enabled translation plugin's first provider. */
data class Translator(val pluginId: String, val providerId: String, val version: String, val languages: List<String>)

/**
 * Focus-mode lyric translation, as desktop's `pluginTranslation.ts`: songs are
 * translated only when asked, the request is remembered for the session so the
 * translation stays shown when focus mode reopens, and results are kept in
 * memory (the plugin keeps its own cache on disk).
 */
class LyricsTranslation(private val plugins: PluginRegistry) {
    private val requested = MutableStateFlow(emptySet<String>())
    private val results = LinkedHashMap<String, Map<String, String>>()

    /** Keys of the translations the listener asked for in this session. */
    val requests: StateFlow<Set<String>> = requested.asStateFlow()

    fun translator(): Translator? = plugins.translationPlugins().firstNotNullOfOrNull { plugin ->
        val manifest = plugin.manifest.platform ?: return@firstNotNullOfOrNull null
        val provider = manifest.translators.first()
        Translator(plugin.id, provider.id, manifest.version, provider.languages ?: TranslationLanguages.DEFAULT)
    }

    fun requestKey(track: Track, translator: Translator, language: String) = listOf(track.id, translator.pluginId, translator.providerId, language).joinToString("\u0000")

    fun setRequested(key: String, value: Boolean) = requested.update { if (value) it + key else it - key }

    /** The translated text by line index, from memory or from the plugin. */
    suspend fun translate(track: Track, lines: List<LyricLine>, translator: Translator, language: String): Map<Int, String> {
        val document = document(track, lines)
        val cacheKey = listOf(translator.pluginId, translator.version, document["id"], document["revision"], language).joinToString("\u0000")
        val byId = synchronized(results) { results[cacheKey] } ?: run {
            val request = buildJsonObject {
                put("document", document)
                put("targetLanguage", language)
            }
            val result = plugins.platformRuntime(translator.pluginId).translate(translator.providerId, request)
            val lines = result["lines"]!!.jsonArray.associate { it.jsonObject["lineId"]!!.jsonPrimitive.content to it.jsonObject["text"]!!.jsonPrimitive.content }
            synchronized(results) {
                results[cacheKey] = lines
                while (results.size > MAX_RESULTS) results.remove(results.keys.first())
            }
            lines
        }
        return byId.mapNotNull { (id, text) -> id.removePrefix(LINE_PREFIX).toIntOrNull()?.let { it to text } }.filter { it.second.isNotBlank() }.toMap()
    }

    /**
     * Desktop's `lyricDocumentForTrack`: lines with text, ids by position, word
     * timings when present; the track id is hashed so no path leaves the app,
     * and the revision changes whenever the lines do.
     */
    private fun document(track: Track, lines: List<LyricLine>): JsonObject {
        val lineArray = buildJsonArray {
            lines.forEachIndexed { index, line ->
                if (line.text.isBlank()) return@forEachIndexed
                add(
                    buildJsonObject {
                        put("id", "$LINE_PREFIX$index")
                        put("text", line.text)
                        put("startMs", line.startMs)
                        if (line.words.isNotEmpty()) {
                            put("words", buildJsonArray {
                                line.words.filter { it.durationMs > 0 }.forEach { word ->
                                    add(buildJsonObject {
                                        put("text", word.text)
                                        put("startMs", word.startMs)
                                        put("endMs", word.endMs)
                                    })
                                }
                            })
                        }
                    },
                )
            }
        }
        val trackId = sha256(track.id)
        return buildJsonObject {
            put("id", "lyrics-$trackId")
            put("trackId", trackId)
            put("revision", sha256(buildJsonObject { put("parser", 1); put("lines", lineArray) }.toString()))
            val metadata = buildJsonObject {
                listOf("title" to track.title, "artist" to track.artist, "album" to track.album).forEach { (key, value) ->
                    value?.trim()?.takeIf(String::isNotEmpty)?.let { put(key, it.take(1024)) }
                }
            }
            if (metadata.isNotEmpty()) put("metadata", metadata)
            put("lines", lineArray)
        }
    }

    private fun sha256(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    private companion object {
        const val LINE_PREFIX = "line-"
        const val MAX_RESULTS = 32
    }
}
