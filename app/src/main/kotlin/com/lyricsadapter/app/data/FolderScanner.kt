package com.lyricsadapter.app.data

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.MediaStore
import com.lyricsadapter.library.FolderFile
import com.lyricsadapter.library.FolderSync
import java.io.IOException

/**
 * Lists the music in a folder the user picked through the Storage Access
 * Framework (a document tree), subfolders included. The one grant for the
 * tree covers every file in it, so a folder of any size costs one of the few
 * hundred grants an app may hold, where files picked singly cost one each.
 */
class FolderScanner(private val resolver: ContentResolver) {
    /**
     * Every audio file under [tree]; hidden files and folders are left out.
     * @throws IOException, SecurityException or IllegalArgumentException when the folder cannot be listed.
     */
    fun list(tree: Uri): List<FolderFile> {
        val authority = tree.authority.orEmpty()
        val found = ArrayList<FolderFile>()
        val pending = ArrayDeque(listOf(DocumentsContract.getTreeDocumentId(tree) to ""))
        while (pending.isNotEmpty()) {
            val (folder, folderPath) = pending.removeFirst()
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, folder)
            val cursor = resolver.query(children, PROJECTION, null, null, null) ?: throw IOException("Could not list $children")
            cursor.use {
                while (cursor.moveToNext()) {
                    val id = cursor.getString(0) ?: continue
                    val name = cursor.getString(1).orEmpty()
                    val type = cursor.getString(2)
                    val path = if (folderPath.isEmpty()) name else "$folderPath/$name"
                    when {
                        name.startsWith(".") -> Unit
                        type == Document.MIME_TYPE_DIR -> pending += id to path
                        FolderSync.isAudio(name, type) ->
                            found += FolderFile(DocumentsContract.buildDocumentUriUsingTree(tree, id).toString(), key(authority, id), path)
                    }
                }
            }
        }
        return found
    }

    /**
     * What names the file behind a track's [uri], equal for a file reached
     * through a folder, picked by itself, or saved by the app to shared
     * storage (a download); null for anything else.
     */
    fun keyOf(uri: Uri): String? = when (uri.authority) {
        MediaStore.AUTHORITY -> mediaStoreKey(uri)
        else -> try {
            key(uri.authority.orEmpty(), DocumentsContract.getDocumentId(uri))
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    /** A song in MediaStore on the built-in storage, named as the storage provider names that file. */
    private fun mediaStoreKey(uri: Uri): String? = try {
        resolver.query(uri, MEDIA_PROJECTION, null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst() || cursor.getString(0) != MediaStore.VOLUME_EXTERNAL_PRIMARY) return@use null
            key(EXTERNAL_STORAGE, "primary:${cursor.getString(1).orEmpty()}${cursor.getString(2).orEmpty()}")
        }
    } catch (_: SecurityException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun key(authority: String, documentId: String) = "$authority/$documentId"

    companion object {
        private const val EXTERNAL_STORAGE = "com.android.externalstorage.documents"
        private val PROJECTION = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE)
        private val MEDIA_PROJECTION = arrayOf(MediaStore.MediaColumns.VOLUME_NAME, MediaStore.MediaColumns.RELATIVE_PATH, MediaStore.MediaColumns.DISPLAY_NAME)

        /** Where a track from the folder [tree] starts its id. */
        fun idPrefix(tree: String): String = "$tree/document/"

        /** What to call the folder [tree]: its path on its storage, as "Music/LyricsAdapter". */
        fun label(tree: Uri): String = try {
            val id = DocumentsContract.getTreeDocumentId(tree)
            id.substringAfter(':').ifEmpty { id }
        } catch (_: IllegalArgumentException) {
            tree.lastPathSegment.orEmpty()
        }
    }
}
