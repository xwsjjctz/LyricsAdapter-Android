package com.lyricsadapter.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lyricsadapter.app.R
import com.lyricsadapter.app.data.SlotId
import com.lyricsadapter.library.Track
import kotlin.math.roundToInt

/** What a slot is called and drawn with, as desktop's sidebar and palette name them. */
internal val SlotId.titleRes: Int
    get() = when (this) {
        SlotId.Local -> R.string.slot_local
        SlotId.Cloud -> R.string.slot_cloud
        SlotId.Online -> R.string.slot_online
        SlotId.Playlist -> R.string.slot_playlist
    }

internal val SlotId.iconRes: Int
    get() = when (this) {
        SlotId.Local -> R.drawable.ui_hard_drive
        SlotId.Cloud -> R.drawable.ui_cloud
        SlotId.Online -> R.drawable.ui_history
        SlotId.Playlist -> R.drawable.ui_queue_music
    }

/** What the page can ask the app to do. */
class LibraryActions(
    val onPlay: (index: Int) -> Unit,
    val onEdit: (Track) -> Unit,
    val onRemove: (Set<String>) -> Unit,
    val onSwitchSlot: (SlotId) -> Unit,
    val onSearch: () -> Unit,
)

/** What an empty slot says, and the one action that fills it, if any. */
class EmptyContent(val message: String, val actionLabel: String?, val onAction: () -> Unit)

/**
 * One library slot as a list in desktop's Apple Music style: a pinned header
 * with the slot's name (tap it to switch slots), its size and the search
 * entry, then cover, title with "artist — album" under it, and duration; the
 * playing row is filled with the accent colour. Long-pressing a row opens
 * its menu; in selection mode taps select instead of playing.
 */
@Composable
fun LibraryScreen(
    slot: SlotId,
    tracks: List<Track>,
    slotCounts: Map<SlotId, Int>,
    currentId: String?,
    listState: LazyListState,
    contentPadding: PaddingValues,
    selection: Set<String>?,
    onSelectionChange: (Set<String>?) -> Unit,
    actions: LibraryActions,
    empty: EmptyContent,
    status: String? = null,
) {
    val colors = LocalAppColors.current
    var pendingRemoval by remember { mutableStateOf<Set<String>?>(null) }
    var menu by remember { mutableStateOf<Pair<Track, IntOffset>?>(null) }
    val editable = slot == SlotId.Local

    Column(Modifier.fillMaxSize().background(colors.background).padding(top = contentPadding.calculateTopPadding())) {
        if (selection != null) {
            SelectionHeader(tracks, selection, onSelectionChange)
        } else {
            LibraryHeader(slot, tracks, slotCounts, actions, status)
        }
        Box(Modifier.weight(1f)) {
            if (tracks.isEmpty()) {
                EmptySlot(slot, empty, Modifier.fillMaxSize())
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding() + if (selection != null) 64.dp else 0.dp),
                ) {
                    itemsIndexed(tracks, key = { _, track -> track.id }) { index, track ->
                        TrackRow(
                            track = track,
                            playing = track.id == currentId,
                            selected = selection?.let { track.id in it },
                            onClick = {
                                if (selection != null) onSelectionChange(selection.toggle(track.id)) else actions.onPlay(index)
                            },
                            onLongPress = { anchor -> if (selection == null && slot.hasTrackMenu) menu = track to anchor },
                        )
                    }
                }
            }
            if (selection != null) {
                SelectionBar(
                    count = selection.size,
                    onRemove = { pendingRemoval = selection },
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = contentPadding.calculateBottomPadding()),
                )
            }
        }
    }

    menu?.let { (track, anchor) ->
        ContextMenu(
            anchor = anchor,
            entries = buildList {
                if (editable) add(MenuEntry.Item(stringResource(R.string.edit_info), R.drawable.ui_edit) { actions.onEdit(track) })
                add(MenuEntry.Item(stringResource(R.string.select_multiple), R.drawable.ui_checklist) { onSelectionChange(setOf(track.id)) })
                add(MenuEntry.Separator)
                add(MenuEntry.Item(stringResource(R.string.remove_from_library), R.drawable.ui_delete, danger = true) { pendingRemoval = setOf(track.id) })
            },
            onDismiss = { menu = null },
        )
    }
    pendingRemoval?.let { ids ->
        ConfirmDialog(
            title = stringResource(R.string.remove_confirm_title),
            message = stringResource(R.string.remove_confirm_message),
            confirmLabel = stringResource(R.string.remove),
            cancelLabel = stringResource(R.string.cancel),
            danger = true,
            onConfirm = {
                actions.onRemove(ids)
                onSelectionChange(null)
            },
            onDismiss = { pendingRemoval = null },
        )
    }
}

/** Local and online-queue lists are managed in the app; other slots are play-only (desktop's `buildTrackMenuItems`). */
private val SlotId.hasTrackMenu: Boolean get() = this == SlotId.Local || this == SlotId.Online

private fun Set<String>.toggle(id: String): Set<String> = if (id in this) this - id else this + id

@Composable
private fun LibraryHeader(slot: SlotId, tracks: List<Track>, slotCounts: Map<SlotId, Int>, actions: LibraryActions, status: String?) {
    val colors = LocalAppColors.current
    var switcherAt by remember { mutableStateOf<IntOffset?>(null) }
    var titleCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            val switchLabel = stringResource(R.string.switch_slot)
            Row(
                Modifier
                    .onGloballyPositioned { titleCoordinates = it }
                    .clip(RoundedCornerShape(8.dp))
                    .semantics {
                        contentDescription = switchLabel
                        role = Role.Button
                    }
                    .clickable {
                        val position = titleCoordinates?.positionInWindow() ?: return@clickable
                        val height = titleCoordinates?.size?.height ?: 0
                        switcherAt = IntOffset(position.x.roundToInt(), position.y.roundToInt() + height)
                    },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BasicText(stringResource(slot.titleRes), style = TextStyle(color = colors.text, fontSize = 32.sp, fontWeight = FontWeight.Bold))
                Icon(R.drawable.ui_expand_more, colors.secondaryText, Modifier.padding(start = 2.dp, top = 4.dp), size = 28.dp)
            }
            BasicText(
                status ?: pluralStringResource(R.plurals.library_summary, tracks.size, tracks.size, formatTotalDuration(tracks.sumOf { it.durationMs })),
                style = TextStyle(color = colors.secondaryText, fontSize = 14.sp),
            )
        }
        IconButton(R.drawable.ui_search, stringResource(R.string.search), tint = colors.accent, onClick = actions.onSearch)
    }
    switcherAt?.let { anchor ->
        ContextMenu(
            anchor = anchor,
            entries = SlotId.entries.map { target ->
                MenuEntry.Item(
                    label = stringResource(target.titleRes),
                    icon = target.iconRes,
                    detail = (slotCounts[target] ?: 0).toString(),
                    checked = target == slot,
                ) { actions.onSwitchSlot(target) }
            },
            onDismiss = { switcherAt = null },
        )
    }
}

@Composable
private fun SelectionHeader(tracks: List<Track>, selection: Set<String>, onSelectionChange: (Set<String>?) -> Unit) {
    val colors = LocalAppColors.current
    val all = selection.size == tracks.size && tracks.isNotEmpty()
    Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        TextAction(stringResource(R.string.done), onClick = { onSelectionChange(null) }, bold = true)
        BasicText(
            pluralStringResource(R.plurals.selected_count, selection.size, selection.size),
            style = TextStyle(color = colors.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold),
            modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
        )
        TextAction(
            stringResource(if (all) R.string.select_none else R.string.select_all),
            onClick = { onSelectionChange(if (all) emptySet() else tracks.mapTo(LinkedHashSet()) { it.id }) },
        )
    }
}

@Composable
private fun SelectionBar(count: Int, onRemove: () -> Unit, modifier: Modifier = Modifier) {
    val colors = LocalAppColors.current
    Row(
        modifier
            .fillMaxWidth()
            .background(colors.surface)
            .padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.Center,
    ) {
        TextAction(stringResource(R.string.remove_from_library), onClick = onRemove, enabled = count > 0, color = colors.danger)
    }
}

@Composable
private fun TrackRow(track: Track, playing: Boolean, selected: Boolean?, onClick: () -> Unit, onLongPress: (IntOffset) -> Unit) {
    val colors = LocalAppColors.current
    val filled = playing && selected == null
    val textColor = if (filled) colors.onAccent else colors.text
    val secondaryColor = if (filled) colors.onAccent.copy(alpha = 0.8f) else colors.secondaryText
    var coordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val openMenu = stringResource(R.string.song_actions)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (filled) colors.accent else colors.background)
            .onGloballyPositioned { coordinates = it }
            .semantics {
                role = Role.Button
                if (selected != null) this.selected = selected
                onLongClick(openMenu) {
                    coordinates?.let { onLongPress(it.positionInWindow().let { p -> IntOffset(p.x.roundToInt() + 48, p.y.roundToInt()) }) }
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
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selected != null) {
            Icon(
                if (selected) R.drawable.ui_check_circle else R.drawable.ui_radio_button_unchecked,
                if (selected) colors.accent else colors.secondaryText,
            )
            Spacer(Modifier.width(12.dp))
        }
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

/** Each slot says how it gets filled, with the one action that fills it. */
@Composable
private fun EmptySlot(slot: SlotId, empty: EmptyContent, modifier: Modifier) {
    val colors = LocalAppColors.current
    Column(modifier.padding(horizontal = 32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(slot.iconRes, colors.secondaryText.copy(alpha = 0.6f), size = 48.dp)
        Spacer(Modifier.height(12.dp))
        BasicText(
            empty.message,
            style = TextStyle(color = colors.secondaryText, fontSize = 15.sp, lineHeight = 22.sp, textAlign = TextAlign.Center),
        )
        if (empty.actionLabel != null) {
            Spacer(Modifier.height(16.dp))
            PillButton(empty.actionLabel, onClick = empty.onAction)
        }
    }
}
