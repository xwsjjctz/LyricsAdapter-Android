package com.lyricsadapter.app.ui.focus

import android.os.Build
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lyricsadapter.lyrics.LyricLine
import com.lyricsadapter.lyrics.activeIndex
import kotlin.math.absoluteValue

/** Where the sung line sits, as a fraction of the view's height from the top. */
private const val ANCHOR_FRACTION = 0.32f

private val UNSUNG = Color.White.copy(alpha = 0.32f)
private val SUNG = Color.White
private val LINE_STYLE = TextStyle(fontSize = 30.sp, lineHeight = 40.sp, fontWeight = FontWeight.Bold, color = UNSUNG)

/**
 * Focus-mode lyrics in the AMLL style: the sung line fills word by word, the
 * other lines are shrunk and blurred by their distance, and the list follows
 * the sung line with a soft spring. Playback time is read only while drawing
 * and in the active-line derivation, so most frames redraw without
 * recomposing. Tapping a line seeks to it.
 */
@Composable
fun LyricsView(
    lines: List<LyricLine>,
    position: () -> Long,
    onLineClick: (LyricLine) -> Unit,
    modifier: Modifier = Modifier,
    blurLines: Boolean = true,
) {
    val activeIndex by remember(lines) { derivedStateOf { lines.activeIndex(position()) } }
    val listState = rememberLazyListState()

    BoxWithConstraints(modifier) {
        val anchor = constraints.maxHeight * ANCHOR_FRACTION
        LaunchedEffect(activeIndex) { listState.follow(activeIndex, anchor) }
        LazyColumn(
            state = listState,
            userScrollEnabled = false,
            contentPadding = PaddingValues(top = maxHeight * ANCHOR_FRACTION, bottom = maxHeight * (1 - ANCHOR_FRACTION)),
            modifier = Modifier.fillMaxSize(),
        ) {
            itemsIndexed(lines) { index, line ->
                val distance = if (activeIndex < 0) index + 1 else index - activeIndex
                LyricLineText(line, distance, blurLines, position, onClick = { onLineClick(line) })
            }
        }
    }
}

/** Scrolls so line [index] starts [anchor] pixels below the top. */
private suspend fun LazyListState.follow(index: Int, anchor: Float) {
    if (index < 0) return
    val item = layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }
    if (item == null) {
        scrollToItem(index)
        return
    }
    val top = item.offset - layoutInfo.viewportStartOffset
    animateScrollBy(top - anchor, spring(dampingRatio = 0.86f, stiffness = 90f))
}

@Composable
private fun LyricLineText(line: LyricLine, distance: Int, blurLines: Boolean, position: () -> Long, onClick: () -> Unit) {
    val active = distance == 0
    val emphasis by animateFloatAsState(if (active) 1f else 0f, spring(stiffness = 140f), label = "emphasis")
    val blurTarget = if (blurLines) distance.absoluteValue.coerceAtMost(4) * 1.5f else 0f
    val blur by animateFloatAsState(blurTarget, spring(stiffness = 140f), label = "blur")
    val painter = remember(line) { SungTextPainter(line) }

    BasicText(
        text = line.text,
        style = LINE_STYLE,
        onTextLayout = painter::onLayout,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(horizontal = 28.dp, vertical = 12.dp)
            .graphicsLayer {
                val scale = 0.94f + 0.06f * emphasis
                scaleX = scale
                scaleY = scale
                transformOrigin = TransformOrigin(0f, 0.5f)
                val radius = blur.dp.toPx()
                renderEffect = if (radius > 0.5f && Build.VERSION.SDK_INT >= 31) BlurEffect(radius, radius, TileMode.Decal) else null
            }
            .drawWithContent {
                drawContent()
                if (active) painter.drawSung(this, position())
            },
    )
}

/**
 * Paints the sung part of one line over its dim text. Finished words are a
 * prefix of the text, clipped by a path cached per word count; the word being
 * sung gets a soft-edged gradient.
 */
private class SungTextPainter(private val line: LyricLine) {
    private val wordEnds: IntArray = line.words.runningFold(0) { end, word -> end + word.text.length }.drop(1).toIntArray()
    private var layout: TextLayoutResult? = null
    private val prefixPaths = HashMap<Int, Path>()
    private val wordPaths = HashMap<Int, Pair<Path, Rect>>()

    fun onLayout(result: TextLayoutResult) {
        layout = result
        prefixPaths.clear()
        wordPaths.clear()
    }

    fun drawSung(scope: DrawScope, positionMs: Long) {
        val layout = layout ?: return
        val words = line.words
        if (words.isEmpty() || wordEnds.last() != line.text.length) {
            // A line without word timing is lit as a whole.
            scope.drawText(layout, color = SUNG)
            return
        }

        val finished = words.count { it.endMs <= positionMs }
        if (finished > 0) {
            val prefix = prefixPaths.getOrPut(finished) { layout.getPathForRange(0, wordEnds[finished - 1]) }
            scope.clipPath(prefix) { drawText(layout, color = SUNG) }
        }

        val word = words.getOrNull(finished) ?: return
        if (positionMs < word.startMs) return
        val progress = ((positionMs - word.startMs).toFloat() / word.durationMs.coerceAtLeast(1)).coerceIn(0f, 1f)
        val (path, bounds) = wordPaths.getOrPut(finished) {
            val start = if (finished == 0) 0 else wordEnds[finished - 1]
            val wordPath = layout.getPathForRange(start, wordEnds[finished])
            wordPath to wordPath.getBounds()
        }
        val edge = bounds.left + bounds.width * progress
        val feather = bounds.height * 0.35f
        val sweep = Brush.horizontalGradient(
            0f to SUNG,
            1f to Color.Transparent,
            startX = edge - feather,
            endX = edge + feather,
        )
        scope.clipPath(path) { drawText(layout, brush = sweep) }
    }
}
