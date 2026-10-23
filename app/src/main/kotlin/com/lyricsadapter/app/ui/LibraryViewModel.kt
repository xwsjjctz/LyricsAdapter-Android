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
    val tracks: StateFlow<List<Track>> = container.library.all
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val notices = Channel<LibraryNotice>(Channel.BUFFERED)
    val events: Flow<LibraryNotice> = notices.receiveAsFlow()

    fun import(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch { notices.send(LibraryNotice.Imported(container.library.import(uris))) }
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
