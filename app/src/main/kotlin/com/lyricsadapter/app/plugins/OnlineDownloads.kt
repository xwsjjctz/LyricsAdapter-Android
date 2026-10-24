package com.lyricsadapter.app.plugins

import android.content.ContentResolver
import android.content.ContentValues
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Log
import com.lyricsadapter.app.data.LibraryRepository
import com.lyricsadapter.audiotags.CoverImage
import com.lyricsadapter.audiotags.CoverUpdate
import com.lyricsadapter.audiotags.TagUpdate
import com.lyricsadapter.audiotags.WordLyrics
import com.lyricsadapter.audiotags.WordLyricsFormat
import com.lyricsadapter.audiotags.WordLyricsUpdate
import com.lyricsadapter.audiotags.writeTagsToFd
import com.lyricsadapter.library.MusicFileName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Desktop's download qualities (`TRACK_DOWNLOAD_QUALITIES`), with the labels it shows. */
enum class DownloadQuality(val id: String, val label: String) {
    Standard("128", "128kbps"),
    High("320", "320kbps"),
    Lossless("flac", "FLAC"),
}

/** What became of a download, for the toast that reports it. */
sealed interface DownloadOutcome {
    val song: OnlineSong

    data class Saved(override val song: OnlineSong, val requested: String, val served: String) : DownloadOutcome {
        val downgraded: Boolean get() = requested != served
    }

    data class Failed(override val song: OnlineSong, val reason: String) : DownloadOutcome
}

/**
 * Downloads online songs into the local library as desktop does
 * (`saveOnlineAudio`): the stream is resolved first, so the file is named
 * after the quality actually served; the title, artist, album, lyrics, word
 * lyrics and cover are written into it; and it joins the local slot. Files
 * go to Music/LyricsAdapter through MediaStore, where other apps see them
 * too. Downloads run in the app's scope, so they finish when the screen
 * that started them is gone.
 */
class OnlineDownloads(
    private val resolver: ContentResolver,
    private val sources: MusicSources,
    private val library: LibraryRepository,
    private val scratch: File,
    private val scope: CoroutineScope,
) {
    private val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
    private val running = MutableStateFlow<Map<String, Float>>(emptyMap())
    private val outcomes = MutableSharedFlow<DownloadOutcome>(extraBufferCapacity = 8)

    /** Songs being downloaded, by track id, with how much has arrived (0 to 1). */
    val progress: StateFlow<Map<String, Float>> = running.asStateFlow()
    val results: SharedFlow<DownloadOutcome> = outcomes.asSharedFlow()

    /** Starts downloading [song]; a song already on its way is not started twice. */
    fun start(song: OnlineSong, quality: DownloadQuality) {
        val id = song.trackId
        var started = false
        running.update { current -> if (id in current) current else current + (id to 0f).also { started = true } }
        if (!started) return
        scope.launch(Dispatchers.IO) {
            val outcome = try {
                download(song, quality)
            } catch (error: Exception) {
                Log.w(TAG, "Download of ${song.title} failed", error)
                DownloadOutcome.Failed(song, error.message ?: error.javaClass.simpleName)
            } finally {
                running.update { it - id }
            }
            outcomes.emit(outcome)
        }
    }

    private suspend fun download(song: OnlineSong, quality: DownloadQuality): DownloadOutcome = coroutineScope {
        val lyrics = async { runCatching { sources.lyrics(song.source, song.songmid) }.getOrNull() }
        val cover = async { song.coverUrl?.let { url -> runCatching { fetchCover(url) }.getOrNull() } }
        val stream = sources.stream(song.source, song.songmid, song.mediaMid, quality.id)
        val extension = EXTENSIONS[stream.quality] ?: "mp3"
        val artist = song.artist.replace(" / ", " & ")
        scratch.mkdirs()
        val file = File.createTempFile("download", ".$extension", scratch)
        try {
            fetchAudio(stream, file, song.trackId)
            running.update { it + (song.trackId to 1f) }
            writeTags(file, song, artist, lyrics.await(), cover.await())
            val uri = publish(file, MusicFileName.of(artist, song.title, extension), MIME_TYPES.getValue(extension))
            library.import(listOf(uri))
            DownloadOutcome.Saved(song, quality.id, stream.quality)
        } finally {
            file.delete()
        }
    }

    private suspend fun fetchAudio(stream: ResolvedStream, file: File, id: String) {
        val request = Request.Builder().url(stream.url).apply { stream.headers.forEach { (name, value) -> header(name, value) } }.build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            val total = response.body.contentLength()
            response.body.byteStream().use { input ->
                file.outputStream().use { output ->
                    val buffer = ByteArray(BUFFER_BYTES)
                    var copied = 0L
                    var reported = 0f
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        copied += read
                        val share = if (total > 0) copied.toFloat() / total else 0f
                        // A step per percent is enough for a progress ring.
                        if (share - reported >= 0.01f) {
                            reported = share
                            running.update { it + (id to share) }
                        }
                    }
                }
            }
        }
    }

    private fun fetchCover(url: String): CoverImage? =
        http.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) return null
            val mime = response.body.contentType()?.let { "${it.type}/${it.subtype}" }?.takeIf { it.startsWith("image/") } ?: "image/jpeg"
            CoverImage(mime, response.body.bytes())
        }

    /** Desktop's `writeAudioMetadata`; a format that cannot hold word lyrics (M4A) keeps the rest. */
    private fun writeTags(file: File, song: OnlineSong, artist: String, lyrics: OnlineLyrics?, cover: CoverImage?) {
        val wordFormat = when (lyrics?.wordLyricsFormat?.lowercase()) {
            "yrc" -> WordLyricsFormat.YRC
            else -> WordLyricsFormat.QRC
        }
        val update = TagUpdate(
            title = song.title,
            artist = artist,
            album = song.album.orEmpty(),
            lyrics = lyrics?.lyrics,
            cover = cover?.let(CoverUpdate::Replace) ?: CoverUpdate.Keep,
            wordLyrics = lyrics?.wordLyrics?.let { WordLyricsUpdate.Replace(WordLyrics(wordFormat, it)) } ?: WordLyricsUpdate.Keep,
        )
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_WRITE).use { writeTagsToFd(it.fd, update) }
    }

    /** Copies the finished file into Music/LyricsAdapter; it stays hidden until it is complete. */
    private suspend fun publish(file: File, name: String, mimeType: String): Uri = withContext(Dispatchers.IO) {
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, name)
            put(MediaStore.Audio.Media.MIME_TYPE, mimeType)
            put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/LyricsAdapter")
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }
        val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = resolver.insert(collection, values) ?: throw IOException("MediaStore refused $name")
        try {
            resolver.openOutputStream(uri)?.use { output -> file.inputStream().use { it.copyTo(output) } } ?: throw IOException("No output for $uri")
            resolver.update(uri, ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }, null, null)
            uri
        } catch (error: Exception) {
            resolver.delete(uri, null, null)
            throw error
        }
    }

    private companion object {
        const val TAG = "OnlineDownloads"
        const val BUFFER_BYTES = 64 * 1024

        /** Desktop's `QUALITY_EXTENSION`. */
        val EXTENSIONS = mapOf("128" to "mp3", "320" to "mp3", "flac" to "flac", "m4a" to "m4a")
        val MIME_TYPES = mapOf("mp3" to "audio/mpeg", "flac" to "audio/flac", "m4a" to "audio/mp4")
    }
}
