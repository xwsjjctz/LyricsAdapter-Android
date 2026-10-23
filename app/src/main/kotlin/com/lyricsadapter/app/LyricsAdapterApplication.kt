package com.lyricsadapter.app

import android.app.Application
import android.content.Context
import com.lyricsadapter.app.data.AppSettings
import com.lyricsadapter.app.data.CloudLibrary
import com.lyricsadapter.app.data.CoverStore
import com.lyricsadapter.app.data.LibraryDatabase
import com.lyricsadapter.app.data.LibraryRepository
import com.lyricsadapter.app.data.SecretStore
import com.lyricsadapter.app.data.TrackReader
import com.lyricsadapter.app.playback.PlayerConnection
import com.lyricsadapter.app.playback.SystemVolume
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File

class LyricsAdapterApplication : Application() {
    val container: AppContainer by lazy { AppContainer(this) }
}

/** App-wide services, created once. */
class AppContainer(context: Context) {
    val settings = AppSettings(context, SecretStore(context))
    private val database: LibraryDatabase = LibraryDatabase.create(context)
    /** Work that outlives any screen, such as refreshing the cloud library. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val library = LibraryRepository(
        resolver = context.contentResolver,
        tracks = database.tracks(),
        reader = TrackReader(context.contentResolver, CoverStore(File(context.filesDir, "covers"))),
    )
    val cloud = CloudLibrary(
        settings = settings,
        cacheFile = File(context.filesDir, "cloud-library.json"),
        covers = CoverStore(File(context.filesDir, "cloud-covers")),
        scratchDirectory = File(context.cacheDir, "cloud-heads"),
        scope = scope,
    )
    val player = PlayerConnection(context)
    val volume = SystemVolume(context)
}

val Context.container: AppContainer get() = (applicationContext as LyricsAdapterApplication).container
