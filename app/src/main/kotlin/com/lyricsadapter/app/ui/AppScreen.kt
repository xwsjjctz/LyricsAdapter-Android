package com.lyricsadapter.app.ui

import android.content.Context
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.lyricsadapter.app.AppContainer
import com.lyricsadapter.app.R
import com.lyricsadapter.app.data.CloudStatus
import com.lyricsadapter.app.data.EditResult
import com.lyricsadapter.app.data.FocusSettings
import com.lyricsadapter.app.data.LibraryLayout
import com.lyricsadapter.app.data.SlotId
import com.lyricsadapter.app.plugins.DownloadOutcome
import com.lyricsadapter.app.plugins.DownloadQuality
import com.lyricsadapter.app.plugins.MusicSourceException
import com.lyricsadapter.app.plugins.OnlinePlaylist
import com.lyricsadapter.app.ui.editor.MetadataEditor
import com.lyricsadapter.app.ui.focus.FocusScreen
import com.lyricsadapter.app.ui.focus.rememberFocusTranslation
import com.lyricsadapter.app.ui.glass.GlassRoundButton
import com.lyricsadapter.app.ui.glass.GlassTab
import com.lyricsadapter.app.ui.glass.GlassTabBar
import com.lyricsadapter.app.ui.search.OnlineResults
import com.lyricsadapter.app.ui.search.SearchMusic
import com.lyricsadapter.app.ui.search.SearchScreen
import com.lyricsadapter.app.ui.settings.SettingsPage
import com.lyricsadapter.app.ui.settings.SettingsScreen
import com.lyricsadapter.library.wall.WallLayout
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/** The app's three tabs, as Apple Music's bottom bar holds them. */
private enum class MainTab { Library, Search, Settings }

/**
 * The app's one screen: the library, search and settings tabs under Apple
 * Music's floating Liquid Glass bars (the mini player over the tab bar and
 * its search button), and the pages that open over them (metadata editor,
 * focus mode). Back closes the topmost page, then returns to the library.
 */
@Composable
fun AppScreen(container: AppContainer) {
    val library: LibraryViewModel = viewModel { LibraryViewModel(container) }
    val player = container.player
    val settings = container.settings
    val localTracks by library.tracks.collectAsStateWithLifecycle()
    val cloudTracks by container.cloud.tracks.collectAsStateWithLifecycle()
    val cloudStatus by container.cloud.status.collectAsStateWithLifecycle()
    val onlineEntries by container.online.entries.collectAsStateWithLifecycle()
    val downloads by container.downloads.progress.collectAsStateWithLifecycle()
    val openedPlaylist by container.online.playlist.collectAsStateWithLifecycle()
    val plugins by container.plugins.plugins.collectAsStateWithLifecycle()
    val hiddenPlaylists by settings.hiddenPlaylists.value.collectAsStateWithLifecycle()
    val playerState by player.state.collectAsStateWithLifecycle()
    val slot by settings.activeSlot.value.collectAsStateWithLifecycle()
    val layout by settings.libraryLayout.value.collectAsStateWithLifecycle()
    val colors = LocalAppColors.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val wallColumns = WallLayout.columnsFor(LocalConfiguration.current.screenWidthDp.toFloat())

    var tab by rememberSaveable { mutableStateOf(MainTab.Library) }
    // The bars collapse as a page scrolls down and come back as it scrolls up, as iOS 26's do.
    var barsCollapsed by remember { mutableStateOf(false) }
    LaunchedEffect(tab) { barsCollapsed = false }
    val collapseOnScroll = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (available.y < -COLLAPSE_SCROLL_PX) barsCollapsed = true
                if (available.y > COLLAPSE_SCROLL_PX) barsCollapsed = false
                return Offset.Zero
            }
        }
    }
    // The settings page to show, and a count that reopens it when asked for again.
    var settingsPage by rememberSaveable { mutableStateOf(SettingsPage.Root) }
    var settingsOpened by rememberSaveable { mutableIntStateOf(0) }
    var focusOpen by rememberSaveable { mutableStateOf(false) }
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    val focusSettings = FocusSettings(
        backgroundBlur = settings.focusBackgroundBlur.value.collectAsStateWithLifecycle().value,
        fontSize = settings.focusFontSize.value.collectAsStateWithLifecycle().value,
        lineSpacing = settings.focusLineSpacing.value.collectAsStateWithLifecycle().value,
        inactiveBlur = settings.focusInactiveBlur.value.collectAsStateWithLifecycle().value,
        flowingBackground = settings.focusFlowingBackground.value.collectAsStateWithLifecycle().value,
    )
    var selection by remember { mutableStateOf<Set<String>?>(null) }

    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> library.import(uris) }
    val importFiles = { pickFiles.launch(arrayOf("audio/*")) }
    LaunchedEffect(library) { library.events.collect { notice -> context.showNotice(notice) } }
    LaunchedEffect(container.downloads) { container.downloads.results.collect { outcome -> context.showDownload(outcome) } }

    val cloudList = remember(cloudTracks) { cloudTracks.map { it.toTrack() } }
    val onlineList = remember(onlineEntries) { onlineEntries.map { it.toTrack() } }
    val playlistList = remember(openedPlaylist) { openedPlaylist?.songs.orEmpty().map { it.toTrack() } }
    val slotTracks = when (slot) {
        SlotId.Local -> localTracks
        SlotId.Cloud -> cloudList
        SlotId.Online -> onlineList
        SlotId.Playlist -> playlistList
    }
    val slotCounts = mapOf(SlotId.Local to localTracks.size, SlotId.Cloud to cloudList.size, SlotId.Online to onlineList.size, SlotId.Playlist to playlistList.size)
    val current = sequenceOf(localTracks, cloudList, onlineList, playlistList).firstNotNullOfOrNull { list -> list.firstOrNull { it.id == playerState.currentId } }
    LaunchedEffect(playerState.error) {
        playerState.error?.let { Toast.makeText(context, it, Toast.LENGTH_LONG).show() }
    }
    // Online songs get their lyrics from their source the first time they play.
    LaunchedEffect(current?.id) { current?.id?.let(container.online::ensureLyrics) }
    val hasSources = plugins.any { it.enabled }
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
    val openSettings = { page: SettingsPage ->
        settingsPage = page
        settingsOpened++
        tab = MainTab.Settings
    }
    // The account playlists, loaded when the sources or their logins change and again when they are about to be listed.
    var playlists by remember { mutableStateOf<List<OnlinePlaylist>>(emptyList()) }
    var playlistsWanted by remember { mutableIntStateOf(0) }
    LaunchedEffect(plugins, playlistsWanted) { playlists = loadPlaylists(container) }
    val playlistKey = { playlist: OnlinePlaylist -> "${playlist.source}:${playlist.id}" }
    val openPlaylist = { playlist: OnlinePlaylist ->
        scope.launch {
            try {
                container.online.open(playlist, container.sources.playlistSongs(playlist.source, playlist.id))
                switchSlot(SlotId.Playlist)
                tab = MainTab.Library
            } catch (error: MusicSourceException) {
                Toast.makeText(context, error.message, Toast.LENGTH_SHORT).show()
            }
        }
        Unit
    }
    val locateCurrent = {
        switchSlot(SlotId.Local)
        val index = localTracks.indexOf(current)
        val item = if (layout == LibraryLayout.Wall) wallBlockOf(index, localTracks.size, wallColumns) else index
        if (index >= 0) scope.launch { listState.animateScrollToItem(item) }
    }

    // Registered before the tabs' own, so a settings page goes back within settings first.
    BackHandler(enabled = tab != MainTab.Library) { tab = MainTab.Library }
    BackHandler(enabled = selection != null) { selection = null }
    BackHandler(enabled = focusOpen) { focusOpen = false }
    BackHandler(enabled = editingId != null) { editingId = null }
    SystemBarIcons(dark = !colors.isDark)

    val more = moreActions(
        slot = slot,
        hasTracks = slotTracks.isNotEmpty(),
        hasCurrent = current != null,
        cloudConnected = cloudStatus != CloudStatus.NotConnected,
        wall = layout == LibraryLayout.Wall,
        importFiles = importFiles,
        refreshCloud = container.cloud::refresh,
        playAll = { player.play(slotTracks, 0) },
        shuffleAll = { player.playShuffled(slotTracks) },
        selectMultiple = { selection = emptySet() },
        locateCurrent = { locateCurrent() },
        toggleLayout = { settings.libraryLayout.set(if (layout == LibraryLayout.Wall) LibraryLayout.List else LibraryLayout.Wall) },
    )

    val backdrop = rememberLayerBackdrop()
    val tabs = rememberSaveableStateHolder()
    Box(Modifier.fillMaxSize()) {
        val insets = WindowInsets.safeDrawing.asPaddingValues()
        val direction = LocalLayoutDirection.current
        val contentPadding = PaddingValues(
            start = insets.calculateStartPadding(direction),
            end = insets.calculateEndPadding(direction),
            top = insets.calculateTopPadding(),
            bottom = insets.calculateBottomPadding() + bottomBarsSpace(hasMiniPlayer = current != null),
        )
        Box(Modifier.fillMaxSize().background(colors.background).layerBackdrop(backdrop).nestedScroll(collapseOnScroll)) {
            tabs.SaveableStateProvider(tab) {
                when (tab) {
                    MainTab.Library -> LibraryScreen(
                        slot = slot,
                        layout = layout,
                        tracks = slotTracks,
                        slotCounts = slotCounts,
                        currentId = playerState.currentId,
                        listState = listState,
                        contentPadding = contentPadding,
                        selection = selection,
                        downloads = downloads,
                        wallColumns = wallColumns,
                        onSelectionChange = { selection = it },
                        actions = LibraryActions(
                            onPlay = { index -> player.play(slotTracks, index) },
                            onEdit = { track -> editingId = track.id },
                            onRemove = { ids -> if (slot == SlotId.Online) container.online.remove(ids) else library.remove(ids) },
                            onDownload = { track, quality ->
                                container.online.find(track.id)?.song?.let { song ->
                                    container.downloads.start(song, quality)
                                    Toast.makeText(context, context.getString(R.string.download_started, song.title), Toast.LENGTH_SHORT).show()
                                }
                            },
                            onSwitchSlot = switchSlot,
                            onSearch = { tab = MainTab.Search },
                        ),
                        menus = HeaderMenus(
                            playlists = playlists.map { it to (playlistKey(it) in hiddenPlaylists) },
                            openedPlaylist = openedPlaylist?.playlist,
                            onOpenPlaylist = openPlaylist,
                            onTogglePlaylistHidden = { playlist ->
                                val key = playlistKey(playlist)
                                settings.hiddenPlaylists.set(if (key in hiddenPlaylists) hiddenPlaylists - key else hiddenPlaylists + key)
                            },
                            onSlotMenuOpen = { playlistsWanted++ },
                            more = more,
                        ),
                        empty = emptyContent(
                            slot = slot,
                            cloudStatus = cloudStatus,
                            hasSources = hasSources,
                            openPlugins = { openSettings(SettingsPage.Plugins) },
                            importFiles = importFiles,
                            openCloudSettings = { openSettings(SettingsPage.Cloud) },
                            refreshCloud = container.cloud::refresh,
                            search = { tab = MainTab.Search },
                        ),
                        status = when {
                            slot == SlotId.Cloud && cloudStatus == CloudStatus.Loading -> stringResource(R.string.cloud_loading)
                            slot == SlotId.Playlist && openedPlaylist != null -> openedPlaylist?.playlist?.name
                            else -> null
                        },
                    )
                    MainTab.Search -> {
                        LaunchedEffect(Unit) { playlistsWanted++ }
                        SearchScreen(
                            music = SearchMusic(
                                localTracks = localTracks,
                                cloudTracks = cloudList,
                                searchOnline = { query -> searchSources(container, query) },
                                playlists = playlists.filterNot { playlistKey(it) in hiddenPlaylists },
                                playTrack = { track ->
                                    val (target, list) = if (cloudList.any { it.id == track.id }) SlotId.Cloud to cloudList else SlotId.Local to localTracks
                                    switchSlot(target)
                                    player.play(list, list.indexOf(track).coerceAtLeast(0))
                                },
                                playOnline = { song ->
                                    // Desktop plays a search result from the online list, adding it on top.
                                    container.online.add(song)
                                    switchSlot(SlotId.Online)
                                    player.play(container.online.entries.value.map { it.toTrack() }, 0)
                                },
                                addOnline = container.online::add,
                                download = { song, quality ->
                                    container.downloads.start(song, quality)
                                    Toast.makeText(context, context.getString(R.string.download_started, song.title), Toast.LENGTH_SHORT).show()
                                },
                                openPlaylist = openPlaylist,
                            ),
                            contentPadding = contentPadding,
                        )
                    }
                    MainTab.Settings -> key(settingsOpened) {
                        SettingsScreen(container, settingsPage, onClose = null, bottomPadding = bottomBarsSpace(hasMiniPlayer = current != null))
                    }
                }
            }
        }
        BottomBars(
            tab = tab,
            onTab = {
                tab = it
                barsCollapsed = false
            },
            collapsed = barsCollapsed,
            onExpand = { barsCollapsed = false },
            backdrop = backdrop,
            miniPlayer = current?.let { track ->
                { height ->
                    MiniPlayer(
                        track = track,
                        isPlaying = playerState.isPlaying,
                        backdrop = backdrop,
                        onTogglePlay = player::togglePlay,
                        onNext = player::next,
                        onPrevious = player::previous,
                        onOpen = { focusOpen = true },
                        height = height,
                    )
                }
            },
            modifier = Modifier.align(Alignment.BottomCenter),
        )
        AnimatedVisibility(visible = focusOpen && current != null, enter = slideInVertically { it }, exit = slideOutVertically { it }) {
            if (current != null) {
                val focusLines = remember(current.id) { current.parsedLyrics().lines }
                val translation = rememberFocusTranslation(container, current, focusLines)
                FocusScreen(current, playerState, player, container.volume, focusSettings, translation, onClose = { focusOpen = false })
            }
        }
        AnimatedVisibility(visible = editing != null, enter = slideInVertically { it }, exit = slideOutVertically { it }) {
            if (editing != null) MetadataEditor(editing, onSave = { edit, done -> library.edit(editing, edit, done) }, onClose = { editingId = null })
        }
    }
}

/**
 * iOS 26 Apple Music's floating bars over the page: the mini player, when
 * something plays, above a Liquid Glass tab bar of the library, search and
 * settings. Scrolled down ([collapsed]), the tab bar shrinks to a round
 * button for the current tab with the mini player beside it; scrolling back
 * up or tapping the button brings the tabs back.
 */
@Composable
private fun BottomBars(
    tab: MainTab,
    onTab: (MainTab) -> Unit,
    collapsed: Boolean,
    onExpand: () -> Unit,
    backdrop: Backdrop,
    miniPlayer: (@Composable (height: Dp) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val colors = LocalAppColors.current
    val barTabs = listOf(
        Triple(MainTab.Library, R.drawable.ui_library_music_filled, stringResource(R.string.tab_library)),
        Triple(MainTab.Search, R.drawable.ui_search, stringResource(R.string.search)),
        Triple(MainTab.Settings, R.drawable.ui_settings_filled, stringResource(R.string.settings_title)),
    )
    AnimatedContent(
        targetState = collapsed,
        transitionSpec = { fadeIn(tween(BAR_FADE_MS)) togetherWith fadeOut(tween(BAR_FADE_MS)) using SizeTransform(clip = false) },
        contentAlignment = Alignment.BottomCenter,
        label = "bars",
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = BOTTOM_BAR_MARGIN),
    ) { isCollapsed ->
        if (isCollapsed) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val (_, icon, label) = barTabs.first { it.first == tab }
                GlassRoundButton(backdrop, onClick = onExpand, size = COLLAPSED_HEIGHT, modifier = Modifier.semantics { contentDescription = label }) {
                    Icon(icon, colors.accent, size = 26.dp)
                }
                if (miniPlayer != null) {
                    Spacer(Modifier.width(BOTTOM_BAR_MARGIN))
                    Box(Modifier.weight(1f)) { miniPlayer(COLLAPSED_HEIGHT) }
                }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(BOTTOM_BAR_MARGIN)) {
                miniPlayer?.invoke(MINI_PLAYER_HEIGHT)
                val selected = remember { mutableIntStateOf(tab.ordinal) }
                selected.intValue = tab.ordinal
                GlassTabBar(
                    selectedTabIndex = { selected.intValue },
                    onTabSelected = { index -> onTab(barTabs[index].first) },
                    backdrop = backdrop,
                    tabsCount = barTabs.size,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    barTabs.forEach { (target, icon, label) ->
                        GlassTab(onClick = { onTab(target) }, modifier = Modifier.semantics { this.selected = tab == target }) {
                            Icon(icon, colors.text, size = 26.dp)
                            BasicText(label, style = TextStyle(color = colors.text, fontSize = 10.sp, fontWeight = FontWeight.SemiBold))
                        }
                    }
                }
            }
        }
    }
}

/** The height of the collapsed bar: the tab button and the mini player beside it. */
private val COLLAPSED_HEIGHT = 56.dp
private const val BAR_FADE_MS = 200

/** A scroll this far in one step collapses or expands the bars. */
private const val COLLAPSE_SCROLL_PX = 6f

/** The library's "more" menu: what used to be commands in the search palette, for the slot on show. */
@Composable
private fun moreActions(
    slot: SlotId,
    hasTracks: Boolean,
    hasCurrent: Boolean,
    cloudConnected: Boolean,
    wall: Boolean,
    importFiles: () -> Unit,
    refreshCloud: () -> Unit,
    playAll: () -> Unit,
    shuffleAll: () -> Unit,
    selectMultiple: () -> Unit,
    locateCurrent: () -> Unit,
    toggleLayout: () -> Unit,
): List<MenuEntry> = buildList {
    if (hasTracks) {
        add(MenuEntry.Item(stringResource(R.string.command_play_all), R.drawable.ui_play_arrow, onClick = playAll))
        add(MenuEntry.Item(stringResource(R.string.command_shuffle_all), R.drawable.ui_shuffle, onClick = shuffleAll))
    }
    if (hasCurrent) add(MenuEntry.Item(stringResource(R.string.command_locate), R.drawable.ui_my_location, onClick = locateCurrent))
    if (isNotEmpty()) add(MenuEntry.Separator)
    if (slot == SlotId.Local) add(MenuEntry.Item(stringResource(R.string.import_files), R.drawable.ui_library_add, onClick = importFiles))
    if (slot == SlotId.Cloud && cloudConnected) add(MenuEntry.Item(stringResource(R.string.cloud_refresh), R.drawable.ui_sync, onClick = refreshCloud))
    if (hasTracks && (slot == SlotId.Local || slot == SlotId.Online)) {
        add(MenuEntry.Item(stringResource(R.string.select_multiple), R.drawable.ui_checklist, onClick = selectMultiple))
    }
    add(
        MenuEntry.Item(
            stringResource(if (wall) R.string.command_use_list else R.string.command_use_wall),
            if (wall) R.drawable.ui_view_list else R.drawable.ui_auto_awesome_mosaic,
            onClick = toggleLayout,
        ),
    )
}

/** What each slot says while it has no songs, and what fills it. */
@Composable
private fun emptyContent(
    slot: SlotId,
    cloudStatus: CloudStatus,
    hasSources: Boolean,
    openPlugins: () -> Unit,
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
    SlotId.Online, SlotId.Playlist -> when {
        !hasSources -> EmptyContent(stringResource(R.string.empty_no_sources), stringResource(R.string.open_plugins), openPlugins)
        slot == SlotId.Online -> EmptyContent(stringResource(R.string.empty_online), stringResource(R.string.search), search)
        else -> EmptyContent(stringResource(R.string.empty_playlist), stringResource(R.string.search), search)
    }
}

/** Every enabled music source, searched at once; a failing source reports its error under its name. */
private suspend fun searchSources(container: AppContainer, query: String): List<OnlineResults> = coroutineScope {
    container.plugins.sources().map { plugin ->
        async {
            try {
                OnlineResults(plugin.manifest.name, container.sources.search(plugin.id, query), null)
            } catch (error: MusicSourceException) {
                OnlineResults(plugin.manifest.name, emptyList(), error.message)
            }
        }
    }.awaitAll()
}

/** The account (or recommended) playlists of every enabled source; sources that fail are left out. */
private suspend fun loadPlaylists(container: AppContainer): List<OnlinePlaylist> = coroutineScope {
    container.plugins.sources().map { plugin ->
        async {
            try {
                container.sources.playlists(plugin.id)
            } catch (_: MusicSourceException) {
                emptyList()
            }
        }
    }.awaitAll().flatten()
}

/** Desktop's download notifications (`trackDownloadSuccess`, `trackDownloadDowngraded`, `downloadFailed`). */
private fun Context.showDownload(outcome: DownloadOutcome) {
    val song = outcome.song
    val artist = song.artist.replace(" / ", " & ")
    val message = when (outcome) {
        is DownloadOutcome.Saved if outcome.downgraded ->
            getString(R.string.download_downgraded, artist, song.title, qualityLabel(outcome.requested), qualityLabel(outcome.served))
        is DownloadOutcome.Saved -> getString(R.string.download_done, artist, song.title)
        is DownloadOutcome.Failed -> getString(R.string.download_failed, outcome.reason)
    }
    Toast.makeText(this, message, Toast.LENGTH_LONG).show()
}

private fun qualityLabel(id: String): String = DownloadQuality.entries.firstOrNull { it.id == id }?.label ?: id.uppercase()

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
