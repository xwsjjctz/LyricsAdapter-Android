package com.lyricsadapter.app.ui.glass

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.Backdrop
import com.lyricsadapter.app.R
import com.lyricsadapter.app.ui.Icon
import com.lyricsadapter.app.ui.LocalAppColors
import com.lyricsadapter.app.ui.MenuEntry
import kotlin.math.roundToInt
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.platform.LocalHapticFeedback

/** The pressed element, drawn again over the dimmed page and lifted: where it is on screen and how to draw it. */
class MenuPreview(val bounds: Rect, val content: @Composable () -> Unit)

/** What a menu shows; set by `ContextMenu` while it is composed. */
internal class MenuRequest(
    val token: Any,
    val anchor: IntOffset,
    val entries: List<MenuEntry>,
    val preview: MenuPreview?,
    val onDismiss: () -> Unit,
)

/**
 * The page's one menu: what it shows, the item a held finger is over, and
 * how far it has opened. `ContextMenu` fills it; [GlassMenuHost] draws it.
 */
@Stable
class GlassMenuState {
    internal var request by mutableStateOf<MenuRequest?>(null)
    internal var hovered by mutableIntStateOf(-1)
    internal val itemBounds = mutableStateMapOf<Int, Rect>()

    /** From closed (0) to open (1), springing; the page blurs and dims along with it. */
    val progress = Animatable(0f)

    /** A finger still held from the long press that opened the menu is at [window]: the item under it lights up. */
    fun hover(window: Offset) {
        hovered = itemBounds.entries.firstOrNull { it.value.contains(window) }?.key ?: -1
    }

    /** That finger lifted: the item under it runs, as on iOS; elsewhere the menu stays open. */
    fun release() {
        val index = hovered
        hovered = -1
        val request = request ?: return
        val item = request.entries.getOrNull(index) as? MenuEntry.Item ?: return
        if (!item.keepOpen) request.onDismiss()
        item.onClick()
    }
}

/** The menu host of the screen, when there is one; menus fall back to a popup without it. */
val LocalGlassMenu = staticCompositionLocalOf<GlassMenuState?> { null }

/**
 * A tap runs [onClick]; a long press runs [onLongPress] with where the finger
 * is and the element's bounds, both in the window, so the element can open its
 * menu lifted. The finger can then stay down, slide onto an item and let go
 * to pick it, as on iOS.
 */
@Composable
fun Modifier.menuPress(onClick: () -> Unit, onLongPress: ((anchor: IntOffset, bounds: Rect) -> Unit)?): Modifier {
    val menu = LocalGlassMenu.current
    val haptics = LocalHapticFeedback.current
    val click by rememberUpdatedState(onClick)
    val longPress by rememberUpdatedState(onLongPress)
    var coordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    return onGloballyPositioned { coordinates = it }
        .pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown()
                var tapped = false
                val beforeLongPress = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                    tapped = waitForUpOrCancellation() != null
                }
                if (beforeLongPress != null) {
                    if (tapped) click()
                    return@awaitEachGesture
                }
                val open = longPress ?: return@awaitEachGesture
                val origin = coordinates ?: return@awaitEachGesture
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                val at = origin.localToWindow(down.position)
                open(IntOffset(at.x.roundToInt(), at.y.roundToInt()), origin.boundsInWindow())
                // The finger stays on the menu: what it is over lights up, and letting go there picks it.
                while (true) {
                    val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                    change.consume()
                    menu?.hover(origin.localToWindow(change.position))
                    if (!change.pressed) {
                        menu?.release()
                        break
                    }
                }
            }
        }
}

private val PANEL_WIDTH = 260.dp
private val PANEL_MARGIN = 12.dp
private val PANEL_SHAPE = RoundedCornerShape(22.dp)

/** The light veil over the page under an open menu. */
private val MenuVeil = Color.Black.copy(alpha = 0.18f)

/** How much the pressed element grows while lifted. */
private const val LIFT_SCALE = 0.03f

/**
 * Draws the open menu over the whole screen: a light veil (a tap on it
 * closes the menu), the pressed element lifted above it, and the Liquid
 * Glass panel springing out from the finger, below it when there is room and
 * above otherwise. Items light up under a held finger and run when tapped.
 */
@Composable
internal fun BoxScope.GlassMenuHost(state: GlassMenuState, backdrop: Backdrop) {
    val colors = LocalAppColors.current
    val density = LocalDensity.current
    val request = state.request
    // Kept while the menu closes, so it can animate out; state, so the veil goes with it once closed.
    var shown by remember { mutableStateOf<MenuRequest?>(null) }
    if (request != null && shown !== request) shown = request
    LaunchedEffect(request != null) {
        state.progress.animateTo(if (request != null) 1f else 0f, spring(dampingRatio = 0.78f, stiffness = 520f))
        if (request == null) {
            shown = null
            state.itemBounds.clear()
        }
    }
    val menu = shown ?: return
    // The progress is read only while drawing, so the springs redraw the menu without recomposing it.
    val progress = state.progress

    Box(
        Modifier
            .matchParentSize()
            .drawBehind { drawRect(MenuVeil, alpha = progress.value.coerceIn(0f, 1f)) }
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { state.request?.onDismiss?.invoke() },
    )
    menu.preview?.let { preview ->
        Box(
            Modifier
                .offset { IntOffset(preview.bounds.left.roundToInt(), preview.bounds.top.roundToInt()) }
                .size(with(density) { preview.bounds.width.toDp() }, with(density) { preview.bounds.height.toDp() })
                .graphicsLayer {
                    val p = progress.value
                    val scale = 1f + LIFT_SCALE * p
                    scaleX = scale
                    scaleY = scale
                    shadowElevation = 18.dp.toPx() * p.coerceIn(0f, 1f)
                    shape = RoundedCornerShape(12.dp)
                    clip = true
                }
                .background(colors.elevated),
        ) {
            // Drawn on the floating surface, so in dark mode it reads as raised where a shadow cannot show.
            CompositionLocalProvider(LocalAppColors provides colors.copy(background = colors.elevated)) { preview.content() }
        }
    }
    var below by remember { mutableStateOf(true) }
    // A press on the right half opens the panel leftwards from the finger, as iOS does.
    var leftwards by remember { mutableStateOf(false) }
    Box(
        Modifier
            .matchParentSize()
            .layout { measurable, constraints ->
                val margin = PANEL_MARGIN.roundToPx()
                val panel = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0, maxHeight = constraints.maxHeight - 2 * margin))
                val top = menu.preview?.bounds?.top?.roundToInt() ?: menu.anchor.y
                val bottom = menu.preview?.bounds?.bottom?.roundToInt() ?: menu.anchor.y
                val gap = 8.dp.roundToPx()
                below = bottom + gap + panel.height <= constraints.maxHeight - margin
                val y = if (below) bottom + gap else (top - gap - panel.height).coerceAtLeast(margin)
                leftwards = menu.anchor.x > constraints.maxWidth / 2
                val start = if (leftwards) menu.anchor.x - panel.width else menu.anchor.x
                val x = start.coerceIn(margin, (constraints.maxWidth - panel.width - margin).coerceAtLeast(margin))
                layout(constraints.maxWidth, constraints.maxHeight) { panel.place(x, y) }
            },
    ) {
        Column(
            Modifier
                .graphicsLayer {
                    val p = progress.value
                    val clamped = p.coerceIn(0f, 1.2f)
                    alpha = p.coerceIn(0f, 1f)
                    val scale = 0.82f + 0.18f * clamped
                    scaleX = scale
                    scaleY = scale
                    transformOrigin = TransformOrigin(if (leftwards) 0.85f else 0.15f, if (below) 0f else 1f)
                }
                .width(PANEL_WIDTH)
                .heightIn(max = 520.dp)
                .glass(backdrop, PANEL_SHAPE, lens = 10.dp)
                .clip(PANEL_SHAPE)
                .verticalScroll(rememberScrollState())
                .padding(vertical = 6.dp),
        ) {
            val checkable = menu.entries.any { it is MenuEntry.Item && it.checked }
            menu.entries.forEachIndexed { index, entry ->
                when (entry) {
                    MenuEntry.Separator -> Box(Modifier.fillMaxWidth().padding(vertical = 4.dp).height(0.5.dp).background(colors.divider))
                    is MenuEntry.Label -> BasicText(
                        entry.text,
                        style = TextStyle(color = colors.secondaryText, fontSize = 13.sp),
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 2.dp),
                    )
                    is MenuEntry.Item -> {
                        val tint = if (entry.danger) colors.danger else colors.text
                        val hovered = index == state.hovered
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .onGloballyPositioned { state.itemBounds[index] = it.boundsInWindow() }
                                .padding(horizontal = 6.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (hovered) colors.text.copy(alpha = 0.1f) else Color.Transparent)
                                .semantics { role = Role.Button }
                                .clickable {
                                    if (!entry.keepOpen) state.request?.onDismiss?.invoke()
                                    entry.onClick()
                                }
                                .padding(horizontal = 10.dp, vertical = 11.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (checkable) {
                                Box(Modifier.width(24.dp)) {
                                    if (entry.checked) Icon(R.drawable.ui_check, colors.accent, size = 18.dp)
                                }
                            }
                            BasicText(entry.label, style = TextStyle(color = tint, fontSize = 16.sp), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            if (entry.detail != null) {
                                Spacer(Modifier.width(8.dp))
                                BasicText(entry.detail, style = TextStyle(color = colors.secondaryText, fontSize = 15.sp))
                            }
                            if (entry.icon != null) {
                                Spacer(Modifier.width(12.dp))
                                Icon(entry.icon, tint, size = 20.dp)
                            }
                        }
                    }
                }
            }
        }
    }
}
