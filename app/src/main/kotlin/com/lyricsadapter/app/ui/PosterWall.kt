package com.lyricsadapter.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.lyricsadapter.app.R
import com.lyricsadapter.library.Track
import com.lyricsadapter.library.wall.WallLayout
import com.lyricsadapter.library.wall.WallSlot
import java.io.File
import kotlin.math.roundToInt

/** One 8-row block of the wall: the songs it holds and their slots, relative to the block. */
private class WallBlock(val index: Int, val rows: Int, val tiles: List<Pair<Int, WallSlot>>)

/** The list item that holds song [trackIndex], for locating the current song. */
fun wallBlockOf(trackIndex: Int, count: Int, columns: Int): Int =
    WallLayout.slots(count, columns).getOrNull(trackIndex)?.let { it.y / WallLayout.blockRows(columns) } ?: 0

/**
 * Desktop's poster wall on a phone: covers edge to edge in desktop's narrow
 * layout, each with its title over a dark gradient (large covers get a bigger
 * two-line title; small ones show the artist only while playing). The
 * playing cover is ringed in the accent colour. Each block of the layout is
 * one lazy item, so long libraries scroll smoothly.
 */
@Composable
fun PosterWall(
    tracks: List<Track>,
    currentId: String?,
    selection: Set<String>?,
    downloads: Map<String, Float>,
    columns: Int,
    listState: LazyListState,
    contentPadding: PaddingValues,
    onClick: (index: Int) -> Unit,
    onLongPress: (Track, IntOffset) -> Unit,
) {
    val blocks = remember(tracks.size, columns) {
        val rows = WallLayout.blockRows(columns)
        WallLayout.slots(tracks.size, columns).withIndex()
            .groupBy { it.value.y / rows }
            .map { (block, slots) ->
                val tiles = slots.map { (index, slot) -> index to slot.copy(y = slot.y - block * rows) }
                WallBlock(block, tiles.maxOf { (_, slot) -> slot.y + slot.rows }, tiles)
            }
    }
    LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = contentPadding) {
        items(blocks, key = { it.index }) { block ->
            Layout(
                content = {
                    block.tiles.forEach { (index, slot) ->
                        val track = tracks[index]
                        PosterTile(
                            track = track,
                            slot = slot,
                            current = track.id == currentId,
                            selected = selection?.let { track.id in it },
                            download = downloads[track.id],
                            onClick = { onClick(index) },
                            onLongPress = { anchor -> onLongPress(track, anchor) },
                            modifier = Modifier.layoutId(index),
                        )
                    }
                },
            ) { measurables, constraints ->
                val cell = constraints.maxWidth / columns.toFloat()
                val placed = measurables.map { measurable ->
                    val slot = block.tiles.first { it.first == measurable.layoutId }.second
                    // Snap both edges to whole pixels, so neighbouring covers meet without seams.
                    val left = (slot.x * cell).roundToInt()
                    val top = (slot.y * cell).roundToInt()
                    val width = ((slot.x + slot.cols) * cell).roundToInt() - left
                    val height = ((slot.y + slot.rows) * cell).roundToInt() - top
                    Triple(measurable.measure(Constraints.fixed(width, height)), left, top)
                }
                layout(constraints.maxWidth, (block.rows * cell).roundToInt()) {
                    placed.forEach { (placeable, x, y) -> placeable.place(x, y) }
                }
            }
        }
    }
}

private val SCRIM = Brush.verticalGradient(0.35f to Color.Transparent, 0.58f to Color.Black.copy(alpha = 0.18f), 1f to Color.Black.copy(alpha = 0.72f))
private val TEXT_SHADOW = Shadow(Color.Black.copy(alpha = 0.45f), blurRadius = 8f)

@Composable
private fun PosterTile(
    track: Track,
    slot: WallSlot,
    current: Boolean,
    selected: Boolean?,
    download: Float?,
    onClick: () -> Unit,
    onLongPress: (IntOffset) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalAppColors.current
    val large = slot.cols >= 4 && slot.rows >= 3
    val small = slot.cols <= 2 && slot.rows <= 2
    var coordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val label = stringResource(R.string.wall_tile_label, track.title, track.artist ?: stringResource(R.string.unknown_artist))
    val menuLabel = stringResource(R.string.song_actions)
    Box(
        modifier
            .background(colors.surface)
            .onGloballyPositioned { coordinates = it }
            .semantics {
                contentDescription = label
                role = if (selected != null) Role.Checkbox else Role.Button
                if (selected != null) this.selected = selected
                onLongClick(menuLabel) {
                    coordinates?.let { onLongPress(it.positionInWindow().let { p -> IntOffset(p.x.roundToInt(), p.y.roundToInt()) }) }
                    true
                }
            }
            .pointerInput(onClick, onLongPress) {
                detectTapGestures(
                    onTap = { onClick() },
                    onLongPress = { offset ->
                        val origin = coordinates?.positionInWindow() ?: return@detectTapGestures
                        onLongPress(IntOffset((origin.x + offset.x).roundToInt(), (origin.y + offset.y).roundToInt()))
                    },
                )
            }
            .then(if (selected == false) Modifier.alpha(0.55f) else Modifier),
    ) {
        track.coverPath?.let { path ->
            AsyncImage(
                model = if (path.startsWith("http")) path else File(path),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Box(Modifier.fillMaxSize().alpha(if (small && !current) 0.6f else 1f).background(SCRIM))
        Column(Modifier.align(Alignment.BottomStart).padding(horizontal = if (large) 14.dp else 10.dp, vertical = if (large) 13.dp else 9.dp)) {
            BasicText(
                track.title,
                style = TextStyle(color = Color.White, fontSize = if (large) 19.sp else 13.sp, fontWeight = FontWeight.ExtraBold, lineHeight = if (large) 24.sp else 16.sp, shadow = TEXT_SHADOW),
                maxLines = if (large) 2 else 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!small || current) {
                BasicText(
                    track.artist ?: stringResource(R.string.unknown_artist),
                    style = TextStyle(color = Color.White.copy(alpha = 0.75f), fontSize = if (large) 12.sp else 11.sp, shadow = TEXT_SHADOW),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (selected != null) {
            Icon(
                if (selected) R.drawable.ui_check_circle else R.drawable.ui_radio_button_unchecked,
                Color.White,
                Modifier.align(Alignment.TopEnd).padding(8.dp),
                size = 22.dp,
            )
        }
        if (current && selected == null) {
            Box(Modifier.fillMaxSize().border(3.dp, colors.accent))
        }
        // Desktop makes the badge compact on covers under 160px tall.
        download?.let { DownloadBadge(it, compact = slot.rows <= 2, modifier = Modifier.align(Alignment.Center)) }
    }
}
