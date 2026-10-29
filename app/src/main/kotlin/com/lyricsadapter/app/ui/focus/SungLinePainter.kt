package com.lyricsadapter.app.ui.focus

import android.os.Build
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.GraphicsContext
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.CompositingStrategy
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.lyricsadapter.lyrics.Emphasis
import com.lyricsadapter.lyrics.FLOAT_EM
import com.lyricsadapter.lyrics.LineMotion
import com.lyricsadapter.lyrics.LyricLine
import com.lyricsadapter.lyrics.Sweep
import com.lyricsadapter.lyrics.WORD_FADE_WIDTH
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/*
 * Desktop's alphas, as its focus mode renders them: text is white at 0.96;
 * every line not being sung is at a fifth of that. The sung line as a whole is
 * at 0.85, and within it words still to come are at 0.4, so they show at about
 * a third and sung words at about four fifths.
 */
private const val TEXT_ALPHA = 0.96f
private const val SUNG_LINE_ALPHA = 0.85f
private const val UPCOMING_SHARE = 0.4f
internal val INACTIVE = Color.White.copy(alpha = TEXT_ALPHA * 0.2f)
private val SUNG = Color.White.copy(alpha = TEXT_ALPHA * SUNG_LINE_ALPHA)
private val UPCOMING = Color.White.copy(alpha = TEXT_ALPHA * SUNG_LINE_ALPHA * UPCOMING_SHARE)
private val WHOLE_LINE = SUNG

/** Glows fainter than this are not drawn. */
private const val MIN_GLOW = 0.004f

/** Keys of the held letters' glow layers, below every letter's own key. */
private const val GLOW_KEYS = -1_000_000

/** Room around a moving word's glyphs in its layer, and around a held letter's for its glow, in ems. */
private const val WORD_MARGIN = 0.25f
private const val LETTER_MARGIN = 0.5f

/**
 * Draws one line while it is or was being sung, moving its words as AMLL
 * does on desktop ([LineMotion]): each word floats up as it starts and the
 * fill runs across the words with a soft edge; held words swell, spread, bob
 * and glow letter by letter. Once the line is left the words float back
 * down. [draw] returns false while the line is at rest, to be drawn plainly.
 *
 * Words that have settled are drawn in one go, and so are words not reached
 * yet; only the words still moving are drawn one by one. Clip shapes and
 * word widths are worked out once per layout.
 *
 * Text drawn under a plain translation lands on whole pixels, so a rise of a
 * few pixels would move in steps. Each moving word and held letter is drawn
 * into its own offscreen layer instead, as desktop's browser composites each
 * `will-change: transform` word, and the layer is moved by fractions of a
 * pixel; it is redrawn only when its fill changes. A glowing letter's glow
 * is a blurred copy in a layer of its own, whose opacity follows the glow.
 * [release] frees the layers.
 */
internal class SungLinePainter(
    private val line: LyricLine,
    private val style: TextStyle,
    /** Where each index of [line]'s text sits in the laid-out text, which may hold word joiners. */
    private val offsets: IntArray,
    private val graphics: GraphicsContext,
) {
    private val wordTimed = line.words.isNotEmpty() && line.words.sumOf { it.text.length } == line.text.length
    private val motion = if (wordTimed) LineMotion(line) else null
    private val atoms = motion?.atoms.orEmpty()
    private val drawn = motion?.words.orEmpty()

    /** For each drawn word, its emphasis and which of that emphasis's letters it holds. */
    private val held: List<Pair<Emphasis, List<Int>>?> = drawn.map { atomIndex ->
        val atom = atoms[atomIndex]
        motion?.emphases?.firstNotNullOfOrNull { emphasis ->
            val letters = emphasis.letters.indices.filter { emphasis.letters[it].first >= atom.start && emphasis.letters[it].last < atom.end }
            if (letters.isEmpty()) null else emphasis to letters
        }
    }
    private val emphasisEndsAtMs = motion?.emphases?.maxOfOrNull { it.endMs } ?: 0.0

    private var layout: TextLayoutResult? = null
    private var placed: Placed? = null
    private val ranges = HashMap<Long, TextRange>()
    
    /** Layers of the moving words (keyed by their first index), held letters (by it negated) and their glows. */
    private val layers = HashMap<Int, Slot>()

    private class Slot(val layer: GraphicsLayer) {
        /** What the layer was last drawn with, to redraw it only when that changes. */
        var content = Long.MIN_VALUE
        var used = false
    }

    /** Where the drawn words sit in the layout, and the fill across them. */
    private class Placed(val left: FloatArray, val width: FloatArray, val sweep: Sweep)

    fun onLayout(result: TextLayoutResult) {
        layout = result
        ranges.clear()
        placed = motion?.let { motion ->
            val left = FloatArray(drawn.size)
            val width = FloatArray(drawn.size)
            drawn.forEachIndexed { index, atomIndex ->
                val atom = atoms[atomIndex]
                left[index] = result.getBoundingBox(offsets[atom.start]).left
                width[index] = result.getBoundingBox(offsets[atom.end - 1]).right - left[index]
            }
            Placed(left, width, Sweep(motion, width, result.lineHeightPx() * WORD_FADE_WIDTH))
        }
    }

    private fun TextLayoutResult.lineHeightPx(): Float = if (lineCount > 1) getLineBaseline(1) - getLineBaseline(0) else size.height.toFloat()

    /**
     * Draws the line at [positionMs]; [emphasis] fades the colours in while it
     * is sung and out once it is left at [leftAtMs]. Returns false when the
     * line is at rest and should be drawn as plain text instead.
     */
    fun draw(scope: DrawScope, positionMs: Long, emphasis: Float, leftAtMs: Long?): Boolean = with(scope) {
        val layout = layout ?: return false
        val motion = motion
        val placed = placed
        if (motion == null || placed == null) {
            if (emphasis <= 0f) return false
            drawText(layout, color = lerp(INACTIVE, WHOLE_LINE, emphasis))
            return true
        }
        if (emphasis <= 0f) {
            // Not reached yet, or left long enough ago that every word has come back to rest.
            if (leftAtMs == null || positionMs >= maxOf(motion.restsAtMs(leftAtMs), emphasisEndsAtMs)) return false
        }
        val colors = Colors(lerp(INACTIVE, SUNG, emphasis), lerp(INACTIVE, UPCOMING, emphasis))
        val em = style.fontSize.toPx()
        // Whole pixels, so a word that has floated up lands where the settled words are drawn.
        val lift = (FLOAT_EM * em).roundToInt().toFloat()
        val fade = placed.sweep.width
        val states = IntArray(drawn.size) { index ->
            val atomIndex = drawn[index]
            val float = motion.float(atomIndex, positionMs, leftAtMs)
            val edge = placed.sweep.edge(index, positionMs)
            val still = held[index]?.first?.let { positionMs < it.movesFromMs || positionMs >= it.endMs } ?: true
            when {
                still && leftAtMs == null && float >= 1f && edge - fade / 2 >= placed.width[index] -> SETTLED
                still && float <= 0f && edge + fade / 2 <= 0f -> WAITING
                else -> MOVING
            }
        }
        val settled = states.indexOfFirst { it != SETTLED }.let { if (it < 0) drawn.size else it }
        val waiting = states.indexOfLast { it != WAITING } + 1

        if (settled > 0) {
            translate(top = -lift) { clip(range(0, atoms[drawn[settled - 1]].end)) { drawText(layout, color = colors.sung) } }
        }
        if (waiting < drawn.size) clip(range(atoms[drawn[waiting]].start, line.text.length)) { drawText(layout, color = colors.upcoming) }
        for (index in settled until waiting) drawWord(layout, motion, placed, index, positionMs, leftAtMs, emphasis, em, lift, colors)
        releaseUnused()
        return true
    }

    /** Frees every layer; the painter makes new ones if it draws again. */
    fun release() {
        layers.values.forEach { graphics.releaseGraphicsLayer(it.layer) }
        layers.clear()
    }

    private fun slot(key: Int): Slot = layers.getOrPut(key) {
        Slot(graphics.createGraphicsLayer().apply { compositingStrategy = CompositingStrategy.Offscreen })
    }.also { it.used = true }

    private fun releaseUnused() {
        val iterator = layers.values.iterator()
        while (iterator.hasNext()) {
            val slot = iterator.next()
            if (slot.used) {
                slot.used = false
            } else {
                graphics.releaseGraphicsLayer(slot.layer)
                iterator.remove()
            }
        }
    }

    private class Colors(val sung: Color, val upcoming: Color)

    private fun DrawScope.drawWord(
        layout: TextLayoutResult,
        motion: LineMotion,
        placed: Placed,
        index: Int,
        positionMs: Long,
        leftAtMs: Long?,
        emphasis: Float,
        em: Float,
        lift: Float,
        colors: Colors,
    ) {
        val atom = atoms[drawn[index]]
        val fade = placed.sweep.width
        // The soft edge in the line's coordinates: lit behind it, dim ahead.
        val edgeAt = placed.left[index] + placed.sweep.edge(index, positionMs)
        fun brush(shift: Float): Brush = when {
            edgeAt - fade / 2 >= placed.left[index] + placed.width[index] -> SolidColor(colors.sung)
            edgeAt + fade / 2 <= placed.left[index] -> SolidColor(colors.upcoming)
            else -> Brush.horizontalGradient(0f to colors.sung, 1f to colors.upcoming, startX = edgeAt - fade / 2 - shift, endX = edgeAt + fade / 2 - shift)
        }
        val edgeKey = when {
            edgeAt - fade / 2 >= placed.left[index] + placed.width[index] -> 1L
            edgeAt + fade / 2 <= placed.left[index] -> 2L
            else -> (edgeAt * 4).roundToInt().toLong() shl 2
        }
        val colorKey = colors.sung.value.toLong() * 31 + colors.upcoming.value.toLong()
        val lineIndex = layout.getLineForOffset(offsets[atom.start])
        translate(top = -lift * motion.float(drawn[index], positionMs, leftAtMs)) {
            val letters = held[index]
            if (letters == null) {
                val margin = em * WORD_MARGIN
                val bounds = pixelBounds(
                    placed.left[index] - margin, layout.getLineTop(lineIndex) - margin,
                    placed.left[index] + placed.width[index] + margin, layout.getLineBottom(lineIndex) + margin,
                )
                val slot = slot(atom.start)
                val content = edgeKey * 31 + colorKey
                if (slot.content != content || slot.layer.size != bounds.size) {
                    slot.content = content
                    slot.layer.record(bounds.size) {
                        translate(-bounds.offset.x.toFloat(), -bounds.offset.y.toFloat()) {
                            // Without an explicit alpha the paint keeps the last colour's, and the gradient is dimmed twice.
                            clip(range(atom.start, atom.end)) { drawText(layout, brush = brush(0f), alpha = 1f) }
                        }
                    }
                }
                slot.layer.topLeft = bounds.offset
                drawLayer(slot.layer)
            } else {
                val (word, own) = letters
                val margin = em * LETTER_MARGIN
                for (letter in own) {
                    val range = word.letters[letter]
                    val box = layout.getBoundingBox(offsets[range.first])
                    val bounds = pixelBounds(box.left - margin, layout.getLineTop(lineIndex) - margin, box.right + margin, layout.getLineBottom(lineIndex) + margin)
                    val origin = Offset(bounds.offset.x.toFloat(), bounds.offset.y.toFloat())
                    val cell = range(range.first, range.last + 1)
                    val scale = word.scale(letter, positionMs)
                    fun GraphicsLayer.place() {
                        topLeft = bounds.offset
                        pivotOffset = box.center - origin
                        scaleX = scale
                        scaleY = scale
                        translationX = word.spreadEm(letter, positionMs) * em * scale
                        translationY = -word.riseEm(letter, positionMs) * em * scale
                    }
                    // Desktop's mask dims the glow ahead of the fill as it dims the text.
                    val lit = ((edgeAt + fade / 2 - box.center.x) / fade).coerceIn(0f, 1f)
                    val glow = word.glowAlpha(letter, positionMs) * SUNG_LINE_ALPHA * emphasis * (UPCOMING_SHARE + (1 - UPCOMING_SHARE) * lit)
                    if (glow >= MIN_GLOW && Build.VERSION.SDK_INT >= 31) {
                        // Desktop's text-shadow: the letter in white, blurred, under the letter. Drawn once
                        // into its own layer; only the layer's opacity follows the glow.
                        val radius = cssShadowRadius(word.glowRadiusEm * em)
                        val glowSlot = slot(GLOW_KEYS - range.first)
                        val content = radius.toRawBits().toLong()
                        if (glowSlot.content != content || glowSlot.layer.size != bounds.size) {
                            glowSlot.content = content
                            glowSlot.layer.record(bounds.size) {
                                translate(-origin.x, -origin.y) { clip(cell) { drawText(layout, color = Color.White) } }
                            }
                            glowSlot.layer.renderEffect = BlurEffect(radius, radius, TileMode.Decal)
                        }
                        glowSlot.layer.alpha = glow
                        glowSlot.layer.place()
                        drawLayer(glowSlot.layer)
                    }
                    val slot = slot(-1 - range.first)
                    val content = edgeKey * 31 + colorKey
                    if (slot.content != content || slot.layer.size != bounds.size) {
                        slot.content = content
                        slot.layer.record(bounds.size) {
                            // Cut from the line's layout, so the letter is drawn exactly as the line draws it at
                            // rest: laid out alone, it would lose its kerning and ligatures and jump when the
                            // word starts and stops moving.
                            translate(-origin.x, -origin.y) { clip(cell) { drawText(layout, brush = brush(0f), alpha = 1f) } }
                        }
                    }
                    slot.layer.place()
                    drawLayer(slot.layer)
                }
            }
        }
    }

    /** The whole-pixel box around a rectangle, so a layer's content sits where the text would be drawn directly. */
    private class PixelBounds(val offset: IntOffset, val size: IntSize)

    private fun pixelBounds(left: Float, top: Float, right: Float, bottom: Float): PixelBounds {
        val x = floor(left).toInt()
        val y = floor(top).toInt()
        return PixelBounds(IntOffset(x, y), IntSize(ceil(right).toInt() - x, ceil(bottom).toInt() - y))
    }

    private fun range(start: Int, end: Int): TextRange = ranges.getOrPut(start.toLong() shl 32 or end.toLong()) {
        val bands = rangeRects(layout!!, offsets[start], offsets[end], OVERHANG)
        TextRange(bands, Path().apply { bands.forEach(::addRect) })
    }

    private class TextRange(val bands: List<Rect>, val path: Path)

    /** Clips to [range]'s bands; a single band takes the cheaper rectangular clip. */
    private inline fun DrawScope.clip(range: TextRange, block: DrawScope.() -> Unit) {
        val band = range.bands.singleOrNull()
        if (band != null) clipRect(band.left, band.top, band.right, band.bottom, block = block) else clipPath(range.path, block = block)
    }
}

private const val SETTLED = 0
private const val WAITING = 1
private const val MOVING = 2

/** The blur radius that blurs as much as a CSS text-shadow of [cssRadius]: CSS blurs by half its radius. */
private fun cssShadowRadius(cssRadius: Float): Float = cssBlurRadius(cssRadius / 2)

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
