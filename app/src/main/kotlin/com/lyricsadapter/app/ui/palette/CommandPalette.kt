package com.lyricsadapter.app.ui.palette

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lyricsadapter.app.R
import com.lyricsadapter.app.plugins.OnlinePlaylist
import com.lyricsadapter.app.plugins.OnlineSong
import com.lyricsadapter.app.search.TextMatcher
import com.lyricsadapter.app.ui.Cover
import com.lyricsadapter.app.ui.Icon
import com.lyricsadapter.app.ui.IconButton
import com.lyricsadapter.app.ui.LocalAppColors
import com.lyricsadapter.app.ui.TextAction
import com.lyricsadapter.app.ui.formatDuration
import com.lyricsadapter.library.Track
import kotlinx.coroutines.delay

/** Desktop's palette groups, in the order they are listed. */
enum class CommandGroup(val titleRes: Int) {
    Library(R.string.group_library),
    Playback(R.string.group_playback),
    Appearance(R.string.group_appearance),
    Settings(R.string.group_settings),
}

/** One thing the app can do from the palette. [detail] shows a count or the current state. */
class PaletteCommand(
    val id: String,
    val group: CommandGroup,
    val title: String,
    val icon: Int,
    val detail: String? = null,
    val keywords: List<String> = emptyList(),
    val run: () -> Unit,
)

/** The palette searches music, or the app's commands, as desktop's two modes. */
enum class PaletteMode { Library, Commands }

/** One music source's answer to a search; [error] when it failed. */
class OnlineResults(val sourceName: String, val songs: List<OnlineSong>, val error: String?)

/** Where the palette's music search looks and what its rows do. */
class PaletteMusic(
    val localTracks: List<Track>,
    val cloudTracks: List<Track>,
    val searchOnline: suspend (String) -> List<OnlineResults>,
    val loadPlaylists: suspend () -> List<OnlinePlaylist>,
    val playTrack: (Track) -> Unit,
    val playOnline: (OnlineSong) -> Unit,
    val addOnline: (OnlineSong) -> Unit,
    val openPlaylist: (OnlinePlaylist) -> Unit,
)

private const val TRACK_LIMIT = 6
private const val ONLINE_LIMIT = 8
private const val SEARCH_DELAY_MS = 400L

/**
 * The phone's ⌘K, the one entry to everything but playback: a search field
 * at the top with the keyboard up, the music library or the command list
 * under it. Music search lists local and cloud matches, each music source's
 * results (searched once typing pauses) and the account playlists, as
 * desktop's global search does. Picking a row runs it and closes the palette.
 */
@Composable
fun CommandPalette(music: PaletteMusic, commands: List<PaletteCommand>, initialMode: PaletteMode, onClose: () -> Unit) {
    val colors = LocalAppColors.current
    var mode by rememberSaveable { mutableStateOf(initialMode) }
    var query by rememberSaveable { mutableStateOf("") }
    var online by remember { mutableStateOf<List<OnlineResults>?>(null) }
    var playlists by remember { mutableStateOf<List<OnlinePlaylist>>(emptyList()) }
    var added by remember { mutableStateOf(emptySet<String>()) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    LaunchedEffect(Unit) { playlists = music.loadPlaylists() }
    LaunchedEffect(mode, query) {
        online = null
        if (mode != PaletteMode.Library || query.isBlank()) return@LaunchedEffect
        delay(SEARCH_DELAY_MS)
        online = music.searchOnline(query.trim())
    }

    val sections = PaletteSections(
        local = stringResource(R.string.slot_local),
        cloud = stringResource(R.string.slot_cloud),
        playlists = stringResource(R.string.slot_playlist),
        commandTitles = CommandGroup.entries.associateWith { stringResource(it.titleRes) },
    )
    val rows = remember(mode, query, music.localTracks, music.cloudTracks, online, playlists, commands) {
        when (mode) {
            PaletteMode.Library -> libraryRows(music, query, online, playlists, sections)
            PaletteMode.Commands -> commandRows(commands, query, sections)
        }
    }
    val searching = mode == PaletteMode.Library && query.isNotBlank() && online == null
    val run = { row: PaletteRow.Runnable ->
        onClose()
        when (row) {
            is PaletteRow.TrackRow -> music.playTrack(row.track)
            is PaletteRow.OnlineRow -> music.playOnline(row.song)
            is PaletteRow.PlaylistRow -> music.openPlaylist(row.playlist)
            is PaletteRow.CommandRow -> row.command.run()
        }
    }

    Column(Modifier.fillMaxSize().background(colors.background).safeDrawingPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(colors.surface).padding(horizontal = 10.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(R.drawable.ui_search, colors.secondaryText, size = 20.dp)
                Spacer(Modifier.width(6.dp))
                val placeholder = stringResource(if (mode == PaletteMode.Library) R.string.palette_placeholder_library else R.string.palette_placeholder_commands)
                BasicTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    textStyle = TextStyle(color = colors.text, fontSize = 17.sp),
                    cursorBrush = SolidColor(colors.accent),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { rows.firstNotNullOfOrNull { it as? PaletteRow.Runnable }?.let(run) }),
                    modifier = Modifier.weight(1f).focusRequester(focus),
                    decorationBox = { field ->
                        Box {
                            if (query.isEmpty()) BasicText(placeholder, style = TextStyle(color = colors.secondaryText, fontSize = 17.sp), maxLines = 1)
                            field()
                        }
                    },
                )
            }
            TextAction(stringResource(R.string.cancel), onClick = onClose)
        }
        ModeSwitch(mode, onChange = { mode = it }, Modifier.padding(horizontal = 16.dp, vertical = 10.dp))
        if (rows.isEmpty()) {
            val hint = when {
                searching -> R.string.palette_searching
                mode == PaletteMode.Library && query.isBlank() -> R.string.palette_library_hint
                else -> R.string.palette_no_results
            }
            BasicText(stringResource(hint), style = TextStyle(color = colors.secondaryText, fontSize = 15.sp), modifier = Modifier.fillMaxWidth().padding(24.dp))
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(rows, key = PaletteRow::key) { row ->
                when (row) {
                    is PaletteRow.Section -> BasicText(
                        row.title,
                        style = TextStyle(color = colors.secondaryText, fontSize = 13.sp, fontWeight = FontWeight.SemiBold),
                        modifier = Modifier.padding(start = 20.dp, top = 14.dp, bottom = 4.dp).semantics { heading() },
                    )
                    is PaletteRow.Note -> BasicText(
                        row.text ?: stringResource(R.string.palette_searching),
                        style = TextStyle(color = colors.secondaryText, fontSize = 14.sp),
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                    )
                    is PaletteRow.TrackRow -> ResultRow(
                        title = row.track.title,
                        subtitle = listOfNotNull(row.track.artist, row.track.album).joinToString(" · ").ifEmpty { null },
                        leading = { Cover(row.track.coverPath, size = 40.dp) },
                        detail = null,
                        onClick = { run(row) },
                    )
                    is PaletteRow.OnlineRow -> ResultRow(
                        title = row.song.title,
                        subtitle = listOfNotNull(row.song.artist.ifEmpty { null }, row.song.album).joinToString(" · ").ifEmpty { null },
                        leading = { Cover(row.song.coverUrl, size = 40.dp) },
                        detail = row.song.duration?.let { formatDuration((it * 1000).toLong()) },
                        onClick = { run(row) },
                        trailing = {
                            val done = row.song.trackId in added
                            IconButton(
                                if (done) R.drawable.ui_check else R.drawable.ic_add,
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
                    is PaletteRow.PlaylistRow -> ResultRow(
                        title = row.playlist.name,
                        subtitle = null,
                        leading = { Cover(row.playlist.coverUrl, size = 40.dp) },
                        detail = row.playlist.songCount.toString(),
                        onClick = { run(row) },
                    )
                    is PaletteRow.CommandRow -> ResultRow(
                        title = row.command.title,
                        subtitle = null,
                        leading = { Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) { Icon(row.command.icon, colors.accent) } },
                        detail = row.command.detail,
                        onClick = { run(row) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ModeSwitch(mode: PaletteMode, onChange: (PaletteMode) -> Unit, modifier: Modifier = Modifier) {
    val colors = LocalAppColors.current
    Row(modifier.fillMaxWidth().clip(RoundedCornerShape(9.dp)).background(colors.surface).padding(2.dp)) {
        PaletteMode.entries.forEach { option ->
            val chosen = option == mode
            BasicText(
                stringResource(if (option == PaletteMode.Library) R.string.palette_mode_library else R.string.palette_mode_commands),
                style = TextStyle(
                    color = colors.text,
                    fontSize = 14.sp,
                    fontWeight = if (chosen) FontWeight.SemiBold else FontWeight.Normal,
                    textAlign = TextAlign.Center,
                ),
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(7.dp))
                    .background(if (chosen) colors.elevated else colors.surface)
                    .semantics {
                        role = Role.Tab
                        selected = chosen
                    }
                    .clickable { onChange(option) }
                    .padding(vertical = 7.dp),
            )
        }
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
) {
    val colors = LocalAppColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .semantics { role = Role.Button }
            .clickable(onClick = onClick)
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

/** Section titles, resolved once per composition so the row builders stay plain functions. */
private class PaletteSections(val local: String, val cloud: String, val playlists: String, val commandTitles: Map<CommandGroup, String>)

/** A line in the result list: a heading, a note, or something to run. */
private sealed interface PaletteRow {
    val key: String

    data class Section(val title: String) : PaletteRow {
        override val key get() = "section:$title"
    }

    /** A source's state under its heading; null text means "searching". */
    data class Note(val owner: String, val text: String?) : PaletteRow {
        override val key get() = "note:$owner"
    }

    sealed interface Runnable : PaletteRow

    data class TrackRow(val track: Track) : Runnable {
        override val key get() = "track:${track.id}"
    }

    data class OnlineRow(val song: OnlineSong) : Runnable {
        override val key get() = "online:${song.trackId}"
    }

    data class PlaylistRow(val playlist: OnlinePlaylist) : Runnable {
        override val key get() = "playlist:${playlist.source}:${playlist.id}"
    }

    data class CommandRow(val command: PaletteCommand) : Runnable {
        override val key get() = "command:${command.id}"
    }
}

private fun libraryRows(
    music: PaletteMusic,
    query: String,
    online: List<OnlineResults>?,
    playlists: List<OnlinePlaylist>,
    sections: PaletteSections,
): List<PaletteRow> = buildList {
    if (query.isNotBlank()) {
        fun matches(track: Track) = TextMatcher.matches(listOfNotNull(track.title, track.artist, track.album).joinToString(" "), query)
        music.localTracks.filter(::matches).take(TRACK_LIMIT).takeIf { it.isNotEmpty() }?.let { hits ->
            add(PaletteRow.Section(sections.local))
            hits.forEach { add(PaletteRow.TrackRow(it)) }
        }
        music.cloudTracks.filter(::matches).take(TRACK_LIMIT).takeIf { it.isNotEmpty() }?.let { hits ->
            add(PaletteRow.Section(sections.cloud))
            hits.forEach { add(PaletteRow.TrackRow(it)) }
        }
        online?.forEach { results ->
            if (results.songs.isEmpty() && results.error == null) return@forEach
            add(PaletteRow.Section(results.sourceName))
            if (results.error != null) add(PaletteRow.Note(results.sourceName, results.error))
            results.songs.take(ONLINE_LIMIT).forEach { add(PaletteRow.OnlineRow(it)) }
        }
        if (online == null && isNotEmpty()) add(PaletteRow.Note("online", null))
    }
    val shownPlaylists = if (query.isBlank()) playlists else playlists.filter { TextMatcher.matches(it.name, query) }
    if (shownPlaylists.isNotEmpty()) {
        add(PaletteRow.Section(sections.playlists))
        shownPlaylists.forEach { add(PaletteRow.PlaylistRow(it)) }
    }
}

private fun commandRows(commands: List<PaletteCommand>, query: String, sections: PaletteSections): List<PaletteRow> {
    val hits = if (query.isBlank()) commands else commands.filter { command ->
        TextMatcher.matches(command.title, query) || command.keywords.any { TextMatcher.matches(it, query) }
    }
    return hits.groupBy(PaletteCommand::group).toSortedMap().flatMap { (group, list) ->
        listOf(PaletteRow.Section(sections.commandTitles.getValue(group))) + list.map(PaletteRow::CommandRow)
    }
}
