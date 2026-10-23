package com.lyricsadapter.app.ui.focus

import androidx.activity.compose.LocalActivity
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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

/** How far the header must be pulled down to close focus mode. */
private val DISMISS_DISTANCE = 120.dp

/** The lyrics run on under the controls; this much at the bottom is theirs while they show. */
private val CONTROLS_SPACE = FOCUS_CONTROLS_HEIGHT + FOCUS_CONTROLS_BOTTOM
private const val CONTROLS_FADE_MS = 200

/**
 * The playing track full screen: lyrics over a backdrop made from the cover,
 * with the progress bar and transport controls under them. Pulling the header
 * down or tapping the grabber closes it.
 */
@Composable
fun FocusScreen(
    track: Track,
    playback: PlayerState,
    player: PlayerConnection,
    volume: SystemVolume,
    settings: FocusSettings,
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

    Box(Modifier.fillMaxSize().graphicsLayer { translationY = dismiss }) {
        CoverBackground(track.coverPath, blurRadius = settings.backgroundBlur, modifier = Modifier.fillMaxSize())
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            Header(
                track = track,
                onClose = onClose,
                modifier = Modifier.pointerInput(Unit) {
                    val threshold = DISMISS_DISTANCE.toPx()
                    detectVerticalDragGestures(
                        onDragEnd = {
                            if (dismiss > threshold) onClose()
                            else scope.launch { animate(dismiss, 0f, animationSpec = spring(stiffness = 400f)) { value, _ -> dismiss = value } }
                        },
                        onDragCancel = { dismiss = 0f },
                    ) { _, delta -> dismiss = (dismiss + delta).coerceAtLeast(0f) }
                },
            )
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when {
                    lyrics.isSynced -> LyricsView(
                        lines = lyrics.lines,
                        position = { clock.positionMs },
                        onLineClick = { line -> seek(line.startMs) },
                        modifier = Modifier.fillMaxSize(),
                        controlsHeight = CONTROLS_SPACE,
                        controlsShown = { controlsAlpha },
                        settings = settings,
                    )
                    lyrics.plainText.isNotEmpty() -> BasicText(
                        lyrics.plainText,
                        style = TextStyle(Color.White.copy(alpha = 0.85f), 24.sp, FontWeight.Bold, lineHeight = 36.sp),
                        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                            .padding(start = 28.dp, end = 28.dp, top = 28.dp, bottom = CONTROLS_SPACE),
                    )
                    else -> BasicText(
                        stringResource(R.string.no_lyrics),
                        style = TextStyle(Color.White.copy(alpha = 0.6f), 18.sp),
                        modifier = Modifier.align(Alignment.Center),
                    )
                }
                FocusControls(
                    position = { clock.positionMs },
                    durationMs = playback.durationMs.takeIf { it > 0 } ?: track.durationMs,
                    isPlaying = playback.isPlaying,
                    mode = playback.mode,
                    volume = volumeLevel,
                    // Translation arrives with the plugin host; until a plugin offers it the button stays hidden, as on desktop.
                    translation = TranslationStatus.Unavailable,
                    onSeek = seek,
                    onPrevious = player::previous,
                    onTogglePlay = player::togglePlay,
                    onNext = player::next,
                    onVolumeChange = volume::set,
                    onToggleMute = volume::toggleMute,
                    onCyclePlaybackMode = player::cyclePlaybackMode,
                    onToggleTranslation = {},
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = FOCUS_CONTROLS_BOTTOM)
                        .wakesControls(idle)
                        .graphicsLayer { alpha = controlsAlpha },
                )
            }
        }
    }
}

@Composable
private fun Header(track: Track, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val closeLabel = stringResource(R.string.action_close)
    Column(modifier.fillMaxWidth()) {
        Box(
            Modifier
                .align(Alignment.CenterHorizontally)
                .semantics { contentDescription = closeLabel }
                .clickable(onClick = onClose)
                .padding(horizontal = 24.dp, vertical = 8.dp),
        ) {
            Box(Modifier.size(width = 36.dp, height = 5.dp).background(Color.White.copy(alpha = 0.35f), RoundedCornerShape(50)))
        }
        Row(Modifier.fillMaxWidth().padding(start = 28.dp, end = 28.dp, top = 4.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Cover(track.coverPath, size = 56.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                BasicText(track.title, style = TextStyle(Color.White, 17.sp, FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                BasicText(
                    track.artist ?: stringResource(R.string.unknown_artist),
                    style = TextStyle(Color.White.copy(alpha = 0.6f), 15.sp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
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
