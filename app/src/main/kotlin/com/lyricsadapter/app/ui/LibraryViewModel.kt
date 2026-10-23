package com.lyricsadapter.app.ui

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lyricsadapter.app.AppContainer
import com.lyricsadapter.app.data.ImportResult
import com.lyricsadapter.library.Track
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class LibraryViewModel(private val container: AppContainer) : ViewModel() {
    val tracks: StateFlow<List<Track>> = container.database.tracks().observeAll()
        .map { entities -> entities.map { it.toTrack() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val results = Channel<ImportResult>(Channel.BUFFERED)

    /** One result per finished import, for a short notice. */
    val importResults: Flow<ImportResult> = results.receiveAsFlow()

    fun import(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch { results.send(container.importer.import(uris)) }
    }
}
