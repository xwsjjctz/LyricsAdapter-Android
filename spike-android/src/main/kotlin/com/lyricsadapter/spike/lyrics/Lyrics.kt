package com.lyricsadapter.spike.lyrics

import android.content.res.AssetManager
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.roundToLong

/** A synced line in the shape desktop's lrcParser produces, in milliseconds. */
data class LyricLine(val startMs: Long, val text: String, val words: List<LyricWord>)

/** A timed word; its text may carry a folded-in trailing space, as desktop keeps it. */
data class LyricWord(val startMs: Long, val durationMs: Long, val text: String) {
    val endMs: Long get() = startMs + durationMs
}

class DemoLyrics(val durationMs: Long, val lines: List<LyricLine>)

/** Reads demo-lyrics.json, written by tools/demo-lyrics.mjs through desktop's parser. */
fun loadDemoLyrics(assets: AssetManager): DemoLyrics {
    val root = Json.parseToJsonElement(assets.open("demo-lyrics.json").bufferedReader().use { it.readText() }).jsonObject
    val lines = root.getValue("syncedLyrics").jsonArray.map { element ->
        val line = element.jsonObject
        LyricLine(
            startMs = line.millis("time"),
            text = line.getValue("text").jsonPrimitive.content,
            words = line["words"]?.jsonArray.orEmpty().map { word ->
                val fields = word.jsonObject
                LyricWord(fields.millis("time"), fields.millis("duration"), fields.getValue("text").jsonPrimitive.content)
            },
        )
    }
    return DemoLyrics(root.millis("durationSeconds"), lines)
}

/** The line being sung at [positionMs]: the last one that has started, or -1 before the first. */
fun List<LyricLine>.activeIndex(positionMs: Long): Int = indexOfLast { it.startMs <= positionMs }

private fun JsonObject.millis(name: String): Long = (getValue(name).jsonPrimitive.double * 1000).roundToLong()
