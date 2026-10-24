package com.lyricsadapter.library

/**
 * Desktop's `buildSafeMusicFileName` (`fileName.ts`): downloaded songs are
 * named `artist - title.ext`, with whatever a file system could refuse
 * replaced, so the same song gets the same name on every platform.
 */
object MusicFileName {
    private val UNSAFE = Regex("[<>:\"/\\\\|?*\\u0000-\\u001F\\u007F]")
    private val DOTS = Regex("\\.\\.+")
    private val SPACES = Regex("\\s+")
    private val EDGES = Regex("^[.\\s]+|[.\\s]+$")
    private val TRAILING = Regex("[.\\s]+$")
    private val RESERVED = Regex("^(con|prn|aux|nul|com[1-9]|lpt[1-9])(?:\\..*)?$", RegexOption.IGNORE_CASE)
    private val EXTENSION = Regex("^[a-z0-9]{1,8}$")
    private const val MAX_PART = 180

    fun of(artist: String, title: String, extension: String): String =
        "${part(artist, "Unknown")} - ${part(title, "Unknown Track")}.${extension(extension)}"

    fun part(input: String?, fallback: String): String {
        val fallbackText = clean(fallback).ifEmpty { "Unknown" }
        var safe = clean(input.orEmpty()).ifEmpty { fallbackText }
        if (RESERVED.matches(safe)) safe = "_$safe"
        return safe.take(MAX_PART).trim().replace(TRAILING, "").ifEmpty { fallbackText }
    }

    private fun extension(value: String): String {
        val safe = value.removePrefix(".").lowercase()
        require(EXTENSION.matches(safe)) { "Invalid file extension" }
        return safe
    }

    private fun clean(value: String): String =
        value.replace(UNSAFE, " ").replace(DOTS, ".").replace(SPACES, " ").trim().replace(EDGES, "")
}
