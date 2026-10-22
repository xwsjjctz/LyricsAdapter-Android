package com.lyricsadapter.audiotags

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Compares the Android tag reader and writer with the desktop app. The
 * fixtures and expected.json come from fixtures/generate.mjs, which tags the
 * files with desktop's libraries and records what desktop reads back.
 * Files written here are also copied to build/written for fixtures/read.mjs.
 */
class AudioTagsTest {
    @TempDir
    lateinit var temporary: File

    private val fixtures = File(requireNotNull(System.getProperty("audioTags.fixtures")) { "audioTags.fixtures is not set" })
    private val written = File(requireNotNull(System.getProperty("audioTags.written")) { "audioTags.written is not set" })
    private val expected = Json.parseToJsonElement(fixtures.resolve("expected.json").readText()).jsonObject
    private val pngCover = CoverImage("image/png", fixtures.resolve("files/cover.png").readBytes())

    @Test
    fun `reads every fixture the way desktop does`() {
        for (name in expected.keys) {
            val desktop = desktop(name)
            val tags = readTagsFromPath(copyOf(name).path)

            assertEquals(desktop.text("title"), tags.title, "$name title")
            assertEquals(desktop.text("artist"), tags.artist, "$name artist")
            assertEquals(desktop.text("album"), tags.album, "$name album")
            assertEquals(desktop.text("lyrics"), tags.lyrics, "$name lyrics")
            assertEquals(desktop.wordLyrics(), tags.wordLyrics, "$name word lyrics")
            assertEquals(desktop.getValue("durationMs").jsonPrimitive.longOrNull, tags.durationMs, "$name duration")
            assertEquals(desktop.getValue("bitRate").jsonPrimitive.intOrNull, tags.bitRate, "$name bit rate")
            assertEquals(desktop.getValue("sampleRate").jsonPrimitive.intOrNull, tags.sampleRate, "$name sample rate")
            assertEquals(desktop.coverSummary(), tags.cover?.summary(), "$name cover")
        }
    }

    @Test
    fun `written fields and word lyrics read back from mp3 and flac`() {
        for (name in listOf("tagged.mp3", "tagged.flac", "untagged.mp3")) {
            val file = copyOf(name)
            val update = TagUpdate(
                title = "七里香",
                artist = "周杰伦",
                album = "七里香",
                lyrics = LRC,
                cover = CoverUpdate.Replace(pngCover),
                wordLyrics = WordLyricsUpdate.Replace(WordLyrics(WordLyricsFormat.QRC, QRC)),
            )

            assertNull(writeTagsToPath(file.path, update).wordLyricsError, name)

            val tags = readTagsFromPath(file.path)
            assertEquals("七里香", tags.title, name)
            assertEquals("周杰伦", tags.artist, name)
            assertEquals("七里香", tags.album, name)
            assertEquals(LRC, tags.lyrics, name)
            assertEquals(WordLyrics(WordLyricsFormat.QRC, QRC), tags.wordLyrics, name)
            assertEquals(pngCover.summary(), tags.cover?.summary(), name)
            keepForDesktop(file)
        }
    }

    @Test
    fun `editing common fields keeps word lyrics and data lofty cannot map`() {
        for (name in listOf("tagged.mp3", "tagged.flac")) {
            val file = copyOf(name)

            writeTagsToPath(file.path, TagUpdate(title = "七里香", cover = CoverUpdate.Replace(pngCover), wordLyrics = WordLyricsUpdate.Keep))

            assertEquals(desktop(name).wordLyrics(), readTagsFromPath(file.path).wordLyrics, name)
            assertTrue(file.readBytes().containsUtf8(UNMAPPED_MARKER), "$name lost its unmapped data")
        }
    }

    @Test
    fun `editing an ID3v1-only mp3 keeps its ID3v1 fields visible`() {
        val file = copyOf("id3v1-only.mp3")

        writeTagsToPath(file.path, TagUpdate(album = "New Album", cover = CoverUpdate.Keep, wordLyrics = WordLyricsUpdate.Keep))

        val tags = readTagsFromPath(file.path)
        assertEquals("New Album", tags.album)
        assertEquals(desktop("id3v1-only.mp3").text("title"), tags.title)
        assertEquals(desktop("id3v1-only.mp3").text("artist"), tags.artist)
    }

    @Test
    fun `removing QRC leaves the stored YRC readable`() {
        val file = copyOf("tagged.flac")

        writeTagsToPath(file.path, TagUpdate(cover = CoverUpdate.Keep, wordLyrics = WordLyricsUpdate.Remove(WordLyricsFormat.QRC)))

        // The same YRC payload is the only word lyrics in lowercase-yrc.flac.
        assertEquals(desktop("lowercase-yrc.flac").wordLyrics(), readTagsFromPath(file.path).wordLyrics)
    }

    @Test
    fun `replacing a lowercase field keeps a single value`() {
        val file = copyOf("lowercase-yrc.flac")
        val replacement = WordLyrics(WordLyricsFormat.YRC, "[0,500](0,500,0)稻")

        writeTagsToPath(file.path, TagUpdate(cover = CoverUpdate.Keep, wordLyrics = WordLyricsUpdate.Replace(replacement)))

        assertEquals(replacement, readTagsFromPath(file.path).wordLyrics)
        keepForDesktop(file)
    }

    @Test
    fun `m4a saves common fields and reports word lyrics as unsupported`() {
        val file = copyOf("tagged.m4a")
        val update = TagUpdate(
            title = "稻香",
            cover = CoverUpdate.Remove,
            wordLyrics = WordLyricsUpdate.Replace(WordLyrics(WordLyricsFormat.QRC, QRC)),
        )

        assertNotNull(writeTagsToPath(file.path, update).wordLyricsError)

        val tags = readTagsFromPath(file.path)
        assertEquals("稻香", tags.title)
        assertEquals("周杰伦", tags.artist)
        assertNull(tags.cover)
        keepForDesktop(file)
    }

    @Test
    fun `an update that keeps everything leaves the file byte for byte`() {
        val file = copyOf("tagged.mp3")
        val before = file.readBytes()

        writeTagsToPath(file.path, TagUpdate(cover = CoverUpdate.Keep, wordLyrics = WordLyricsUpdate.Keep))

        assertContentEquals(before, file.readBytes())
    }

    @Test
    fun `files that are not audio are unsupported`() {
        val file = temporary.resolve("notes.txt").apply { writeText("not audio") }
        assertFailsWith<TagException.Unsupported> { readTagsFromPath(file.path) }
    }

    private fun copyOf(name: String): File = fixtures.resolve("files/$name").copyTo(temporary.resolve(name))

    private fun keepForDesktop(file: File) {
        file.copyTo(written.resolve(file.name), overwrite = true)
    }

    private fun desktop(name: String): JsonObject = expected.getValue(name).jsonObject

    private fun JsonObject.text(name: String): String? = (get(name) as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.wordLyrics(): WordLyrics? = (get("wordLyrics") as? JsonObject)?.let {
        val format = if (it.text("format") == "qrc") WordLyricsFormat.QRC else WordLyricsFormat.YRC
        WordLyrics(format, requireNotNull(it.text("text")))
    }

    private fun JsonObject.coverSummary(): CoverSummary? = (get("cover") as? JsonObject)?.let {
        CoverSummary(requireNotNull(it.text("mimeType")), it.getValue("size").jsonPrimitive.intOrNull ?: -1, it.text("sha256"))
    }

    private fun CoverImage.summary() = CoverSummary(mimeType, data.size, sha256(data))

    private fun ByteArray.containsUtf8(text: String): Boolean {
        val needle = text.toByteArray()
        return (0..size - needle.size).any { start -> needle.indices.all { this[start + it] == needle[it] } }
    }

    private data class CoverSummary(val mimeType: String, val size: Int, val sha256: String?)

    private companion object {
        /** Written by fixtures/generate.mjs into a PRIV frame and a custom Vorbis field. */
        const val UNMAPPED_MARKER = "unmapped data to keep"
        const val LRC = "[ti:七里香]\n[00:00.00]窗外的麻雀\n[00:01.00]在电线杆上多嘴\n"
        const val QRC = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<QrcInfos>\n<LyricInfo LyricCount=\"1\">\n" +
            "<Lyric_1 LyricType=\"1\" LyricContent=\"[0,1000]窗(0,500)外(500,500)\n\"/>\n</LyricInfo>\n</QrcInfos>"

        fun sha256(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
