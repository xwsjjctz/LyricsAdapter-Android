package com.lyricsadapter.app.ui.glass

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawPlainBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.runtimeShaderEffect
import com.kyant.backdrop.isRuntimeShaderSupported

/** How much of the page's colour lies over the blurred list just above the fade, where the veil is thinnest. */
private const val EDGE_TINT = 0.86f

private val EDGE_BLUR = 14.dp

private const val EDGE_SHADER = """
uniform shader content;
uniform float2 size;
uniform float fadeStart;
layout(color) uniform half4 tint;
uniform float tintIntensity;

half4 main(float2 coord) {
    float mask = 1.0 - smoothstep(fadeStart, size.y, coord.y);
    // The veil thickens upwards: the page's own colour at the top, tintIntensity of it where the fade begins.
    float veil = mix(1.0, tintIntensity, smoothstep(0.0, fadeStart, coord.y));
    return mix(content.eval(coord), tint, veil) * mask;
}"""

/**
 * iOS 26's scroll edge under a top bar, in place of a filled bar with a hard
 * line: the list under it ([backdrop]) is blurred and veiled in the page's
 * [color]. The veil is the plain colour at the top and thins downwards, and
 * over the last [fade] of this element the whole edge eases out to nothing,
 * so rows soften, pale and sink into the bar as they scroll up under it.
 * Where shaders are missing (before Android 13) the colour alone fades out.
 */
internal fun Modifier.scrollEdge(backdrop: Backdrop, color: Color, fade: Dp): Modifier {
    if (!isRuntimeShaderSupported()) {
        return drawBehind {
            if (size.height <= 0f) return@drawBehind
            val solid = ((size.height - fade.toPx()) / size.height).coerceIn(0f, 1f)
            drawRect(Brush.verticalGradient(0f to color, solid to color, 1f to color.copy(alpha = 0f)))
        }
    }
    return drawPlainBackdrop(
        backdrop = backdrop,
        shape = { RectangleShape },
        effects = {
            blur(EDGE_BLUR.toPx())
            runtimeShaderEffect("ScrollEdge", EDGE_SHADER, "content") {
                setFloatUniform("size", size.width, size.height)
                setFloatUniform("fadeStart", (size.height - fade.toPx()).coerceAtLeast(0f))
                setColorUniform("tint", color)
                setFloatUniform("tintIntensity", EDGE_TINT)
            }
        },
    )
}
