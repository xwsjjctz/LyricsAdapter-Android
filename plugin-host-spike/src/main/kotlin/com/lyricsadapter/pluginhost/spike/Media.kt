package com.lyricsadapter.pluginhost.spike

import okhttp3.OkHttpClient
import okhttp3.Request
import java.nio.file.Path
import java.util.Base64
import kotlin.io.path.createDirectories
import kotlin.io.path.writeBytes

/** What the first bytes of a stream request returned, as the player's data source would see them. */
class StreamProbe(val status: Int, val contentType: String?, val contentRange: String?, val format: String) {
    override fun toString() = "HTTP $status, ${contentType ?: "no content-type"}, ${contentRange ?: "no content-range"}, $format"
}

object Media {
    private val client = OkHttpClient()

    /** Requests the first 16 bytes of [url] with the plugin's stream headers. */
    fun probeStream(url: String, headers: Map<String, String>): StreamProbe {
        val request = Request.Builder().url(url).apply {
            headers.forEach { (name, value) -> header(name, value) }
            header("Range", "bytes=0-15")
        }.build()
        return client.newCall(request).execute().use { response ->
            val bytes = response.body.byteStream().readNBytes(16)
            check(response.isSuccessful) { "HTTP ${response.code}" }
            StreamProbe(response.code, response.header("content-type"), response.header("content-range"), audioFormat(bytes))
        }
    }

    private fun audioFormat(bytes: ByteArray): String {
        fun ascii(from: Int, to: Int) = bytes.copyOfRange(from, minOf(to, bytes.size)).toString(Charsets.ISO_8859_1)
        return when {
            ascii(0, 3) == "ID3" -> "MP3 (ID3)"
            bytes.size >= 2 && bytes[0] == 0xFF.toByte() && (bytes[1].toInt() and 0xE0) == 0xE0 -> "MP3 frame"
            ascii(0, 4) == "fLaC" -> "FLAC"
            ascii(4, 8) == "ftyp" -> "MP4/M4A"
            ascii(0, 4) == "OggS" -> "Ogg"
            else -> "unknown (${bytes.joinToString(" ") { "%02x".format(it) }})"
        }
    }

    /** Decodes a `data:image/png;base64,...` URL, checks the PNG signature and writes it to [file]. */
    fun savePng(dataUrl: String, file: Path): Int {
        val prefix = "data:image/png;base64,"
        require(dataUrl.startsWith(prefix)) { "Not a PNG data URL: ${dataUrl.take(40)}" }
        val bytes = Base64.getDecoder().decode(dataUrl.removePrefix(prefix))
        require(bytes.take(8) == PNG_SIGNATURE) { "Missing PNG signature" }
        file.parent?.createDirectories()
        file.writeBytes(bytes)
        return bytes.size
    }

    private val PNG_SIGNATURE = listOf(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A).map(Int::toByte)
}
