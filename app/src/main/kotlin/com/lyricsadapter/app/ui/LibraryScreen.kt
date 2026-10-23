package com.lyricsadapter.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.lyricsadapter.app.R
import com.lyricsadapter.library.Track
import java.io.File

/**
 * The local library as one list, styled after desktop's Apple Music list:
 * cover, title with "artist — album" under it, duration; the playing row is
 * filled with the accent colour. Long-press menus come with editing.
 */
@Composable
fun LibraryScreen(
    tracks: List<Track>,
    currentId: String?,
    contentPadding: PaddingValues,
    onPlay: (index: Int) -> Unit,
    onImport: () -> Unit,
) {
    val colors = LocalAppColors.current
    if (tracks.isEmpty()) {
        EmptyLibrary(onImport, Modifier.fillMaxSize().background(colors.background).padding(contentPadding))
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize().background(colors.background),
        contentPadding = contentPadding,
    ) {
        item { LibraryHeader(tracks, onImport) }
        itemsIndexed(tracks, key = { _, track -> track.id }) { index, track ->
            TrackRow(track, playing = track.id == currentId, onClick = { onPlay(index) })
        }
    }
}

@Composable
private fun LibraryHeader(tracks: List<Track>, onImport: () -> Unit) {
    val colors = LocalAppColors.current
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 12.dp, bottom = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            BasicText(stringResource(R.string.library_title), style = TextStyle(color = colors.text, fontSize = 32.sp, fontWeight = FontWeight.Bold))
            BasicText(
                stringResource(R.string.library_summary, tracks.size, formatTotalDuration(tracks.sumOf { it.durationMs })),
                style = TextStyle(color = colors.secondaryText, fontSize = 14.sp),
            )
        }
        IconButton(R.drawable.ic_add, stringResource(R.string.import_files), tint = colors.accent, onClick = onImport)
    }
}

@Composable
private fun TrackRow(track: Track, playing: Boolean, onClick: () -> Unit) {
    val colors = LocalAppColors.current
    val textColor = if (playing) colors.onAccent else colors.text
    val secondaryColor = if (playing) colors.onAccent.copy(alpha = 0.8f) else colors.secondaryText
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (playing) colors.accent else colors.background)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Cover(track.coverPath, size = 44.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            BasicText(track.title, style = TextStyle(color = textColor, fontSize = 16.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
            BasicText(
                listOfNotNull(track.artist ?: stringResource(R.string.unknown_artist), track.album).joinToString(" — "),
                style = TextStyle(color = secondaryColor, fontSize = 13.sp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(12.dp))
        BasicText(formatDuration(track.durationMs), style = TextStyle(color = secondaryColor, fontSize = 13.sp))
    }
}

@Composable
private fun EmptyLibrary(onImport: () -> Unit, modifier: Modifier) {
    val colors = LocalAppColors.current
    Column(modifier, verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        BasicText(stringResource(R.string.library_empty), style = TextStyle(color = colors.secondaryText, fontSize = 15.sp))
        Spacer(Modifier.height(16.dp))
        BasicText(
            stringResource(R.string.import_files),
            style = TextStyle(color = colors.onAccent, fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
            modifier = Modifier
                .clip(RoundedCornerShape(20.dp))
                .background(colors.accent)
                .clickable(onClick = onImport)
                .padding(horizontal = 24.dp, vertical = 10.dp),
        )
    }
}

@Composable
fun Cover(path: String?, size: Dp, modifier: Modifier = Modifier) {
    val colors = LocalAppColors.current
    Box(modifier.size(size).clip(RoundedCornerShape(6.dp)).background(colors.surface)) {
        if (path != null) {
            AsyncImage(model = File(path), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
    }
}

/** An icon control: named for accessibility, no tooltip. */
@Composable
fun IconButton(icon: Int, label: String, tint: androidx.compose.ui.graphics.Color, size: Dp = 24.dp, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(size + 20.dp)
            .clip(RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.foundation.Image(
            painter = painterResource(icon),
            contentDescription = null,
            colorFilter = ColorFilter.tint(tint),
            modifier = Modifier.size(size),
        )
    }
}
