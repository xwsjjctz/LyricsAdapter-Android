package com.lyricsadapter.app.ui.glass

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.emptyBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.highlight.HighlightStyle
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import com.lyricsadapter.app.ui.LocalAppColors

/**
 * How a pane of glass looks apart from what shows through it: its tint, and
 * the light on its edges. The rim is a soft glint strongest on the corners
 * facing the light and fading along the sides, not a drawn outline; light
 * entering the top edge glows inside it; and its shadow lifts it off the page.
 */
@Immutable
internal class GlassLook(
    val tint: Color,
    val highlight: Highlight,
    val edgeGlow: InnerShadow,
    val shadow: Shadow,
)

private val DarkGlass = GlassLook(
    tint = Color(0xFF1C1C1E).copy(alpha = 0.65f),
    highlight = Highlight(
        width = 1.dp,
        blurRadius = 0.75.dp,
        style = HighlightStyle.Default(color = Color.White.copy(alpha = 0.34f), falloff = 2.4f),
    ),
    edgeGlow = InnerShadow(radius = 5.dp, offset = DpOffset(0.dp, 2.5.dp), color = Color.White.copy(alpha = 0.11f), blendMode = BlendMode.Plus),
    shadow = Shadow(radius = 20.dp, offset = DpOffset(0.dp, 6.dp), color = Color.Black.copy(alpha = 0.36f)),
)

private val LightGlass = GlassLook(
    tint = Color(0xFFF9F9F9).copy(alpha = 0.7f),
    highlight = Highlight(
        width = 1.dp,
        blurRadius = 0.75.dp,
        style = HighlightStyle.Default(color = Color.White.copy(alpha = 0.7f), falloff = 2.4f),
    ),
    edgeGlow = InnerShadow(radius = 5.dp, offset = DpOffset(0.dp, 2.5.dp), color = Color.White.copy(alpha = 0.5f), blendMode = BlendMode.Plus),
    shadow = Shadow(radius = 20.dp, offset = DpOffset(0.dp, 6.dp), color = Color.Black.copy(alpha = 0.14f)),
)

/** Light glass on light pages, dark glass on dark ones; dense enough that the list under a bar does not read through it. */
@Composable
internal fun glassLook(): GlassLook = if (LocalAppColors.current.isDark) DarkGlass else LightGlass

/** How much the glass blurs what is under it. */
internal val GLASS_BLUR = 16.dp

/**
 * Liquid Glass behind this element: the page under it ([backdrop]) blurred,
 * made more vivid and bent at the edges like a lens, under the [GlassLook].
 * Without [edges] it is only the pane's inside, for [SettlingGlass].
 */
@Composable
internal fun Modifier.glass(backdrop: Backdrop, shape: Shape, lens: Dp = 16.dp, edges: Boolean = true): Modifier {
    val look = glassLook()
    return drawBackdrop(
        backdrop = backdrop,
        shape = { shape },
        effects = {
            vibrancy()
            blur(GLASS_BLUR.toPx())
            lens(lens.toPx(), lens.toPx() * 2)
        },
        highlight = if (edges) ({ look.highlight }) else null,
        shadow = if (edges) ({ look.shadow }) else null,
        innerShadow = if (edges) ({ look.edgeGlow }) else null,
        onDrawSurface = { drawRect(look.tint) },
    )
}

/** How long glass takes to fade in over its stand-in once it settles. */
private const val GLASS_FADE_MS = 180

/**
 * The background of glass that springs into view. Its rim, glow and shadow
 * are there from the first frame, around a surface in the glass's tint: what
 * shows through real glass costs more than a frame to set up and would
 * stutter the spring, so it fades in inside the same edges once [settled].
 * Put it first in a `Box` around the content.
 */
@Composable
internal fun BoxScope.SettlingGlass(backdrop: Backdrop, shape: Shape, lens: Dp, settled: Boolean) {
    val look = glassLook()
    val glass = remember { Animatable(0f) }
    LaunchedEffect(settled) {
        if (settled) glass.animateTo(1f, tween(GLASS_FADE_MS)) else glass.snapTo(0f)
    }
    Box(
        Modifier.matchParentSize().drawBackdrop(
            backdrop = emptyBackdrop(),
            shape = { shape },
            effects = {},
            highlight = { look.highlight },
            shadow = { look.shadow },
            innerShadow = { look.edgeGlow },
            onDrawSurface = { drawRect(look.tint, alpha = 1f - glass.value) },
        ),
    ) {
        // Not drawn while fully transparent, so the glass is first made once the fade starts.
        if (settled) Box(Modifier.fillMaxSize().graphicsLayer { alpha = glass.value }.glass(backdrop, shape, lens, edges = false))
    }
}
