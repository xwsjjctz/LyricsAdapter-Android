/*
 * Ported from AMLL (Apple Music-like Lyrics), @applemusic-like-lyrics/core
 * 0.5.2, which desktop's focus mode runs: src/utils/lyric-split-words.ts,
 * src/utils/is-cjk.ts, src/lyric-player/base/line.ts (shouldEmphasize) and
 * the float, emphasis and mask animations of src/lyric-player/dom/lyric-line.ts.
 * https://github.com/amll-dev/applemusic-like-lyrics
 *
 * AMLL is licensed under the GNU Affero General Public License v3.0 only, and
 * so is this file, as part of LyricsAdapter Android (see LICENSE).
 */
package com.lyricsadapter.lyrics

import java.text.BreakIterator
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** How far a word floats up once sung, in ems. */
const val FLOAT_EM = 0.05f

/** `wordFadeWidth`: the fill's soft edge is this many word heights wide. */
const val WORD_FADE_WIDTH = 0.5f

private const val FLOAT_MIN_MS = 1_000.0
private const val EMPHASIS_MIN_MS = 1_000.0
private const val EMPHASIS_MAX_LETTERS = 7

/** Desktop's Web Animations sample the emphasis curves this many times and interpolate linearly between. */
private const val FRAMES = 32

private const val BOB_LEAD_MS = 400.0
private const val BOB_STRETCH = 1.4

/** CSS `ease-out`. */
private val EASE_OUT = CubicBezier(0.0, 0.0, 0.58, 1.0)
private val SWELL_IN = CubicBezier(0.2, 0.4, 0.58, 1.0)
private val SWELL_OUT = CubicBezier(0.3, 0.0, 0.58, 1.0)

/** The emphasis curve, rising to 1 halfway and back to 0, at each keyframe; keyframe 0 is the element at rest. */
private val SWELL_FRAMES = DoubleArray(FRAMES + 1) { j ->
    if (j == 0) 0.0 else (j.toDouble() / FRAMES).let { x -> if (x < 0.5) SWELL_IN.y(x / 0.5) else 1 - SWELL_OUT.y((x - 0.5) / 0.5) }
}
private val BOB_FRAMES = DoubleArray(FRAMES + 1) { j -> if (j == 0) 0.0 else sin(j.toDouble() / FRAMES * PI) }

/**
 * One piece of a line as AMLL animates it: words are split at whitespace and
 * CJK words into single characters. [start] and [end] index the line's text.
 */
class LyricAtom(val start: Int, val end: Int, val startMs: Double, val endMs: Double, val isSpace: Boolean)

/**
 * The motion of one word-timed line: each atom floats up as it starts and the
 * fill sweeps across the atoms; held words are emphasized letter by letter.
 * Times are playback positions in milliseconds; lengths are in ems unless said.
 */
class LineMotion(private val line: LyricLine) {
    /** Where the line's animations count from: AMLL starts them when the line becomes current. */
    val startMs: Double = line.startMs.toDouble()

    val atoms: List<LyricAtom> = splitAtoms(line)

    /** The atoms that are drawn and animated, in order; spaces only take up room. */
    val words: List<Int> = atoms.indices.filter { !atoms[it].isSpace }

    /** The held words, each over the letters of one or more atoms. */
    val emphases: List<Emphasis> = chunkEmphases()

    /**
     * How far [atom] has floated, from 0 to 1. Once the line was left at
     * [leftAtMs], the float runs backwards from where it was, as AMLL reverses it.
     */
    fun float(atom: Int, positionMs: Long, leftAtMs: Long? = null): Float {
        val word = atoms[atom]
        val delay = word.startMs - startMs
        val duration = max(FLOAT_MIN_MS, word.endMs - word.startMs)
        val local = if (leftAtMs == null) {
            max(0.0, positionMs - startMs)
        } else {
            if (positionMs < leftAtMs) return 0f
            min(leftAtMs - startMs, delay + duration) - (positionMs - leftAtMs)
        }
        val progress = (local - delay) / duration
        return when {
            progress <= 0 -> 0f
            progress >= 1 -> 1f
            else -> EASE_OUT.y(progress).toFloat()
        }
    }

    /** By when every float has run back down after the line was left at [leftAtMs]. */
    fun restsAtMs(leftAtMs: Long): Double = leftAtMs + (words.maxOfOrNull { max(FLOAT_MIN_MS, atoms[it].endMs - atoms[it].startMs) } ?: 0.0)

    /** When [atom] has floated all the way up while the line is current. */
    fun floatEndMs(atom: Int): Double = atoms[atom].startMs + max(FLOAT_MIN_MS, atoms[atom].endMs - atoms[atom].startMs)

    private fun chunkEmphases(): List<Emphasis> {
        val lastWord = line.words.lastOrNull()?.text ?: return emptyList()
        val result = mutableListOf<Emphasis>()
        var group = mutableListOf<Int>()
        fun flush() {
            if (group.isNotEmpty()) emphasisOf(group, lastWord)?.let(result::add)
            group = mutableListOf()
        }
        atoms.forEachIndexed { index, atom ->
            val text = textOf(atom)
            if (!atom.isSpace && !isCjk(text)) {
                group += index
            } else {
                flush()
                if (!atom.isSpace) emphasisOf(listOf(index), lastWord)?.let(result::add)
            }
        }
        flush()
        return result
    }

    private fun emphasisOf(chunk: List<Int>, lastWord: String): Emphasis? {
        val pieces = chunk.map { atoms[it] }
        val merged = pieces.joinToString("") { textOf(it) }
        val wordStartMs = pieces.minOf { it.startMs }
        val endMs = pieces.maxOf { it.endMs }
        val emphasized = pieces.any { shouldEmphasize(textOf(it), it.endMs - it.startMs) } ||
            (!isCjk(merged) && shouldEmphasize(merged, endMs - wordStartMs))
        if (!emphasized) return null
        val letters = pieces.flatMap { graphemes(it) }
        var duration = max(EMPHASIS_MIN_MS, endMs - wordStartMs)
        var amount = growth(duration / 2000) * 0.6
        var glow = growth(duration / 3000) * 0.5
        if (merged.contains(lastWord)) {
            amount *= 1.6
            glow *= 1.5
            duration *= 1.2
        }
        return Emphasis(
            letters = letters,
            startMs = startMs + max(0.0, wordStartMs - startMs),
            durationMs = duration,
            amount = min(1.2, amount).toFloat(),
            glow = min(0.8, glow).toFloat(),
        )
    }

    private fun textOf(atom: LyricAtom): String = line.text.substring(atom.start, atom.end)

    /** The atom's trimmed text, one range per grapheme, indexing the line's text. */
    private fun graphemes(atom: LyricAtom): List<IntRange> {
        val text = textOf(atom)
        val from = text.indexOfFirst { !it.isWhitespace() }
        if (from < 0) return emptyList()
        val to = text.indexOfLast { !it.isWhitespace() } + 1
        val breaks = BreakIterator.getCharacterInstance().apply { setText(text.substring(from, to)) }
        val ranges = mutableListOf<IntRange>()
        var start = breaks.first()
        var end = breaks.next()
        while (end != BreakIterator.DONE) {
            ranges += (atom.start + from + start) until (atom.start + from + end)
            start = end
            end = breaks.next()
        }
        return ranges
    }
}

/**
 * A held word: each of its [letters] (ranges of the line's text) in turn
 * swells, spreads from the word's centre, rises and glows, and bobs up.
 */
class Emphasis internal constructor(
    val letters: List<IntRange>,
    /** When the first letter starts to swell. */
    val startMs: Double,
    /** How long each letter swells. */
    val durationMs: Double,
    /** How much the letters swell, spread and rise. */
    val amount: Float,
    /** How brightly the letters glow. */
    val glow: Float,
) {
    fun letterStartMs(letter: Int): Double = startMs + durationMs / 2.5 / letters.size * letter

    /** The letter's emphasis, from 0 to 1 halfway and back. */
    fun swell(letter: Int, positionMs: Long): Float = sample(SWELL_FRAMES, (positionMs - letterStartMs(letter)) / durationMs)

    /** The letter's size about its centre. */
    fun scale(letter: Int, positionMs: Long): Float = 1 + swell(letter, positionMs) * 0.1f * amount

    /** How far the letter moves right, in ems: letters spread out from the word's centre. */
    fun spreadEm(letter: Int, positionMs: Long): Float = -swell(letter, positionMs) * 0.03f * amount * (letters.size / 2f - letter)

    /** How far the letter rises as it swells and bobs, in ems. */
    fun riseEm(letter: Int, positionMs: Long): Float = swell(letter, positionMs) * 0.025f * amount + FLOAT_EM * bob(letter, positionMs)

    /** The glow's opacity; its CSS blur radius stays [glowRadiusEm]. */
    fun glowAlpha(letter: Int, positionMs: Long): Float = swell(letter, positionMs) * glow

    val glowRadiusEm: Float get() = min(0.3f, glow * 0.3f)

    /** The letter's bob, a half sine from 0 to 1 and back that starts a little before it swells. */
    fun bob(letter: Int, positionMs: Long): Float =
        sample(BOB_FRAMES, (positionMs - (letterStartMs(letter) - BOB_LEAD_MS)) / (BOB_STRETCH * durationMs))

    /** When the letters start to move. */
    val movesFromMs: Double get() = letterStartMs(0) - BOB_LEAD_MS

    /** When the last letter has stopped. */
    val endMs: Double get() = letterStartMs(letters.lastIndex) + max(durationMs, BOB_STRETCH * durationMs - BOB_LEAD_MS)
}

/**
 * Where the fill stands on each word, as AMLL's mask animation moves it: one
 * position runs across the words in turn, each word's width while it is sung,
 * so the soft edge carries on from one word into the next. [widths] are the
 * drawn words' widths ([LineMotion.words]) and [fade] the edge's width, both
 * in pixels.
 */
class Sweep(motion: LineMotion, widths: FloatArray, private val fade: Float) {
    private val lineStartMs = motion.startMs

    /** The fill's position over time, relative to the line's start: knots it moves between linearly. */
    private val times: DoubleArray
    private val positions: FloatArray

    /** The widths of the drawn words before each one. */
    private val before = FloatArray(widths.size).also { sums -> for (i in 1 until sums.size) sums[i] = sums[i - 1] + widths[i - 1] }

    init {
        val words = motion.words.map { motion.atoms[it] }
        val knotTimes = mutableListOf(0.0)
        val knotPositions = mutableListOf(0f)
        var position = 0f
        var last = 0.0
        fun knot(time: Double) {
            knotTimes += max(time, knotTimes.last())
            knotPositions += position
        }
        words.forEachIndexed { index, word ->
            // Still between words, then across this one while it is sung.
            val start = word.startMs - lineStartMs
            if (start > last) knot(start)
            last = start
            val duration = max(0.0, word.endMs - word.startMs)
            position += widths[index] + (if (index == 0) fade * 1.5f else 0f) + (if (index == words.lastIndex) fade * 0.5f else 0f)
            if (duration > 0) knot(last + duration)
            last += duration
        }
        times = knotTimes.toDoubleArray()
        positions = knotPositions.toFloatArray()
    }

    private fun position(positionMs: Long): Float {
        val t = max(0.0, positionMs - lineStartMs)
        if (t >= times.last()) return positions.last()
        var index = 1
        while (times[index] < t) index++
        val span = times[index] - times[index - 1]
        if (span <= 0) return positions[index]
        return positions[index - 1] + ((positions[index] - positions[index - 1]) * ((t - times[index - 1]) / span)).toFloat()
    }

    /** The middle of the soft edge on the [word]th drawn word, in pixels from the word's left. */
    fun edge(word: Int, positionMs: Long): Float = position(positionMs) - fade * 1.5f - before[word]

    /** How lit the [word]th drawn word is at [x] pixels from its left: 1 behind the edge, 0 ahead of it. */
    fun light(word: Int, x: Float, positionMs: Long): Float = (1 - (x - (edge(word, positionMs) - fade / 2)) / fade).coerceIn(0f, 1f)

    /** The edge's width in pixels. */
    val width: Float get() = fade
}

/** AMLL splits words at whitespace, and multi-character CJK words into single characters, sharing out the word's time. */
private fun splitAtoms(line: LyricLine): List<LyricAtom> {
    val atoms = mutableListOf<LyricAtom>()
    var offset = 0
    for (word in line.words) {
        val text = word.text
        val start = word.startMs.toDouble()
        val end = word.endMs.toDouble()
        if (text.isBlank()) {
            atoms += LyricAtom(offset, offset + text.length, start, end, isSpace = true)
            offset += text.length
            continue
        }
        val units = text.count { !it.isWhitespace() }.coerceAtLeast(1)
        val perUnit = (end - start) / units
        var unit = 0
        for (part in WHITESPACE_SPLIT.findAll(text).map { it.value }) {
            val at = start + unit * perUnit
            when {
                part.isBlank() -> atoms += LyricAtom(offset, offset + part.length, at, at, isSpace = true)
                isCjk(part) && part.length > 1 -> for (index in part.indices) {
                    val charStart = start + (unit + index) * perUnit
                    atoms += LyricAtom(offset + index, offset + index + 1, charStart, charStart + perUnit, isSpace = false)
                }
                else -> {
                    atoms += LyricAtom(offset, offset + part.length, at, at + part.length * perUnit, isSpace = false)
                    unit += part.length
                }
            }
            if (isCjk(part) && part.length > 1) unit += part.length
            offset += part.length
        }
    }
    return atoms
}

private val WHITESPACE_SPLIT = Regex("""\s+|\S+""")

/** AMLL's `isCJK`: every character a unified ideograph or between U+0800 and U+9FFC. */
internal fun isCjk(text: String): Boolean =
    text.isNotEmpty() && text.codePoints().allMatch { Character.isIdeographic(it) || it in 0x0800..0x9FFC }

/** AMLL's `shouldEmphasize`: held a second, and for other scripts two to seven letters. */
private fun shouldEmphasize(text: String, durationMs: Double): Boolean {
    if (isCjk(text)) return durationMs >= EMPHASIS_MIN_MS
    val length = text.trim().length
    return durationMs >= EMPHASIS_MIN_MS && length in 2..EMPHASIS_MAX_LETTERS
}

private fun growth(x: Double): Double = if (x > 1) sqrt(x) else x * x * x

/** A keyframe table at [progress], linear between keyframes and at rest outside the animation. */
private fun sample(frames: DoubleArray, progress: Double): Float {
    if (progress <= 0 || progress >= 1) return frames[if (progress <= 0) 0 else frames.lastIndex].toFloat()
    val at = progress * (frames.size - 1)
    val below = at.toInt()
    return (frames[below] + (frames[below + 1] - frames[below]) * (at - below)).toFloat()
}

/** A CSS `cubic-bezier()` timing function from (0, 0) to (1, 1). */
private class CubicBezier(private val x1: Double, private val y1: Double, private val x2: Double, private val y2: Double) {
    fun y(x: Double): Double {
        // x(s) rises monotonically for control points inside [0, 1], so bisection finds s.
        var low = 0.0
        var high = 1.0
        repeat(40) {
            val mid = (low + high) / 2
            if (curve(mid, x1, x2) < x) low = mid else high = mid
        }
        return curve((low + high) / 2, y1, y2)
    }

    private fun curve(s: Double, p1: Double, p2: Double): Double {
        val inverse = 1 - s
        return 3 * inverse * inverse * s * p1 + 3 * inverse * s * s * p2 + s * s * s
    }
}
