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

internal val UNSUNG = Color.White.copy(alpha = 0.35f)
internal val SUNG = Color.White

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
        val sung = lerp(UNSUNG, SUNG, emphasis)
        if (!wordTimed) {
            // A line without word timing lights up as a whole.
            drawText(layout, color = sung)
            return
        }
        val finished = words.indexOfFirst { it.endMs > positionMs }.let { if (it < 0) words.size else it }
        val lift = LIFT.toPx() * emphasis
        if (finished > 0) {
            translate(top = -lift) { clipPath(range(0, wordEnds[finished - 1]).first) { drawText(layout, color = sung) } }
        }
        val current = words.getOrNull(finished)
        val singing = current != null && positionMs >= current.startMs
        val restFrom = if (singing) finished + 1 else finished
        if (restFrom < words.size) {
            clipPath(range(startOf(restFrom), line.text.length).first) { drawText(layout, color = UNSUNG) }
        }
        if (current != null && singing) drawSinging(layout, finished, positionMs, emphasis)
    }

    private fun DrawScope.drawSinging(layout: TextLayoutResult, index: Int, positionMs: Long, emphasis: Float) {
        val word = words[index]
        val progress = ((positionMs - word.startMs).toFloat() / word.durationMs.coerceAtLeast(1)).coerceIn(0f, 1f)
        val (path, bounds) = range(startOf(index), wordEnds[index])
        val rise = 1f - (1f - progress) * (1f - progress) * (1f - progress)
        val feather = style.fontSize.toPx() * SWEEP_SOFTNESS
        val edge = bounds.left - feather + (bounds.width + 2 * feather) * progress
        val sung = lerp(UNSUNG, SUNG, emphasis)
        val sweep = Brush.horizontalGradient(0f to sung, 1f to UNSUNG, startX = edge - feather, endX = edge + feather)
        translate(top = -LIFT.toPx() * emphasis * rise) {
            val glow = if (glows(word.durationMs, word.text)) sin(PI.toFloat() * progress) * emphasis else 0f
            if (glow > 0.01f && Build.VERSION.SDK_INT >= 31) drawGlow(layout, index, bounds, glow)
            clipPath(path) { drawText(layout, brush = sweep) }
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
        val path = layout!!.getPathForRange(start, end)
        path to path.getBounds()
    }
}
