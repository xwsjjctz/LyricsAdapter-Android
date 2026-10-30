package com.lyricsadapter.app.ui.search

import com.lyricsadapter.app.ui.glass.glass
import com.kyant.shapes.Capsule
import com.kyant.backdrop.Backdrop
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lyricsadapter.app.R
import com.lyricsadapter.app.plugins.DownloadQuality
import com.lyricsadapter.app.plugins.OnlinePlaylist
import com.lyricsadapter.app.plugins.OnlineSong
import com.lyricsadapter.app.search.TextMatcher
import com.lyricsadapter.app.ui.ContextMenu
import com.lyricsadapter.app.ui.Cover
import com.lyricsadapter.app.ui.Icon
import com.lyricsadapter.app.ui.IconButton
import com.lyricsadapter.app.ui.LocalAppColors
import com.lyricsadapter.app.ui.MenuEntry
import com.lyricsadapter.app.ui.TextAction
import com.lyricsadapter.app.ui.formatDuration
import com.lyricsadapter.library.Track
import kotlinx.coroutines.delay
import kotlin.math.roundToInt
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import com.lyricsadapter.app.ui.glass.MenuPreview
import com.lyricsadapter.app.ui.glass.menuPress

/** One music source's answer to a search; [error] when it failed. */
class OnlineResults(val sourceName: String, val songs: List<OnlineSong>, val error: String?)

/** Where the search looks and what its rows do. */
class SearchMusic(
    val localTracks: List<Track>,
    val cloudTracks: List<Track>,
    val searchOnline: suspend (String) -> List<OnlineResults>,
    /** The account playlists the library shows (hidden ones left out). */
    val playlists: List<OnlinePlaylist>,
    val playTrack: (Track) -> Unit,
    val playOnline: (OnlineSong) -> Unit,
    val addOnline: (OnlineSong) -> Unit,
    val download: (OnlineSong, DownloadQuality) -> Unit,
    val openPlaylist: (OnlinePlaylist) -> Unit,
)

private const val TRACK_LIMIT = 6

/** Where a result's menu opens, from the row's top-left corner, in pixels. */
private const val MENU_INSET = 96
private const val ONLINE_LIMIT = 8
private const val SEARCH_DELAY_MS = 400L

/**
 * The search field over the list: a Liquid Glass capsule that stays put
 * while the list scrolls under it. [focused] reports whether the keyboard is
 * on it; Cancel shows while it is focused or holds text.
 */
@Composable
fun SearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    onFocusChange: (Boolean) -> Unit,
    focused: Boolean,
    onCancel: () -> Unit,
    onGo: () -> Unit,
    backdrop: Backdrop,
    modifier: Modifier = Modifier,
) {
    val colors = LocalAppColors.current
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier
                .weight(1f)
                .height(SEARCH_FIELD_HEIGHT)
                .glass(backdrop, Capsule(), lens = 8.dp)
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(R.drawable.ui_search, colors.secondaryText, size = 20.dp)
            Spacer(Modifier.width(8.dp))
            val placeholder = stringResource(R.string.palette_placeholder_library)
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = TextStyle(color = colors.text, fontSize = 16.sp),
                cursorBrush = SolidColor(colors.accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { onGo() }),
                modifier = Modifier.weight(1f).onFocusChanged { onFocusChange(it.isFocused) },
                decorationBox = { field ->
                    Box {
                        if (query.isEmpty()) BasicText(placeholder, style = TextStyle(color = colors.secondaryText, fontSize = 16.sp), maxLines = 1)
                        field()
                    }
                },
            )
        }
        if (focused || query.isNotEmpty()) TextAction(stringResource(R.string.cancel), onClick = onCancel)
    }
}

/** The search field's height. */
val SEARCH_FIELD_HEIGHT = 44.dp

/**
 * What a search for [query] finds, in place of the list: the local and cloud
 * libraries, each music source (once typing pauses) and the account
 * playlists, as desktop's global search does. Picking a result plays or opens
 * it; [onPicked] then lets the page leave search.
 */
@Composable
fun SearchResults(music: SearchMusic, query: String, contentPadding: PaddingValues, onPicked: () -> Unit) {
    val colors = LocalAppColors.current
    var online by remember { mutableStateOf<List<OnlineResults>?>(null) }
    var added by remember { mutableStateOf(emptySet<String>()) }
    // The song whose menu is open, where the finger pressed, and the row to lift.
    var menu by remember { mutableStateOf<Triple<OnlineSong, IntOffset, Rect>?>(null) }
    LaunchedEffect(query) {
        online = null
        if (query.isBlank()) return@LaunchedEffect
        delay(SEARCH_DELAY_MS)
        online = music.searchOnline(query.trim())
    }
    val sections = SearchSections(
        local = stringResource(R.string.slot_local),
        cloud = stringResource(R.string.slot_cloud),
        playlists = stringResource(R.string.slot_playlist),
    )
    val rows = remember(query, music.localTracks, music.cloudTracks, music.playlists, online) {
        searchRows(music, query, online, music.playlists, sections)
    }
    val searching = query.isNotBlank() && online == null
    val run = { row: SearchRow.Result ->
        when (row) {
            is SearchRow.TrackRow -> music.playTrack(row.track)
            is SearchRow.OnlineRow -> music.playOnline(row.song)
            is SearchRow.PlaylistRow -> music.openPlaylist(row.playlist)
        }
        onPicked()
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = contentPadding) {
        if (rows.isEmpty()) {
            item(key = "hint") {
                val hint = when {
                    searching -> R.string.palette_searching
                    query.isBlank() -> R.string.palette_library_hint
                    else -> R.string.palette_no_results
                }
                BasicText(stringResource(hint), style = TextStyle(color = colors.secondaryText, fontSize = 15.sp), modifier = Modifier.fillMaxWidth().padding(24.dp))
            }
        }
        items(rows, key = SearchRow::key) { row ->
            when (row) {
                is SearchRow.Section -> BasicText(
                    row.title,
                    style = TextStyle(color = colors.secondaryText, fontSize = 13.sp, fontWeight = FontWeight.SemiBold),
                    modifier = Modifier.padding(start = 20.dp, top = 14.dp, bottom = 4.dp).semantics { heading() },
                )
                is SearchRow.Note -> BasicText(
                    row.text ?: stringResource(R.string.palette_searching),
                    style = TextStyle(color = colors.secondaryText, fontSize = 14.sp),
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                )
                is SearchRow.TrackRow -> ResultRow(
                    title = row.track.title,
                    subtitle = listOfNotNull(row.track.artist, row.track.album).joinToString(" · ").ifEmpty { null },
                    leading = { Cover(row.track.coverPath, size = 40.dp) },
                    detail = null,
                    onClick = { run(row) },
                )
                is SearchRow.OnlineRow -> ResultRow(
                    title = row.song.title,
                    subtitle = onlineSubtitle(row.song),
                    leading = { Cover(row.song.coverUrl, size = 40.dp) },
                    detail = onlineDuration(row.song),
                    onClick = { run(row) },
                    onLongPress = { anchor, bounds -> menu = Triple(row.song, anchor, bounds) },
                    trailing = {
                        val done = row.song.trackId in added
                        IconButton(
                            if (done) R.drawable.ui_check else R.drawable.ui_add,
                            stringResource(if (done) R.string.added_to_online else R.string.add_to_online),
                            tint = colors.accent,
                            size = 20.dp,
                        ) {
                            if (!done) {
                                music.addOnline(row.song)
                                added = added + row.song.trackId
                            }
                        }
                    },
                )
                is SearchRow.PlaylistRow -> ResultRow(
                    title = row.playlist.name,
                    subtitle = null,
                    leading = { Cover(row.playlist.coverUrl, size = 40.dp) },
                    detail = row.playlist.songCount.toString(),
                    onClick = { run(row) },
                )
            }
        }
    }
    menu?.let { (song, anchor, bounds) ->
        val done = song.trackId in added
        ContextMenu(
            anchor = anchor,
            preview = MenuPreview(bounds) {
                ResultRowBody(song.title, onlineSubtitle(song), { Cover(song.coverUrl, size = 40.dp) }, onlineDuration(song))
            },
            entries = buildList {
                add(MenuEntry.Label(stringResource(R.string.track_download)))
                DownloadQuality.entries.forEach { quality -> add(MenuEntry.Item(quality.label, R.drawable.ui_download) { music.download(song, quality) }) }
                add(MenuEntry.Separator)
                add(
                    MenuEntry.Item(stringResource(if (done) R.string.added_to_online else R.string.add_to_online), if (done) R.drawable.ui_check else R.drawable.ui_add) {
                        if (!done) {
                            music.addOnline(song)
                            added = added + song.trackId
                        }
                    },
                )
            },
            onDismiss = { menu = null },
        )
    }
}

/** The first result a search for [query] would list, for the keyboard's Go key. */
fun firstResult(music: SearchMusic, query: String): (() -> Unit)? {
    if (query.isBlank()) return null
    fun matches(track: Track) = TextMatcher.matches(listOfNotNull(track.title, track.artist, track.album).joinToString(" "), query)
    val track = music.localTracks.firstOrNull(::matches) ?: music.cloudTracks.firstOrNull(::matches) ?: return null
    return { music.playTrack(track) }
}

private fun onlineSubtitle(song: OnlineSong) = listOfNotNull(song.artist.ifEmpty { null }, song.album).joinToString(" · ").ifEmpty { null }

private fun onlineDuration(song: OnlineSong) = song.duration?.let { formatDuration((it * 1000).toLong()) }

/** A result: a tap picks it; a long press, where given, opens its menu with the row lifted. */
@Composable
private fun ResultRow(
    title: String,
    subtitle: String?,
    leading: @Composable () -> Unit,
    detail: String?,
    onClick: () -> Unit,
    trailing: (@Composable () -> Unit)? = null,
    onLongPress: ((anchor: IntOffset, bounds: Rect) -> Unit)? = null,
) {
    var coordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val actions = stringResource(R.string.song_actions)
    ResultRowBody(
        title, subtitle, leading, detail, trailing,
        Modifier
            .onGloballyPositioned { coordinates = it }
            .semantics {
                role = Role.Button
                onClick { onClick(); true }
                if (onLongPress != null) {
                    onLongClick(actions) {
                        coordinates?.let { onLongPress(it.positionInWindow().let { p -> IntOffset(p.x.roundToInt() + MENU_INSET, p.y.roundToInt()) }, it.boundsInWindow()) }
                        true
                    }
                }
            }
            .menuPress(onClick, onLongPress),
    )
}

/** How a result looks: its artwork, title over subtitle, a detail such as the duration, and an optional trailing button. */
@Composable
private fun ResultRowBody(
    title: String,
    subtitle: String?,
    leading: @Composable () -> Unit,
    detail: String?,
    trailing: (@Composable () -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val colors = LocalAppColors.current
    Row(
        modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = if (trailing != null) 4.dp else 16.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading()
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            BasicText(title, style = TextStyle(color = colors.text, fontSize = 16.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) {
                BasicText(subtitle, style = TextStyle(color = colors.secondaryText, fontSize = 13.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (detail != null) {
            Spacer(Modifier.width(8.dp))
            BasicText(detail, style = TextStyle(color = colors.secondaryText, fontSize = 14.sp))
        }
        trailing?.invoke()
    }
}

/** Section titles, resolved once per composition so the row builder stays a plain function. */
private class SearchSections(val local: String, val cloud: String, val playlists: String)

/** A line in the result list: a heading, a note, or a result. */
private sealed interface SearchRow {
    val key: String

    data class Section(val title: String) : SearchRow {
        override val key get() = "section:$title"
    }

    /** A source's state under its heading; null text means "searching". */
    data class Note(val owner: String, val text: String?) : SearchRow {
        override val key get() = "note:$owner"
    }

    sealed interface Result : SearchRow

    data class TrackRow(val track: Track) : Result {
        override val key get() = "track:${track.id}"
    }

    data class OnlineRow(val song: OnlineSong) : Result {
        override val key get() = "online:${song.trackId}"
    }

    data class PlaylistRow(val playlist: OnlinePlaylist) : Result {
        override val key get() = "playlist:${playlist.source}:${playlist.id}"
    }
}

private fun searchRows(
    music: SearchMusic,
    query: String,
    online: List<OnlineResults>?,
    playlists: List<OnlinePlaylist>,
    sections: SearchSections,
): List<SearchRow> = buildList {
    if (query.isNotBlank()) {
        fun matches(track: Track) = TextMatcher.matches(listOfNotNull(track.title, track.artist, track.album).joinToString(" "), query)
        music.localTracks.filter(::matches).take(TRACK_LIMIT).takeIf { it.isNotEmpty() }?.let { hits ->
            add(SearchRow.Section(sections.local))
            hits.forEach { add(SearchRow.TrackRow(it)) }
        }
        music.cloudTracks.filter(::matches).take(TRACK_LIMIT).takeIf { it.isNotEmpty() }?.let { hits ->
            add(SearchRow.Section(sections.cloud))
            hits.forEach { add(SearchRow.TrackRow(it)) }
        }
        online?.forEach { results ->
            if (results.songs.isEmpty() && results.error == null) return@forEach
            add(SearchRow.Section(results.sourceName))
            if (results.error != null) add(SearchRow.Note(results.sourceName, results.error))
            results.songs.take(ONLINE_LIMIT).forEach { add(SearchRow.OnlineRow(it)) }
        }
        if (online == null && isNotEmpty()) add(SearchRow.Note("online", null))
    }
    val shownPlaylists = if (query.isBlank()) playlists else playlists.filter { TextMatcher.matches(it.name, query) }
    if (shownPlaylists.isNotEmpty()) {
        add(SearchRow.Section(sections.playlists))
        shownPlaylists.forEach { add(SearchRow.PlaylistRow(it)) }
    }
}
