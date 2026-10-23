package com.lyricsadapter.lyrics

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.math.roundToLong
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Compares the parser with desktop. fixtures/expected.json records what
 * desktop's lrcParser.ts returns for each case (fixtures/generate.mjs).
 */
class LyricsParserTest {
    private val fixtures = File(requireNotNull(System.getProperty("lyrics.fixtures")) { "lyrics.fixtures is not set" })
    private val cases = Json.parseToJsonElement(fixtures.resolve("expected.json").readText()).jsonArray.map { it.jsonObject }

    @Test
    fun `parses every case the way desktop does`() {
        for (case in cases) {
            val name = case.getValue("name").jsonPrimitive.content
            val format = case.getValue("format").jsonPrimitive.contentOrNull?.let { WordLyricsFormat.valueOf(it.uppercase()) }
            val parsed = LyricsParser.parse(
                lyrics = case.getValue("lyrics").jsonPrimitive.content,
                wordLyrics = case.getValue("wordLyrics").jsonPrimitive.contentOrNull,
                format = format,
            )
            val desktop = case.getValue("result").jsonObject

            assertEquals(desktop.getValue("plainText").jsonPrimitive.content, parsed.plainText, "$name plain text")
            assertEquals(desktop.lines(), parsed.lines, "$name lines")
        }
    }

    @Test
    fun `active index finds the last started line`() {
        val lines = listOf(LyricLine(1000, "a"), LyricLine(2000, "b"), LyricLine(3000, "c"))
        assertEquals(-1, lines.activeIndex(999))
        assertEquals(0, lines.activeIndex(1000))
        assertEquals(1, lines.activeIndex(2999))
        assertEquals(2, lines.activeIndex(10_000))
    }

    private fun JsonObject.lines(): List<LyricLine> =
        (get("syncedLyrics") as? JsonArray).orEmpty().map { element ->
            val line = element.jsonObject
            LyricLine(
                startMs = line.millis("time"),
                text = line.getValue("text").jsonPrimitive.content,
                words = (line["words"] as? JsonArray).orEmpty().map { word ->
                    val fields = word.jsonObject
                    LyricWord(fields.millis("time"), fields.millis("duration"), fields.getValue("text").jsonPrimitive.content)
                },
            )
        }

    private fun JsonObject.millis(name: String): Long = (getValue(name).jsonPrimitive.double * 1000).roundToLong()
}
