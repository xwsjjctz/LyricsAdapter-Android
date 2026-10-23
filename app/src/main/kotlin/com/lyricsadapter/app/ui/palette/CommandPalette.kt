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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lyricsadapter.app.R
import com.lyricsadapter.app.search.TextMatcher
import com.lyricsadapter.app.ui.Cover
import com.lyricsadapter.app.ui.Icon
import com.lyricsadapter.app.ui.LocalAppColors
import com.lyricsadapter.app.ui.TextAction
import com.lyricsadapter.library.Track

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

private const val TRACK_LIMIT = 50

/**
 * The phone's ⌘K, the one entry to everything but playback: a search field
 * at the top with the keyboard up, the music library or the command list
 * under it. Picking a row runs it and closes the palette.
 */
@Composable
fun CommandPalette(
    tracks: List<Track>,
    commands: List<PaletteCommand>,
    initialMode: PaletteMode,
    onPlayTrack: (Track) -> Unit,
    onClose: () -> Unit,
) {
    val colors = LocalAppColors.current
    var mode by rememberSaveable { mutableStateOf(initialMode) }
    var query by rememberSaveable { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }

    val rows: List<PaletteRow> = remember(mode, query, tracks, commands) {
        when (mode) {
            PaletteMode.Library -> libraryRows(tracks, query)
            PaletteMode.Commands -> commandRows(commands, query)
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
                    keyboardActions = KeyboardActions(onGo = { rows.firstOrNull { it is PaletteRow.Runnable }?.let { (it as PaletteRow.Runnable).run(onPlayTrack, onClose) } }),
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
                mode == PaletteMode.Library && query.isBlank() -> R.string.palette_library_hint
                else -> R.string.palette_no_results
            }
            BasicText(
                stringResource(hint),
                style = TextStyle(color = colors.secondaryText, fontSize = 15.sp),
                modifier = Modifier.fillMaxWidth().padding(24.dp),
            )
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(rows, key = PaletteRow::key) { row ->
                when (row) {
                    is PaletteRow.Section -> BasicText(
                        stringResource(row.titleRes),
                        style = TextStyle(color = colors.secondaryText, fontSize = 13.sp, fontWeight = FontWeight.SemiBold),
                        modifier = Modifier.padding(start = 20.dp, top = 14.dp, bottom = 4.dp).semantics { heading() },
                    )
                    is PaletteRow.TrackRow -> ResultRow(
                        title = row.track.title,
                        subtitle = listOfNotNull(row.track.artist, row.track.album).joinToString(" · ").ifEmpty { null },
                        leading = { Cover(row.track.coverPath, size = 40.dp) },
                        detail = null,
                        onClick = { row.run(onPlayTrack, onClose) },
                    )
                    is PaletteRow.CommandRow -> ResultRow(
                        title = row.command.title,
                        subtitle = null,
                        leading = {
                            Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) { Icon(row.command.icon, colors.accent) }
                        },
                        detail = row.command.detail,
                        onClick = { row.run(onPlayTrack, onClose) },
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
private fun ResultRow(title: String, subtitle: String?, leading: @Composable () -> Unit, detail: String?, onClick: () -> Unit) {
    val colors = LocalAppColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .semantics { role = Role.Button }
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
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
    }
}

/** A line in the result list: a group heading, or something to run. */
private sealed interface PaletteRow {
    val key: String

    data class Section(val titleRes: Int) : PaletteRow {
        override val key get() = "section:$titleRes"
    }

    sealed interface Runnable : PaletteRow {
        fun run(onPlayTrack: (Track) -> Unit, onClose: () -> Unit)
    }

    data class TrackRow(val track: Track) : Runnable {
        override val key get() = "track:${track.id}"
        override fun run(onPlayTrack: (Track) -> Unit, onClose: () -> Unit) {
            onClose()
            onPlayTrack(track)
        }
    }

    data class CommandRow(val command: PaletteCommand) : Runnable {
        override val key get() = "command:${command.id}"
        override fun run(onPlayTrack: (Track) -> Unit, onClose: () -> Unit) {
            onClose()
            command.run()
        }
    }
}

private fun libraryRows(tracks: List<Track>, query: String): List<PaletteRow> {
    if (query.isBlank()) return emptyList()
    val hits = tracks.filter { track ->
        TextMatcher.matches(listOfNotNull(track.title, track.artist, track.album).joinToString(" "), query)
    }
    if (hits.isEmpty()) return emptyList()
    return listOf(PaletteRow.Section(R.string.slot_local)) + hits.take(TRACK_LIMIT).map(PaletteRow::TrackRow)
}

private fun commandRows(commands: List<PaletteCommand>, query: String): List<PaletteRow> {
    val hits = if (query.isBlank()) commands else commands.filter { command ->
        TextMatcher.matches(command.title, query) || command.keywords.any { TextMatcher.matches(it, query) }
    }
    return hits.groupBy(PaletteCommand::group).toSortedMap().flatMap { (group, list) ->
        listOf(PaletteRow.Section(group.titleRes)) + list.map(PaletteRow::CommandRow)
    }
}
