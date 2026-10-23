package com.lyricsadapter.app.data

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.lyricsadapter.audiotags.CoverImage
import com.lyricsadapter.audiotags.CoverUpdate
import com.lyricsadapter.audiotags.TagException
import com.lyricsadapter.audiotags.TagUpdate
import com.lyricsadapter.audiotags.WordLyrics
import com.lyricsadapter.audiotags.WordLyricsUpdate
import com.lyricsadapter.audiotags.writeTagsToFd
import com.lyricsadapter.library.Track
import com.lyricsadapter.lyrics.WordLyricsFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.IOException
import com.lyricsadapter.audiotags.WordLyricsFormat as TagWordLyricsFormat

/** How an import went; [failed] lists the files that could not be read, by name. */
data class ImportResult(val imported: Int, val failed: List<String>)

/** Changes made in the metadata editor; a null field is left as stored. */
data class TrackEdit(
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    /** Plain or LRC lyrics. */
    val lyrics: String? = null,
    /** A rebuilt QRC/YRC payload, in the track's own word-lyrics format. */
    val wordLyrics: String? = null,
    val cover: PickedCover? = null,
)

class PickedCover(val mimeType: String, val data: ByteArray)

sealed interface EditResult {
    data object Saved : EditResult

    /** The other changes were saved, but this format cannot hold word lyrics (as on desktop for M4A). */
    data class SavedWithoutWordLyrics(val reason: String) : EditResult

    /** The file was imported with read access only; importing it again asks for write access. */
    data object NoWriteAccess : EditResult
    data class Failed(val message: String) : EditResult
}

/**
 * The local library: files the user picked through the Storage Access
 * Framework, kept in Room. A track's id is its document URI, so picking a
 * file again refreshes it in place. Access to the files is persisted, read
 * and write where the provider allows it, so the library survives restarts
 * and the editor can save tags.
 */
class LibraryRepository(
    private val resolver: ContentResolver,
    private val tracks: TrackDao,
    private val reader: TrackReader,
) {
    val all: Flow<List<Track>> = tracks.observeAll().map { entities -> entities.map(TrackEntity::toTrack) }

    suspend fun import(uris: List<Uri>): ImportResult = withContext(Dispatchers.IO) {
        val positions = tracks.positions().associate { it.id to it.position }
        var nextPosition = (positions.values.maxOrNull() ?: -1) + 1
        val failed = mutableListOf<String>()
        val read = uris.mapNotNull { uri ->
            try {
                persistAccess(uri)
                reader.read(uri, System.currentTimeMillis())
            } catch (error: Exception) {
                if (!error.isFileProblem()) throw error
                val name = reader.displayName(uri)
                Log.w(TAG, "Could not import $name", error)
                failed += name
                null
            }
        }
        val entities = read.map { track -> track.toEntity(positions[track.id] ?: nextPosition++) }
        tracks.upsert(entities)
        ImportResult(imported = entities.size, failed = failed)
    }

    /** Removes tracks from the library; the files themselves are untouched. */
    suspend fun remove(ids: Collection<String>) = withContext(Dispatchers.IO) {
        tracks.delete(ids.toList())
        ids.forEach { id -> releaseAccess(Uri.parse(id)) }
        reader.deleteCoversExcept(tracks.coverPaths().toSet())
    }

    /** Writes [edit] into the file, then reads it back so the library shows what the file now holds. */
    suspend fun edit(track: Track, edit: TrackEdit): EditResult = withContext(Dispatchers.IO) {
        val uri = Uri.parse(track.id)
        try {
            val report = resolver.openFileDescriptor(uri, "rw")?.use { writeTagsToFd(it.fd, edit.toUpdate(track)) }
                ?: return@withContext EditResult.Failed("No file descriptor for $uri")
            val position = tracks.positions().firstOrNull { it.id == track.id }?.position ?: 0
            tracks.upsert(listOf(reader.read(uri, track.addedAtMs).toEntity(position)))
            reader.deleteCoversExcept(tracks.coverPaths().toSet())
            report.wordLyricsError?.let(EditResult::SavedWithoutWordLyrics) ?: EditResult.Saved
        } catch (error: SecurityException) {
            Log.w(TAG, "No write access to ${track.id}", error)
            EditResult.NoWriteAccess
        } catch (error: Exception) {
            if (!error.isFileProblem()) throw error
            Log.w(TAG, "Could not save ${track.id}", error)
            EditResult.Failed(error.message.orEmpty())
        }
    }

    private fun persistAccess(uri: Uri) {
        val readWrite = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        try {
            resolver.takePersistableUriPermission(uri, readWrite)
        } catch (_: SecurityException) {
            // Some providers grant reading only; the track can still be played.
            resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    private fun releaseAccess(uri: Uri) {
        for (flag in listOf(Intent.FLAG_GRANT_WRITE_URI_PERMISSION, Intent.FLAG_GRANT_READ_URI_PERMISSION)) {
            try {
                resolver.releasePersistableUriPermission(uri, flag)
            } catch (_: SecurityException) {
                // Not held; nothing to release.
            }
        }
    }

    private fun Exception.isFileProblem() = this is TagException || this is IOException || this is SecurityException

    private fun TrackEdit.toUpdate(track: Track) = TagUpdate(
        title = title,
        artist = artist,
        album = album,
        lyrics = lyrics,
        cover = cover?.let { CoverUpdate.Replace(CoverImage(it.mimeType, it.data)) } ?: CoverUpdate.Keep,
        wordLyrics = wordLyrics?.let { text ->
            val format = when (track.wordLyricsFormat) {
                WordLyricsFormat.YRC -> TagWordLyricsFormat.YRC
                else -> TagWordLyricsFormat.QRC
            }
            WordLyricsUpdate.Replace(WordLyrics(format, text))
        } ?: WordLyricsUpdate.Keep,
    )

    private companion object {
        const val TAG = "LibraryRepository"
    }
}
