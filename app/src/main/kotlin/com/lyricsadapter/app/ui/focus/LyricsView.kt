package com.lyricsadapter.app.ui.focus

import android.os.Build
import androidx.compose.animation.core.FloatExponentialDecaySpec
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalGraphicsContext
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.lyricsadapter.app.data.FocusSettings
import com.lyricsadapter.lyrics.LyricLine
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Desktop's lyric type, as its focus mode computes it: 1.2em lines and tight tracking. */
private const val LINE_HEIGHT = 1.2f
private const val LETTER_SPACING = -0.025f

/** Where the lyrics start in portrait: desktop's text starts 40px in (20px page margin and 20px line padding). */
val LINE_PADDING = 40.dp

/**
 * Desktop's lines stop this much further from the right than they start from
 * the left: AMLL pulls each line out by 6.8px a side and pads it back in, but
 * the box keeps its padding inside its width.
 */
private val LINE_END_EXTRA = 13.6.dp

/** Desktop's blur, measured on its focus mode: 0.8px per line of distance plus one line below the sung one and two above, never past 5px. */
private const val BLUR_PER_LINE = 0.8f
private const val MAX_BLUR = 5f

/** The inactive-blur setting at which the blur matches desktop's. */
private const val DEFAULT_INACTIVE_BLUR = 2f

private fun lineStyle(fontSize: Int) = TextStyle(
    fontSize = fontSize.sp,
    lineHeight = (fontSize * LINE_HEIGHT).sp,
    letterSpacing = LETTER_SPACING.em,
    fontWeight = FontWeight.Bold,
    fontFamily = FocusFonts.display,
    color = INACTIVE,
    // balancedLines() decides where lines break; plain breaking keeps its choice.
    lineBreak = LineBreak.Simple,
)

/** The blur of a line [distance] lines from the sung one (negative above it). */
private fun lineBlur(distance: Float, strength: Float): Float {
    val lines = if (distance < 0) -distance + 2 else distance + 1
    return (lines * BLUR_PER_LINE).coerceAtMost(MAX_BLUR) * strength
}

/** After a manual scroll, how long the lyrics wait before following the song again. */
private const val BROWSE_HOLD_MS = 3_000L

/**
 * Focus-mode lyrics in the Apple Music style. The sung line sits near the top
 * and lights word by word; other lines are dim, a little smaller and blurred
 * by distance. A line change ripples down the list (see [LyricsScroller]).
 * Dragging scrolls freely and clears the blur; the lyrics return to the sung
 * line a few seconds later; [onDraggingChange] reports when a finger starts
 * and stops dragging them. Tapping a line seeks to it.
 *
 * Playback time is read only while drawing and in the active-item
 * derivation, so most frames redraw without recomposing.
 */
@Composable
fun LyricsView(
    lines: List<LyricLine>,
    position: () -> Long,
    onLineClick: (LyricLine) -> Unit,
    modifier: Modifier = Modifier,
    controlsHeight: Dp = 0.dp,
    controlsShown: () -> Float = { 0f },
    settings: FocusSettings = FocusSettings(),
    anchor: LyricsAnchor = LyricsAnchor.Top,
    /** How far the text starts from the view's left side; it ends a little further from the right, as on desktop. */
    linePadding: Dp = LINE_PADDING,
    /** Translated text by line index, shown under each line. */
    translations: Map<Int, String> = emptyMap(),
    onDraggingChange: (Boolean) -> Unit = {},
) {
    val items = remember(lines) { buildLyricItems(lines) }
    val style = remember(settings.fontSize) { lineStyle(settings.fontSize) }
    val measurer = rememberTextMeasurer()
    // Desktop's `padding-block: calc(0.38em + (lineSpacing - 24px) / 2)` around lines exactly the line
    // height tall. Compose keeps a text's first and last line at the font's natural height (about
    // 1.45em for CJK), so the padding gives that extra back.
    val density = LocalDensity.current
    val overhang = remember(style, measurer, density) {
        with(density) { ((measurer.measure("中", style).size.height - style.lineHeight.toPx()) / 2).toDp() }
    }
    val gap = ((settings.fontSize * 0.38f + (settings.lineSpacing - 24) / 2f).dp - overhang).coerceAtLeast(0.dp)
    val blurStrength = settings.inactiveBlur / DEFAULT_INACTIVE_BLUR
    val active by remember(items) { derivedStateOf { items.activeItemAt(position()) } }
    val scroller = remember(items, anchor) { LyricsScroller(anchor) }
    val scope = rememberCoroutineScope()
    // The manual scroll on top of the song-driven one; zero while following the song.
    var browse by remember(items) { mutableFloatStateOf(0f) }
    // From the first touch until the lyrics are back on the sung line.
    var scrolling by remember(items) { mutableStateOf(false) }
    // Only touched from callbacks, so changing it never recomposes.
    var browseJob by remember { mutableStateOf<Job?>(null) }

    val latestOnDraggingChange by rememberUpdatedState(onDraggingChange)

    LaunchedEffect(scroller) {
        while (true) withFrameNanos { scroller.step(it, active) }
    }

    fun browseRange(): ClosedFloatingPointRange<Float> {
        val tops = scroller.tops
        if (tops.isEmpty()) return 0f..0f
        val current = tops[active.coerceAtLeast(0)].toFloat()
        return (current - tops.last())..(current - tops.first())
    }

    fun followSong(after: Long) {
        browseJob?.cancel()
        browseJob = scope.launch {
            delay(after)
            animate(browse, 0f, animationSpec = spring(dampingRatio = 0.9f, stiffness = 120f)) { value, _ -> browse = value }
            scrolling = false
        }
    }

    val dragState = rememberDraggableState { delta -> browse = (browse + delta).coerceIn(browseRange()) }
    val activeRank = items.getOrNull(active)?.rank ?: -1f

    Layout(
        modifier = modifier
            .clipToBounds()
            .fadeEdges(controlsHeight, controlsShown)
            .draggable(
                state = dragState,
                orientation = Orientation.Vertical,
                onDragStarted = {
                    browseJob?.cancel()
                    scrolling = true
                    latestOnDraggingChange(true)
                },
                onDragStopped = { velocity ->
                    latestOnDraggingChange(false)
                    browseJob = scope.launch {
                        animateDecay(browse, velocity, FloatExponentialDecaySpec()) { value, _ -> browse = value.coerceIn(browseRange()) }
                        delay(BROWSE_HOLD_MS)
                        animate(browse, 0f, animationSpec = spring(dampingRatio = 0.9f, stiffness = 120f)) { value, _ -> browse = value }
                        scrolling = false
                    }
                },
            ),
        content = {
            items.forEachIndexed { index, item ->
                key(index) {
                    when (item) {
                        is LineItem -> LyricLineText(
                            line = item.line,
                            distance = item.rank - activeRank,
                            scrolling = scrolling,
                            position = position,
                            measurer = measurer,
                            style = style,
                            gap = gap,
                            blurStrength = blurStrength,
                            translation = translations[item.index],
                            linePadding = linePadding,
                            onClick = {
                                onLineClick(item.line)
                                followSong(after = 0)
                            },
                        )
                        is InterludeItem -> InterludeDots(item, active = index == active, position = position, fontSize = settings.fontSize.toFloat(), linePadding = linePadding)
                    }
                }
            }
        },
    ) { measurables, constraints ->
        val itemConstraints = constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity)
        val placeables = measurables.map { it.measure(itemConstraints) }
        val tops = IntArray(placeables.size)
        var y = 0
        placeables.forEachIndexed { index, placeable ->
            tops[index] = y
            y += placeable.height
        }
        val height = constraints.maxHeight
        scroller.tops = tops
        scroller.heights = IntArray(placeables.size) { placeables[it].height }
        scroller.viewportHeight = height
        layout(constraints.maxWidth, height) {
            placeables.forEachIndexed { index, placeable ->
                placeable.placeWithLayer(0, tops[index]) {
                    val offset = scroller.shift(index) + browse
                    val top = tops[index] + offset
                    translationY = offset
                    // Rows off screen skip drawing, blur included.
                    alpha = if (top + placeable.height < 0 || top > height) 0f else 1f
                }
            }
        }
    }
}

@Composable
private fun LyricLineText(
    line: LyricLine,
    distance: Float,
    scrolling: Boolean,
    position: () -> Long,
    measurer: TextMeasurer,
    style: TextStyle,
    gap: Dp,
    blurStrength: Float,
    translation: String?,
    linePadding: Dp,
    onClick: () -> Unit,
) {
    val active = distance == 0f
    val emphasis by animateFloatAsState(if (active) 1f else 0f, spring(dampingRatio = 1f, stiffness = 160f), label = "emphasis")
    // When the line stopped being sung: its words then float back down.
    var leftAtMs by remember(line) { mutableStateOf<Long?>(null) }
    var wasActive by remember(line) { mutableStateOf(active) }
    LaunchedEffect(active) {
        leftAtMs = if (active) null else if (wasActive) position() else leftAtMs
        wasActive = active
    }
    val blurTarget = if (scrolling || active) 0f else lineBlur(distance, blurStrength)
    val blur by animateFloatAsState(blurTarget, spring(stiffness = 120f), label = "blur")

    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .graphicsLayer {
                val scale = 0.97f + 0.03f * emphasis
                scaleX = scale
                scaleY = scale
                // About where the text starts.
                transformOrigin = TransformOrigin(if (size.width > 0f) linePadding.toPx() / size.width else 0f, 0.5f)
                // Offscreen, the line moves by fractions of a pixel while it scrolls instead of in whole-pixel steps.
                // The layer takes in the padding, so words floating up, swelling and glowing are not cut off.
                compositingStrategy = CompositingStrategy.Offscreen
                val radius = cssBlurRadius(blur.dp.toPx())
                renderEffect = if (radius > 0.5f && Build.VERSION.SDK_INT >= 31) BlurEffect(radius, radius, TileMode.Decal) else null
            }
            .padding(start = linePadding, end = linePadding + LINE_END_EXTRA, top = gap, bottom = gap),
    ) {
        val width = constraints.maxWidth
        // Wrapped as desktop wraps it: between words, into lines of similar width.
        val laidOut = remember(line, style, width) { balancedLines(line.text, measurer, style, width) }
        val graphics = LocalGraphicsContext.current
        val painter = remember(laidOut, measurer, graphics) { SungLinePainter(line, style, laidOut.offsets, graphics) }
        DisposableEffect(painter) { onDispose(painter::release) }
        Column {
        BasicText(
            text = laidOut.text,
            style = style,
            onTextLayout = painter::onLayout,
            modifier = Modifier.drawWithContent {
                if (!painter.draw(this, position(), emphasis, leftAtMs)) drawContent()
            },
        )
        if (translation != null) {
            val translationStyle = remember(style) { translationStyle(style) }
            BasicText(
                text = remember(translation, translationStyle, width) { balancedLines(translation, measurer, translationStyle, width).text },
                style = translationStyle,
                // Desktop's translation line sits at 0.3 inside its line, which is at 0.2, or 0.85 while sung.
                modifier = Modifier.graphicsLayer { alpha = TRANSLATION_ALPHA * (OTHER_LINE_ALPHA + (SUNG_LINE_ALPHA - OTHER_LINE_ALPHA) * emphasis) },
            )
        }
        }
    }
}

/** Desktop's translation line: half the lyric size, 1.2 line height, semibold, slightly tighter. */
private fun translationStyle(style: TextStyle): TextStyle {
    val size = style.fontSize * 0.5f
    return TextStyle(
        fontSize = size,
        lineHeight = size * 1.2f,
        letterSpacing = (-0.015f).em,
        fontWeight = FontWeight.SemiBold,
        fontFamily = FocusFonts.text,
        color = Color.White.copy(alpha = 0.96f),
        lineBreak = LineBreak.Simple,
    )
}

private const val TRANSLATION_ALPHA = 0.3f
private const val SUNG_LINE_ALPHA = 0.85f
private const val OTHER_LINE_ALPHA = 0.2f

/**
 * The [BlurEffect] radius that blurs as much as CSS `blur(sigma)`: CSS takes
 * the Gaussian's deviation, Android a radius it turns into `0.57735 r + 0.5`.
 */
internal fun cssBlurRadius(sigma: Float): Float = ((sigma - 0.5f) / 0.57735f).coerceAtLeast(0f)

/** Desktop's `--focus-lyrics-edge-fade` and portrait bottom fade, each at most a quarter of the view. */
private val TOP_FADE = 72.dp
private val BOTTOM_FADE = 36.dp

/**
 * Desktop's portrait lyrics mask: the lyrics fade in at the top and out at
 * the bottom, and run on under the controls, where they are hidden while the
 * controls show ([controlsShown] at 1) and visible once they hide. The lyrics
 * themselves never move for it.
 */
private fun Modifier.fadeEdges(controlsHeight: Dp, controlsShown: () -> Float): Modifier =
    graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen).drawWithCache {
        val height = size.height
        val cutaway = (controlsHeight.toPx() / height).coerceIn(0f, 0.75f)
        val fade = (BOTTOM_FADE.toPx() / height).coerceAtMost(0.25f)
        val topFade = (TOP_FADE.toPx() / height).coerceAtMost(0.25f)
        val clearEnd = (1f - cutaway - fade).coerceAtLeast(topFade)
        onDrawWithContent {
            drawContent()
            val under = Color.Black.copy(alpha = 1f - controlsShown().coerceIn(0f, 1f))
            val mask = Brush.verticalGradient(
                0f to Color.Transparent,
                topFade to Color.Black,
                clearEnd to Color.Black,
                (1f - cutaway).coerceAtLeast(clearEnd) to under,
                (1f - fade).coerceAtLeast(clearEnd) to under,
                1f to Color.Transparent,
            )
            drawRect(mask, blendMode = BlendMode.DstIn)
        }
    }
