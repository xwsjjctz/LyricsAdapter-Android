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
import com.lyricsadapter.app.ui.glass.glass
import com.lyricsadapter.library.Track
import kotlin.math.abs

/** The height of the bottom row: the page button and the mini player beside it. */
val BOTTOM_BAR_HEIGHT = 60.dp

/** The gap between the page button and the mini player, and around the row. */
val BOTTOM_BAR_MARGIN = 8.dp

/** How much room the bottom row takes at the bottom of a page, over the navigation bar. */
val BOTTOM_BARS_SPACE = BOTTOM_BAR_HEIGHT + BOTTOM_BAR_MARGIN * 2

private val COVER_SIZE = 44.dp

/** A horizontal swipe this long changes the track. */
private val SWIPE_DISTANCE = 56.dp

/**
 * Apple Music's mini player: a Liquid Glass capsule with the artwork, the
 * title over the artist, play or pause and next. Tapping it opens focus
 * mode; swiping it sideways skips to the next or previous track, as iOS 26.1
 * does. It is always there: with nothing loaded it shows the app's icon and
 * name, play starts the list on show and next is off, as desktop's bar stays
 * with "no track selected".
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
            .height(BOTTOM_BAR_HEIGHT)
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
            .padding(start = 12.dp, end = 4.dp),
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
                style = TextStyle(color = colors.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
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
        IconButton(
            icon = if (isPlaying) R.drawable.ui_pause_filled else R.drawable.ui_play_arrow_filled,
            label = stringResource(if (isPlaying) R.string.action_pause else R.string.action_play),
            tint = colors.text,
            size = 28.dp,
            onClick = onTogglePlay,
        )
        if (loaded) {
            IconButton(R.drawable.ui_fast_forward_filled, stringResource(R.string.action_next), tint = colors.text, size = 28.dp, onClick = onNext)
        } else {
            val label = stringResource(R.string.action_next)
            Box(
                Modifier.size(48.dp).semantics {
                    contentDescription = label
                    disabled()
                },
                contentAlignment = Alignment.Center,
            ) {
                Icon(R.drawable.ui_fast_forward_filled, colors.text.copy(alpha = 0.3f), size = 28.dp)
            }
        }
    }
}
