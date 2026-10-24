package com.lyricsadapter.app.ui.focus

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/** AMLL's dots (`interlude-dots.ts` and its CSS), in ems of the lyric font. */
private const val DOT_EM = 0.5f
private const val DOT_GAP_EM = 0.25f
private val DOT_MARGIN = 4.dp
private const val ROW_MARGIN_EM = 0.4f
private const val ROW_PADDING_EM = 0.75f
private const val ROW_PADDING_WIDTH = 0.025f
private const val BASE_SCALE = 0.7f
private const val BREATHE_MS = 1_500f
private const val INTRO_SCALE_MS = 2_000f
private const val INTRO_HIDDEN_MS = 500f
private const val OUTRO_SCALE_MS = 750f
private const val OUTRO_FADE_MS = 375f
private val DOT_COLOR = Color.White.copy(alpha = 0.96f)

/** AMLL's player starts 20px left of the lyric text; its dots are padded 0.75em in from there. */
private val PLAYER_INSET = 20.dp

/**
 * Three dots holding the place of an instrumental gap, timed as AMLL times
 * them: they fade in after half a second, light one after another as the gap
 * passes, breathe gently and shrink away in the last three quarters of a
 * second. The row only takes room while its gap is playing.
 */
@Composable
internal fun InterludeDots(item: InterludeItem, active: Boolean, position: () -> Long, fontSize: Float, linePadding: Dp) {
    val opening by animateFloatAsState(if (active) 1f else 0f, spring(dampingRatio = 0.85f, stiffness = 200f), label = "interlude")
    // The spring overshoots; a row can grow past full size but never below nothing.
    val expand by remember { derivedStateOf { opening.coerceAtLeast(0f) } }
    Spacer(
        Modifier
            .fillMaxWidth()
            // The dots draw in the full row; the row reports its shrunken height, so it really takes less room.
            .layout { measurable, constraints ->
                val em = fontSize * density * fontScale
                val width = constraints.maxWidth
                val height = (em * DOT_EM + 2 * ROW_PADDING_WIDTH * width + 2 * ROW_MARGIN_EM * em).roundToInt()
                val placeable = measurable.measure(Constraints.fixed(width, height))
                layout(width, (height * expand).roundToInt()) { placeable.place(0, 0) }
            }
            .drawBehind {
                if (expand <= 0f) return@drawBehind
                val em = fontSize * density * fontScale
                val elapsed = (position() - item.startMs).toFloat()
                val duration = (item.endMs - item.startMs).toFloat()
                if (elapsed < 0f || elapsed > duration) return@drawBehind
                val dot = em * DOT_EM
                val step = dot + em * DOT_GAP_EM + DOT_MARGIN.toPx()
                val left = linePadding.toPx() - PLAYER_INSET.toPx() + em * ROW_PADDING_EM
                val center = Offset(left + step + dot / 2, size.height / 2)
                val show = fadeIn(elapsed) * ((duration - elapsed) / OUTRO_FADE_MS).coerceIn(0f, 1f)
                val dotsDuration = (duration - OUTRO_SCALE_MS).coerceAtLeast(1f)
                scale(dotScale(elapsed, duration) * expand, pivot = center) {
                    for (index in 0 until 3) {
                        val lit = ((elapsed - dotsDuration * index / 3) * 3 / dotsDuration * 0.75f).coerceIn(0.25f, 1f)
                        val alpha = DOT_COLOR.alpha * (show * lit).coerceIn(0f, 1f)
                        drawCircle(DOT_COLOR.copy(alpha = alpha), dot / 2, Offset(left + index * step + dot / 2, center.y))
                    }
                }
            },
    )
}

/** Hidden for half a second, then fading in over the next half. */
private fun fadeIn(elapsed: Float): Float = when {
    elapsed < INTRO_HIDDEN_MS -> 0f
    elapsed < 2 * INTRO_HIDDEN_MS -> (elapsed - INTRO_HIDDEN_MS) / INTRO_HIDDEN_MS
    else -> 1f
}

private fun dotScale(elapsed: Float, duration: Float): Float {
    val breathe = duration / ceil(duration / BREATHE_MS)
    var scale = sin(1.5f * PI.toFloat() - elapsed / breathe * 2) / 20 + 1
    if (elapsed < INTRO_SCALE_MS) scale *= easeOutExpo(elapsed / INTRO_SCALE_MS)
    val left = duration - elapsed
    if (left < OUTRO_SCALE_MS) scale *= 1 - easeInOutBack((OUTRO_SCALE_MS - left) / OUTRO_SCALE_MS / 2)
    return scale.coerceAtLeast(0f) * BASE_SCALE
}

private fun easeOutExpo(x: Float): Float = if (x >= 1f) 1f else 1 - 2f.pow(-10 * x)

private fun easeInOutBack(x: Float): Float {
    val c2 = 1.70158f * 1.525f
    return if (x < 0.5f) {
        (2 * x).pow(2) * ((c2 + 1) * 2 * x - c2) / 2
    } else {
        ((2 * x - 2).pow(2) * ((c2 + 1) * (x * 2 - 2) + c2) + 2) / 2
    }
}
