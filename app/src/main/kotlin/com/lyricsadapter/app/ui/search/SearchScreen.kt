package com.lyricsadapter.app.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalFocusManager
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
 * The search tab, as Apple Music's: a large title over the search field,
 * the results under it. It searches the local and cloud libraries, each
 * music source (once typing pauses) and the account playlists, as desktop's
 * global search does; picking a result plays or opens it.
 */
@Composable
fun SearchScreen(music: SearchMusic, contentPadding: PaddingValues) {
    val colors = LocalAppColors.current
    var query by rememberSaveable { mutableStateOf("") }
    var focused by remember { mutableStateOf(false) }
    var online by remember { mutableStateOf<List<OnlineResults>?>(null) }
    var added by remember { mutableStateOf(emptySet<String>()) }
    var menu by remember { mutableStateOf<Pair<OnlineSong, IntOffset>?>(null) }
    val focus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    LaunchedEffect(Unit) { focus.requestFocus() }
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
        focusManager.clearFocus()
        when (row) {
            is SearchRow.TrackRow -> music.playTrack(row.track)
            is SearchRow.OnlineRow -> music.playOnline(row.song)
            is SearchRow.PlaylistRow -> music.openPlaylist(row.playlist)
        }
    }

    Column(Modifier.fillMaxSize().background(colors.background).padding(top = contentPadding.calculateTopPadding())) {
        BasicText(
            stringResource(R.string.search),
            style = TextStyle(color = colors.text, fontSize = 32.sp, fontWeight = FontWeight.Bold),
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 8.dp).semantics { heading() },
        )
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(colors.surface).padding(horizontal = 10.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(R.drawable.ui_search, colors.secondaryText, size = 20.dp)
                Spacer(Modifier.width(6.dp))
                val placeholder = stringResource(R.string.palette_placeholder_library)
                BasicTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    textStyle = TextStyle(color = colors.text, fontSize = 17.sp),
                    cursorBrush = SolidColor(colors.accent),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { rows.firstNotNullOfOrNull { it as? SearchRow.Result }?.let(run) }),
                    modifier = Modifier.weight(1f).focusRequester(focus).onFocusChanged { focused = it.isFocused },
                    decorationBox = { field ->
                        Box {
                            if (query.isEmpty()) BasicText(placeholder, style = TextStyle(color = colors.secondaryText, fontSize = 17.sp), maxLines = 1)
                            field()
                        }
                    },
                )
            }
            if (focused || query.isNotEmpty()) {
                TextAction(stringResource(R.string.cancel), onClick = {
                    query = ""
                    focusManager.clearFocus()
                })
            } else {
                Spacer(Modifier.width(8.dp))
            }
        }
        if (rows.isEmpty()) {
            val hint = when {
                searching -> R.string.palette_searching
                query.isBlank() -> R.string.palette_library_hint
                else -> R.string.palette_no_results
            }
            BasicText(stringResource(hint), style = TextStyle(color = colors.secondaryText, fontSize = 15.sp), modifier = Modifier.fillMaxWidth().padding(24.dp))
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 6.dp, bottom = contentPadding.calculateBottomPadding())) {
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
                        subtitle = listOfNotNull(row.song.artist.ifEmpty { null }, row.song.album).joinToString(" · ").ifEmpty { null },
                        leading = { Cover(row.song.coverUrl, size = 40.dp) },
                        detail = row.song.duration?.let { formatDuration((it * 1000).toLong()) },
                        onClick = { run(row) },
                        onLongPress = { anchor -> menu = row.song to anchor },
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
    }
    menu?.let { (song, anchor) ->
        val done = song.trackId in added
        ContextMenu(
            anchor = anchor,
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

@Composable
private fun ResultRow(
    title: String,
    subtitle: String?,
    leading: @Composable () -> Unit,
    detail: String?,
    onClick: () -> Unit,
    trailing: (@Composable () -> Unit)? = null,
    onLongPress: ((IntOffset) -> Unit)? = null,
) {
    val colors = LocalAppColors.current
    var origin by remember { mutableStateOf(IntOffset.Zero) }
    Row(
        Modifier
            .fillMaxWidth()
            .onGloballyPositioned { origin = it.positionInWindow().let { p -> IntOffset(p.x.roundToInt(), p.y.roundToInt()) } }
            .semantics { role = Role.Button }
            .combinedClickable(
                onClick = onClick,
                onLongClickLabel = onLongPress?.let { stringResource(R.string.song_actions) },
                onLongClick = onLongPress?.let { open -> { open(origin + IntOffset(MENU_INSET, MENU_INSET)) } },
            )
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
