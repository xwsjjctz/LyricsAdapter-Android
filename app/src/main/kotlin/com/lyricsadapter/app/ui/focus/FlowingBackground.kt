package com.lyricsadapter.app.ui.focus

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.platform.LocalContext
import com.lyricsadapter.app.R
import com.lyricsadapter.app.playback.AudioPulse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The cover is reduced to this many pixels a side: a few broad fields of its colours, not its picture. */
private const val COLOURS = 4

/** One full drift of the pattern takes about this long; desktop's barely moves within a second or two. */
private const val DRIFT_SECONDS = 90f

/** Desktop's flowing background is muted enough for white lyrics on top. */
private const val BRIGHTNESS = 0.72f

/** How far a beat zooms the pattern in. */
private const val BEAT_ZOOM = 0.12f

/**
 * The flowing pattern: the cover's colours, sampled from a tiny copy, drift
 * through two layers of slow waves and a slow turn; [zoom] pushes the
 * pattern in on a beat.
 */
private const val FLOW = """
uniform shader colours;
uniform float2 size;
uniform float colourSize;
uniform float phase;
uniform float zoom;
uniform float brightness;

float2 turn(float2 p, float angle) {
    float c = cos(angle);
    float s = sin(angle);
    return float2(p.x * c - p.y * s, p.x * s + p.y * c);
}

half4 main(float2 coord) {
    float aspect = size.x / size.y;
    float2 p = (coord / size - 0.5) * float2(aspect, 1.0) / zoom;
    p = turn(p, phase);
    p += 0.30 * float2(sin(p.y * 1.3 + phase * 1.6), cos(p.x * 1.5 - phase * 1.3));
    p += 0.12 * float2(sin(p.y * 2.6 - phase * 1.1 + 1.3), cos(p.x * 2.3 + phase * 0.9 + 0.7));
    float2 uv = clamp(p / float2(aspect, 1.0) * 0.7 + 0.5, 0.0, 1.0);
    half4 colour = colours.eval(0.5 + uv * (colourSize - 1.0));
    // Averaging a cover down to a few pixels greys it; lift the colour back out.
    float luma = dot(colour.rgb, half3(0.2126, 0.7152, 0.0722));
    half3 rich = clamp(mix(half3(luma), colour.rgb, 1.5), 0.0, 1.0);
    return half4(rich * brightness, 1.0);
}
"""

/** The same shade desktop lays over either backdrop, so lyrics stay readable. */
private val SHADE = Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.3f), 0.5f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.5f))

/** Whether the flowing background can run here (runtime shaders came with Android 13). */
val flowingBackgroundSupported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

/**
 * Desktop's 动态流光背景: a flowing gradient in the cover's colours that
 * pulses with the beat, drawn by a shader written for Android (the desktop
 * uses AMLL's renderer). The beat comes from [AudioPulse]: a jump in level
 * above its recent average springs the pattern in a little.
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
@Composable
fun FlowingBackground(coverPath: String?, playing: Boolean, modifier: Modifier = Modifier) {
    val resources = LocalContext.current.resources
    val colours by produceState<Bitmap?>(null, coverPath) { value = withContext(Dispatchers.IO) { colourSample(resources, coverPath) } }
    val shader = remember { RuntimeShader(FLOW) }
    var phase by remember { mutableFloatStateOf(0f) }
    var beat by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(playing) {
        var last = 0L
        var average = 0f
        var velocity = 0f
        while (true) {
            withFrameNanos { now ->
                val seconds = if (last == 0L) 0f else ((now - last) / 1e9f).coerceAtMost(0.1f)
                last = now
                if (!playing && beat < 0.001f) return@withFrameNanos
                phase += seconds * 2f * Math.PI.toFloat() / DRIFT_SECONDS
                val level = if (playing) AudioPulse.level else 0f
                average += (level - average) * (seconds * 2f).coerceAtMost(1f)
                val kick = ((level - average * 1.15f) * 4f).coerceIn(0f, 1f)
                // A soft spring toward the kick, so the zoom swells and settles instead of jumping.
                velocity += (kick - beat) * 90f * seconds - velocity * 12f * seconds
                beat = (beat + velocity * seconds).coerceIn(0f, 1f)
            }
            if (!playing && beat < 0.001f) break
        }
    }

    Box(
        modifier.fillMaxSize().drawBehind {
            val bitmap = colours ?: return@drawBehind
            shader.setInputShader("colours", BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply { filterMode = BitmapShader.FILTER_MODE_LINEAR })
            shader.setFloatUniform("size", size.width, size.height)
            shader.setFloatUniform("colourSize", COLOURS.toFloat())
            shader.setFloatUniform("phase", phase)
            shader.setFloatUniform("zoom", 1f + BEAT_ZOOM * beat)
            shader.setFloatUniform("brightness", BRIGHTNESS)
            drawRect(ShaderBrush(shader))
            drawRect(SHADE)
        },
    )
}

/** The cover (or the app icon when there is none) shrunk to a few pixels a side, smoothing its colours together. */
private fun colourSample(resources: Resources, coverPath: String?): Bitmap? {
    val decoded = coverPath?.let(::decodeCover) ?: BitmapFactory.decodeResource(resources, R.mipmap.ic_launcher) ?: return null
    return Bitmap.createScaledBitmap(decoded, COLOURS, COLOURS, true)
}
