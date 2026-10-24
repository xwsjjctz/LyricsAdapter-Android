package com.lyricsadapter.app.ui.focus

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
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
import kotlin.math.cos
import kotlin.math.roundToInt

/*
 * Sizes and timings measured on desktop's focus mode: dots half an em wide,
 * a quarter em plus 4px apart, drawn at 70 % size, 0.75em in from the
 * player's edge; the row has 0.4em above and below plus 2.5 % of the width.
 * They stay hidden for half a second, fade in over the next half, light one
 * after another, breathe by about 5 %, and in the last 750 ms dip and shrink
 * away, fading out over the last 375 ms.
 */
private const val DOT_EM = 0.5f
private const val GAP_EM = 0.25f
private val DOT_MARGIN = 4.dp
private const val ROW_MARGIN_EM = 0.4f
private const val ROW_INSET_EM = 0.75f
private const val ROW_PADDING_SHARE = 0.025f
private const val DRAWN_SIZE = 0.7f
private const val DIM_DOT = 0.25f
private val DOT_COLOR = Color.White.copy(alpha = 0.96f)

private const val HIDDEN_MS = 500f
private const val FADE_IN_MS = 500f
private const val GROW_MS = 2_000f
private const val SHRINK_MS = 750f
private const val FADE_OUT_MS = 375f
private const val BREATH_DEPTH = 0.05f
private const val BREATH_MS = 4_700f

/** Pulls back a little before it goes: an ease-in with anticipation. */
private val ShrinkEasing = CubicBezierEasing(0.36f, 0f, 0.66f, -0.56f)

/** The lyric text starts this far right of where desktop's player draws its dots from. */
private val PLAYER_INSET = 20.dp

/**
 * Three dots holding the place of an instrumental gap. They appear shortly
 * after the gap starts, light one after another as it passes, breathe while
 * waiting and shrink away just before the next line. The row only takes room
 * while its gap is playing.
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
                val height = (em * (DOT_EM + 2 * ROW_MARGIN_EM) + 2 * ROW_PADDING_SHARE * width).roundToInt()
                val placeable = measurable.measure(Constraints.fixed(width, height))
                layout(width, (height * expand).roundToInt()) { placeable.place(0, 0) }
            }
            .drawBehind {
                if (expand <= 0f) return@drawBehind
                val elapsed = (position() - item.startMs).toFloat()
                val length = (item.endMs - item.startMs).toFloat()
                val left = length - elapsed
                if (elapsed < 0f || left < 0f) return@drawBehind
                val em = fontSize * density * fontScale
                val dot = em * DOT_EM
                val step = dot + em * GAP_EM + DOT_MARGIN.toPx()
                val start = linePadding.toPx() - PLAYER_INSET.toPx() + em * ROW_INSET_EM
                val middle = Offset(start + step + dot / 2, size.height / 2)

                val shown = visibility(elapsed, left)
                // Each dot has a third of the gap (less the exit) to come up from dim to full.
                val lighting = (length - SHRINK_MS).coerceAtLeast(1f)
                scale(DRAWN_SIZE * size(elapsed, left) * expand, pivot = middle) {
                    repeat(3) { index ->
                        val share = ((elapsed - lighting * index / 3f) / (lighting / 3f)).coerceIn(0f, 1f)
                        val brightness = DIM_DOT + (1f - DIM_DOT) * share
                        drawCircle(DOT_COLOR.copy(alpha = DOT_COLOR.alpha * shown * brightness), dot / 2, Offset(start + index * step + dot / 2, middle.y))
                    }
                }
            },
    )
}

/** How visible the dots are: hidden, then fading in, and fading out at the very end. */
private fun visibility(elapsed: Float, left: Float): Float {
    val fadeIn = ((elapsed - HIDDEN_MS) / FADE_IN_MS).coerceIn(0f, 1f)
    val fadeOut = (left / FADE_OUT_MS).coerceIn(0f, 1f)
    return fadeIn * fadeOut
}

/** The row's size: growing in, breathing, then dipping and shrinking away. */
private fun size(elapsed: Float, left: Float): Float {
    val grow = LinearOutSlowInEasing.transform((elapsed / GROW_MS).coerceIn(0f, 1f))
    val breath = 1f - BREATH_DEPTH * cos(2 * PI.toFloat() * elapsed / BREATH_MS)
    val shrink = if (left < SHRINK_MS) 1f - ShrinkEasing.transform(1f - left / SHRINK_MS) else 1f
    return (grow * breath * shrink).coerceAtLeast(0f)
}
