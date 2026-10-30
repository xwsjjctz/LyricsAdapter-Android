package com.lyricsadapter.app.ui

import androidx.compose.foundation.background
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
import com.lyricsadapter.app.plugins.DownloadQuality
import com.lyricsadapter.app.plugins.OnlinePlaylist
import com.lyricsadapter.app.data.LibraryLayout
import com.lyricsadapter.app.data.SlotId
import com.lyricsadapter.library.Track
import kotlin.math.roundToInt
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.onClick
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.lyricsadapter.app.ui.search.SearchField
import com.lyricsadapter.app.ui.search.SearchMusic
import com.lyricsadapter.app.ui.search.SearchResults
import com.lyricsadapter.app.ui.search.firstResult
import kotlinx.coroutines.launch
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import com.lyricsadapter.app.ui.glass.MenuPreview
import com.lyricsadapter.app.ui.glass.menuPress

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
    /** Online songs only; null where the list has none. */
    val onDownload: ((Track, DownloadQuality) -> Unit)?,
    val onSwitchSlot: (SlotId) -> Unit,
)

/** What the header's menus list besides the slots: the account playlists and the page's other actions. */
class HeaderMenus(
    /** Every account playlist, and whether the library hides it. */
    val playlists: List<Pair<OnlinePlaylist, Boolean>>,
    /** The playlist open in the playlist slot, if any. */
    val openedPlaylist: OnlinePlaylist?,
    val onOpenPlaylist: (OnlinePlaylist) -> Unit,
    val onTogglePlaylistHidden: (OnlinePlaylist) -> Unit,
    /** Called as the slot menu opens, to list the playlists afresh. */
    val onSlotMenuOpen: () -> Unit,
    /** The page's other actions, behind the "more" button. */
    val more: List<MenuEntry>,
)

/** What an empty slot says, and the one action that fills it, if any. */
class EmptyContent(val message: String, val actionLabel: String?, val onAction: () -> Unit)

/**
 * One library slot as a list in desktop's Apple Music style: a pinned header
 * with the slot's name (tap it to switch slots or open an account playlist;
 * double-tap the header to go back to the top), its size and a menu of the
 * page's other actions, a Liquid Glass search field that stays over the list
 * as it scrolls under it, then cover, title with "artist — album" under it,
 * and duration; the playing row is filled with the accent colour. While the
 * search field is in use its results take the list's place. Long-pressing a
 * row opens its menu; in selection mode taps select instead of playing.
 */
@Composable
fun LibraryScreen(
    slot: SlotId,
    layout: LibraryLayout,
    tracks: List<Track>,
    slotCounts: Map<SlotId, Int>,
    currentId: String?,
    listState: LazyListState,
    contentPadding: PaddingValues,
    selection: Set<String>?,
    downloads: Map<String, Float>,
    wallColumns: Int,
    onSelectionChange: (Set<String>?) -> Unit,
    actions: LibraryActions,
    menus: HeaderMenus,
    search: SearchMusic,
    empty: EmptyContent,
    status: String? = null,
) {
    val colors = LocalAppColors.current
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val density = LocalDensity.current
    var pendingRemoval by remember { mutableStateOf<Set<String>?>(null) }
    // The song whose menu is open, where the finger pressed, and the row to lift (none on the wall).
    var menu by remember { mutableStateOf<Triple<Track, IntOffset, Rect?>?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    var searchFocused by remember { mutableStateOf(false) }
    val searching = selection == null && (searchFocused || query.isNotEmpty())
    val endSearch = {
        query = ""
        focusManager.clearFocus()
    }
    BackHandler(enabled = searching, onBack = endSearch)
    var headerHeight by remember { mutableStateOf(0.dp) }
    val editable = slot == SlotId.Local
    val listBackdrop = rememberLayerBackdrop()

    BoxWithConstraints(Modifier.fillMaxSize().background(colors.background)) {
        val landscape = maxWidth > maxHeight
        val column = Modifier.widthIn(max = MAX_CONTENT_WIDTH).fillMaxWidth().align(Alignment.TopCenter)
        val listPadding = PaddingValues(top = headerHeight, bottom = contentPadding.calculateBottomPadding() + if (selection != null) 64.dp else 0.dp)
        Box(column.fillMaxHeight().layerBackdrop(listBackdrop)) {
            if (searching) {
                SearchResults(search, query, listPadding, onPicked = endSearch)
            } else if (tracks.isEmpty()) {
                EmptySlot(slot, empty, Modifier.fillMaxSize().padding(top = headerHeight))
            } else if (layout == LibraryLayout.Wall) {
                PosterWall(
                    tracks = tracks,
                    currentId = currentId,
                    selection = selection,
                    downloads = downloads,
                    columns = wallColumns,
                    listState = listState,
                    contentPadding = listPadding,
                    onClick = { index ->
                        if (selection != null) onSelectionChange(selection.toggle(tracks[index].id)) else actions.onPlay(index)
                    },
                    onLongPress = { track, anchor -> if (selection == null && slot.hasTrackMenu) menu = Triple(track, anchor, null) },
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = listPadding,
                ) {
                    itemsIndexed(tracks, key = { _, track -> track.id }) { index, track ->
                        TrackRow(
                            track = track,
                            playing = track.id == currentId,
                            selected = selection?.let { track.id in it },
                            download = downloads[track.id],
                            onClick = {
                                if (selection != null) onSelectionChange(selection.toggle(track.id)) else actions.onPlay(index)
                            },
                            onLongPress = { anchor, bounds -> if (selection == null && slot.hasTrackMenu) menu = Triple(track, anchor, bounds) },
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
        // The header stays put; the list scrolls under it and, past the title, under the glass search field.
        Column(column.onSizeChanged { headerHeight = with(density) { it.height.toDp() } }) {
            Column(Modifier.fillMaxWidth().background(colors.background).padding(top = contentPadding.calculateTopPadding())) {
                if (selection != null) {
                    SelectionHeader(tracks, selection, onSelectionChange)
                } else {
                    LibraryHeader(
                        slot, tracks, slotCounts, actions, menus, status, compact = landscape,
                        onScrollToTop = { scope.launch { listState.animateScrollToItem(0) } },
                    )
                }
            }
            if (selection == null) {
                SearchField(
                    query = query,
                    onQueryChange = { query = it },
                    onFocusChange = { searchFocused = it },
                    focused = searchFocused,
                    onCancel = endSearch,
                    onGo = {
                        firstResult(search, query)?.let { play ->
                            play()
                            endSearch()
                        }
                    },
                    backdrop = listBackdrop,
                    modifier = Modifier.padding(start = 16.dp, end = 8.dp, bottom = 8.dp),
                )
            }
        }
    }

    menu?.let { (track, anchor, bounds) ->
        ContextMenu(
            anchor = anchor,
            preview = bounds?.let { MenuPreview(it) { TrackRowBody(track, playing = track.id == currentId, selected = null, download = downloads[track.id]) } },
            entries = buildList {
                // Desktop's `buildTrackMenuItems`: download first, for online songs outside the local and cloud slots.
                val download = actions.onDownload
                if (download != null && slot != SlotId.Local && slot != SlotId.Cloud) {
                    add(MenuEntry.Label(stringResource(R.string.track_download)))
                    DownloadQuality.entries.forEach { quality -> add(MenuEntry.Item(quality.label, R.drawable.ui_download) { download(track, quality) }) }
                }
                if (slot.isManaged) {
                    if (isNotEmpty()) add(MenuEntry.Separator)
                    if (editable) add(MenuEntry.Item(stringResource(R.string.edit_info), R.drawable.ui_edit) { actions.onEdit(track) })
                    add(MenuEntry.Item(stringResource(R.string.select_multiple), R.drawable.ui_checklist) { onSelectionChange(setOf(track.id)) })
                    add(MenuEntry.Separator)
                    add(MenuEntry.Item(stringResource(R.string.remove_from_library), R.drawable.ui_delete, danger = true) { pendingRemoval = setOf(track.id) })
                }
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

/** The list's widest, so landscape keeps rows a readable length. */
private val MAX_CONTENT_WIDTH = 720.dp

/** Local and online-queue lists are managed in the app; other slots are play-only (desktop's `buildTrackMenuItems`). */
private val SlotId.isManaged: Boolean get() = this == SlotId.Local || this == SlotId.Online

/** Managed lists have a song menu, and so do online playlists, for downloading. */
private val SlotId.hasTrackMenu: Boolean get() = isManaged || this == SlotId.Playlist

private fun Set<String>.toggle(id: String): Set<String> = if (id in this) this - id else this + id

@Composable
private fun LibraryHeader(
    slot: SlotId,
    tracks: List<Track>,
    slotCounts: Map<SlotId, Int>,
    actions: LibraryActions,
    menus: HeaderMenus,
    status: String?,
    compact: Boolean,
    onScrollToTop: () -> Unit,
) {
    val colors = LocalAppColors.current
    var switcherAt by remember { mutableStateOf<IntOffset?>(null) }
    var managerAt by remember { mutableStateOf<IntOffset?>(null) }
    var moreAt by remember { mutableStateOf<IntOffset?>(null) }
    var headerCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var titleCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var moreCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val openSwitcher = {
        titleCoordinates?.below()?.let { anchor ->
            switcherAt = anchor
            menus.onSlotMenuOpen()
        }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .onGloballyPositioned { headerCoordinates = it }
            // A tap on the title opens the slot menu; a double tap anywhere here goes back to the top.
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { offset ->
                        val header = headerCoordinates ?: return@detectTapGestures
                        val title = titleCoordinates ?: return@detectTapGestures
                        if (header.localBoundingBoxOf(title).contains(offset)) openSwitcher()
                    },
                    onDoubleTap = { onScrollToTop() },
                )
            }
            .padding(start = 20.dp, end = 8.dp, top = if (compact) 4.dp else 12.dp, bottom = if (compact) 4.dp else 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            val switchLabel = stringResource(R.string.switch_slot)
            Row(
                Modifier
                    .onGloballyPositioned { titleCoordinates = it }
                    .semantics {
                        contentDescription = switchLabel
                        role = Role.Button
                        onClick {
                            openSwitcher()
                            true
                        }
                    },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BasicText(stringResource(slot.titleRes), style = TextStyle(color = colors.text, fontSize = if (compact) 24.sp else 32.sp, fontWeight = FontWeight.Bold))
                Icon(R.drawable.ui_expand_more, colors.secondaryText, Modifier.padding(start = 2.dp, top = 4.dp), size = if (compact) 22.dp else 28.dp)
            }
            BasicText(
                status ?: pluralStringResource(R.plurals.library_summary, tracks.size, tracks.size, formatTotalDuration(tracks.sumOf { it.durationMs })),
                style = TextStyle(color = colors.secondaryText, fontSize = 14.sp),
            )
        }
        if (menus.more.isNotEmpty()) {
            Box(Modifier.onGloballyPositioned { moreCoordinates = it }) {
                IconButton(R.drawable.ui_more_horiz, stringResource(R.string.more_actions), tint = colors.accent) {
                    moreAt = moreCoordinates?.below()
                }
            }
        }
    }
    switcherAt?.let { anchor ->
        val playlistsTitle = stringResource(R.string.slot_playlist)
        val manageTitle = stringResource(R.string.command_manage_playlists)
        val shown = menus.playlists.filterNot { it.second }.map { it.first }
        ContextMenu(
            anchor = anchor,
            entries = buildList {
                listOf(SlotId.Local, SlotId.Cloud, SlotId.Online).forEach { target ->
                    add(
                        MenuEntry.Item(
                            label = stringResource(target.titleRes),
                            icon = target.iconRes,
                            detail = (slotCounts[target] ?: 0).toString(),
                            checked = target == slot,
                        ) { actions.onSwitchSlot(target) },
                    )
                }
                if (menus.playlists.isNotEmpty()) {
                    add(MenuEntry.Separator)
                    add(MenuEntry.Label(playlistsTitle))
                    shown.forEach { playlist ->
                        val open = slot == SlotId.Playlist && menus.openedPlaylist?.let { it.source == playlist.source && it.id == playlist.id } == true
                        add(MenuEntry.Item(playlist.name, R.drawable.ui_queue_music, detail = playlist.songCount.toString(), checked = open) { menus.onOpenPlaylist(playlist) })
                    }
                    add(MenuEntry.Item(manageTitle, R.drawable.ui_visibility) { managerAt = anchor })
                }
            },
            onDismiss = { switcherAt = null },
        )
    }
    managerAt?.let { anchor ->
        val hidden = stringResource(R.string.palette_state_hidden)
        val visible = stringResource(R.string.palette_state_shown)
        ContextMenu(
            anchor = anchor,
            entries = menus.playlists.map { (playlist, isHidden) ->
                MenuEntry.Item(
                    playlist.name,
                    if (isHidden) R.drawable.ui_visibility_off else R.drawable.ui_visibility,
                    detail = if (isHidden) hidden else visible,
                    keepOpen = true,
                ) { menus.onTogglePlaylistHidden(playlist) }
            },
            onDismiss = { managerAt = null },
        )
    }
    moreAt?.let { anchor -> ContextMenu(anchor = anchor, entries = menus.more, onDismiss = { moreAt = null }) }
}

/** Just under this element, in window coordinates: where its menu opens. */
private fun LayoutCoordinates.below(): IntOffset {
    val position = positionInWindow()
    return IntOffset(position.x.roundToInt(), position.y.roundToInt() + size.height)
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

/**
 * One song in the list. A tap plays it (or selects it); a long press opens
 * its menu with the row lifted over the page, and the finger can slide onto
 * an item and let go to pick it.
 */
@Composable
private fun TrackRow(
    track: Track,
    playing: Boolean,
    selected: Boolean?,
    download: Float?,
    onClick: () -> Unit,
    onLongPress: (anchor: IntOffset, bounds: Rect) -> Unit,
) {
    var coordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val openMenu = stringResource(R.string.song_actions)
    TrackRowBody(
        track = track,
        playing = playing,
        selected = selected,
        download = download,
        modifier = Modifier
            .padding(horizontal = 8.dp)
            .onGloballyPositioned { coordinates = it }
            .semantics {
                role = Role.Button
                if (selected != null) this.selected = selected
                onLongClick(openMenu) {
                    coordinates?.let { onLongPress(it.positionInWindow().let { p -> IntOffset(p.x.roundToInt() + 48, p.y.roundToInt()) }, it.boundsInWindow()) }
                    true
                }
            }
            .menuPress(onClick, onLongPress),
    )
}

/** How a song row looks: cover, title over "artist — album", and duration or download progress; filled while it plays. */
@Composable
private fun TrackRowBody(track: Track, playing: Boolean, selected: Boolean?, download: Float?, modifier: Modifier = Modifier) {
    val colors = LocalAppColors.current
    val filled = playing && selected == null
    val textColor = if (filled) colors.onAccent else colors.text
    val secondaryColor = if (filled) colors.onAccent.copy(alpha = 0.8f) else colors.secondaryText
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (filled) colors.accent else colors.background)
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
        if (download != null) {
            DownloadBadge(download, compact = true)
        } else {
            BasicText(formatDuration(track.durationMs), style = TextStyle(color = secondaryColor, fontSize = 13.sp))
        }
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
