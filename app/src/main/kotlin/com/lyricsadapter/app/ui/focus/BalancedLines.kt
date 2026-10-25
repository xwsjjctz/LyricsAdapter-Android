package com.lyricsadapter.app.ui.focus

import android.icu.text.BreakIterator
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import java.util.Locale

/** How much wider than the narrowest split the later lines may be, as a share of the widest piece. */
private const val TIE_SHARE = 0.05f

/** Text ready to lay out, with [offsets] mapping each index of the original text into it. */
internal class LaidOutText(val text: String, val offsets: IntArray)

/**
 * Desktop's wrapping of a lyric line, as its focus mode shows it: a line
 * that needs several lines keeps that many but splits into lines of similar
 * width, the first one the shortest ("Four years / no calls"), and breaks only
 * between words. Breaks are found by ICU (after spaces, and between the words
 * of Chinese or Japanese, see [WordWrapped]); the chosen ones are written into
 * the text as newlines. [width] is the room for the text, in pixels.
 */
internal fun balancedLines(original: String, measurer: TextMeasurer, style: TextStyle, width: Int): LaidOutText {
    val joined = WordWrapped.of(original)
    val unchanged = LaidOutText(joined.text, joined.offsets)
    val text = joined.text
    if (width <= 0 || text.isBlank()) return unchanged

    val stops = BreakIterator.getLineInstance(Locale.CHINESE).run {
        setText(text)
        generateSequence(first()) { next().takeIf { it != BreakIterator.DONE } }.toList()
    }
    if (stops.size <= 2) return unchanged
    val pieces = stops.zipWithNext()
    fun measure(piece: String) = measurer.measure(piece, style, softWrap = false, maxLines = 1).size.width.toFloat()
    // A piece's width inside a line, and at a line's end, where its trailing space does not count.
    val inside = pieces.map { (start, end) -> measure(text.substring(start, end)) }
    val atEnd = pieces.mapIndexed { index, (start, end) ->
        val piece = text.substring(start, end)
        if (piece.last().isWhitespace()) measure(piece.trimEnd()) else inside[index]
    }
    val room = width - 1f
    if (atEnd.any { it > room }) return unchanged

    // How many lines ordinary wrapping needs.
    var lines = 1
    var used = 0f
    pieces.indices.forEach { index ->
        if (used > 0f && used + atEnd[index] > room) {
            lines++
            used = 0f
        }
        used += inside[index]
    }
    if (lines == 1) return unchanged

    /** Piece indexes that start a line when lines are filled from the end at [limit]; null if more than [lines] are needed. */
    fun fromEnd(limit: Float): List<Int>? {
        val starts = ArrayList<Int>()
        var lineWidth = 0f
        var lineEnd = pieces.size - 1
        for (index in pieces.indices.reversed()) {
            val candidate = if (index == lineEnd) atEnd[index] else inside[index] + lineWidth
            if (candidate > limit && index < lineEnd) {
                starts += index + 1
                if (starts.size >= lines) return null
                lineEnd = index
                lineWidth = atEnd[index]
            } else {
                lineWidth = candidate
            }
        }
        return starts.reversed()
    }

    var low = atEnd.max()
    var high = room
    if (fromEnd(high) == null) return unchanged
    repeat(24) {
        val middle = (low + high) / 2
        if (fromEnd(middle) != null) high = middle else low = middle
    }
    // Splits of equal width differ only by rounding in the measured pieces; like desktop, the
    // tie goes to the later lines, so the first line is the shorter one.
    val tie = minOf(high + atEnd.max() * TIE_SHARE, room)
    val breaks = fromEnd(tie)?.map { stops[it] } ?: fromEnd(high)?.map { stops[it] } ?: return unchanged

    val result = StringBuilder(text)
    breaks.reversed().forEach { result.insert(it, '\n') }
    val offsets = IntArray(joined.offsets.size) { index ->
        val position = joined.offsets[index]
        position + breaks.count { it <= position }
    }
    return LaidOutText(result.toString(), offsets)
}
