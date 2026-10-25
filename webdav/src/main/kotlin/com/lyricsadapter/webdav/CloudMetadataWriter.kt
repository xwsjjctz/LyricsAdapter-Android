package com.lyricsadapter.webdav

import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.temporal.ChronoUnit

/** What the app knows about one song in the cloud library, ready to be shared through the metadata folder. */
data class LocalMetadata(
    val path: String,
    val title: String,
    val artist: String,
    val album: String,
    /** Seconds. */
    val duration: Double,
    val fileSize: Long,
    /** The listing's `getlastmodified`, verbatim. */
    val lastModified: String,
    /** A self-contained `data:` URL; never a path on this device. */
    val coverDataUrl: String?,
    val lyrics: String?,
    val syncedLyrics: List<SyncedLine>?,
)

/**
 * Writes the metadata folder the way desktop's `createManifestSync`
 * (`manifestSync.ts`) does. The server's manifest is the baseline: entries
 * stay as they are unless [LocalMetadata] describes the song, and entries for
 * files no longer listed are dropped. Each chunk with updates is re-read,
 * merged (a song without a new cover keeps the chunk's cover) and cleared of
 * songs the manifest no longer has. Chunks are written first and the manifest
 * last, so a failure leaves at worst an orphan chunk, never a manifest that
 * points at a broken one. Nothing is written when nothing changed.
 */
class CloudMetadataWriter(private val client: WebDavClient, private val clock: () -> Instant = Instant::now) {
    private val reader = CloudMetadataReader(client)

    /** Merges [local] into the server's metadata for the files in [listed]; returns whether anything was written. */
    suspend fun upload(local: List<LocalMetadata>, listed: Set<String>): Boolean {
        val existing = reader.manifest()
        val chunkSize = existing?.chunkSize ?: DEFAULT_CHUNK_SIZE
        val entries = LinkedHashMap<String, ManifestEntry>()
        var manifestChanged = false
        existing?.entries?.forEach { (path, entry) -> if (path in listed) entries[path] = entry else manifestChanged = true }

        val updates = LinkedHashMap<String, Pair<String, ChunkEntry>>()
        for (song in local.filter { it.path in listed }) {
            val previous = entries[song.path]
            val chunkId = previous?.chunkId ?: assignChunkId(entries, chunkSize)
            val next = ManifestEntry(
                title = song.title,
                artist = song.artist,
                album = song.album.ifEmpty { "Unknown Album" },
                duration = song.duration,
                fileSize = song.fileSize,
                fileName = song.path.substringAfterLast('/').ifEmpty { "audio.flac" },
                lastModified = song.lastModified,
                chunkId = chunkId,
                hasCover = song.coverDataUrl != null || previous?.hasCover == true,
                hasLyrics = !song.lyrics.isNullOrEmpty(),
                hasSyncedLyrics = !song.syncedLyrics.isNullOrEmpty(),
            )
            if (next != previous) manifestChanged = true
            entries[song.path] = next
            if (song.coverDataUrl != null || !song.lyrics.isNullOrEmpty() || !song.syncedLyrics.isNullOrEmpty()) {
                updates[song.path] = chunkId to ChunkEntry(song.coverDataUrl, song.lyrics?.ifEmpty { null }, song.syncedLyrics?.ifEmpty { null })
            }
        }
        if (!existing?.entries.isNullOrEmpty() && updates.isEmpty() && !manifestChanged) return false

        val chunks = updates.values.map { it.first }.toSortedSet().associateWith { chunkId ->
            val stored = reader.chunk(chunkId)?.takeIf { it.chunkId == chunkId }?.entries.orEmpty()
            val merged = LinkedHashMap(stored)
            updates.forEach { (path, update) ->
                if (update.first != chunkId) return@forEach
                val keptCover = stored[path]?.coverUrl?.takeIf { update.second.coverUrl == null && it.startsWith("data:") }
                merged[path] = if (keptCover != null) update.second.copy(coverUrl = keptCover) else update.second
            }
            merged.keys.retainAll(entries.keys)
            Chunk(chunkId, merged)
        }
        val manifest = Manifest(version = 3, generatedAt = clock().truncatedTo(ChronoUnit.MILLIS).toString(), chunkSize = chunkSize, entries = entries)

        client.ensureCollection(FOLDER)
        chunks.forEach { (chunkId, chunk) -> putWithFolder(chunkPath(chunkId), json.encodeToString(Chunk.serializer(), chunk)) }
        putWithFolder(MANIFEST_PATH, json.encodeToString(Manifest.serializer(), manifest))
        return true
    }

    /** Desktop's `uploadJsonWithMkcolRetry`: one retry after making the folder, for servers that answer 409 without it. */
    private suspend fun putWithFolder(path: String, text: String) {
        try {
            client.putText(path, text)
        } catch (_: WebDavException) {
            client.ensureCollection(FOLDER)
            client.putText(path, text)
        }
    }

    companion object {
        const val DEFAULT_CHUNK_SIZE = 50
        private const val FOLDER = "/Metadata"
        private val json = Json { encodeDefaults = true; explicitNulls = false }

        /**
         * Desktop's `assignChunkId` for a new song: the highest-numbered chunk
         * while it has room, else the next one; `0001` when there is none.
         */
        fun assignChunkId(entries: Map<String, ManifestEntry>, chunkSize: Int): String {
            val counts = entries.values.groupingBy { it.chunkId }.eachCount()
            val highest = entries.values.mapNotNull { it.chunkId.toIntOrNull() }.maxOrNull() ?: return pad(1)
            if (highest == 0) return pad(1)
            return if ((counts[pad(highest)] ?: 0) < chunkSize) pad(highest) else pad(highest + 1)
        }

        private fun pad(number: Int) = number.toString().padStart(4, '0')
    }
}
