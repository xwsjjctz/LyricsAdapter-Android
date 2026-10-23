package com.lyricsadapter.app.ui.focus

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

private val DOT_RADIUS = 5.dp
private val DOT_GAP = 16.dp
private val START_PADDING = 28.dp
private const val BREATH_PERIOD_MS = 1_600f
private const val OUTRO_MS = 450f

/**
 * Three dots holding the place of an instrumental gap. They light one after
 * another as the gap passes, breathe while waiting and shrink away just
 * before the next line. The row only takes room while its gap is playing.
 */
@Composable
internal fun InterludeDots(item: InterludeItem, active: Boolean, position: () -> Long) {
    val opening by animateFloatAsState(if (active) 1f else 0f, spring(dampingRatio = 0.85f, stiffness = 200f), label = "interlude")
    // The spring overshoots; a row can grow past full size but never below nothing.
    val expand by remember { derivedStateOf { opening.coerceAtLeast(0f) } }
    Spacer(
        Modifier
            .fillMaxWidth()
            // Reports the shrunken height outside the fixed one, so the row really takes less room.
            .layout { measurable, constraints ->
                val placeable = measurable.measure(constraints)
                layout(placeable.width, (placeable.height * expand).roundToInt()) { placeable.place(0, 0) }
            }
            .height(48.dp)
            .drawBehind {
                if (expand <= 0f) return@drawBehind
                val now = position()
                val progress = ((now - item.startMs).toFloat() / (item.endMs - item.startMs)).coerceIn(0f, 1f)
                val breath = 1f + 0.08f * sin(2 * PI.toFloat() * now / BREATH_PERIOD_MS)
                val outro = ((item.endMs - now) / OUTRO_MS).coerceIn(0f, 1f)
                val radius = DOT_RADIUS.toPx()
                val gap = DOT_GAP.toPx()
                val left = START_PADDING.toPx()
                val center = Offset(left + radius + gap, size.height * expand / 2)
                scale(expand * breath * outro, pivot = center) {
                    for (dot in 0 until 3) {
                        val lit = (progress * 3 - dot).coerceIn(0f, 1f)
                        drawCircle(Color.White.copy(alpha = 0.3f + 0.7f * lit), radius, Offset(left + radius + dot * gap, center.y))
                    }
                }
            },
    )
}
