package com.lyricsadapter.app.ui.glass

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.Backdrop
import com.kyant.shapes.Capsule
import com.lyricsadapter.app.ui.Icon
import com.lyricsadapter.app.ui.LocalAppColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToInt
import androidx.compose.foundation.layout.fillMaxHeight

/** One page the switcher offers: its icon and name. */
class PageOption(val icon: Int, val label: String)

/**
 * The page switcher's state, shared by its round button and the bubbles it
 * opens over the page: whether they are open, which bubble a held finger is
 * over, and where everything is on screen.
 */
@Stable
class PageSwitcher {
    var open by mutableStateOf(false)
    var hovered by mutableIntStateOf(-1)
    internal var button by mutableStateOf<Rect?>(null)
    internal val bubbles = mutableStateMapOf<Int, Rect>()

    /** From closed (0) to open (1); the page blurs along with it, as under a menu, so the bubbles read as glass. */
    val progress = Animatable(0f)

    internal fun bubbleAt(position: Offset): Int = bubbles.entries.firstOrNull { it.value.contains(position) }?.key ?: -1
}

/** The bubble height, and the gap between bubbles and above the button. */
private val BUBBLE_HEIGHT = 52.dp
private val BUBBLE_GAP = 10.dp

/** How much later each bubble starts than the one under it. */
private const val BUBBLE_STAGGER_MS = 40L

/**
 * The round glass button for the page on show. A tap opens the page bubbles
 * over it (or closes them); holding it opens them too, and the finger can
 * then slide onto a bubble and let go to switch, as a press-and-drag menu.
 */
@Composable
internal fun PageButton(
    switcher: PageSwitcher,
    current: PageOption,
    onSelect: (Int) -> Unit,
    backdrop: Backdrop,
    modifier: Modifier = Modifier,
) {
    val colors = LocalAppColors.current
    val haptics = LocalHapticFeedback.current
    val select by rememberUpdatedState(onSelect)
    var coordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    GlassRoundButton(
        backdrop = backdrop,
        onClick = null,
        modifier = modifier
            .onGloballyPositioned {
                coordinates = it
                switcher.button = it.boundsInWindow()
            }
            .semantics {
                contentDescription = current.label
                role = Role.Button
            }
            .pointerInput(switcher) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    var released = false
                    val tap = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                        released = waitForUpOrCancellation() != null
                    }
                    if (tap != null) {
                        if (released) switcher.open = !switcher.open
                        return@awaitEachGesture
                    }
                    // Held: open the bubbles and follow the finger over them.
                    switcher.open = true
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    var pointer = down.id
                    while (true) {
                        val change = awaitPointerEvent().changes.firstOrNull { it.id == pointer } ?: break
                        pointer = change.id
                        val window = coordinates?.localToWindow(change.position) ?: break
                        val over = switcher.bubbleAt(window)
                        if (over != switcher.hovered && over >= 0) haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                        switcher.hovered = over
                        change.consume()
                        if (!change.pressed) break
                    }
                    val picked = switcher.hovered
                    switcher.hovered = -1
                    if (picked >= 0) {
                        switcher.open = false
                        select(picked)
                    }
                }
            },
    ) {
        Icon(current.icon, colors.accent, size = 26.dp)
    }
}

/**
 * The page bubbles over the whole page while [switcher] is open: one glass
 * capsule per page, rising from the button one after another with a spring.
 * A tap on a bubble switches to its page; a tap anywhere else closes them.
 */
@Composable
internal fun BoxScope.PageBubbles(
    switcher: PageSwitcher,
    pages: List<PageOption>,
    selected: Int,
    onSelect: (Int) -> Unit,
    backdrop: Backdrop,
) {
    val colors = LocalAppColors.current
    val density = LocalDensity.current
    val progress = remember(pages.size) { List(pages.size) { Animatable(0f) } }
    LaunchedEffect(switcher.open) {
        launch { switcher.progress.animateTo(if (switcher.open) 1f else 0f, spring(stiffness = 420f)) }
        progress.forEachIndexed { index, animation ->
            launch {
                delay(BUBBLE_STAGGER_MS * if (switcher.open) index else pages.lastIndex - index)
                animation.animateTo(if (switcher.open) 1f else 0f, spring(dampingRatio = 0.68f, stiffness = 420f))
            }
        }
    }
    // Derived, so the springs redraw the bubbles without recomposing them every frame.
    val shown by remember(progress) { derivedStateOf { switcher.open || progress.any { it.value > 0.01f } } }
    // The real glass fades in once the bubbles stand still; see SettlingGlass.
    val settled by remember(progress) { derivedStateOf { switcher.open && progress.none { it.isRunning } } }
    if (!shown) {
        switcher.bubbles.clear()
        return
    }
    val button = switcher.button ?: return
    // A tap outside the bubbles closes them.
    Box(
        Modifier
            .matchParentSize()
            .graphicsLayer { alpha = progress.maxOf { it.value }.coerceIn(0f, 1f) }
            .background(BubbleScrim)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { switcher.open = false },
    )
    Column(
        Modifier.offset {
            IntOffset(button.left.roundToInt(), (button.top - with(density) { (BUBBLE_HEIGHT * pages.size + BUBBLE_GAP * pages.size).toPx() }).roundToInt())
        },
        verticalArrangement = Arrangement.spacedBy(BUBBLE_GAP),
    ) {
        // The first page sits nearest the button.
        pages.indices.reversed().forEach { index ->
            val page = pages[index]
            val active = index == selected
            val hovered = index == switcher.hovered
            Box(
                Modifier
                    .graphicsLayer {
                        val p = progress[index].value
                        alpha = p.coerceIn(0f, 1f)
                        val scale = (0.6f + 0.4f * p) * if (hovered) 1.08f else 1f
                        scaleX = scale
                        scaleY = scale
                        translationY = (1f - p) * 24.dp.toPx()
                        transformOrigin = TransformOrigin(0f, 1f)
                    }
                    .onGloballyPositioned { switcher.bubbles[index] = it.boundsInWindow() }
                    .height(BUBBLE_HEIGHT),
            ) {
                SettlingGlass(backdrop, Capsule(), lens = 10.dp, settled = settled)
                Row(
                    Modifier
                        .fillMaxHeight()
                        .clip(Capsule())
                        .semantics {
                            role = Role.Tab
                            this.selected = active
                        }
                        .clickable {
                            switcher.open = false
                            onSelect(index)
                        }
                        .padding(start = 16.dp, end = 20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val tint = if (active || hovered) colors.accent else colors.text
                    Icon(page.icon, tint, size = 24.dp)
                    Spacer(Modifier.width(10.dp))
                    BasicText(page.label, style = TextStyle(color = tint, fontSize = 15.sp, fontWeight = FontWeight.SemiBold))
                }
            }
        }
    }
}

/** A light veil over the page while the bubbles are open, so they read as above it. */
internal val BubbleScrim = Color.Black.copy(alpha = 0.12f)
