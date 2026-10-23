package com.lyricsadapter.app.plugins

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.add

/** Desktop's `OnlineSong`, tagged with the plugin it came from. */
data class OnlineSong(
    val source: String,
    val songmid: String,
    val title: String,
    val artist: String,
    val album: String?,
    /** Seconds, when the source reports it. */
    val duration: Double?,
    val coverUrl: String?,
    val mediaMid: String?,
) {
    val trackId: String get() = onlineTrackId(source, songmid)
}

/** Desktop's `PlaylistInfo`. */
data class OnlinePlaylist(val source: String, val id: String, val name: String, val coverUrl: String?, val songCount: Int)

/** Desktop's `OnlineLyricsResult`: LRC or plain text, with an optional word-timed payload. */
data class OnlineLyrics(val lyrics: String, val wordLyrics: String?, val wordLyricsFormat: String?)

/** A source refused or could not answer, with its message for people. */
class MusicSourceException(message: String, cause: Throwable? = null) : Exception(message, cause)

private const val ONLINE_PREFIX = "online:"

fun onlineTrackId(source: String, songmid: String) = "$ONLINE_PREFIX$source:$songmid"

/** The source and song id of an online track id. */
fun parseOnlineTrackId(id: String): Pair<String, String>? {
    if (!id.startsWith(ONLINE_PREFIX)) return null
    val rest = id.removePrefix(ONLINE_PREFIX)
    val source = rest.substringBefore(':', "")
    val songmid = rest.substringAfter(':', "")
    return if (source.isEmpty() || songmid.isEmpty()) null else source to songmid
}

/** Desktop's stream protocol falls back from the asked quality through these. */
private val QUALITY_FALLBACKS = mapOf(
    "flac" to listOf("flac", "320", "128", "m4a"),
    "320" to listOf("320", "128", "m4a"),
    "128" to listOf("128", "m4a"),
)

/**
 * The online-music calls desktop's renderer makes through a plugin's
 * provider (`OnlineMusicProvider`), with their JSON read into Kotlin types.
 */
class MusicSources(private val registry: PluginRegistry) {
    suspend fun search(source: String, query: String, limit: Int = 20): List<OnlineSong> =
        songs(source, call(source, "searchMusic", buildJsonArray { add(query); add(limit) }))

    suspend fun playlists(source: String): List<OnlinePlaylist> =
        (call(source, "getPlaylists", JsonArray(emptyList())) as? JsonArray).orEmpty().mapNotNull { item ->
            val value = item as? JsonObject ?: return@mapNotNull null
            OnlinePlaylist(
                source = source,
                id = value.text("id") ?: return@mapNotNull null,
                name = value.text("name").orEmpty(),
                coverUrl = value.text("coverUrl"),
                songCount = (value["songCount"] as? JsonPrimitive)?.intOrNull ?: 0,
            )
        }

    suspend fun playlistSongs(source: String, playlistId: String, offset: Int = 0, limit: Int = 200): List<OnlineSong> =
        songs(source, call(source, "getPlaylistSongs", buildJsonArray { add(playlistId); add(offset); add(limit) }))

    suspend fun lyrics(source: String, songmid: String): OnlineLyrics? {
        val value = call(source, "getLyrics", buildJsonArray { add(songmid) }) as? JsonObject ?: return null
        val lyrics = value.text("lyrics").orEmpty()
        val word = value.text("wordLyrics")
        if (lyrics.isBlank() && word.isNullOrBlank()) return null
        return OnlineLyrics(lyrics, word, value.text("wordLyricsFormat"))
    }

    /**
     * A playable URL and the headers to request it with, as desktop's stream
     * protocol resolves it: refuse when the source needs a login that is
     * missing, and fall back to lower qualities.
     */
    suspend fun stream(source: String, songmid: String, mediaMid: String?, quality: String = "320"): Pair<String, Map<String, String>> {
        val plugin = registry.runtime(source)
        val cookie = registry.cookie(source)
        if (cookie.isEmpty() && plugin.requiresCookie()) throw MusicSourceException("请先登录音乐源")
        var failure: Throwable? = null
        for (candidate in QUALITY_FALLBACKS[quality] ?: listOf(quality, "128")) {
            try {
                val args = buildJsonArray {
                    add(songmid)
                    add(candidate)
                    if (mediaMid != null) add(mediaMid)
                }
                val url = ((plugin.call("getMusicUrl", args) as? JsonObject)?.text("url"))
                if (!url.isNullOrEmpty()) return url to plugin.streamHeaders(cookie)
            } catch (error: Exception) {
                failure = error
            }
        }
        throw MusicSourceException(failure?.message ?: "无法获取播放地址", failure)
    }

    private suspend fun call(source: String, method: String, args: JsonArray): JsonElement = try {
        registry.runtime(source).call(method, args)
    } catch (error: PluginPackageException) {
        throw MusicSourceException(error.message.orEmpty(), error)
    } catch (error: Exception) {
        throw MusicSourceException(error.message ?: error.javaClass.simpleName, error)
    }

    private fun songs(source: String, value: JsonElement): List<OnlineSong> =
        (value as? JsonArray).orEmpty().mapNotNull { item -> (item as? JsonObject)?.let { song(source, it) } }

    private fun song(source: String, value: JsonObject): OnlineSong? {
        val songmid = value.text("songmid") ?: return null
        val singers = (value["singer"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.text("name") }
        return OnlineSong(
            source = source,
            songmid = songmid,
            title = value.text("songname").orEmpty().ifEmpty { songmid },
            artist = singers.joinToString(" / "),
            album = value.text("albumname")?.takeIf(String::isNotBlank),
            duration = (value["interval"] as? JsonPrimitive)?.doubleOrNull,
            coverUrl = value.text("coverUrlFullSize") ?: value.text("coverUrl"),
            mediaMid = value.text("mediaMid"),
        )
    }

    private fun JsonObject.text(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.contentOrNull?.takeIf(String::isNotEmpty)
}
