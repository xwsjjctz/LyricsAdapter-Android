package com.lyricsadapter.spike.lyrics

import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.background
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.toArgb

private val PALETTE = listOf(Color(0xFFFF5FA2), Color(0xFF5F8BFF), Color(0xFF2A1B4D), Color(0xFFFF9E6B))

/** Keeps white lyrics readable on the brightest parts of the gradient. */
private val SCRIM = Color.Black.copy(alpha = 0.3f)

/**
 * Four colour blobs drifting across the screen, blended by inverse squared
 * distance: a cheap stand-in for desktop's mesh-gradient backdrop.
 */
private const val FLOW_SHADER = """
uniform float2 resolution;
uniform float time;
layout(color) uniform float4 color0;
layout(color) uniform float4 color1;
layout(color) uniform float4 color2;
layout(color) uniform float4 color3;

float weight(float2 uv, float2 center) {
    float2 d = uv - center;
    return 1.0 / (0.015 + dot(d, d));
}

half4 main(float2 fragCoord) {
    float aspect = resolution.x / resolution.y;
    float2 uv = float2(fragCoord.x / resolution.y, fragCoord.y / resolution.y);
    float t = time * 0.18;
    float w0 = weight(uv, float2((0.25 + 0.20 * sin(t * 1.1)) * aspect, 0.25 + 0.18 * cos(t * 0.9)));
    float w1 = weight(uv, float2((0.75 + 0.18 * cos(t * 0.8)) * aspect, 0.35 + 0.22 * sin(t * 1.3)));
    float w2 = weight(uv, float2((0.35 + 0.25 * sin(t * 0.6 + 1.7)) * aspect, 0.75 + 0.15 * cos(t * 1.1)));
    float w3 = weight(uv, float2((0.80 + 0.15 * sin(t * 1.4 + 3.1)) * aspect, 0.85 + 0.12 * cos(t * 0.7)));
    float4 color = (color0 * w0 + color1 * w1 + color2 * w2 + color3 * w3) / (w0 + w1 + w2 + w3);
    return half4(color.rgb, 1.0);
}
"""

/** An animated gradient behind the lyrics; a static one below Android 13, which has no AGSL. */
fun Modifier.flowingBackground(timeMs: () -> Long): Modifier =
    if (Build.VERSION.SDK_INT >= 33) shaderBackground(timeMs) else background(Brush.linearGradient(PALETTE))

@RequiresApi(33)
private fun Modifier.shaderBackground(timeMs: () -> Long): Modifier = drawWithCache {
    val shader = RuntimeShader(FLOW_SHADER)
    PALETTE.forEachIndexed { index, color -> shader.setColorUniform("color$index", color.toArgb()) }
    shader.setFloatUniform("resolution", size.width, size.height)
    val brush = ShaderBrush(shader)
    onDrawBehind {
        shader.setFloatUniform("time", timeMs() / 1000f)
        drawRect(brush)
        drawRect(SCRIM)
    }
}
