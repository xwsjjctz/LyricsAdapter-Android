package com.lyricsadapter.app.ui

import android.content.Context
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.lyricsadapter.app.AppContainer
import com.lyricsadapter.app.R
import com.lyricsadapter.app.data.CloudStatus
import com.lyricsadapter.app.data.EditResult
import com.lyricsadapter.app.data.FocusSettings
import com.lyricsadapter.app.data.LastPlayback
import com.lyricsadapter.app.data.LibraryLayout
import com.lyricsadapter.app.data.SlotId
import com.lyricsadapter.app.plugins.DownloadOutcome
import com.lyricsadapter.app.plugins.DownloadQuality
import com.lyricsadapter.app.plugins.MusicSourceException
import com.lyricsadapter.app.plugins.OnlinePlaylist
import com.lyricsadapter.app.ui.editor.MetadataEditor
import com.lyricsadapter.app.ui.focus.FocusScreen
import com.lyricsadapter.app.ui.focus.rememberFocusTranslation
import com.lyricsadapter.app.ui.glass.GlassTab
import com.lyricsadapter.app.ui.glass.GlassTabBar
import com.lyricsadapter.app.ui.search.OnlineResults
import com.lyricsadapter.app.ui.search.SearchMusic
import com.lyricsadapter.app.ui.settings.SettingsPage
import com.lyricsadapter.app.ui.settings.SettingsScreen
import com.lyricsadapter.library.Track
import com.lyricsadapter.library.wall.WallLayout
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import android.os.Build
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.text.BasicText
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.Backdrop
import com.lyricsadapter.app.ui.glass.GlassMenuHost
import com.lyricsadapter.app.ui.glass.GlassMenuState
import com.lyricsadapter.app.ui.glass.LocalGlassMenu

/** The app's two pages, switched by the tab bar at the bottom. */
private enum class MainPage { Library, Settings }

/** The stacked bottom bars' widest, on a tall screen that is also wide. */
private val BOTTOM_BARS_MAX_WIDTH = 520.dp

/** The tab bar's width in landscape, where it sits at the end of the mini player's row. */
private val LANDSCAPE_TAB_BAR_WIDTH = 180.dp

/** The mini player's widest in landscape, where it keeps to the middle of the screen. */
private val LANDSCAPE_MINI_PLAYER_MAX_WIDTH = 480.dp

/** How long a fresh start waits for the saved track's list to load before giving up on restoring it. */
private const val RESTORE_TIMEOUT_MS = 15_000L

/**
 * The app's one screen: the playlist and settings pages, Apple Music's
 * bottom bars over them (the always-present mini player above a Liquid Glass
 * tab bar, which is tapped or dragged to switch pages), and the pages that
 * open over them (metadata editor, focus mode). Back closes the topmost
 * page, then returns to the playlist. What played last is saved as it
 * changes and loaded again, paused, on a fresh start.
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

    var page by rememberSaveable { mutableStateOf(MainPage.Library) }
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
    val tracksOf = { target: SlotId ->
        when (target) {
            SlotId.Local -> localTracks
            SlotId.Cloud -> cloudList
            SlotId.Online -> onlineList
            SlotId.Playlist -> playlistList
        }
    }
    val slotTracks = tracksOf(slot)
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
    val openSettings = { target: SettingsPage ->
        settingsPage = target
        settingsOpened++
        page = MainPage.Settings
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
                settings.openedPlaylist.set(playlistKey(playlist))
                switchSlot(SlotId.Playlist)
                page = MainPage.Library
            } catch (error: MusicSourceException) {
                Toast.makeText(context, error.message, Toast.LENGTH_SHORT).show()
            }
        }
        Unit
    }

    // The list the player's queue came from, so what plays is saved against the right slot.
    var queueSlot by rememberSaveable { mutableStateOf(settings.lastPlayback.value.value?.slot ?: slot) }
    val playFrom = { target: SlotId, list: List<Track>, index: Int ->
        queueSlot = target
        player.play(list, index)
    }
    val scrollTo = { index: Int, size: Int ->
        val item = if (layout == LibraryLayout.Wall) wallBlockOf(index, size, wallColumns) else index
        if (index >= 0) scope.launch { listState.animateScrollToItem(item) }
    }
    val locateCurrent = {
        val target = current?.let { track -> SlotId.entries.firstOrNull { s -> tracksOf(s).any { it.id == track.id } } }
        if (target != null) {
            switchSlot(target)
            val list = tracksOf(target)
            scrollTo(list.indexOfFirst { it.id == current.id }, list.size)
        }
    }

    // A fresh start picks up where playback stood, paused; a playback service still holding a queue is left alone.
    var restored by rememberSaveable { mutableStateOf(false) }
    val latestTracksOf by rememberUpdatedState(tracksOf)
    LaunchedEffect(Unit) {
        if (restored) return@LaunchedEffect
        player.awaitConnected()
        val saved = settings.lastPlayback.value.value
        val playlistRef = settings.openedPlaylist.value.value
        if (playlistRef != null && container.online.playlist.value == null && (slot == SlotId.Playlist || saved?.slot == SlotId.Playlist)) {
            // A source that never answers gives up like one that fails.
            withTimeoutOrNull(RESTORE_TIMEOUT_MS) {
                val playlist = loadPlaylists(container).firstOrNull { playlistKey(it) == playlistRef }
                if (playlist != null) {
                    try {
                        container.online.open(playlist, container.sources.playlistSongs(playlist.source, playlist.id))
                    } catch (_: MusicSourceException) {
                        // Offline or signed out: the playlist slot stays empty, as before.
                    }
                }
            }
        }
        if (saved != null && player.state.value.currentId == null) {
            val list = withTimeoutOrNull(RESTORE_TIMEOUT_MS) {
                snapshotFlow { latestTracksOf(saved.slot) }.first { tracks -> tracks.any { it.id == saved.trackId } }
            }
            // A song picked while the list was still loading wins over the saved one.
            if (list != null && player.state.value.currentId == null) {
                val index = list.indexOfFirst { it.id == saved.trackId }
                queueSlot = saved.slot
                player.load(list, index, saved.positionMs)
                switchSlot(saved.slot)
                scrollTo(index, list.size)
            }
        }
        restored = true
    }
    val savePlayback = {
        val id = player.state.value.currentId
        if (restored && id != null) settings.lastPlayback.set(LastPlayback(queueSlot, id, player.positionMs()))
    }
    LaunchedEffect(playerState.currentId, playerState.isPlaying, restored) { savePlayback() }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { savePlayback() }

    BackHandler(enabled = page != MainPage.Library) { page = MainPage.Library }
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
        playAll = { playFrom(slot, slotTracks, 0) },
        shuffleAll = {
            queueSlot = slot
            player.playShuffled(slotTracks)
        },
        selectMultiple = { selection = emptySet() },
        locateCurrent = { locateCurrent() },
        toggleLayout = { settings.libraryLayout.set(if (layout == LibraryLayout.Wall) LibraryLayout.List else LibraryLayout.Wall) },
    )
    val search = SearchMusic(
        localTracks = localTracks,
        cloudTracks = cloudList,
        searchOnline = { query -> searchSources(container, query) },
        playlists = playlists.filterNot { playlistKey(it) in hiddenPlaylists },
        playTrack = { track ->
            val target = if (cloudList.any { it.id == track.id }) SlotId.Cloud else SlotId.Local
            val list = tracksOf(target)
            switchSlot(target)
            playFrom(target, list, list.indexOf(track).coerceAtLeast(0))
        },
        playOnline = { song ->
            // Desktop plays a search result from the online list, adding it on top.
            container.online.add(song)
            switchSlot(SlotId.Online)
            playFrom(SlotId.Online, container.online.entries.value.map { it.toTrack() }, 0)
        },
        addOnline = container.online::add,
        download = { song, quality ->
            container.downloads.start(song, quality)
            Toast.makeText(context, context.getString(R.string.download_started, song.title), Toast.LENGTH_SHORT).show()
        },
        openPlaylist = openPlaylist,
    )

    // Beside each other on a landscape phone, whose height the stack would use up.
    val barsStacked = LocalConfiguration.current.let { it.screenWidthDp <= it.screenHeightDp }
    val barsSpace = bottomBarsSpace(barsStacked)
    val menu = remember { GlassMenuState() }
    val backdrop = rememberLayerBackdrop()
    val pageStates = rememberSaveableStateHolder()
    CompositionLocalProvider(LocalGlassMenu provides menu) {
        Box(Modifier.fillMaxSize()) {
            // The page and its bottom bars blur while a menu is open over them.
            Box(
                Modifier.fillMaxSize().graphicsLayer {
                    // Always its own layer, so blurring it does not first draw the whole page, glass and all, again.
                    compositingStrategy = CompositingStrategy.Offscreen
                    val radius = MENU_BLUR.toPx() * menu.progress.value.coerceIn(0f, 1f)
                    renderEffect = if (radius > 0.5f && Build.VERSION.SDK_INT >= 31) BlurEffect(radius, radius, TileMode.Clamp) else null
                },
            ) {
                val insets = WindowInsets.safeDrawing.asPaddingValues()
                val direction = LocalLayoutDirection.current
                val contentPadding = PaddingValues(
                    start = insets.calculateStartPadding(direction),
                    end = insets.calculateEndPadding(direction),
                    top = insets.calculateTopPadding(),
                    bottom = insets.calculateBottomPadding() + barsSpace,
                )
                Box(Modifier.fillMaxSize().background(colors.background).layerBackdrop(backdrop)) {
                    pageStates.SaveableStateProvider(page) {
                        when (page) {
                            MainPage.Library -> LibraryScreen(
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
                                    onPlay = { index -> playFrom(slot, slotTracks, index) },
                                    onEdit = { track -> editingId = track.id },
                                    onRemove = { ids -> if (slot == SlotId.Online) container.online.remove(ids) else library.remove(ids) },
                                    onDownload = { track, quality ->
                                        container.online.find(track.id)?.song?.let { song ->
                                            container.downloads.start(song, quality)
                                            Toast.makeText(context, context.getString(R.string.download_started, song.title), Toast.LENGTH_SHORT).show()
                                        }
                                    },
                                    onSwitchSlot = switchSlot,
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
                                search = search,
                                empty = emptyContent(
                                    slot = slot,
                                    cloudStatus = cloudStatus,
                                    hasSources = hasSources,
                                    openPlugins = { openSettings(SettingsPage.Plugins) },
                                    importFiles = importFiles,
                                    openCloudSettings = { openSettings(SettingsPage.Cloud) },
                                    refreshCloud = container.cloud::refresh,
                                ),
                                status = when {
                                    slot == SlotId.Cloud && cloudStatus == CloudStatus.Loading -> stringResource(R.string.cloud_loading)
                                    slot == SlotId.Playlist && openedPlaylist != null -> openedPlaylist?.playlist?.name
                                    else -> null
                                },
                            )
                            MainPage.Settings -> key(settingsOpened) {
                                BoxWithConstraints(Modifier.fillMaxSize()) {
                                    Box(Modifier.widthIn(max = SETTINGS_MAX_WIDTH).fillMaxWidth().fillMaxHeight().align(Alignment.TopCenter)) {
                                        SettingsScreen(container, settingsPage, onClose = null, bottomPadding = barsSpace)
                                    }
                                }
                            }
                        }
                    }
                }
                BottomBars(
                    page = { page },
                    onPage = { page = it },
                    stacked = barsStacked,
                    backdrop = backdrop,
                    modifier = Modifier.align(Alignment.BottomCenter),
                ) { miniModifier ->
                    MiniPlayer(
                        track = current,
                        isPlaying = playerState.isPlaying,
                        backdrop = backdrop,
                        onTogglePlay = { if (current == null) playFrom(slot, slotTracks, 0) else player.togglePlay() },
                        onNext = player::next,
                        onPrevious = player::previous,
                        onOpen = { focusOpen = true },
                        modifier = miniModifier,
                    )
                }
            }
            GlassMenuHost(menu, backdrop)
            // Focus mode keeps the last track through a moment without one, so a track change never
            // rebuilds the page and resets it (its controls would vanish at once).
            val lastTrack = remember { arrayOfNulls<Track>(1) }
            if (current != null) lastTrack[0] = current
            val focusTrack = current ?: lastTrack[0]
            AnimatedVisibility(visible = focusOpen && focusTrack != null, enter = slideInVertically { it }, exit = slideOutVertically { it }) {
                if (focusTrack != null) {
                    val focusLines = remember(focusTrack.id) { focusTrack.parsedLyrics().lines }
                    val translation = rememberFocusTranslation(container, focusTrack, focusLines)
                    FocusScreen(focusTrack, playerState, player, container.volume, focusSettings, translation, onClose = { focusOpen = false })
                }
            }
            AnimatedVisibility(visible = editing != null, enter = slideInVertically { it }, exit = slideOutVertically { it }) {
                if (editing != null) MetadataEditor(editing, onSave = { edit, done -> library.edit(editing, edit, done) }, onClose = { editingId = null })
            }
        }
    }
}

/**
 * The mini player above the tab bar of the two pages. When not [stacked], on a
 * landscape phone, they share a row: the mini player in the middle of the
 * screen, the tab bar at the end, as far as the mini player's width leaves room.
 */
@Composable
private fun BottomBars(
    page: () -> MainPage,
    onPage: (MainPage) -> Unit,
    stacked: Boolean,
    backdrop: Backdrop,
    modifier: Modifier = Modifier,
    miniPlayer: @Composable (Modifier) -> Unit,
) {
    val outer = modifier
        .fillMaxWidth()
        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
        .padding(horizontal = 16.dp, vertical = BOTTOM_BAR_MARGIN)
    val tabBar: @Composable (Modifier) -> Unit = { tabModifier -> PageTabBar(page, onPage, backdrop, tabModifier) }
    if (stacked) {
        Box(outer, contentAlignment = Alignment.Center) {
            Column(Modifier.widthIn(max = BOTTOM_BARS_MAX_WIDTH), verticalArrangement = Arrangement.spacedBy(BOTTOM_BAR_MARGIN)) {
                miniPlayer(Modifier)
                tabBar(Modifier.fillMaxWidth())
            }
        }
    } else {
        Box(outer) {
            // The same room is kept free on both sides, so the mini player stays centred beside the tab bar.
            val side = LANDSCAPE_TAB_BAR_WIDTH + BOTTOM_BAR_MARGIN
            miniPlayer(Modifier.align(Alignment.Center).padding(horizontal = side).widthIn(max = LANDSCAPE_MINI_PLAYER_MAX_WIDTH))
            tabBar(Modifier.align(Alignment.CenterEnd).width(LANDSCAPE_TAB_BAR_WIDTH))
        }
    }
}

/** The glass tab bar of the app's pages: tap a tab, or drag the lens across to it. */
@Composable
private fun PageTabBar(page: () -> MainPage, onPage: (MainPage) -> Unit, backdrop: Backdrop, modifier: Modifier = Modifier) {
    val colors = LocalAppColors.current
    val tabs = listOf(
        Triple(MainPage.Library, R.drawable.ui_library_music_filled, stringResource(R.string.tab_library)),
        Triple(MainPage.Settings, R.drawable.ui_settings_filled, stringResource(R.string.settings_title)),
    )
    GlassTabBar(
        selectedTabIndex = { page().ordinal },
        onTabSelected = { index -> onPage(MainPage.entries[index]) },
        backdrop = backdrop,
        tabsCount = tabs.size,
        modifier = modifier,
    ) {
        tabs.forEach { (target, icon, label) ->
            GlassTab(onClick = { onPage(target) }, modifier = Modifier.semantics { selected = page() == target }) {
                Icon(icon, colors.text, size = 26.dp)
                BasicText(label, style = TextStyle(color = colors.text, fontSize = 10.sp, fontWeight = FontWeight.SemiBold))
            }
        }
    }
}

/** How much the page blurs under an open menu. */
private val MENU_BLUR = 10.dp

/** Settings stay a readable width on wide screens. */
private val SETTINGS_MAX_WIDTH = 600.dp

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
        // The search field over the list fills these.
        slot == SlotId.Online -> EmptyContent(stringResource(R.string.empty_online), null, {})
        else -> EmptyContent(stringResource(R.string.empty_playlist), null, {})
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
