package com.lyricsadapter.webdav

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.util.Base64

/*
 * Desktop's WebDAV Metadata v3 (`metadataFolderService.ts`):
 *
 *   Metadata/_manifest.json    light index: list text and fingerprints, no covers or lyrics
 *   Metadata/_chunk_0001.json  heavy details (cover data URL, lyrics) for up to 50 songs
 *
 * Paths are the songs' library paths. CloudMetadataReader reads the folder;
 * CloudMetadataWriter writes it where desktop would (123pan).
 */

@Serializable
data class Manifest(
    val version: Int,
    val generatedAt: String = "",
    val chunkSize: Int = 50,
    val entries: Map<String, ManifestEntry> = emptyMap(),
)

@Serializable
data class ManifestEntry(
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    /** Seconds. */
    val duration: Double = 0.0,
    val fileSize: Long = 0,
    val fileName: String = "",
    /** The listing's `getlastmodified`, verbatim. */
    val lastModified: String = "",
    val chunkId: String = "",
    val hasCover: Boolean = false,
    val hasLyrics: Boolean = false,
    val hasSyncedLyrics: Boolean = false,
) {
    /** Desktop trusts an entry only while the file it describes is unchanged and the entry is complete. */
    fun matches(file: WebDavFile): Boolean = fileSize == file.size && lastModified == file.lastModified && duration > 0
}

@Serializable
data class Chunk(
    val chunkId: String = "",
    val entries: Map<String, ChunkEntry> = emptyMap(),
)

@Serializable
data class ChunkEntry(
    /** A `data:` URL; desktop also wrote machine-local `cover://` pointers, which mean nothing here. */
    val coverUrl: String? = null,
    val lyrics: String? = null,
    val syncedLyrics: List<SyncedLine>? = null,
)

@Serializable
data class SyncedLine(
    /** Seconds. */
    val time: Double,
    val text: String,
)

/** A cover decoded from a chunk's data URL. */
class CoverBytes(val mimeType: String, val data: ByteArray)

private val json = Json { ignoreUnknownKeys = true }

internal const val MANIFEST_PATH = "/Metadata/_manifest.json"

fun chunkPath(chunkId: String) = "/Metadata/_chunk_$chunkId.json"

/** Reads the metadata folder; anything missing or unreadable is null, as desktop falls back to the audio files. */
class CloudMetadataReader(private val client: WebDavClient) {
    suspend fun manifest(): Manifest? = parse<Manifest>(client.fetchText(MANIFEST_PATH))?.takeIf { it.version == 3 }

    suspend fun chunk(chunkId: String): Chunk? = parse<Chunk>(client.fetchText(chunkPath(chunkId)))

    private inline fun <reified T> parse(text: String?): T? = try {
        text?.let { json.decodeFromString<T>(it) }
    } catch (_: SerializationException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }
}

/** LRC text for timed lines, so cloud lyrics go through the same parser as file lyrics. */
fun List<SyncedLine>.toLrc(): String = joinToString("\n") { line ->
    val centis = Math.round(line.time * 100).coerceAtLeast(0)
    "[%02d:%02d.%02d]%s".format(centis / 6000, centis / 100 % 60, centis % 100, line.text)
}

/** The bytes of a base64 `data:` URL, or null for anything else. */
fun decodeDataUrl(url: String): CoverBytes? {
    val match = DATA_URL.matchEntire(url) ?: return null
    return try {
        CoverBytes(match.groupValues[1].ifEmpty { "image/jpeg" }, Base64.getMimeDecoder().decode(match.groupValues[2]))
    } catch (_: IllegalArgumentException) {
        null
    }
}

private val DATA_URL = Regex("data:([^;,]*)(?:;[^,]*)?;base64,(.*)", RegexOption.DOT_MATCHES_ALL)
