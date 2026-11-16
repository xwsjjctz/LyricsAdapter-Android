package com.lyricsadapter.lyrics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The port of AMLL's word animations against what desktop's focus mode ran,
 * read over CDP from its Web Animations while playing 王大毛《去年夏天》 and
 * 澤野弘之《Call of Silence》, whose QRC gives the word timings below.
 */
class LineMotionTest {
    private fun line(vararg words: Pair<String, Long>, startMs: Long = 0): LyricLine {
        var at = startMs
        val timed = words.map { (text, duration) -> LyricWord(at, duration, text).also { at += duration } }
        return LyricLine(startMs, timed.joinToString("") { it.text }, timed)
    }

    private val callOfSilence = line(
        "Call" to 1745L, " " to 436L, "of" to 873L, " " to 436L, "Silence" to 3055L, " " to 436L,
        "-" to 437L, " " to 436L, "澤" to 436L, "野" to 437L, "弘" to 436L, "之" to 437L,
    )

    /** The held word whose letters spell [text]. */
    private fun emphasisOf(line: LyricLine, text: String): Emphasis = LineMotion(line).emphases.single { emphasis ->
        emphasis.letters.joinToString("") { line.text.substring(it.first, it.last + 1) } == text
    }

    private fun peak(emphasis: Emphasis, letter: Int = 0): Long = (emphasis.letterStartMs(letter) + emphasis.durationMs / 2).toLong()

    @Test
    fun `a held last word swells and glows as on desktop`() {
        // Desktop's peak scale and glow, and the emphasis duration, for 爱, 呆, 头, 徊 and 啦.
        for ((duration, scale, glow, motionMs) in listOf(
            Quad(1073L, 1.0148f, 0.035f, 1287.6),
            Quad(1152L, 1.0183f, 0.043f, 1382.4),
            Quad(1766L, 1.0661f, 0.153f, 2119.2),
            Quad(2737L, 1.1123f, 0.570f, 3284.4),
            Quad(2792L, 1.1134f, 0.604f, 3350.4),
            Quad(7775L, 1.12f, 0.8f, 9330.0),
        )) {
            val emphasis = LineMotion(line("我" to 200L, "爱" to duration)).emphases.single()
            assertEquals(scale, emphasis.scale(0, peak(emphasis)), 0.0002f, "scale for $duration ms")
            assertEquals(glow, emphasis.glowAlpha(0, peak(emphasis)), 0.0015f, "glow for $duration ms")
            assertEquals(motionMs, emphasis.durationMs, 0.1, "duration for $duration ms")
        }
    }

    @Test
    fun `letters spread from the word's centre as on desktop`() {
        val call = emphasisOf(callOfSilence, "Call")
        assertEquals(listOf(0.0, 174.5, 349.0, 523.5), (0..3).map { call.letterStartMs(it) })
        assertEquals(1.0399f, call.scale(0, peak(call)), 0.0002f)
        assertEquals(0.098f, call.glowAlpha(0, peak(call)), 0.001f)
        assertEquals(0.0295198f, call.glowRadiusEm, 0.0001f)
        listOf(-0.0239111f, -0.0119555f, 0f, 0.0119555f).forEachIndexed { letter, spread ->
            assertEquals(spread, call.spreadEm(letter, peak(call, letter)), 0.00005f, "spread of letter $letter")
        }
        val silence = emphasisOf(callOfSilence, "Silence")
        assertEquals(1.0742f, silence.scale(0, peak(silence)), 0.0002f)
        assertEquals(174.571, silence.letterStartMs(1) - silence.letterStartMs(0), 0.001)
    }

    @Test
    fun `the swell follows AMLL's two beziers`() {
        val call = emphasisOf(callOfSilence, "Call")
        // Keyframe 8 of 32, read from desktop's capped 啦 as translateY / -0.03em.
        assertEquals(0.73940f, call.swell(0, (call.durationMs / 4).toLong()), 0.0005f)
        assertEquals(0f, call.swell(0, call.durationMs.toLong() + 1))
    }

    @Test
    fun `letters bob from 400 ms early for 1_4 times their swell`() {
        val emphasis = LineMotion(line("我" to 936L, "爱" to 1152L)).emphases.single()
        // Desktop: delay 536 ms into the line, 1935.4 ms long.
        assertEquals(0f, emphasis.bob(0, 536))
        assertEquals(1f, emphasis.bob(0, (536 + 1935.4 / 2).toLong()), 0.002f)
        assertEquals(536.0, emphasis.movesFromMs, 0.001)
    }

    @Test
    fun `only words held a second are emphasized, two to seven letters outside CJK`() {
        assertTrue(LineMotion(line("reticent" to 3908L)).emphases.isEmpty(), "eight letters")
        assertTrue(LineMotion(line("I" to 1500L, " " to 100L, "go" to 300L)).emphases.isEmpty(), "one letter")
        assertTrue(LineMotion(line("爱" to 845L)).emphases.isEmpty(), "under a second")
        val run = LineMotion(line("run " to 2127L, "because " to 467L)).emphases.single()
        assertEquals(3, run.letters.size)
        assertEquals(283.6, run.letterStartMs(1), 0.001)
        assertEquals(1.0619f, run.scale(0, peak(run)), 0.0002f)
    }

    @Test
    fun `syllables without a space between are judged as one word`() {
        val forever = emphasisOf(line("fo" to 500L, "rev" to 400L, "er" to 300L, " " to 100L, "and" to 200L), "forever")
        assertEquals(7, forever.letters.size)
        assertEquals(0.0, forever.letterStartMs(0))
        assertTrue(LineMotion(line("Oscil" to 600L, "lating" to 700L)).emphases.isEmpty(), "eleven letters")
    }

    @Test
    fun `a CJK word is split into characters sharing its time`() {
        val motion = LineMotion(line("去年" to 2400L))
        assertEquals(listOf(0.0 to 1200.0, 1200.0 to 2400.0), motion.atoms.map { it.startMs to it.endMs })
        assertEquals(2, motion.emphases.size)
    }

    @Test
    fun `words float over at least a second and float back once the line is left`() {
        val motion = LineMotion(line("你" to 204L, "拿" to 3908L, startMs = 1_000))
        assertEquals(0f, motion.float(0, 1_000))
        assertEquals(0.6846f, motion.float(0, 1_500), 0.0005f)
        assertEquals(1f, motion.float(0, 2_000))
        assertEquals(0.6846f, motion.float(1, 1_204 + 1_954), 0.0005f)
        // Left after settling: the float runs back from the top over its own duration.
        assertEquals(1f, motion.float(0, 9_000, leftAtMs = 9_000))
        assertEquals(1 - 0.6846f, 1 - motion.float(0, 9_500, leftAtMs = 9_000), 0.0005f)
        assertEquals(0f, motion.float(0, 10_000, leftAtMs = 9_000))
    }

    @Test
    fun `the fill carries on from one word into the next`() {
        val motion = LineMotion(line("ab" to 1000L, " " to 200L, "cd" to 1000L))
        val sweep = Sweep(motion, floatArrayOf(100f, 50f), fade = 10f)
        assertEquals(-15f, sweep.edge(0, 0))
        assertEquals(100f, sweep.edge(0, 1_000))
        assertEquals(0f, sweep.edge(1, 1_200), "the next word starts with the edge at its left")
        assertEquals(55f, sweep.edge(1, 2_200), "half an edge past the end of the line")
        assertEquals(1f, sweep.light(1, 0f, 2_200))
        assertEquals(0f, sweep.light(1, 10f, 1_200))
    }

    private data class Quad(val duration: Long, val scale: Float, val glow: Float, val motionMs: Double)
}
