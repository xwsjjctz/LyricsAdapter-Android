package com.lyricsadapter.app.ui.focus

import com.lyricsadapter.lyrics.LyricLine

/** An intro at least this long shows the interlude dots. */
private const val INTERLUDE_MIN_MS = 4_000L

/** The dots finish this long before the first line starts. */
private const val INTERLUDE_LEAD_MS = 250L

/** What the lyrics column lists: sung lines and the instrumental gaps between them. */
internal sealed interface LyricItem {
    val startMs: Long

    /** Position among the lines, for distance effects; a gap sits halfway between its neighbours. */
    val rank: Float
}

internal data class LineItem(val line: LyricLine, val index: Int) : LyricItem {
    override val startMs: Long get() = line.startMs
    override val rank: Float get() = index.toFloat()
}

internal data class InterludeItem(override val startMs: Long, val endMs: Long, val nextIndex: Int) : LyricItem {
    override val rank: Float get() = nextIndex - 0.5f
}

/**
 * Lines in order, with a gap before the first line when the intro is long.
 * As on desktop, a line stays the sung one until the next line starts, so
 * instrumental breaks between lines keep the last line lit instead of
 * showing dots (desktop gives each line the next one's start as its end).
 */
internal fun buildLyricItems(lines: List<LyricLine>): List<LyricItem> = buildList {
    val first = lines.firstOrNull() ?: return@buildList
    val introEnd = first.startMs - INTERLUDE_LEAD_MS
    if (introEnd >= INTERLUDE_MIN_MS) add(InterludeItem(0L, introEnd, 0))
    lines.forEachIndexed { index, line -> add(LineItem(line, index)) }
}

/** The item being sung at [positionMs]: the last one that has started, or -1 before the first. */
internal fun List<LyricItem>.activeItemAt(positionMs: Long): Int {
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
