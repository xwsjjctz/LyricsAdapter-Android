package com.lyricsadapter.app.data

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import com.lyricsadapter.audiotags.readTagsFromFd
import com.lyricsadapter.library.Track
import com.lyricsadapter.library.TrackFactory
import java.io.IOException

/** Reads a document's tags into a [Track] and keeps its cover in [covers]. */
class TrackReader(private val resolver: ContentResolver, private val covers: CoverStore) {
    /** @throws IOException, SecurityException or a tag error when the file cannot be read. */
    fun read(uri: Uri, addedAtMs: Long): Track {
        val tags = resolver.openFileDescriptor(uri, "r")?.use { readTagsFromFd(it.fd) }
            ?: throw IOException("No file descriptor for $uri")
        return TrackFactory.fromTags(
            id = uri.toString(),
            fileName = displayName(uri),
            tags = tags,
            coverPath = tags.cover?.let { covers.save(it.mimeType, it.data) },
            addedAtMs = addedAtMs,
        )
    }

    fun displayName(uri: Uri): String =
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        } ?: uri.lastPathSegment.orEmpty()

    /** Deletes stored covers that no track in [inUse] refers to. */
    fun deleteCoversExcept(inUse: Set<String>) = covers.deleteExcept(inUse)
}
