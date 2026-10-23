package com.lyricsadapter.app.ui

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lyricsadapter.app.AppContainer
import com.lyricsadapter.app.R
import com.lyricsadapter.app.ui.focus.FocusScreen

/** Room under the list for the floating mini player. */
private val MINI_PLAYER_SPACE = 84.dp

@Composable
fun AppScreen(container: AppContainer) {
    val library: LibraryViewModel = viewModel { LibraryViewModel(container) }
    val player = container.player
    val tracks by library.tracks.collectAsStateWithLifecycle()
    val playerState by player.state.collectAsStateWithLifecycle()
    var focusOpen by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current

    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        library.import(uris)
    }
    LaunchedEffect(library) {
        library.importResults.collect { result ->
            val message = if (result.failed.isEmpty()) {
                context.getString(R.string.import_done, result.imported)
            } else {
                context.getString(R.string.import_done, result.imported) + "，" + context.getString(R.string.import_failed, result.failed.size)
            }
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    val current = tracks.firstOrNull { it.id == playerState.currentId }
    BackHandler(enabled = focusOpen) { focusOpen = false }

    Box(Modifier.fillMaxSize()) {
        val insets = WindowInsets.safeDrawing.asPaddingValues()
        val direction = LocalLayoutDirection.current
        LibraryScreen(
            tracks = tracks,
            currentId = playerState.currentId,
            contentPadding = PaddingValues(
                start = insets.calculateStartPadding(direction),
                end = insets.calculateEndPadding(direction),
                top = insets.calculateTopPadding(),
                bottom = insets.calculateBottomPadding() + if (current != null) MINI_PLAYER_SPACE else 0.dp,
            ),
            onPlay = { index -> player.play(tracks, index) },
            onImport = { pickFiles.launch(arrayOf("audio/*")) },
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
        AnimatedVisibility(
            visible = focusOpen && current != null,
            enter = slideInVertically { it },
            exit = slideOutVertically { it },
        ) {
            if (current != null) FocusScreen(current, playerState, player, container.volume, onClose = { focusOpen = false })
        }
    }
}
