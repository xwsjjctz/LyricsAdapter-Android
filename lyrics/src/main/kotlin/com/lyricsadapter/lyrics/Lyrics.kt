package com.lyricsadapter.lyrics

/** A timed word. Text may carry a folded-in separator such as a trailing space. */
data class LyricWord(val startMs: Long, val durationMs: Long, val text: String) {
    val endMs: Long get() = startMs + durationMs
}

/** A synced line; [words] is empty for line-synced (LRC) lyrics. */
data class LyricLine(val startMs: Long, val text: String, val words: List<LyricWord> = emptyList())

/**
 * Lyrics as the app stores and shows them, matching desktop's `ParsedLrc`:
 * [lines] is empty when nothing carries timing, and [plainText] is then the
 * cleaned-up text.
 */
data class ParsedLyrics(val plainText: String, val lines: List<LyricLine>) {
    val isSynced: Boolean get() = lines.isNotEmpty()
    val hasWordTiming: Boolean get() = lines.any { it.words.isNotEmpty() }
}

enum class WordLyricsFormat { QRC, YRC }

/** The line being sung at [positionMs]: the last one that has started, or -1 before the first. */
fun List<LyricLine>.activeIndex(positionMs: Long): Int {
    var low = 0
    var high = size - 1
    var found = -1
    while (low <= high) {
        val middle = (low + high) ushr 1
        if (this[middle].startMs <= positionMs) {
            found = middle
            low = middle + 1
        } else {
            high = middle - 1
        }
    }
    return found
}
