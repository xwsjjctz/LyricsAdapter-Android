package com.lyricsadapter.app.ui.focus

import androidx.activity.compose.LocalActivity
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import com.lyricsadapter.app.R
import com.lyricsadapter.app.data.FocusSettings
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lyricsadapter.app.playback.PlayerConnection
import com.lyricsadapter.app.playback.PlayerState
import com.lyricsadapter.app.playback.SystemVolume
import com.lyricsadapter.app.ui.Cover
import com.lyricsadapter.library.Track
import kotlinx.coroutines.launch
import com.lyricsadapter.app.ui.blocksTouches

/** How far the header must be pulled down to close focus mode. */
private val DISMISS_DISTANCE = 120.dp

/** The lyrics run on under the controls; this much at the bottom is theirs while they show. */
private val CONTROLS_SPACE = FOCUS_CONTROLS_HEIGHT + FOCUS_CONTROLS_BOTTOM
private const val CONTROLS_FADE_MS = 200

/** Scrolling the lyrics by hand shows the controls until the lyrics follow the song again. */
private const val SCROLL_HOLD = "scroll"

/** Landscape: the artwork column's share of the width, and its cover's share of the height. */
/*
 * Desktop's narrow landscape layout (`FocusModeLayout.css`, below 1080px):
 * 24px sides, artwork and lyrics columns at 0.88 : 1.12 with a gap of 4% of
 * the width (20-48px), a cover 27% of the width (140-280px), and lyrics in a
 * band 60% of the height, inset 1em on each side.
 */
private val LANDSCAPE_SIDES = 24.dp
private const val LANDSCAPE_ARTWORK_WEIGHT = 0.88f
private const val LANDSCAPE_LYRICS_WEIGHT = 1.12f
private const val LANDSCAPE_GAP_SHARE = 0.04f
private const val LANDSCAPE_COVER_SHARE = 0.27f
private const val LANDSCAPE_LYRICS_HEIGHT = 0.6f

/**
 * The playing track full screen: lyrics over a backdrop made from the cover,
 * with the progress bar and transport controls. In portrait the cover and
 * title sit above the lyrics and the controls under them, as desktop's
 * portrait layout; in landscape the cover and title fill the left side and
 * the lyrics the right, and the controls take the cover's place while they
 * show. Pulling the cover down or tapping the grabber closes it.
 */
@Composable
fun FocusScreen(
    track: Track,
    playback: PlayerState,
    player: PlayerConnection,
    volume: SystemVolume,
    settings: FocusSettings,
    translation: FocusTranslation,
    onClose: () -> Unit,
) {
    val lyrics = remember(track.id) { track.parsedLyrics() }
    val clock = rememberFocusClock(player, playback.isPlaying, track.id)
    val volumeLevel by volume.level.collectAsStateWithLifecycle(initialValue = volume.current())
    LightSystemBars(false)

    val scope = rememberCoroutineScope()
    val idle = remember(scope) { FocusControlsIdle(scope) }
    val controlsAlpha by animateFloatAsState(if (idle.shown) 1f else 0f, tween(CONTROLS_FADE_MS), label = "controls")
    var dismiss by remember { mutableFloatStateOf(0f) }
    // The clock follows a seek on the next frame: the jump is far beyond its easing range.
    val seek: (Long) -> Unit = player::seekTo
    val dismissDrag = Modifier.pointerInput(Unit) {
        val threshold = DISMISS_DISTANCE.toPx()
        detectVerticalDragGestures(
            onDragEnd = {
                if (dismiss > threshold) onClose()
                else scope.launch { animate(dismiss, 0f, animationSpec = spring(stiffness = 400f)) { value, _ -> dismiss = value } }
            },
            onDragCancel = { dismiss = 0f },
        ) { _, delta -> dismiss = (dismiss + delta).coerceAtLeast(0f) }
    }

    val lyricsPane: @Composable (Modifier, Dp, LyricsAnchor, Dp) -> Unit = { modifier, controlsSpace, anchor, linePadding ->
        Box(modifier) {
            when {
                lyrics.isSynced -> LyricsView(
                    lines = lyrics.lines,
                    position = { clock.positionMs },
                    onLineClick = { line -> seek(line.startMs) },
                    modifier = Modifier.fillMaxSize(),
                    controlsHeight = controlsSpace,
                    controlsShown = { controlsAlpha },
                    settings = settings,
                    anchor = anchor,
                    linePadding = linePadding,
                    translations = translation.lines,
                    onScrollingChange = { scrolling -> if (scrolling) idle.hold(SCROLL_HOLD) else idle.release(SCROLL_HOLD) },
                )
                lyrics.plainText.isNotEmpty() -> BasicText(
                    lyrics.plainText,
                    style = TextStyle(Color.White.copy(alpha = 0.85f), 24.sp, FontWeight.Bold, lineHeight = 36.sp),
                    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                        .padding(start = 28.dp, end = 28.dp, top = 28.dp, bottom = controlsSpace),
                )
                else -> BasicText(
                    stringResource(R.string.no_lyrics),
                    style = TextStyle(Color.White.copy(alpha = 0.6f), 18.sp),
                    modifier = Modifier.align(Alignment.Center),
                )
            }
        }
    }
    val controls: @Composable (Modifier) -> Unit = { modifier ->
        FocusControls(
            position = { clock.positionMs },
            durationMs = playback.durationMs.takeIf { it > 0 } ?: track.durationMs,
            isPlaying = playback.isPlaying,
            mode = playback.mode,
            volume = volumeLevel,
            // Hidden until an enabled plugin can translate, as on desktop.
            translation = translation.status,
            onSeek = seek,
            onPrevious = player::previous,
            onTogglePlay = player::togglePlay,
            onNext = player::next,
            onVolumeChange = volume::set,
            onToggleMute = volume::toggleMute,
            onCyclePlaybackMode = player::cyclePlaybackMode,
            onToggleTranslation = translation.toggle,
            modifier = modifier.wakesControls(idle).graphicsLayer { alpha = controlsAlpha },
        )
    }

    BoxWithConstraints(Modifier.fillMaxSize().blocksTouches().graphicsLayer { translationY = dismiss }) {
        if (settings.flowingBackground && flowingBackgroundSupported) {
            FlowingBackground(track.coverPath, playback.isPlaying, Modifier.fillMaxSize())
        } else {
            CoverBackground(track.coverPath, blurRadius = settings.backgroundBlur, modifier = Modifier.fillMaxSize())
        }
        if (maxWidth > maxHeight) {
            val gap = (maxWidth * LANDSCAPE_GAP_SHARE).coerceIn(20.dp, 48.dp)
            val cover = (maxWidth * LANDSCAPE_COVER_SHARE).coerceIn(140.dp, 280.dp)
            Row(
                Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = LANDSCAPE_SIDES),
                horizontalArrangement = Arrangement.spacedBy(gap),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.weight(LANDSCAPE_ARTWORK_WEIGHT).fillMaxHeight()) {
                    LandscapeArtwork(track, cover, Modifier.fillMaxSize().graphicsLayer { alpha = 1f - controlsAlpha }.then(dismissDrag))
                    controls(Modifier.align(Alignment.Center))
                    // Over the artwork, so it never covers the lyrics.
                    Grabber(onClose, Modifier.align(Alignment.TopCenter))
                }
                lyricsPane(
                    Modifier.weight(LANDSCAPE_LYRICS_WEIGHT).fillMaxHeight(LANDSCAPE_LYRICS_HEIGHT),
                    0.dp,
                    LyricsAnchor.Centre,
                    settings.fontSize.dp,
                )
            }
        } else {
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                Grabber(onClose, Modifier.align(Alignment.CenterHorizontally))
                PortraitHeader(track, dismissDrag)
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    lyricsPane(Modifier.fillMaxSize(), CONTROLS_SPACE, LyricsAnchor.Top, LINE_PADDING)
                    controls(Modifier.align(Alignment.BottomCenter).padding(bottom = FOCUS_CONTROLS_BOTTOM))
                }
            }
        }
    }
}

/** The bar at the top that closes focus mode when tapped. */
@Composable
private fun Grabber(onClose: () -> Unit, modifier: Modifier = Modifier) {
    val closeLabel = stringResource(R.string.action_close)
    Box(
        modifier
            .semantics { contentDescription = closeLabel }
            .clickable(onClick = onClose)
            .padding(horizontal = 24.dp, vertical = 8.dp),
    ) {
        Box(Modifier.size(width = 36.dp, height = 5.dp).background(Color.White.copy(alpha = 0.35f), RoundedCornerShape(50)))
    }
}

/** Desktop's portrait header (`FocusModeLayout.css`): an 88px cover and three lines of text. */
@Composable
private fun PortraitHeader(track: Track, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Cover(track.coverPath, size = 88.dp, cornerRadius = 12.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            BasicText(
                track.title,
                style = TextStyle(Color.White, 20.sp, FontWeight.ExtraBold, lineHeight = 24.sp, letterSpacing = (-0.025).em),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            TrackDetails(track, artistSize = 14, centred = false)
        }
    }
}

/** Desktop's landscape artwork column: a large rounded cover with the title, artist and album centred under it. */
@Composable
private fun LandscapeArtwork(track: Track, coverSize: Dp, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        // Short screens keep room for the three lines of text under the cover.
        val cover = minOf(coverSize, maxWidth, maxHeight - 96.dp).coerceAtLeast(96.dp)
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Cover(track.coverPath, size = cover, cornerRadius = 16.dp)
            Spacer(Modifier.height(20.dp))
            BasicText(
                track.title,
                style = TextStyle(Color.White, 24.sp, FontWeight.ExtraBold, lineHeight = 29.sp, letterSpacing = (-0.025).em, textAlign = TextAlign.Center),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // Desktop's landscape title keeps `mb-2`; its artist line is `text-base`, 1.5 line height.
            Spacer(Modifier.height(8.dp))
            TrackDetails(track, artistSize = 16, centred = true, lineHeight = 1.5f)
        }
    }
}

@Composable
private fun TrackDetails(track: Track, artistSize: Int, centred: Boolean, lineHeight: Float = 1.4f) {
    val align = if (centred) TextAlign.Center else TextAlign.Start
    BasicText(
        track.artist ?: stringResource(R.string.unknown_artist),
        style = TextStyle(Color.White.copy(alpha = 0.8f), artistSize.sp, FontWeight.SemiBold, lineHeight = (artistSize * lineHeight).sp, textAlign = align),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
    track.album?.takeIf(String::isNotBlank)?.let { album ->
        BasicText(
            album,
            style = TextStyle(Color.White.copy(alpha = 0.4f), 12.sp, FontWeight.Medium, lineHeight = 16.8.sp, textAlign = align),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/** Sets whether the system bars use dark icons while this is shown, restoring them afterwards. */
@Composable
private fun LightSystemBars(light: Boolean) {
    val window = LocalActivity.current?.window ?: return
    val view = LocalView.current
    DisposableEffect(window, light) {
        val controller = WindowCompat.getInsetsController(window, view)
        val status = controller.isAppearanceLightStatusBars
        val navigation = controller.isAppearanceLightNavigationBars
        controller.isAppearanceLightStatusBars = light
        controller.isAppearanceLightNavigationBars = light
        onDispose {
            controller.isAppearanceLightStatusBars = status
            controller.isAppearanceLightNavigationBars = navigation
        }
    }
}
