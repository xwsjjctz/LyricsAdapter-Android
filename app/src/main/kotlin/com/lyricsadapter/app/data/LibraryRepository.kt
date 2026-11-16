package com.lyricsadapter.app.data

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import com.lyricsadapter.audiotags.CoverImage
import com.lyricsadapter.audiotags.CoverUpdate
import com.lyricsadapter.audiotags.TagException
import com.lyricsadapter.audiotags.TagUpdate
import com.lyricsadapter.audiotags.WordLyrics
import com.lyricsadapter.audiotags.WordLyricsUpdate
import com.lyricsadapter.audiotags.writeTagsToFd
import com.lyricsadapter.library.FolderSync
import com.lyricsadapter.library.Track
import com.lyricsadapter.lyrics.WordLyricsFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import com.lyricsadapter.audiotags.WordLyricsFormat as TagWordLyricsFormat

/**
 * How an import went; [failed] lists the files that could not be read, by
 * name. Looking through followed folders also counts the tracks [removed]
 * because their files are gone, and the folders that could not be listed.
 */
data class ImportResult(val imported: Int, val failed: List<String>, val removed: Int = 0, val unreadableFolders: Int = 0) {
    operator fun plus(other: ImportResult) =
        ImportResult(imported + other.imported, failed + other.failed, removed + other.removed, unreadableFolders + other.unreadableFolders)
}

/** Reports how many of the files of an import have been read so far. */
typealias ImportProgress = (done: Int, total: Int) -> Unit

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
 * Framework, singly or as whole folders, kept in Room. A track's id is its
 * document URI, so picking a file again refreshes it in place. Access to the
 * files is persisted, read and write where the provider allows it, so the
 * library survives restarts and the editor can save tags.
 *
 * A picked folder is followed from then on ([folders]): looking through it
 * again ([syncFolders]) brings in files added to it and drops tracks whose
 * files are gone. A track of a folder that the user removes from the library
 * is remembered in [exclusions], so it does not come back.
 */
class LibraryRepository(
    private val resolver: ContentResolver,
    private val tracks: TrackDao,
    private val reader: TrackReader,
    private val scanner: FolderScanner,
    private val folders: Setting<Set<String>>,
    private val exclusions: Setting<Set<String>>,
) {
    val all: Flow<List<Track>> = tracks.observeAll().map { entities -> entities.map(TrackEntity::toTrack) }

    /** One import or look through the folders at a time, so two never hand out the same place in the list. */
    private val importing = Mutex()

    suspend fun import(uris: List<Uri>, onProgress: ImportProgress = { _, _ -> }): ImportResult = withContext(Dispatchers.IO) {
        importing.withLock { read(uris, persist = true, onProgress) }
    }

    /** Follows the folder [tree] from now on and imports the music in it, also what the user removed from it before. */
    suspend fun importFolder(tree: Uri, onProgress: ImportProgress = { _, _ -> }): ImportResult = withContext(Dispatchers.IO) {
        importing.withLock {
            try {
                persistAccess(tree)
            } catch (error: SecurityException) {
                Log.w(TAG, "No lasting access to $tree", error)
                return@withLock ImportResult(0, emptyList(), unreadableFolders = 1)
            }
            val prefix = FolderScanner.idPrefix(tree.toString())
            exclusions.set(exclusions.value.value.filterNotTo(LinkedHashSet()) { it.startsWith(prefix) })
            folders.set(folders.value.value + tree.toString())
            syncFolder(tree, onProgress)
        }
    }

    /** Looks through every followed folder: new files are imported, and tracks whose files are gone are removed. */
    suspend fun syncFolders(onProgress: ImportProgress = { _, _ -> }): ImportResult = withContext(Dispatchers.IO) {
        importing.withLock {
            folders.value.value.fold(ImportResult(0, emptyList())) { result, folder -> result + syncFolder(Uri.parse(folder), onProgress) }
        }
    }

    /** Stops following [tree] and removes its tracks from the library; the files themselves are untouched. */
    suspend fun removeFolder(tree: Uri) = withContext(Dispatchers.IO) {
        importing.withLock {
            val prefix = FolderScanner.idPrefix(tree.toString())
            drop(tracks.positions().map { it.id }.filter { it.startsWith(prefix) })
            folders.set(folders.value.value - tree.toString())
            exclusions.set(exclusions.value.value.filterNotTo(LinkedHashSet()) { it.startsWith(prefix) })
            releaseAccess(tree)
        }
    }

    /** Removes tracks from the library; the files themselves are untouched. */
    suspend fun remove(ids: Collection<String>) = withContext(Dispatchers.IO) {
        val prefixes = folders.value.value.map(FolderScanner::idPrefix)
        val fromFolders = ids.filter { id -> prefixes.any(id::startsWith) }
        if (fromFolders.isNotEmpty()) exclusions.set(exclusions.value.value + fromFolders)
        drop(ids)
    }

    private suspend fun drop(ids: Collection<String>) {
        if (ids.isEmpty()) return
        // SQLite takes a limited number of values in one statement.
        ids.chunked(DELETE_BATCH).forEach { tracks.delete(it) }
        ids.forEach { id -> releaseAccess(Uri.parse(id)) }
        reader.deleteCoversExcept(tracks.coverPaths().toSet())
    }

    private suspend fun syncFolder(tree: Uri, onProgress: ImportProgress): ImportResult {
        val ids = tracks.positions().map { it.id }
        val prefix = FolderScanner.idPrefix(tree.toString())
        val ownIds = ids.filterTo(HashSet()) { it.startsWith(prefix) }
        val listed = try {
            scanner.list(tree)
        } catch (error: Exception) {
            if (!error.isFolderProblem()) throw error
            Log.w(TAG, "Could not list $tree", error)
            return ImportResult(0, emptyList(), unreadableFolders = 1)
        }
        // A storage that is not there may list nothing instead of failing; its tracks stay until it says otherwise.
        if (listed.isEmpty() && ownIds.isNotEmpty()) return ImportResult(0, emptyList())
        val keys = ids.mapNotNullTo(HashSet()) { scanner.keyOf(Uri.parse(it)) }
        val changes = FolderSync.changes(listed, keys, ownIds, exclusions.value.value)
        drop(changes.removed)
        // The folder's grant covers its files; there is none to take for each.
        return read(changes.added.map { Uri.parse(it.id) }, persist = false, onProgress).copy(removed = changes.removed.size)
    }

    /** Reads [uris] into the library a few at a time, so a long import shows up as it goes. */
    private suspend fun read(uris: List<Uri>, persist: Boolean, onProgress: ImportProgress): ImportResult {
        val positions = tracks.positions().associate { it.id to it.position }
        var nextPosition = (positions.values.maxOrNull() ?: -1) + 1
        val failed = mutableListOf<String>()
        var imported = 0
        var done = 0
        uris.chunked(READ_BATCH).forEach { batch ->
            val read = batch.mapNotNull { uri ->
                try {
                    if (persist) persistAccess(uri)
                    reader.read(uri, System.currentTimeMillis())
                } catch (error: Exception) {
                    if (!error.isFileProblem()) throw error
                    val name = reader.displayName(uri)
                    Log.w(TAG, "Could not import $name", error)
                    failed += name
                    null
                }
            }
            tracks.upsert(read.map { track -> track.toEntity(positions[track.id] ?: nextPosition++) })
            imported += read.size
            done += batch.size
            onProgress(done, uris.size)
        }
        return ImportResult(imported = imported, failed = failed)
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
        // Songs the app saved itself (downloads) are its own; MediaStore grants nothing to persist.
        if (uri.authority == MediaStore.AUTHORITY) return
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

    /** What listing a folder fails with when it was moved or deleted, its storage is gone, or its grant was taken back. */
    private fun Exception.isFolderProblem() = this is IOException || this is SecurityException || this is IllegalArgumentException

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
        const val READ_BATCH = 8
        const val DELETE_BATCH = 500
    }
}
