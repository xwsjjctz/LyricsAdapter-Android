package com.lyricsadapter.app

import android.app.Application
import android.content.Context
import com.lyricsadapter.app.data.AppSettings
import com.lyricsadapter.app.data.LibraryDatabase
import com.lyricsadapter.app.data.LibraryRepository
import com.lyricsadapter.app.data.TrackReader
import com.lyricsadapter.app.playback.PlayerConnection
import com.lyricsadapter.app.playback.SystemVolume
import java.io.File

class LyricsAdapterApplication : Application() {
    val container: AppContainer by lazy { AppContainer(this) }
}

/** App-wide services, created once. */
class AppContainer(context: Context) {
    val settings = AppSettings(context)
    private val database: LibraryDatabase = LibraryDatabase.create(context)
    val library = LibraryRepository(
        resolver = context.contentResolver,
        tracks = database.tracks(),
        reader = TrackReader(context.contentResolver, File(context.filesDir, "covers")),
    )
    val player = PlayerConnection(context)
    val volume = SystemVolume(context)
}

val Context.container: AppContainer get() = (applicationContext as LyricsAdapterApplication).container
