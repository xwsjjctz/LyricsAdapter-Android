package com.lyricsadapter.app.ui.focus

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import com.lyricsadapter.app.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.math.sqrt

/*
 * Desktop's classic FocusMode backdrop (`FocusCanvasBackdrop.tsx`, `FocusBackdrop.tsx`
 * in the desktop repository): the cover cropped to fill the screen, blurred
 * by the background-blur setting (80 px by default) with clamped edges, saturated 1.5x and
 * dimmed to 0.55, under a black gradient for readability.
 */
private const val SATURATION = 1.5f
private const val BRIGHTNESS = 0.55f
private const val CROSSFADE_MS = 1000

/** The blurred cover is computed at one pixel per this many dp; the blur hides the low resolution. */
private const val DP_PER_PIXEL = 4f

/** Three box blurs in a row come close to a Gaussian. */
private const val BOX_PASSES = 3

private val READABILITY = Brush.verticalGradient(
    0f to Color.Black.copy(alpha = 0.3f),
    0.5f to Color.Transparent,
    1f to Color.Black.copy(alpha = 0.5f),
)

private val PLACEHOLDER = Color(0xFF1C1C1E)

/** The track's cover, blurred and dimmed behind the lyrics; the app icon stands in when there is none. */
@Composable
fun CoverBackground(coverPath: String?, blurRadius: Int, modifier: Modifier = Modifier) {
    val resources = LocalContext.current.resources
    val density = LocalDensity.current.density
    var size by remember { mutableStateOf(IntSize.Zero) }
    val backdrop by produceState<ImageBitmap?>(null, coverPath, size, blurRadius) {
        if (size.width > 0 && size.height > 0) value = prepareBackdrop(resources, coverPath, size, density, blurRadius.toFloat())
    }
    Box(modifier.onSizeChanged { size = it }.background(PLACEHOLDER)) {
        Crossfade(backdrop, animationSpec = tween(CROSSFADE_MS), label = "backdrop") { bitmap ->
            if (bitmap != null) Box(Modifier.fillMaxSize().stretched(bitmap))
        }
        Box(Modifier.fillMaxSize().background(READABILITY))
    }
}

private fun Modifier.stretched(bitmap: ImageBitmap): Modifier = drawWithCache {
    val target = IntSize(size.width.roundToInt(), size.height.roundToInt())
    onDrawBehind { drawImage(bitmap, dstSize = target, filterQuality = FilterQuality.Low) }
}

private suspend fun prepareBackdrop(resources: Resources, coverPath: String?, screen: IntSize, density: Float, blurRadius: Float): ImageBitmap? =
    withContext(Dispatchers.Default) {
        val cover = coverPath?.let(::decodeCover) ?: BitmapFactory.decodeResource(resources, R.mipmap.ic_launcher)
        cover?.let { source ->
            val pixelDp = DP_PER_PIXEL * density
            val width = ceil(screen.width / pixelDp).toInt().coerceAtLeast(1)
            val height = ceil(screen.height / pixelDp).toInt().coerceAtLeast(1)
            val cropped = cropToFill(source, width, height)
            source.recycle()
            val blurred = gaussianBlur(cropped, width, height, sigma = blurRadius / DP_PER_PIXEL)
            Bitmap.createBitmap(blurred.map(::tone).toIntArray(), width, height, Bitmap.Config.ARGB_8888).asImageBitmap()
        }
    }

/** A file cover, or an online one fetched over the network; null when it cannot be read. */
private fun decodeCover(path: String): Bitmap? {
    if (!path.startsWith("http")) return BitmapFactory.decodeFile(path)
    return try {
        java.net.URL(path).openConnection().apply {
            connectTimeout = 10_000
            readTimeout = 10_000
        }.getInputStream().use(BitmapFactory::decodeStream)
    } catch (_: java.io.IOException) {
        null
    }
}

/** The centre of [source] scaled to cover [width] x [height], as CSS `object-fit: cover`, as pixels. */
private fun cropToFill(source: Bitmap, width: Int, height: Int): IntArray {
    val targetRatio = width.toFloat() / height
    val sourceRatio = source.width.toFloat() / source.height
    val crop = if (sourceRatio > targetRatio) {
        val cropWidth = (source.height * targetRatio).roundToInt()
        Rect((source.width - cropWidth) / 2, 0, (source.width + cropWidth) / 2, source.height)
    } else {
        val cropHeight = (source.width / targetRatio).roundToInt()
        Rect(0, (source.height - cropHeight) / 2, source.width, (source.height + cropHeight) / 2)
    }
    val target = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    Canvas(target).drawBitmap(source, crop, Rect(0, 0, width, height), Paint(Paint.FILTER_BITMAP_FLAG))
    val pixels = IntArray(width * height)
    target.getPixels(pixels, 0, width, 0, 0, width, height)
    target.recycle()
    return pixels
}

/** Repeated box blurs with edges clamped, matching desktop's edge-extended canvas blur. */
private fun gaussianBlur(pixels: IntArray, width: Int, height: Int, sigma: Float): IntArray {
    val boxWidth = sqrt(12 * sigma * sigma / BOX_PASSES + 1)
    val radius = ((boxWidth - 1) / 2).roundToInt().coerceAtLeast(1)
    var current = pixels
    repeat(BOX_PASSES) {
        // Each pass blurs along rows and writes columns, so two passes cover both directions.
        current = boxBlurTransposed(boxBlurTransposed(current, width, height, radius), height, width, radius)
    }
    return current
}

/** Blurs each row of a [width] x [height] image and returns the result transposed. */
private fun boxBlurTransposed(source: IntArray, width: Int, height: Int, radius: Int): IntArray {
    val result = IntArray(source.size)
    val span = 2 * radius + 1
    for (y in 0 until height) {
        val row = y * width
        var red = 0
        var green = 0
        var blue = 0
        for (offset in -radius..radius) {
            val pixel = source[row + offset.coerceIn(0, width - 1)]
            red += pixel shr 16 and 0xFF
            green += pixel shr 8 and 0xFF
            blue += pixel and 0xFF
        }
        for (x in 0 until width) {
            result[x * height + y] = (0xFF shl 24) or (red / span shl 16) or (green / span shl 8) or (blue / span)
            val entering = source[row + (x + radius + 1).coerceAtMost(width - 1)]
            val leaving = source[row + (x - radius).coerceAtLeast(0)]
            red += (entering shr 16 and 0xFF) - (leaving shr 16 and 0xFF)
            green += (entering shr 8 and 0xFF) - (leaving shr 8 and 0xFF)
            blue += (entering and 0xFF) - (leaving and 0xFF)
        }
    }
    return result
}

/** CSS `saturate(1.5)` followed by `brightness(0.55)`. */
private fun tone(pixel: Int): Int {
    val red = (pixel shr 16 and 0xFF).toFloat()
    val green = (pixel shr 8 and 0xFF).toFloat()
    val blue = (pixel and 0xFF).toFloat()
    val s = SATURATION
    val r = (0.213f + 0.787f * s) * red + (0.715f - 0.715f * s) * green + (0.072f - 0.072f * s) * blue
    val g = (0.213f - 0.213f * s) * red + (0.715f + 0.285f * s) * green + (0.072f - 0.072f * s) * blue
    val b = (0.213f - 0.213f * s) * red + (0.715f - 0.715f * s) * green + (0.072f + 0.928f * s) * blue
    // Each CSS filter step clamps to the colour range before the next one applies.
    fun channel(value: Float) = (value.coerceIn(0f, 255f) * BRIGHTNESS).roundToInt()
    return (0xFF shl 24) or (channel(r) shl 16) or (channel(g) shl 8) or channel(b)
}
