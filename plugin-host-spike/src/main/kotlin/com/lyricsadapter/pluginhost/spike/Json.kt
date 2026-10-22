package com.lyricsadapter.pluginhost.spike

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

val NO_ARGS = JsonArray(emptyList())

fun jsonArgs(vararg values: Any): JsonArray = JsonArray(
    values.map {
        when (it) {
            is String -> JsonPrimitive(it)
            is Number -> JsonPrimitive(it)
            is JsonElement -> it
            else -> error("Unsupported argument: $it")
        }
    },
)

fun JsonObject.string(name: String): String =
    (get(name) as? JsonPrimitive)?.contentOrNull ?: error("Missing \"$name\" in $this")

/** The login channels answer `{ success, error, ... }` instead of rejecting. */
fun JsonElement.requireSuccess(): JsonObject {
    val result = jsonObject
    if (result["success"]?.jsonPrimitive?.booleanOrNull == false) error(result["error"]?.jsonPrimitive?.contentOrNull ?: "failed")
    return result
}

fun describeSongs(songs: JsonElement): String {
    val list = songs.jsonArray
    val titles = list.take(3).joinToString("; ") { element ->
        val song = element.jsonObject
        val singers = song["singer"]?.jsonArray?.joinToString("/") { it.jsonObject.string("name") }
        "${song.string("songname")} - $singers"
    }
    return "${list.size} results: $titles"
}

fun describeUrl(result: JsonElement): String {
    val url = result.jsonObject.string("url")
    return "quality ${result.jsonObject.string("quality")}, ${url.substringAfter("://").substringBefore('/')}"
}

fun describeLyrics(result: JsonElement): String {
    if (result is JsonNull) return "no lyrics"
    val lyrics = result.jsonObject
    val lines = lyrics.string("lyrics").lines().count { it.isNotBlank() }
    val format = (lyrics["wordLyricsFormat"] as? JsonPrimitive)?.contentOrNull
    val words = (lyrics["wordLyrics"] as? JsonPrimitive)?.contentOrNull?.length ?: 0
    return "$lines LRC lines" + if (format != null) ", word lyrics $format ($words chars)" else ""
}
