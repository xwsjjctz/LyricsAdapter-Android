package com.lyricsadapter.spike

import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lyricsadapter.audiotags.CoverImage
import com.lyricsadapter.audiotags.CoverUpdate
import com.lyricsadapter.audiotags.TagUpdate
import com.lyricsadapter.audiotags.WordLyricsFormat
import com.lyricsadapter.audiotags.WordLyricsUpdate
import com.lyricsadapter.audiotags.readTagsFromFd
import com.lyricsadapter.audiotags.writeTagsToFd
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import kotlin.time.measureTimedValue

/**
 * The Rust tag library on a device, through file descriptors as the Storage
 * Access Framework hands them out. Expectations are what desktop reads from
 * the same fixtures (audio-tags/fixtures/expected.json).
 */
@RunWith(AndroidJUnit4::class)
class AudioTagsDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val expected = Json.parseToJsonElement(assetText("audio-tags/expected.json")).jsonObject
    private val workDirectory = File(context.cacheDir, "audio-tags").apply {
        deleteRecursively()
        mkdirs()
    }

    @Test
    fun readsFixturesThroughDescriptorsLikeDesktop() {
        for (name in expected.keys) {
            val file = copyAsset(name)
            val (tags, elapsed) = measureTimedValue {
                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { readTagsFromFd(it.fd) }
            }
            Log.i(TAG, "$name read in ${elapsed.inWholeMicroseconds} µs")

            val desktop = expected.getValue(name).jsonObject
            assertEquals("$name title", desktop.text("title"), tags.title)
            assertEquals("$name artist", desktop.text("artist"), tags.artist)
            assertEquals("$name album", desktop.text("album"), tags.album)
            assertEquals("$name lyrics", desktop.text("lyrics"), tags.lyrics)
            val wordLyrics = desktop["wordLyrics"] as? JsonObject
            assertEquals("$name word lyrics format", wordLyrics?.text("format"), tags.wordLyrics?.format?.name?.lowercase())
            assertEquals("$name word lyrics", wordLyrics?.text("text"), tags.wordLyrics?.text)
            assertEquals("$name duration", desktop.getValue("durationMs").jsonPrimitive.long, tags.durationMs)
            val cover = desktop["cover"] as? JsonObject
            assertEquals("$name cover", cover?.text("sha256"), tags.cover?.data?.let(::sha256))
        }
    }

    @Test
    fun writesThroughReadWriteDescriptor() {
        val file = copyAsset("tagged.flac")
        val cover = CoverImage("image/png", context.assets.open("audio-tags/files/cover.png").use { it.readBytes() })
        val update = TagUpdate(
            title = "七里香",
            cover = CoverUpdate.Replace(cover),
            wordLyrics = WordLyricsUpdate.Remove(WordLyricsFormat.QRC),
        )

        val (report, elapsed) = measureTimedValue {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_WRITE).use { writeTagsToFd(it.fd, update) }
        }
        Log.i(TAG, "tagged.flac written in ${elapsed.inWholeMicroseconds} µs")
        assertNull(report.wordLyricsError)

        val tags = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { readTagsFromFd(it.fd) }
        assertEquals("七里香", tags.title)
        assertEquals(WordLyricsFormat.YRC, tags.wordLyrics?.format)
        assertArrayEquals(cover.data, tags.cover?.data)
    }

    private fun copyAsset(name: String): File {
        val file = File(workDirectory, name)
        context.assets.open("audio-tags/files/$name").use { input -> file.outputStream().use(input::copyTo) }
        return file
    }

    private fun assetText(path: String): String = context.assets.open(path).bufferedReader().use { it.readText() }

    private fun JsonObject.text(name: String): String? = (get(name) as? JsonPrimitive)?.contentOrNull

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private companion object {
        const val TAG = "LASpike"
    }
}
