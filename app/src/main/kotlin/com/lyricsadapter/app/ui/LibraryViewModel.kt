package com.lyricsadapter.app.ui

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lyricsadapter.app.AppContainer
import com.lyricsadapter.app.data.EditResult
import com.lyricsadapter.app.data.ImportResult
import com.lyricsadapter.app.data.TrackEdit
import com.lyricsadapter.library.Track
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Something finished that deserves a short notice. */
sealed interface LibraryNotice {
    data class Imported(val result: ImportResult) : LibraryNotice
    data class Removed(val count: Int) : LibraryNotice
    data class Edited(val result: EditResult) : LibraryNotice
}

class LibraryViewModel(private val container: AppContainer) : ViewModel() {
    /** Null until the library has been read from storage. */
    private val stored: StateFlow<List<Track>?> = container.library.all.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val tracks: StateFlow<List<Track>> = stored.map { it.orEmpty() }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** False while [tracks] is empty only because the library has not been read yet. */
    val loaded: StateFlow<Boolean> = stored.map { it != null }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** The library once it has been read. */
    suspend fun awaitTracks(): List<Track> = stored.filterNotNull().first()

    private val notices = Channel<LibraryNotice>(Channel.BUFFERED)
    val events: Flow<LibraryNotice> = notices.receiveAsFlow()

    private val progress = MutableStateFlow<Pair<Int, Int>?>(null)

    /** How many files of a running import have been read, and of how many; null while none runs. */
    val importProgress: StateFlow<Pair<Int, Int>?> = progress.asStateFlow()

    init {
        // What was added to the followed folders since the last start comes in by itself, without a word unless there was something.
        viewModelScope.launch {
            val result = container.library.syncFolders(::report)
            progress.value = null
            if (result.imported > 0) notices.send(LibraryNotice.Imported(result))
        }
    }

    fun import(uris: List<Uri>) {
        if (uris.isEmpty()) return
        run { container.library.import(uris, ::report) }
    }

    /** Follows the folder the user picked and imports its music; null when the picker was closed. */
    fun importFolder(tree: Uri?) {
        if (tree == null) return
        run { container.library.importFolder(tree, ::report) }
    }

    fun rescanFolders() = run { container.library.syncFolders(::report) }

    private fun run(import: suspend () -> ImportResult) {
        viewModelScope.launch {
            val result = import()
            progress.value = null
            notices.send(LibraryNotice.Imported(result))
        }
    }

    private fun report(done: Int, total: Int) {
        progress.value = done to total
    }

    fun remove(ids: Collection<String>) {
        if (ids.isEmpty()) return
        viewModelScope.launch {
            container.library.remove(ids)
            notices.send(LibraryNotice.Removed(ids.size))
        }
    }

    /** Saves [edit]; [onDone] runs on the main thread with the outcome. */
    fun edit(track: Track, edit: TrackEdit, onDone: (EditResult) -> Unit) {
        viewModelScope.launch {
            val result = container.library.edit(track, edit)
            notices.send(LibraryNotice.Edited(result))
            onDone(result)
        }
    }
}
