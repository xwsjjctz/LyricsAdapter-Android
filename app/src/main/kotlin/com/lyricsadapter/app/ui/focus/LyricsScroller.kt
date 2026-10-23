package com.lyricsadapter.app.ui.focus

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue
import kotlin.math.abs
import kotlin.math.sqrt

/** Where the sung item sits, as a fraction of the view's height from the top. */
internal const val ANCHOR_FRACTION = 0.26f

private const val STIFFNESS = 110f
private const val DAMPING_RATIO = 0.9f

/** How much later each item below the sung one starts to move. */
private const val STAGGER_NANOS = 45_000_000L
private const val MAX_STAGGER_ITEMS = 10

/** Line changes further than this are seeks: everything moves together. */
private const val MAX_RIPPLE_JUMP = 2
private const val MAX_STEP_SECONDS = 1f / 240

/**
 * Scrolls the lyric items so the sung one sits at the anchor. Each item
 * follows its own spring, and items below the sung one start later the
 * further down they are, so a line change ripples down the list. Offsets
 * advance once per frame in [step]; layers read them through [shift], which
 * redraws them without recomposing or measuring again.
 */
internal class LyricsScroller {
    /** Item tops and the view height from the latest measure pass. */
    var tops: IntArray = IntArray(0)
    var viewportHeight: Int = 0

    private var shifts = FloatArray(0)
    private var velocities = FloatArray(0)
    private var releases = LongArray(0)
    private var lastFrame = 0L
    private var lastActive = 0
    private var frame by mutableLongStateOf(0L)

    fun shift(index: Int): Float {
        frame
        return shifts.getOrElse(index) { 0f }
    }

    /** The shift that puts [active] at the anchor; before the first item, the first one. */
    fun targetShift(active: Int): Float = viewportHeight * ANCHOR_FRACTION - tops.getOrElse(active.coerceAtLeast(0)) { 0 }

    fun step(frameNanos: Long, active: Int) {
        val count = tops.size
        if (count == 0) return
        val target = targetShift(active)
        if (shifts.size != count) {
            jumpTo(count, target, active, frameNanos)
            return
        }
        if (active != lastActive) {
            val ripple = active > lastActive && active - lastActive <= MAX_RIPPLE_JUMP
            for (index in 0 until count) {
                val delay = if (ripple) (index - active).coerceIn(0, MAX_STAGGER_ITEMS) * STAGGER_NANOS else 0L
                releases[index] = frameNanos + delay
            }
            lastActive = active
        }
        val elapsed = ((frameNanos - lastFrame) / 1e9f).coerceIn(0f, 0.05f)
        lastFrame = frameNanos
        if (advance(target, frameNanos, elapsed)) frame = frameNanos
    }

    private fun jumpTo(count: Int, target: Float, active: Int, frameNanos: Long) {
        shifts = FloatArray(count) { target }
        velocities = FloatArray(count)
        releases = LongArray(count)
        lastActive = active
        lastFrame = frameNanos
        frame = frameNanos
    }

    /** Moves every released item toward [target]; true if anything is still in motion. */
    private fun advance(target: Float, frameNanos: Long, elapsed: Float): Boolean {
        val damping = 2 * DAMPING_RATIO * sqrt(STIFFNESS)
        var moving = false
        for (index in shifts.indices) {
            var position = shifts[index]
            var velocity = velocities[index]
            if (position == target && velocity == 0f) continue
            moving = true
            if (frameNanos < releases[index]) continue
            if (abs(position - target) < 0.5f && abs(velocity) < 5f) {
                shifts[index] = target
                velocities[index] = 0f
                continue
            }
            var remaining = elapsed
            while (remaining > 0f) {
                val step = minOf(remaining, MAX_STEP_SECONDS)
                velocity += (STIFFNESS * (target - position) - damping * velocity) * step
                position += velocity * step
                remaining -= step
            }
            shifts[index] = position
            velocities[index] = velocity
        }
        return moving
    }
}
