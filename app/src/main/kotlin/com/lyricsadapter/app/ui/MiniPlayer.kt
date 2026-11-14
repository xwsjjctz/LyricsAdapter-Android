package com.lyricsadapter.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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

/** The gap between the mini player and the tab bar, and around them. */
val BOTTOM_BAR_MARGIN = 8.dp

/** How much room the bottom bars take at the bottom of a page, over the navigation bar: stacked, or side by side in landscape. */
fun bottomBarsSpace(stacked: Boolean) =
    GLASS_BAR_HEIGHT + BOTTOM_BAR_MARGIN * 2 + if (stacked) MINI_PLAYER_HEIGHT + BOTTOM_BAR_MARGIN else 0.dp

private val COVER_SIZE = 32.dp

/** A horizontal swipe this long changes the track. */
private val SWIPE_DISTANCE = 56.dp

/**
 * Apple Music's mini player: a Liquid Glass capsule over the tab bar with the
 * artwork, the title over the artist, and previous, play or pause and next.
 * Tapping it opens focus mode; swiping it sideways skips to the next or
 * previous track, as iOS 26.1 does. It is always there: with nothing loaded
 * it shows the app's icon and name, play starts the list on show and the
 * skip buttons are off, as desktop's bar stays with "no track selected".
 */
@Composable
fun MiniPlayer(
    track: Track?,
    isPlaying: Boolean,
    backdrop: Backdrop,
    onTogglePlay: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalAppColors.current
    val next by rememberUpdatedState(onNext)
    val previous by rememberUpdatedState(onPrevious)
    val loaded = track != null
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(MINI_PLAYER_HEIGHT)
            .glass(backdrop, Capsule(), lens = 12.dp)
            .clip(Capsule())
            .then(if (loaded) Modifier.clickable(onClick = onOpen) else Modifier)
            .pointerInput(loaded) {
                if (!loaded) return@pointerInput
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
            // The artwork keeps clear of the capsule's round end.
            .padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (track != null) {
            Cover(track.coverPath, size = COVER_SIZE)
        } else {
            Image(
                painterResource(R.mipmap.ic_launcher),
                contentDescription = null,
                modifier = Modifier.size(COVER_SIZE).clip(RoundedCornerShape(10.dp)),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            BasicText(
                track?.title ?: stringResource(R.string.app_name),
                style = TextStyle(color = colors.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            BasicText(
                if (track == null) stringResource(R.string.mini_player_idle) else track.artist ?: stringResource(R.string.unknown_artist),
                style = TextStyle(color = colors.secondaryText, fontSize = 12.sp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        TransportButton(R.drawable.ui_fast_rewind_filled, stringResource(R.string.action_previous), enabled = loaded, onClick = onPrevious)
        TransportButton(
            icon = if (isPlaying) R.drawable.ui_pause_filled else R.drawable.ui_play_arrow_filled,
            label = stringResource(if (isPlaying) R.string.action_pause else R.string.action_play),
            enabled = true,
            onClick = onTogglePlay,
        )
        TransportButton(R.drawable.ui_fast_forward_filled, stringResource(R.string.action_next), enabled = loaded, onClick = onNext)
    }
}

private val TRANSPORT_ICON_SIZE = 24.dp

/** One of the mini player's controls; dimmed and inert while there is nothing to skip from. */
@Composable
private fun TransportButton(icon: Int, label: String, enabled: Boolean, onClick: () -> Unit) {
    val colors = LocalAppColors.current
    if (enabled) {
        IconButton(icon, label, tint = colors.text, size = TRANSPORT_ICON_SIZE, onClick = onClick)
    } else {
        Box(
            Modifier.size(TRANSPORT_ICON_SIZE + 20.dp).semantics {
                contentDescription = label
                disabled()
            },
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, colors.text.copy(alpha = 0.3f), size = TRANSPORT_ICON_SIZE)
        }
    }
}
