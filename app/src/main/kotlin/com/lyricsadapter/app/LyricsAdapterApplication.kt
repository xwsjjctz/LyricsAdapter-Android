package com.lyricsadapter.app

import android.app.Application
import android.content.Context
import com.lyricsadapter.app.data.LibraryDatabase
import com.lyricsadapter.app.data.TrackImporter
import com.lyricsadapter.app.playback.PlayerConnection
import java.io.File

class LyricsAdapterApplication : Application() {
    val container: AppContainer by lazy { AppContainer(this) }
}

/** App-wide services, created once. */
class AppContainer(context: Context) {
    val database: LibraryDatabase = LibraryDatabase.create(context)
    val importer = TrackImporter(context.contentResolver, database.tracks(), File(context.filesDir, "covers"))
    val player = PlayerConnection(context)
}

val Context.container: AppContainer get() = (applicationContext as LyricsAdapterApplication).container
