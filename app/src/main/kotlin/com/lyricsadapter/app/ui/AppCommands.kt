package com.lyricsadapter.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.lyricsadapter.app.R
import com.lyricsadapter.app.data.SlotId
import com.lyricsadapter.app.playback.PlaybackMode
import com.lyricsadapter.app.ui.palette.CommandGroup
import com.lyricsadapter.app.ui.palette.PaletteCommand
import com.lyricsadapter.app.ui.settings.SettingsPage

/** What the commands read and do; the screen supplies it. */
class CommandDeps(
    val slot: SlotId,
    val slotCounts: Map<SlotId, Int>,
    val hasTracks: Boolean,
    val hasCurrent: Boolean,
    val mode: PlaybackMode,
    val muted: Boolean,
    val dark: Boolean,
    val switchSlot: (SlotId) -> Unit,
    val importFiles: () -> Unit,
    val selectMultiple: () -> Unit,
    val locateCurrent: () -> Unit,
    val openFocus: () -> Unit,
    val cyclePlaybackMode: () -> Unit,
    val toggleMute: () -> Unit,
    val playAll: () -> Unit,
    val shuffleAll: () -> Unit,
    val toggleColorMode: () -> Unit,
    val openSettings: (SettingsPage) -> Unit,
    /** Null while no WebDAV library is connected. */
    val refreshCloud: (() -> Unit)?,
)

/**
 * The palette's command list, following desktop's `buildAppCommands`: ids,
 * icons and groups match, and commands that cannot run right now are left
 * out rather than shown disabled.
 */
@Composable
fun appCommands(deps: CommandDeps): List<PaletteCommand> {
    val switchTitle = stringResource(R.string.command_switch_source)
    val sources = listOf(SlotId.Local, SlotId.Cloud, SlotId.Online).map { target ->
        val name = stringResource(target.titleRes)
        PaletteCommand(
            id = "source.${target.name.lowercase()}",
            group = CommandGroup.Library,
            title = switchTitle.format(name),
            icon = target.iconRes,
            detail = (deps.slotCounts[target] ?: 0).toString(),
            keywords = listOf(target.name, name),
            run = { deps.switchSlot(target) },
        )
    }
    val modeLabel = stringResource(
        when (deps.mode) {
            PlaybackMode.Order -> R.string.mode_order
            PlaybackMode.Shuffle -> R.string.mode_shuffle
            PlaybackMode.RepeatOne -> R.string.mode_repeat_one
        },
    )
    return buildList {
        addAll(sources)
        deps.refreshCloud?.let { refresh ->
            add(PaletteCommand("library.refreshCloud", CommandGroup.Library, stringResource(R.string.cloud_refresh), R.drawable.ui_sync, keywords = listOf("webdav", "cloud"), run = refresh))
        }
        add(PaletteCommand("library.import", CommandGroup.Library, stringResource(R.string.import_files), R.drawable.ui_library_add, keywords = listOf("import"), run = deps.importFiles))
        if (deps.hasTracks) {
            add(PaletteCommand("library.select", CommandGroup.Library, stringResource(R.string.select_multiple), R.drawable.ui_checklist, run = deps.selectMultiple))
            add(PaletteCommand("playback.playAll", CommandGroup.Playback, stringResource(R.string.command_play_all), R.drawable.ui_play_arrow, run = deps.playAll))
            add(PaletteCommand("playback.shuffleAll", CommandGroup.Playback, stringResource(R.string.command_shuffle_all), R.drawable.ui_shuffle, run = deps.shuffleAll))
        }
        if (deps.hasCurrent) {
            add(PaletteCommand("playback.locate", CommandGroup.Playback, stringResource(R.string.command_locate), R.drawable.ui_my_location, run = deps.locateCurrent))
            add(PaletteCommand("playback.focusMode", CommandGroup.Playback, stringResource(R.string.command_focus_mode), R.drawable.ui_fullscreen, keywords = listOf("focus", "lyrics"), run = deps.openFocus))
        }
        add(PaletteCommand("playback.mode", CommandGroup.Playback, stringResource(R.string.command_playback_mode), R.drawable.ui_repeat, detail = modeLabel, run = deps.cyclePlaybackMode))
        add(
            PaletteCommand(
                "playback.mute", CommandGroup.Playback, stringResource(R.string.command_mute), R.drawable.ui_volume_off,
                detail = stringResource(if (deps.muted) R.string.command_state_on else R.string.command_state_off), run = deps.toggleMute,
            ),
        )
        add(
            PaletteCommand(
                "appearance.colorMode", CommandGroup.Appearance, stringResource(R.string.command_color_mode),
                if (deps.dark) R.drawable.ui_light_mode else R.drawable.ui_dark_mode,
                detail = stringResource(if (deps.dark) R.string.state_dark else R.string.state_light),
                keywords = listOf("dark", "light", "theme"), run = deps.toggleColorMode,
            ),
        )
        val settingsTitle = stringResource(R.string.settings_title)
        add(PaletteCommand("settings.open", CommandGroup.Settings, settingsTitle, R.drawable.ui_settings, keywords = listOf("settings")) { deps.openSettings(SettingsPage.Root) })
        listOf(
            Triple(SettingsPage.General, R.drawable.ui_settings, "general"),
            Triple(SettingsPage.Focus, R.drawable.ui_tune, "focus"),
            Triple(SettingsPage.Cloud, R.drawable.ui_cloud, "webdav cloud"),
        ).forEach { (page, icon, keyword) ->
            val title = "$settingsTitle › ${stringResource(page.titleRes)}"
            add(PaletteCommand("settings.${page.name.lowercase()}", CommandGroup.Settings, title, icon, keywords = listOf(keyword)) { deps.openSettings(page) })
        }
        add(PaletteCommand("settings.about", CommandGroup.Settings, stringResource(R.string.settings_about), R.drawable.ui_info, keywords = listOf("about")) { deps.openSettings(SettingsPage.About) })
    }
}
