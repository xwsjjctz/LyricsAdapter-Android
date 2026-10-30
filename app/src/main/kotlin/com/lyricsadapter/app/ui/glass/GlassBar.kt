package com.lyricsadapter.app.ui.glass

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.lyricsadapter.app.ui.LocalAppColors
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.graphicsLayer

/** The height of the round glass buttons, as the bottom row's. */
internal val GLASS_BAR_HEIGHT = 60.dp

/**
 * The tint over the glass: light glass on light pages, dark glass on dark
 * ones. Dense enough that the list under the bars does not read
 * through them, as on iOS.
 */
@Composable
internal fun glassTint(): Color {
    val colors = LocalAppColors.current
    return if (colors.isDark) Color(0xFF1C1C1E).copy(alpha = 0.65f) else Color(0xFFF9F9F9).copy(alpha = 0.7f)
}

/** How much the glass blurs what is under it. */
internal val GLASS_BLUR = 16.dp

/**
 * Liquid Glass behind this element: the page under it ([backdrop]) blurred,
 * made more vivid and bent at the edges like a lens, under a light tint.
 */
@Composable
internal fun Modifier.glass(backdrop: Backdrop, shape: Shape, lens: Dp = 16.dp): Modifier {
    val tint = glassTint()
    return drawBackdrop(
        backdrop = backdrop,
        shape = { shape },
        effects = {
            vibrancy()
            blur(GLASS_BLUR.toPx())
            lens(lens.toPx(), lens.toPx() * 2)
        },
        onDrawSurface = { drawRect(tint) },
    )
}

/** How long glass takes to fade in over its stand-in once it settles. */
private const val GLASS_FADE_MS = 180

/**
 * The background of glass that springs into view: while it moves, a surface
 * in the glass's tint stands in, since creating the real glass costs more
 * than a frame and would stutter the spring; once [settled], the glass fades
 * in over it. Put it first in a `Box` around the content.
 */
@Composable
internal fun BoxScope.SettlingGlass(backdrop: Backdrop, shape: Shape, lens: Dp, settled: Boolean) {
    val tint = glassTint()
    val glass = remember { Animatable(0f) }
    LaunchedEffect(settled) {
        if (settled) glass.animateTo(1f, tween(GLASS_FADE_MS)) else glass.snapTo(0f)
    }
    Box(Modifier.matchParentSize().graphicsLayer { alpha = 1f - glass.value }.background(tint, shape))
    // Not drawn while fully transparent, so the glass is first made once the fade starts.
    if (settled) Box(Modifier.matchParentSize().graphicsLayer { alpha = glass.value }.glass(backdrop, shape, lens))
}

/** A round glass button, the bottom row's height unless [size] says otherwise, as iOS 26's collapsed tab bar. */
@Composable
internal fun GlassRoundButton(
    backdrop: Backdrop,
    /** Null when the [modifier] handles the gestures itself. */
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    size: Dp = GLASS_BAR_HEIGHT,
    content: @Composable () -> Unit,
) {
    Box(
        modifier
            .size(size)
            .glass(backdrop, CircleShape, lens = 12.dp)
            .clip(CircleShape)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}
