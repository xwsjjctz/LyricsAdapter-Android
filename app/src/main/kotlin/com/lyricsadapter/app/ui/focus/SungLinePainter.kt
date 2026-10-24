package com.lyricsadapter.app.ui.focus

import android.os.Build
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.ceil
import com.lyricsadapter.lyrics.LyricLine
import kotlin.math.PI
import kotlin.math.sin

/*
 * AMLL's alphas: every line not being sung is at 0.2. The sung line's words
 * rise to 0.96 once sung, and those still to come to 0.4; a line without word
 * timing lights as a whole, at AMLL's 0.85 line opacity.
 */
internal val INACTIVE = Color.White.copy(alpha = 0.2f)
private val SUNG = Color.White.copy(alpha = 0.96f)
private val UPCOMING = Color.White.copy(alpha = 0.4f)
private val WHOLE_LINE = Color.White.copy(alpha = 0.96f * 0.85f)

/** How far a sung word rises. */
private val LIFT = 3.dp
private val GLOW_RADIUS = 14.dp
private const val GLOW_STRENGTH = 0.75f

/** Half the width of the fill's soft edge, in ems; narrower than one CJK character, so it reads as a fill. */
private const val SWEEP_SOFTNESS = 0.25f

/** Held words this long glow while they are sung, as long as they are short. */
private const val GLOW_MIN_MS = 1_000L
private const val GLOW_MAX_CHARS = 8

/**
 * Draws one line while it is or was being sung. Sung words are lit and lifted
 * a little; the word being sung fills from the left with a soft edge and rises
 * as it fills; words not reached yet stay dim and at rest. [emphasis] fades all
 * of it in when the line starts and back out when the line is left. A long
 * held word also glows: the word is drawn white once into [glowLayer] with a
 * real blur, and only the layer's opacity follows the word, so the glow
 * neither depends on text-shadow support nor is cut at the word's box. Clip
 * paths are cached per text range.
 */
internal class SungLinePainter(
    private val line: LyricLine,
    private val measurer: TextMeasurer,
    private val style: TextStyle,
    private val glowLayer: GraphicsLayer,
) {
    private val words = line.words
    private val wordEnds: IntArray = words.runningFold(0) { end, word -> end + word.text.length }.drop(1).toIntArray()
    private val wordTimed = words.isNotEmpty() && wordEnds.last() == line.text.length
    private var layout: TextLayoutResult? = null
    private val paths = HashMap<Long, Pair<Path, Rect>>()

    /** The word now drawn in [glowLayer], or -1. */
    private var glowWord = -1

    fun onLayout(result: TextLayoutResult) {
        layout = result
        paths.clear()
        glowWord = -1
    }

    fun draw(scope: DrawScope, positionMs: Long, emphasis: Float) = with(scope) {
        val layout = layout ?: return
        if (!wordTimed) {
            drawText(layout, color = lerp(INACTIVE, WHOLE_LINE, emphasis))
            return
        }
        val sung = lerp(INACTIVE, SUNG, emphasis)
        val upcoming = lerp(INACTIVE, UPCOMING, emphasis)
        val finished = words.indexOfFirst { it.endMs > positionMs }.let { if (it < 0) words.size else it }
        val lift = LIFT.toPx() * emphasis
        if (finished > 0) {
            translate(top = -lift) { clipPath(range(0, wordEnds[finished - 1]).first) { drawText(layout, color = sung) } }
        }
        val current = words.getOrNull(finished)
        val singing = current != null && positionMs >= current.startMs
        val restFrom = if (singing) finished + 1 else finished
        if (restFrom < words.size) {
            clipPath(range(startOf(restFrom), line.text.length).first) { drawText(layout, color = upcoming) }
        }
        if (current != null && singing) drawSinging(layout, finished, positionMs, emphasis, sung, upcoming)
    }

    private fun DrawScope.drawSinging(layout: TextLayoutResult, index: Int, positionMs: Long, emphasis: Float, sung: Color, upcoming: Color) {
        val word = words[index]
        val progress = ((positionMs - word.startMs).toFloat() / word.durationMs.coerceAtLeast(1)).coerceIn(0f, 1f)
        val (path, bounds) = range(startOf(index), wordEnds[index])
        val rise = 1f - (1f - progress) * (1f - progress) * (1f - progress)
        val feather = style.fontSize.toPx() * SWEEP_SOFTNESS
        val edge = bounds.left - feather + (bounds.width + 2 * feather) * progress
        val sweep = Brush.horizontalGradient(0f to sung, 1f to upcoming, startX = edge - feather, endX = edge + feather)
        translate(top = -LIFT.toPx() * emphasis * rise) {
            val glow = if (glows(word.durationMs, word.text)) sin(PI.toFloat() * progress) * emphasis else 0f
            if (glow > 0.01f && Build.VERSION.SDK_INT >= 31) drawGlow(layout, index, bounds, glow)
            // Without an explicit alpha the paint keeps the last colour's, and the gradient is dimmed twice.
            clipPath(path) { drawText(layout, brush = sweep, alpha = 1f) }
        }
    }

    private fun DrawScope.drawGlow(layout: TextLayoutResult, index: Int, bounds: Rect, glow: Float) {
        val radius = GLOW_RADIUS.toPx()
        // Room for the blur to fade out before the layer's edge.
        val margin = ceil(radius * 3).toInt()
        if (glowWord != index) {
            val alone = measurer.measure(words[index].text, style, softWrap = false, maxLines = 1)
            glowLayer.record(IntSize(alone.size.width + 2 * margin, alone.size.height + 2 * margin)) {
                drawText(alone, color = Color.White, topLeft = Offset(margin.toFloat(), margin.toFloat()))
            }
            glowLayer.renderEffect = BlurEffect(radius, radius, TileMode.Decal)
            glowWord = index
        }
        glowLayer.alpha = GLOW_STRENGTH * glow
        val top = layout.getLineTop(layout.getLineForOffset(startOf(index)))
        translate(left = bounds.left - margin, top = top - margin) { drawLayer(glowLayer) }
    }

    private fun glows(durationMs: Long, text: String): Boolean = durationMs >= GLOW_MIN_MS && text.trim().length in 1..GLOW_MAX_CHARS

    private fun startOf(index: Int): Int = if (index == 0) 0 else wordEnds[index - 1]

    private fun range(start: Int, end: Int): Pair<Path, Rect> = paths.getOrPut(start.toLong() shl 32 or end.toLong()) {
        val path = Path()
        rangeRects(layout!!, start, end, OVERHANG).forEach(path::addRect)
        // The sweep and glow follow the glyphs, not the widened clip.
        val bounds = rangeRects(layout!!, start, end, overhang = 0f).reduce { union, rect ->
            Rect(minOf(union.left, rect.left), minOf(union.top, rect.top), maxOf(union.right, rect.right), maxOf(union.bottom, rect.bottom))
        }
        path to bounds
    }
}

/** Where one line's band ends, as a share of the way to the next baseline: below CJK and Latin descenders, above the next line's glyphs. */
private const val BAND_SPLIT = 0.2f

/** How far a range's band reaches past the line's ends, for glyphs that overhang their advance. */
private const val OVERHANG = 1_000f

/**
 * One rectangle per visual line of [start] until [end]. The line boxes that
 * `getPathForRange` uses cut glyphs at tight line heights, so a lifted word
 * would leave a slice behind; these bands meet a fifth of the way between
 * baselines instead, and the first and last lines stay open above and below.
 */
private fun rangeRects(layout: TextLayoutResult, start: Int, end: Int, overhang: Float): List<Rect> {
    if (start >= end) return listOf(Rect.Zero)
    val first = layout.getLineForOffset(start)
    val last = layout.getLineForOffset(end - 1)
    return (first..last).map { line ->
        val lineStart = layout.getLineStart(line)
        val lineEnd = layout.getLineEnd(line)
        val left = if (start > lineStart) layout.getHorizontalPosition(start, true) else layout.getLineLeft(line) - overhang
        val right = if (end < lineEnd) layout.getHorizontalPosition(end, true) else layout.getLineRight(line) + overhang
        val top = if (line == 0) -overhang else split(layout, line - 1)
        val bottom = if (line == layout.lineCount - 1) layout.size.height + overhang else split(layout, line)
        Rect(left, top, right, bottom)
    }
}

/** The boundary between [line] and the line after it. */
private fun split(layout: TextLayoutResult, line: Int): Float {
    val baseline = layout.getLineBaseline(line)
    return baseline + (layout.getLineBaseline(line + 1) - baseline) * BAND_SPLIT
}
