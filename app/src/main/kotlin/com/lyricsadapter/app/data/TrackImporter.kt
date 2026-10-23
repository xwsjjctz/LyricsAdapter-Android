package com.lyricsadapter.app.data

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import com.lyricsadapter.audiotags.CoverImage
import com.lyricsadapter.audiotags.TagException
import com.lyricsadapter.audiotags.readTagsFromFd
import com.lyricsadapter.library.Track
import com.lyricsadapter.library.TrackFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/** How an import went; [failed] lists the files that could not be read, by name. */
data class ImportResult(val imported: Int, val failed: List<String>)

/**
 * Adds files the user picked through the Storage Access Framework. The read
 * permission is persisted so the library survives restarts; a track's id is
 * its document URI, so picking a file again refreshes it in place.
 */
class TrackImporter(
    private val resolver: ContentResolver,
    private val tracks: TrackDao,
    private val coverDirectory: File,
) {
    suspend fun import(uris: List<Uri>): ImportResult = withContext(Dispatchers.IO) {
        val positions = tracks.positions().associate { it.id to it.position }
        var nextPosition = (positions.values.maxOrNull() ?: -1) + 1
        val failed = mutableListOf<String>()
        fun skip(name: String, error: Exception): Track? {
            Log.w(TAG, "Could not import $name", error)
            failed += name
            return null
        }
        val read = uris.mapNotNull { uri ->
            val name = displayName(uri)
            try {
                resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                readTrack(uri, name)
            } catch (error: TagException) {
                skip(name, error)
            } catch (error: IOException) {
                skip(name, error)
            } catch (error: SecurityException) {
                skip(name, error)
            }
        }
        val entities = read.map { track -> track.toEntity(positions[track.id] ?: nextPosition++) }
        tracks.upsert(entities)
        ImportResult(imported = entities.size, failed = failed)
    }

    private fun readTrack(uri: Uri, name: String): Track {
        val tags = resolver.openFileDescriptor(uri, "r")?.use { readTagsFromFd(it.fd) }
            ?: throw IOException("No file descriptor for $uri")
        return TrackFactory.fromTags(
            id = uri.toString(),
            fileName = name,
            tags = tags,
            coverPath = tags.cover?.let(::saveCover),
            addedAtMs = System.currentTimeMillis(),
        )
    }

    /** Covers are stored by content hash, so tracks of one album share a file. */
    private fun saveCover(cover: CoverImage): String {
        val hash = MessageDigest.getInstance("SHA-256").digest(cover.data).joinToString("") { "%02x".format(it) }
        val extension = when (cover.mimeType.lowercase()) {
            "image/png" -> "png"
            "image/webp" -> "webp"
            else -> "jpg"
        }
        val file = File(coverDirectory, "$hash.$extension")
        if (!file.exists()) {
            coverDirectory.mkdirs()
            val partial = File(coverDirectory, "$hash.$extension.part")
            partial.writeBytes(cover.data)
            partial.renameTo(file)
        }
        return file.absolutePath
    }

    private fun displayName(uri: Uri): String =
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        } ?: uri.lastPathSegment.orEmpty()

    private companion object {
        const val TAG = "TrackImporter"
    }
}
