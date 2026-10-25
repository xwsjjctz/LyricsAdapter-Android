package com.lyricsadapter.app.data

import android.util.Log
import com.lyricsadapter.audiotags.AudioTags
import com.lyricsadapter.audiotags.TagException
import com.lyricsadapter.audiotags.readTagsFromPath
import com.lyricsadapter.library.Track
import com.lyricsadapter.lyrics.WordLyricsFormat
import com.lyricsadapter.webdav.Chunk
import com.lyricsadapter.webdav.CloudMetadataReader
import java.util.Base64
import com.lyricsadapter.webdav.SyncedLine
import com.lyricsadapter.webdav.LocalMetadata
import com.lyricsadapter.webdav.CloudMetadataWriter
import com.lyricsadapter.webdav.ManifestEntry
import com.lyricsadapter.webdav.WebDavClient
import com.lyricsadapter.webdav.WebDavConfig
import com.lyricsadapter.webdav.WebDavException
import com.lyricsadapter.webdav.WebDavFile
import com.lyricsadapter.webdav.decodeDataUrl
import com.lyricsadapter.webdav.toLrc
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import com.lyricsadapter.audiotags.WordLyricsFormat as TagWordLyricsFormat

/** Where the cloud list stands. */
sealed interface CloudStatus {
    data object NotConnected : CloudStatus
    data object Idle : CloudStatus
    data object Loading : CloudStatus
    data class Failed(val message: String) : CloudStatus
}

private const val ID_PREFIX = "webdav:"

/** How many chunk files are fetched at once; desktop keeps this low for slow WebDAV hosts. */
private const val CHUNK_CONCURRENCY = 2

/** How much of a song is read for its tags; the second size covers large embedded covers. */
private val HEAD_SIZES = listOf(512L * 1024, 4L * 1024 * 1024)

/** A cloud track's id is its library path behind a prefix, so it never collides with a document URI. */
fun cloudTrackId(path: String) = ID_PREFIX + path
fun cloudPathOf(trackId: String): String? = trackId.takeIf { it.startsWith(ID_PREFIX) }?.removePrefix(ID_PREFIX)

/**
 * The WebDAV library, read the way desktop's `useWebDAV` reads it: list the
 * root directory, take titles and durations from `Metadata/_manifest.json`
 * where its fingerprint still matches the file, and fill in covers and
 * lyrics from the manifest's chunks in the background. Files the manifest
 * does not describe show their file name. The list is cached on the device,
 * so it appears at once on the next start.
 */
class CloudLibrary(
    private val settings: AppSettings,
    private val cacheFile: File,
    private val covers: CoverStore,
    private val scratchDirectory: File,
    private val scope: CoroutineScope,
) {
    private val state = MutableStateFlow<List<CloudTrack>>(emptyList())
    private val statusState = MutableStateFlow<CloudStatus>(CloudStatus.NotConnected)
    private var job: Job? = null

    val tracks: StateFlow<List<CloudTrack>> = state.asStateFlow()
    val status: StateFlow<CloudStatus> = statusState.asStateFlow()

    init {
        scope.launch(Dispatchers.IO) {
            settings.webDavConfig.collect { config ->
                job?.cancel()
                if (config == null) {
                    state.value = emptyList()
                    statusState.value = CloudStatus.NotConnected
                    cacheFile.delete()
                    covers.deleteExcept(emptySet())
                } else {
                    state.value = readCache(config)
                    statusState.value = CloudStatus.Idle
                }
            }
        }
    }

    /** Lists the server again; a refresh already running is replaced. */
    fun refresh() {
        val config = settings.webDavConfig.value ?: return
        job?.cancel()
        job = scope.launch(Dispatchers.IO) {
            statusState.value = CloudStatus.Loading
            statusState.value = try {
                load(config)
                CloudStatus.Idle
            } catch (error: WebDavException) {
                Log.w(TAG, "Could not load the cloud library", error)
                CloudStatus.Failed(error.message.orEmpty())
            }
        }
    }

    private suspend fun load(config: WebDavConfig) {
        val client = WebDavClient(config)
        val reader = CloudMetadataReader(client)
        val files = client.list("/").filterNot(WebDavFile::isDirectory)
        val manifest = reader.manifest()
        val previous = state.value.associateBy(CloudTrack::path)
        val listed = files.map { file ->
            val entry = manifest?.entries?.get(file.path)?.takeIf { it.matches(file) }
            val known = previous[file.path]?.takeIf { it.size == file.size && it.lastModified == file.lastModified }
            CloudTrack.from(file, entry, known)
        }
        state.value = listed
        writeCache(config, listed)
        if (manifest != null) fillDetails(reader, manifest.entries, config)
        val read = readHeads(client, config)
        if (read.isNotEmpty() && writesMetadata(config)) shareMetadata(client, read, files.mapTo(HashSet(), WebDavFile::path))
        covers.deleteExcept(state.value.mapNotNullTo(HashSet()) { it.coverPath })
    }

    /** Fetches the chunks that hold covers or lyrics not loaded yet, publishing after each one. */
    private suspend fun fillDetails(reader: CloudMetadataReader, entries: Map<String, ManifestEntry>, config: WebDavConfig) {
        val wanted = state.value.filter { track ->
            val entry = entries[track.path] ?: return@filter false
            (entry.hasCover && track.coverPath == null) || ((entry.hasLyrics || entry.hasSyncedLyrics) && track.lyrics == null)
        }
        val chunkIds = wanted.mapNotNullTo(LinkedHashSet()) { entries[it.path]?.chunkId?.takeIf(String::isNotEmpty) }
        val gate = Semaphore(CHUNK_CONCURRENCY)
        coroutineScope {
            chunkIds.map { chunkId ->
                async {
                    val chunk = gate.withPermit { reader.chunk(chunkId) } ?: return@async
                    apply(chunk)
                }
            }.awaitAll()
        }
        writeCache(config, state.value)
    }

    /**
     * Desktop's slow path for songs the manifest does not describe: read the
     * tags from the start of the file. The bytes go into a sparse file of the
     * song's full size, so formats that estimate duration from size get it right.
     */
    private suspend fun readHeads(client: WebDavClient, config: WebDavConfig): Set<String> {
        val pending = state.value.filter { !it.probed && it.durationMs == 0L }
        if (pending.isEmpty()) return emptySet()
        val gate = Semaphore(CHUNK_CONCURRENCY)
        coroutineScope {
            pending.map { track ->
                async {
                    val tags = gate.withPermit { headTags(client, track) }
                    update(track.path) { it.withTags(tags, covers) }
                }
            }.awaitAll()
        }
        writeCache(config, state.value)
        return pending.mapTo(HashSet(), CloudTrack::path)
    }

    /**
     * Shares what the file heads told us through the metadata folder, so other
     * devices list these songs without reading them again. Desktop writes the
     * folder only on 123pan (`providerConfig.ts`; generic WebDAV is read-only),
     * and so does Android. A failed write leaves the library as it is.
     */
    private suspend fun shareMetadata(client: WebDavClient, paths: Set<String>, listed: Set<String>) {
        val songs = state.value.filter { it.path in paths && it.durationMs > 0 }.map { track ->
            val synced = track.toTrack().parsedLyrics().lines.map { SyncedLine(it.startMs / 1000.0, it.text) }
            LocalMetadata(
                path = track.path,
                title = track.title,
                artist = track.artist.orEmpty(),
                album = track.album.orEmpty(),
                duration = track.durationMs / 1000.0,
                fileSize = track.size,
                lastModified = track.lastModified,
                coverDataUrl = track.coverPath?.let(::dataUrl),
                lyrics = track.lyrics,
                syncedLyrics = synced.ifEmpty { null },
            )
        }
        if (songs.isEmpty()) return
        try {
            CloudMetadataWriter(client).upload(songs, listed)
        } catch (error: WebDavException) {
            Log.w(TAG, "Could not write the cloud metadata folder", error)
        }
    }

    /** A cover file as a self-contained `data:` URL, the only kind the metadata folder may hold. */
    private fun dataUrl(path: String): String? {
        val file = File(path).takeIf(File::exists) ?: return null
        val mime = when (file.extension.lowercase()) {
            "png" -> "image/png"
            "webp" -> "image/webp"
            else -> "image/jpeg"
        }
        return "data:$mime;base64," + Base64.getEncoder().encodeToString(file.readBytes())
    }

    private suspend fun headTags(client: WebDavClient, track: CloudTrack): AudioTags? {
        for (limit in HEAD_SIZES) {
            val end = minOf(track.size, limit) - 1
            if (end < 0) return null
            val bytes = try {
                client.fetchRange(track.path, 0, end)
            } catch (error: WebDavException) {
                Log.w(TAG, "Could not read the start of ${track.path}", error)
                return null
            }
            scratchDirectory.mkdirs()
            val scratch = File.createTempFile("head", "." + track.path.substringAfterLast('.', "bin"), scratchDirectory)
            try {
                RandomAccessFile(scratch, "rw").use { file ->
                    file.setLength(maxOf(track.size, bytes.size.toLong()))
                    file.write(bytes)
                }
                return readTagsFromPath(scratch.path)
            } catch (error: TagException) {
                // Cut off inside a big cover or tag; try a larger piece unless the whole file was read.
                if (end + 1 >= track.size) return null
            } finally {
                scratch.delete()
            }
        }
        return null
    }

    @Synchronized
    private fun update(path: String, change: (CloudTrack) -> CloudTrack) {
        state.value = state.value.map { if (it.path == path) change(it) else it }
    }

    @Synchronized
    private fun apply(chunk: Chunk) {
        state.value = state.value.map { track ->
            val entry = chunk.entries[track.path] ?: return@map track
            track.copy(
                coverPath = track.coverPath ?: entry.coverUrl?.let(::decodeDataUrl)?.let { covers.save(it.mimeType, it.data) },
                lyrics = track.lyrics ?: entry.syncedLyrics?.takeIf { it.isNotEmpty() }?.toLrc() ?: entry.lyrics?.takeIf(String::isNotBlank),
            )
        }
    }

    private fun readCache(config: WebDavConfig): List<CloudTrack> = try {
        val root = JSONObject(cacheFile.readText())
        if (root.optString("serverUrl") != config.serverUrl || root.optString("username") != config.username) {
            emptyList()
        } else {
            val list = root.getJSONArray("tracks")
            (0 until list.length()).map { CloudTrack.fromJson(list.getJSONObject(it)) }
        }
    } catch (_: java.io.IOException) {
        emptyList()
    } catch (_: JSONException) {
        emptyList()
    }

    private fun writeCache(config: WebDavConfig, tracks: List<CloudTrack>) {
        val root = JSONObject()
            .put("serverUrl", config.serverUrl)
            .put("username", config.username)
            .put("tracks", JSONArray(tracks.map(CloudTrack::toJson)))
        val partial = File(cacheFile.path + ".part")
        partial.writeText(root.toString())
        partial.renameTo(cacheFile)
    }

    private companion object {
        /** Desktop's `detectProvider`: only 123pan gets the metadata folder written. */
        fun writesMetadata(config: WebDavConfig) = "123pan.cn" in config.serverUrl.lowercase()

        const val TAG = "CloudLibrary"
    }
}

/** A song in the WebDAV library, with the fingerprint its details belong to. */
data class CloudTrack(
    val path: String,
    val title: String,
    val artist: String?,
    val album: String?,
    val durationMs: Long,
    val size: Long,
    val lastModified: String,
    val coverPath: String?,
    val lyrics: String?,
    val wordLyrics: String? = null,
    val wordLyricsFormat: WordLyricsFormat? = null,
    /** The file's own tags were read (or could not be), so they are not read again. */
    val probed: Boolean = false,
) {
    /** Fills in what the file's tags hold, keeping what is already known. */
    fun withTags(tags: AudioTags?, covers: CoverStore): CloudTrack {
        if (tags == null) return copy(probed = true)
        return copy(
            title = tags.title?.takeIf(String::isNotBlank) ?: title,
            artist = tags.artist?.takeIf(String::isNotBlank) ?: artist,
            album = tags.album?.takeIf(String::isNotBlank) ?: album,
            durationMs = tags.durationMs.takeIf { it > 0 } ?: durationMs,
            coverPath = coverPath ?: tags.cover?.let { covers.save(it.mimeType, it.data) },
            lyrics = lyrics ?: tags.lyrics?.takeIf(String::isNotBlank),
            wordLyrics = wordLyrics ?: tags.wordLyrics?.text,
            wordLyricsFormat = wordLyricsFormat ?: tags.wordLyrics?.format?.let { if (it == TagWordLyricsFormat.YRC) WordLyricsFormat.YRC else WordLyricsFormat.QRC },
            probed = true,
        )
    }

    fun toTrack() = Track(
        id = cloudTrackId(path),
        title = title,
        artist = artist,
        album = album,
        durationMs = durationMs,
        coverPath = coverPath,
        lyrics = lyrics,
        wordLyrics = wordLyrics,
        wordLyricsFormat = wordLyricsFormat,
        addedAtMs = 0,
    )

    fun toJson(): JSONObject = JSONObject()
        .put("path", path).put("title", title).put("artist", artist).put("album", album)
        .put("durationMs", durationMs).put("size", size).put("lastModified", lastModified)
        .put("coverPath", coverPath).put("lyrics", lyrics)
        .put("wordLyrics", wordLyrics).put("wordLyricsFormat", wordLyricsFormat?.name).put("probed", probed)

    companion object {
        fun from(file: WebDavFile, entry: ManifestEntry?, known: CloudTrack?) = CloudTrack(
            path = file.path,
            title = entry?.title?.takeIf(String::isNotBlank) ?: known?.title ?: file.name.substringBeforeLast('.'),
            artist = entry?.artist?.takeIf(String::isNotBlank) ?: known?.artist,
            album = entry?.album?.takeIf(String::isNotBlank) ?: known?.album,
            durationMs = entry?.let { (it.duration * 1000).toLong() } ?: known?.durationMs ?: 0,
            size = file.size,
            lastModified = file.lastModified,
            coverPath = known?.coverPath,
            lyrics = known?.lyrics,
            wordLyrics = known?.wordLyrics,
            wordLyricsFormat = known?.wordLyricsFormat,
            // A manifest entry already describes the song; otherwise keep what an earlier read found.
            probed = entry != null || known?.probed == true,
        )

        fun fromJson(json: JSONObject) = CloudTrack(
            path = json.getString("path"),
            title = json.getString("title"),
            artist = json.optStringOrNull("artist"),
            album = json.optStringOrNull("album"),
            durationMs = json.optLong("durationMs"),
            size = json.optLong("size"),
            lastModified = json.optString("lastModified"),
            coverPath = json.optStringOrNull("coverPath")?.takeIf { File(it).exists() },
            lyrics = json.optStringOrNull("lyrics"),
            wordLyrics = json.optStringOrNull("wordLyrics"),
            wordLyricsFormat = json.optStringOrNull("wordLyricsFormat")?.let { name -> WordLyricsFormat.entries.firstOrNull { it.name == name } },
            probed = json.optBoolean("probed"),
        )

        private fun JSONObject.optStringOrNull(name: String): String? = if (isNull(name)) null else optString(name).ifEmpty { null }
    }
}
