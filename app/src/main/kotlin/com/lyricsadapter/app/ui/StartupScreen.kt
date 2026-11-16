package com.lyricsadapter.app.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.lyricsadapter.app.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/*
 * Desktop's startup screen (`index.html`, `src/styles/startup.css` and
 * `src/services/startupScreen.ts` in the desktop repository): the logo on its
 * own colours, blurred, over the app until what it restores has settled.
 * It shows for at least 350 ms, then lifts over 560 ms: the screen fades
 * after the first eighth while the logo grows to 1.85 times and fades.
 */
private const val MIN_VISIBLE_MS = 350L
private const val REVEAL_MS = 560
private const val REVEAL_HOLD = 0.12f
private const val ICON_GROWTH = 0.85f
private val SCREEN_EASING = CubicBezierEasing(0.4f, 0f, 0.2f, 1f)
private val ICON_EASING = CubicBezierEasing(0.2f, 0.7f, 0.3f, 1f)

/** Something that never settles (a music source that does not answer) is not waited for longer than this. */
private const val MAX_WAIT_MS = 1_500L

/** The artwork comes in over the launch window's plain colour instead of replacing it at once. */
private const val ARTWORK_FADE_MS = 160

/** The picture of the logo is this wide, the logo its middle third: the size Android 12's launch window draws it at. */
private val ICON_PICTURE_SIZE = 288.dp

/**
 * Covers the app from its first frame, where the system's launch window
 * leaves off with the same logo on the same colour, so loading the library
 * and restoring what played last are not seen. Once [ready] (or after
 * [MAX_WAIT_MS]) it lifts and calls [onDone]. Nothing under it can be touched.
 */
@Composable
fun StartupScreen(ready: () -> Boolean, onDone: () -> Unit) {
    val isReady by rememberUpdatedState(ready)
    val done by rememberUpdatedState(onDone)
    val artwork = remember { Animatable(0f) }
    val reveal = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        launch { artwork.animateTo(1f, tween(ARTWORK_FADE_MS)) }
        val shownAt = withFrameNanos { it }
        withTimeoutOrNull(MAX_WAIT_MS) { snapshotFlow { isReady() }.first { it } }
        val shownMs = (withFrameNanos { it } - shownAt) / 1_000_000
        delay((MIN_VISIBLE_MS - shownMs).coerceAtLeast(0))
        // The settled page is drawn before it shows, as desktop waits two frames for its layout.
        repeat(2) { withFrameNanos {} }
        reveal.animateTo(1f, tween(REVEAL_MS, easing = LinearEasing))
        done()
    }
    val name = stringResource(R.string.app_name)
    Box(
        Modifier
            .fillMaxSize()
            .blocksTouches()
            .semantics { contentDescription = name }
            .graphicsLayer { alpha = 1f - SCREEN_EASING.transform(((reveal.value - REVEAL_HOLD) / (1f - REVEAL_HOLD)).coerceIn(0f, 1f)) }
            .background(colorResource(R.color.startup_background)),
    ) {
        Image(
            painterResource(R.drawable.startup_background),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize().graphicsLayer { alpha = artwork.value },
        )
        Image(
            painterResource(R.drawable.startup_icon),
            contentDescription = null,
            modifier = Modifier.align(Alignment.Center).size(ICON_PICTURE_SIZE).graphicsLayer {
                val progress = ICON_EASING.transform(reveal.value)
                scaleX = 1f + ICON_GROWTH * progress
                scaleY = scaleX
                alpha = 1f - progress
            },
        )
    }
}
