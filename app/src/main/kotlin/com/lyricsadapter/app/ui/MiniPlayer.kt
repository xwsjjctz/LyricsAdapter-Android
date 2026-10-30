package com.lyricsadapter.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.Backdrop
import com.kyant.shapes.Capsule
import com.lyricsadapter.app.R
import com.lyricsadapter.app.ui.glass.GLASS_BAR_HEIGHT
import com.lyricsadapter.app.ui.glass.glass
import com.lyricsadapter.library.Track
import kotlin.math.abs

/** The mini player's height, as iOS 26 Apple Music's over its tab bar. */
val MINI_PLAYER_HEIGHT = 48.dp

/** A horizontal swipe this long changes the track. */
private val SWIPE_DISTANCE = 56.dp

/**
 * Apple Music's mini player: a Liquid Glass capsule over the tab bar with the
 * artwork, the title over the artist, play or pause and next. Tapping it
 * opens focus mode; swiping it sideways skips to the next or previous track,
 * as iOS 26.1 does. In the collapsed bar it sits beside the tab button,
 * [height] tall.
 */
@Composable
fun MiniPlayer(
    track: Track,
    isPlaying: Boolean,
    backdrop: Backdrop,
    onTogglePlay: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = MINI_PLAYER_HEIGHT,
) {
    val colors = LocalAppColors.current
    val next by rememberUpdatedState(onNext)
    val previous by rememberUpdatedState(onPrevious)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .glass(backdrop, Capsule(), lens = 12.dp)
            .clip(Capsule())
            .clickable(onClick = onOpen)
            .pointerInput(Unit) {
                var travelled = 0f
                detectHorizontalDragGestures(
                    onDragStart = { travelled = 0f },
                    onDragEnd = {
                        if (abs(travelled) >= SWIPE_DISTANCE.toPx()) if (travelled < 0) next() else previous()
                    },
                ) { change, amount ->
                    change.consume()
                    travelled += amount
                }
            }
            .padding(start = 8.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Cover(track.coverPath, size = 32.dp)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            BasicText(
                track.title,
                style = TextStyle(color = colors.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            BasicText(
                track.artist ?: stringResource(R.string.unknown_artist),
                style = TextStyle(color = colors.secondaryText, fontSize = 12.sp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(
            icon = if (isPlaying) R.drawable.ui_pause_filled else R.drawable.ui_play_arrow_filled,
            label = stringResource(if (isPlaying) R.string.action_pause else R.string.action_play),
            tint = colors.text,
            size = 26.dp,
            onClick = onTogglePlay,
        )
        IconButton(R.drawable.ui_fast_forward_filled, stringResource(R.string.action_next), tint = colors.text, size = 26.dp, onClick = onNext)
    }
}

/** How much room the floating bars take at the bottom of a page, over the navigation bar. */
fun bottomBarsSpace(hasMiniPlayer: Boolean) =
    GLASS_BAR_HEIGHT + BOTTOM_BAR_MARGIN * 2 + if (hasMiniPlayer) MINI_PLAYER_HEIGHT + BOTTOM_BAR_MARGIN else 0.dp

/** The gap between the floating bars, and around them. */
val BOTTOM_BAR_MARGIN = 8.dp
