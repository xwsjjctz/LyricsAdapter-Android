package com.lyricsadapter.app.plugins

import android.util.Log
import com.lyricsadapter.library.Track
import com.lyricsadapter.lyrics.WordLyricsFormat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException

/** A song in the online list, with its lyrics once they have been fetched. */
data class OnlineEntry(val song: OnlineSong, val lyrics: OnlineLyrics? = null) {
    fun toTrack() = Track(
        id = song.trackId,
        title = song.title,
        artist = song.artist.ifEmpty { null },
        album = song.album,
        durationMs = ((song.duration ?: 0.0) * 1000).toLong(),
        coverPath = song.coverUrl,
        lyrics = lyrics?.lyrics?.ifBlank { null },
        wordLyrics = lyrics?.wordLyrics?.ifBlank { null },
        wordLyricsFormat = when (lyrics?.wordLyricsFormat) {
            "qrc" -> WordLyricsFormat.QRC
            "yrc" -> WordLyricsFormat.YRC
            else -> null
        },
        addedAtMs = 0,
    )
}

/** The playlist opened into the playlist slot. */
data class OpenedPlaylist(val playlist: OnlinePlaylist, val songs: List<OnlineEntry>)

/**
 * Desktop's online and playlist slots. The online list collects the songs
 * played or added from search, newest first, and is kept on the device; the
 * playlist slot holds the account playlist opened last. Lyrics are fetched
 * from the song's source the first time they are needed and kept with it.
 */
class OnlineLibrary(
    private val sources: MusicSources,
    private val file: File,
    private val scope: CoroutineScope,
) {
    private val queue = MutableStateFlow(read())
    private val opened = MutableStateFlow<OpenedPlaylist?>(null)

    val entries: StateFlow<List<OnlineEntry>> = queue.asStateFlow()
    val playlist: StateFlow<OpenedPlaylist?> = opened.asStateFlow()

    /** The song behind an online track id, wherever it is listed. */
    fun find(trackId: String): OnlineEntry? =
        queue.value.firstOrNull { it.song.trackId == trackId } ?: opened.value?.songs?.firstOrNull { it.song.trackId == trackId }

    /** Puts [song] at the top of the online list (moving it there if already listed). */
    @Synchronized
    fun add(song: OnlineSong) {
        val existing = queue.value.firstOrNull { it.song.trackId == song.trackId }
        queue.value = listOf(existing?.copy(song = song) ?: OnlineEntry(song)) + queue.value.filterNot { it.song.trackId == song.trackId }
        save()
    }

    @Synchronized
    fun remove(trackIds: Collection<String>) {
        queue.value = queue.value.filterNot { it.song.trackId in trackIds }
        save()
    }

    fun open(playlist: OnlinePlaylist, songs: List<OnlineSong>) {
        opened.value = OpenedPlaylist(playlist, songs.map { find(it.trackId)?.copy(song = it) ?: OnlineEntry(it) })
    }

    /** Fetches lyrics for an online track that has none yet. */
    fun ensureLyrics(trackId: String) {
        val entry = find(trackId) ?: return
        if (entry.lyrics != null) return
        scope.launch(Dispatchers.IO) {
            val lyrics = try {
                sources.lyrics(entry.song.source, entry.song.songmid)
            } catch (error: MusicSourceException) {
                Log.w(TAG, "No lyrics for $trackId", error)
                null
            } ?: return@launch
            storeLyrics(trackId, lyrics)
        }
    }

    @Synchronized
    private fun storeLyrics(trackId: String, lyrics: OnlineLyrics) {
        queue.value = queue.value.map { if (it.song.trackId == trackId) it.copy(lyrics = lyrics) else it }
        opened.value = opened.value?.let { list ->
            list.copy(songs = list.songs.map { if (it.song.trackId == trackId) it.copy(lyrics = lyrics) else it })
        }
        save()
    }

    private fun save() {
        val json = JSONArray(queue.value.map { entry ->
            val song = entry.song
            JSONObject()
                .put("source", song.source).put("songmid", song.songmid).put("title", song.title)
                .put("artist", song.artist).put("album", song.album).put("duration", song.duration)
                .put("coverUrl", song.coverUrl).put("mediaMid", song.mediaMid)
                .put("lyrics", entry.lyrics?.lyrics).put("wordLyrics", entry.lyrics?.wordLyrics)
                .put("wordLyricsFormat", entry.lyrics?.wordLyricsFormat)
        })
        val partial = File(file.path + ".part")
        partial.writeText(json.toString())
        partial.renameTo(file)
    }

    private fun read(): List<OnlineEntry> = try {
        val list = JSONArray(file.readText())
        (0 until list.length()).map { index ->
            val item = list.getJSONObject(index)
            OnlineEntry(
                song = OnlineSong(
                    source = item.getString("source"),
                    songmid = item.getString("songmid"),
                    title = item.getString("title"),
                    artist = item.optString("artist"),
                    album = item.optText("album"),
                    duration = item.optDouble("duration").takeUnless { it.isNaN() },
                    coverUrl = item.optText("coverUrl"),
                    mediaMid = item.optText("mediaMid"),
                ),
                lyrics = item.optText("lyrics")?.let { OnlineLyrics(it, item.optText("wordLyrics"), item.optText("wordLyricsFormat")) },
            )
        }
    } catch (_: IOException) {
        emptyList()
    } catch (_: JSONException) {
        emptyList()
    }

    private fun JSONObject.optText(name: String): String? = if (isNull(name)) null else optString(name).ifEmpty { null }

    private companion object {
        const val TAG = "OnlineLibrary"
    }
}
