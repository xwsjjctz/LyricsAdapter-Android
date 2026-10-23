package com.lyricsadapter.app.ui.focus

import com.lyricsadapter.lyrics.LyricLine

/** A gap at least this long between sung lines shows the interlude dots. */
private const val INTERLUDE_MIN_MS = 4_000L

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
 * Lines in order, with a gap before the first line when the intro is long
 * and between word-timed lines when the singing stops long enough. Line-timed
 * lyrics have no end times, so only their intro can become a gap.
 */
internal fun buildLyricItems(lines: List<LyricLine>): List<LyricItem> = buildList {
    lines.forEachIndexed { index, line ->
        val gapStart = if (index == 0) 0L else lines[index - 1].words.lastOrNull()?.endMs
        if (gapStart != null && line.startMs - gapStart >= INTERLUDE_MIN_MS) add(InterludeItem(gapStart, line.startMs, index))
        add(LineItem(line, index))
    }
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
