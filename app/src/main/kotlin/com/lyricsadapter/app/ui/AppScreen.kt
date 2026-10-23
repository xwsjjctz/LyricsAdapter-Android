package com.lyricsadapter.app.ui

import android.content.Context
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lyricsadapter.app.AppContainer
import com.lyricsadapter.app.R
import com.lyricsadapter.app.data.CloudStatus
import com.lyricsadapter.app.data.ColorMode
import com.lyricsadapter.app.data.EditResult
import com.lyricsadapter.app.data.FocusSettings
import com.lyricsadapter.app.data.SlotId
import com.lyricsadapter.app.ui.editor.MetadataEditor
import com.lyricsadapter.app.ui.focus.FocusScreen
import com.lyricsadapter.app.ui.palette.CommandPalette
import com.lyricsadapter.app.ui.palette.PaletteMode
import com.lyricsadapter.app.ui.settings.SettingsPage
import com.lyricsadapter.app.ui.settings.SettingsScreen
import kotlinx.coroutines.launch

/** Room under the list for the floating mini player. */
private val MINI_PLAYER_SPACE = 84.dp

/**
 * The app's one screen: the active library slot, the mini player, and the
 * pages that open over them (palette, metadata editor, focus mode). Back
 * closes the topmost one.
 */
@Composable
fun AppScreen(container: AppContainer) {
    val library: LibraryViewModel = viewModel { LibraryViewModel(container) }
    val player = container.player
    val settings = container.settings
    val localTracks by library.tracks.collectAsStateWithLifecycle()
    val cloudTracks by container.cloud.tracks.collectAsStateWithLifecycle()
    val cloudStatus by container.cloud.status.collectAsStateWithLifecycle()
    val playerState by player.state.collectAsStateWithLifecycle()
    val slot by settings.activeSlot.value.collectAsStateWithLifecycle()
    val volume by container.volume.level.collectAsStateWithLifecycle(initialValue = container.volume.current())
    val colors = LocalAppColors.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    var focusOpen by rememberSaveable { mutableStateOf(false) }
    var palette by rememberSaveable { mutableStateOf<PaletteMode?>(null) }
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    var settingsPage by rememberSaveable { mutableStateOf<SettingsPage?>(null) }
    val focusSettings = FocusSettings(
        backgroundBlur = settings.focusBackgroundBlur.value.collectAsStateWithLifecycle().value,
        fontSize = settings.focusFontSize.value.collectAsStateWithLifecycle().value,
        lineSpacing = settings.focusLineSpacing.value.collectAsStateWithLifecycle().value,
        inactiveBlur = settings.focusInactiveBlur.value.collectAsStateWithLifecycle().value,
    )
    var selection by remember { mutableStateOf<Set<String>?>(null) }

    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> library.import(uris) }
    val importFiles = { pickFiles.launch(arrayOf("audio/*")) }
    LaunchedEffect(library) { library.events.collect { notice -> context.showNotice(notice) } }

    val cloudList = remember(cloudTracks) { cloudTracks.map { it.toTrack() } }
    val slotTracks = when (slot) {
        SlotId.Local -> localTracks
        SlotId.Cloud -> cloudList
        SlotId.Online, SlotId.Playlist -> emptyList()
    }
    val slotCounts = mapOf(SlotId.Local to localTracks.size, SlotId.Cloud to cloudList.size, SlotId.Online to 0, SlotId.Playlist to 0)
    val current = localTracks.firstOrNull { it.id == playerState.currentId } ?: cloudList.firstOrNull { it.id == playerState.currentId }
    // The cloud is listed again the first time it is shown in a session, as desktop does on connecting.
    var cloudRefreshed by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(slot, cloudStatus) {
        if (slot == SlotId.Cloud && cloudStatus == CloudStatus.Idle && !cloudRefreshed) {
            cloudRefreshed = true
            container.cloud.refresh()
        }
    }
    val editing = localTracks.firstOrNull { it.id == editingId }
    val switchSlot = { target: SlotId ->
        selection = null
        settings.activeSlot.set(target)
    }

    BackHandler(enabled = selection != null) { selection = null }
    BackHandler(enabled = focusOpen) { focusOpen = false }
    BackHandler(enabled = palette != null) { palette = null }
    BackHandler(enabled = editingId != null) { editingId = null }
    SystemBarIcons(dark = !colors.isDark)

    val commands = appCommands(
        CommandDeps(
            slot = slot,
            slotCounts = slotCounts,
            hasTracks = slotTracks.isNotEmpty(),
            hasCurrent = current != null,
            mode = playerState.mode,
            muted = volume == 0f,
            dark = colors.isDark,
            switchSlot = switchSlot,
            importFiles = importFiles,
            selectMultiple = { selection = emptySet() },
            locateCurrent = {
                switchSlot(SlotId.Local)
                val index = localTracks.indexOf(current)
                if (index >= 0) scope.launch { listState.animateScrollToItem(index) }
            },
            openFocus = { focusOpen = true },
            cyclePlaybackMode = player::cyclePlaybackMode,
            toggleMute = container.volume::toggleMute,
            playAll = { player.play(slotTracks, 0) },
            shuffleAll = { player.playShuffled(slotTracks) },
            toggleColorMode = { settings.colorMode.set(if (colors.isDark) ColorMode.Light else ColorMode.Dark) },
            openSettings = { settingsPage = it },
            refreshCloud = if (cloudStatus != CloudStatus.NotConnected) container.cloud::refresh else null,
        ),
    )

    Box(Modifier.fillMaxSize()) {
        val insets = WindowInsets.safeDrawing.asPaddingValues()
        val direction = LocalLayoutDirection.current
        LibraryScreen(
            slot = slot,
            tracks = slotTracks,
            slotCounts = slotCounts,
            currentId = playerState.currentId,
            listState = listState,
            contentPadding = PaddingValues(
                start = insets.calculateStartPadding(direction),
                end = insets.calculateEndPadding(direction),
                top = insets.calculateTopPadding(),
                bottom = insets.calculateBottomPadding() + if (current != null) MINI_PLAYER_SPACE else 0.dp,
            ),
            selection = selection,
            onSelectionChange = { selection = it },
            actions = LibraryActions(
                onPlay = { index -> player.play(slotTracks, index) },
                onEdit = { track -> editingId = track.id },
                onRemove = library::remove,
                onSwitchSlot = switchSlot,
                onSearch = { palette = PaletteMode.Library },
            ),
            empty = emptyContent(
                slot = slot,
                cloudStatus = cloudStatus,
                importFiles = importFiles,
                openCloudSettings = { settingsPage = SettingsPage.Cloud },
                refreshCloud = container.cloud::refresh,
                search = { palette = PaletteMode.Library },
            ),
            status = if (slot == SlotId.Cloud && cloudStatus == CloudStatus.Loading) stringResource(R.string.cloud_loading) else null,
        )
        if (current != null) {
            MiniPlayer(
                track = current,
                isPlaying = playerState.isPlaying,
                onTogglePlay = player::togglePlay,
                onNext = player::next,
                onOpen = { focusOpen = true },
                modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding(),
            )
        }
        AnimatedVisibility(visible = focusOpen && current != null, enter = slideInVertically { it }, exit = slideOutVertically { it }) {
            if (current != null) FocusScreen(current, playerState, player, container.volume, focusSettings, onClose = { focusOpen = false })
        }
        AnimatedVisibility(visible = palette != null, enter = fadeIn(), exit = fadeOut()) {
            val mode = palette
            if (mode != null) {
                CommandPalette(
                    tracks = localTracks,
                    commands = commands,
                    initialMode = mode,
                    onPlayTrack = { track ->
                        switchSlot(SlotId.Local)
                        player.play(localTracks, localTracks.indexOf(track).coerceAtLeast(0))
                    },
                    onClose = { palette = null },
                )
            }
        }
        AnimatedVisibility(visible = settingsPage != null, enter = slideInHorizontally { it }, exit = slideOutHorizontally { it }) {
            val page = settingsPage
            if (page != null) SettingsScreen(settings, page, onClose = { settingsPage = null })
        }
        AnimatedVisibility(visible = editing != null, enter = slideInVertically { it }, exit = slideOutVertically { it }) {
            if (editing != null) MetadataEditor(editing, onSave = { edit, done -> library.edit(editing, edit, done) }, onClose = { editingId = null })
        }
    }
}

/** What each slot says while it has no songs, and what fills it. */
@Composable
private fun emptyContent(
    slot: SlotId,
    cloudStatus: CloudStatus,
    importFiles: () -> Unit,
    openCloudSettings: () -> Unit,
    refreshCloud: () -> Unit,
    search: () -> Unit,
): EmptyContent = when (slot) {
    SlotId.Local -> EmptyContent(stringResource(R.string.empty_local), stringResource(R.string.import_files), importFiles)
    SlotId.Cloud -> when (cloudStatus) {
        CloudStatus.NotConnected -> EmptyContent(stringResource(R.string.empty_cloud), stringResource(R.string.open_settings), openCloudSettings)
        CloudStatus.Loading -> EmptyContent(stringResource(R.string.cloud_loading), null, {})
        CloudStatus.Idle -> EmptyContent(stringResource(R.string.cloud_empty), stringResource(R.string.cloud_refresh), refreshCloud)
        is CloudStatus.Failed -> EmptyContent(stringResource(R.string.cloud_failed, cloudStatus.message), stringResource(R.string.retry), refreshCloud)
    }
    SlotId.Online -> EmptyContent(stringResource(R.string.empty_online), stringResource(R.string.search), search)
    SlotId.Playlist -> EmptyContent(stringResource(R.string.empty_playlist), stringResource(R.string.search), search)
}

private fun Context.showNotice(notice: LibraryNotice) {
    val message = when (notice) {
        is LibraryNotice.Imported -> buildString {
            append(resources.getQuantityString(R.plurals.import_done, notice.result.imported, notice.result.imported))
            if (notice.result.failed.isNotEmpty()) {
                append("，")
                append(resources.getQuantityString(R.plurals.import_failed, notice.result.failed.size, notice.result.failed.size))
            }
        }
        is LibraryNotice.Removed -> resources.getQuantityString(R.plurals.removed_count, notice.count, notice.count)
        is LibraryNotice.Edited -> when (val result = notice.result) {
            EditResult.Saved -> getString(R.string.metadata_saved)
            is EditResult.SavedWithoutWordLyrics -> getString(R.string.metadata_saved_without_word_lyrics)
            // The editor shows these itself and stays open.
            EditResult.NoWriteAccess, is EditResult.Failed -> return
        }
    }
    Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
}

/** Dark status and navigation bar icons on light pages; focus mode overrides this while open. */
@Composable
private fun SystemBarIcons(dark: Boolean) {
    val window = LocalActivity.current?.window ?: return
    val view = LocalView.current
    LaunchedEffect(window, dark) {
        val controller = WindowCompat.getInsetsController(window, view)
        controller.isAppearanceLightStatusBars = dark
        controller.isAppearanceLightNavigationBars = dark
    }
}
