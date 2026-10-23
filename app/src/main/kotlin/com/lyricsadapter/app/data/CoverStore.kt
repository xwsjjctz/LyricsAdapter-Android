package com.lyricsadapter.app.data

import java.io.File
import java.security.MessageDigest

/** Cover images in app storage, named by content hash, so the songs of one album share one file. */
class CoverStore(private val directory: File) {
    /** Stores [data] once and returns the file's path. */
    fun save(mimeType: String, data: ByteArray): String {
        val hash = MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }
        val extension = when (mimeType.lowercase()) {
            "image/png" -> "png"
            "image/webp" -> "webp"
            else -> "jpg"
        }
        val file = File(directory, "$hash.$extension")
        if (!file.exists()) {
            directory.mkdirs()
            val partial = File(directory, "$hash.$extension.part")
            partial.writeBytes(data)
            partial.renameTo(file)
        }
        return file.absolutePath
    }

    /** Deletes covers no longer referred to. */
    fun deleteExcept(inUse: Set<String>) {
        directory.listFiles()?.filter { it.absolutePath !in inUse }?.forEach { it.delete() }
    }
}
