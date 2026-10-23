package com.lyricsadapter.app.ui.focus

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lyricsadapter.app.R
import com.lyricsadapter.app.playback.PlayerConnection
import com.lyricsadapter.app.ui.Cover
import com.lyricsadapter.app.ui.IconButton
import com.lyricsadapter.library.Track

/** The playing track with its synced lyrics over a flowing backdrop, and the main controls. */
@Composable
fun FocusScreen(track: Track, isPlaying: Boolean, player: PlayerConnection, onClose: () -> Unit) {
    val lyrics = remember(track.id) { track.parsedLyrics() }
    val position = remember { mutableLongStateOf(player.positionMs()) }
    LaunchedEffect(track.id, isPlaying) {
        position.longValue = player.positionMs()
        while (isPlaying) {
            withFrameNanos { position.longValue = player.positionMs() }
        }
    }

    Box(Modifier.fillMaxSize().flowingBackground { position.longValue }) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Cover(track.coverPath, size = 52.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    BasicText(track.title, style = TextStyle(Color.White, 17.sp, FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    BasicText(
                        track.artist ?: stringResource(R.string.unknown_artist),
                        style = TextStyle(Color.White.copy(alpha = 0.7f), 14.sp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(R.drawable.ic_close, stringResource(R.string.action_close), tint = Color.White, onClick = onClose)
            }

            Box(Modifier.weight(1f).fillMaxWidth()) {
                when {
                    lyrics.isSynced -> LyricsView(
                        lines = lyrics.lines,
                        position = { position.longValue },
                        onLineClick = { line ->
                            player.seekTo(line.startMs)
                            position.longValue = line.startMs
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                    lyrics.plainText.isNotEmpty() -> BasicText(
                        lyrics.plainText,
                        style = TextStyle(Color.White.copy(alpha = 0.85f), 22.sp, FontWeight.SemiBold, lineHeight = 36.sp),
                        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(28.dp),
                    )
                    else -> BasicText(
                        stringResource(R.string.no_lyrics),
                        style = TextStyle(Color.White.copy(alpha = 0.6f), 18.sp),
                        modifier = Modifier.align(Alignment.Center),
                    )
                }
            }

            Row(
                Modifier.fillMaxWidth().padding(bottom = 24.dp, top = 8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(R.drawable.ic_previous, stringResource(R.string.action_previous), tint = Color.White, size = 32.dp, onClick = player::previous)
                IconButton(
                    icon = if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play,
                    label = stringResource(if (isPlaying) R.string.action_pause else R.string.action_play),
                    tint = Color.White,
                    size = 44.dp,
                    onClick = player::togglePlay,
                )
                IconButton(R.drawable.ic_next, stringResource(R.string.action_next), tint = Color.White, size = 32.dp, onClick = player::next)
            }
        }
    }
}
