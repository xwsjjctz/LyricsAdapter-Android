package com.lyricsadapter.app.playback

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import android.net.Uri
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.ResolvingDataSource
import com.lyricsadapter.app.plugins.MusicSourceException
import com.lyricsadapter.app.plugins.MusicSources
import com.lyricsadapter.app.plugins.OnlineLibrary
import com.lyricsadapter.app.plugins.parseOnlineTrackId
import kotlinx.coroutines.runBlocking
import java.io.IOException
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.lyricsadapter.app.MainActivity
import com.lyricsadapter.app.container
import com.lyricsadapter.app.data.AppSettings
import com.lyricsadapter.app.data.cloudPathOf
import com.lyricsadapter.webdav.WebDavPath
import okhttp3.Credentials
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response

/**
 * Owns the player, so playback, the notification, lock-screen and headset
 * controls keep working after the activity is gone.
 */
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val settings = container.settings
        // Local documents and http(s) streams; WebDAV requests get the saved account's credentials.
        val http = OkHttpClient.Builder().addInterceptor(WebDavAuthorization(settings)).build()
        // Online songs are resolved through their plugin each time a stream opens, since the URLs expire.
        val dataSources = ResolvingDataSource.Factory(DefaultDataSource.Factory(this, OkHttpDataSource.Factory(http)), OnlineStreams(container.sources, container.online))
        val player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSources))
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .build()
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaSession.Builder(this, player)
            .setSessionActivity(openApp)
            .setCallback(ItemsCallback(settings))
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = session?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }

    /**
     * Controllers send items without their playback URI. A local track's id
     * is its document URI; a cloud track's id names its WebDAV path, which is
     * resolved against the saved server.
     */
    private class ItemsCallback(private val settings: AppSettings) : MediaSession.Callback {
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> = Futures.immediateFuture(
            mediaItems.map { item -> item.buildUpon().setUri(uriOf(item.mediaId)).build() }.toMutableList(),
        )

        private fun uriOf(id: String): String {
            val path = cloudPathOf(id) ?: return id
            val config = settings.webDavConfig.value ?: return id
            return WebDavPath.url(config.serverUrl, path).toString()
        }
    }
}

/**
 * Turns an `online:<source>:<songmid>` URI into the song's current stream URL
 * and the headers its plugin asks for. Resolved URLs are reused for a while,
 * so seeking does not ask the plugin again.
 */
private class OnlineStreams(private val sources: MusicSources, private val online: OnlineLibrary) : ResolvingDataSource.Resolver {
    private val resolved = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, Pair<String, Map<String, String>>>>()

    override fun resolveDataSpec(dataSpec: DataSpec): DataSpec {
        val id = dataSpec.uri.toString()
        val (source, songmid) = parseOnlineTrackId(id) ?: return dataSpec
        val now = System.currentTimeMillis()
        val (url, headers) = resolved[id]?.takeIf { it.first > now }?.second ?: try {
            runBlocking { sources.stream(source, songmid, online.find(id)?.song?.mediaMid).let { it.url to it.headers } }
        } catch (error: MusicSourceException) {
            throw IOException(error.message, error)
        }.also { resolved[id] = (now + STREAM_TTL_MS) to it }
        return dataSpec.withUri(Uri.parse(url)).withAdditionalHeaders(headers)
    }

    private companion object {
        const val STREAM_TTL_MS = 10 * 60 * 1000L
    }
}

/**
 * Adds Basic authentication to requests for the saved WebDAV server only. It
 * runs before redirects are followed, and OkHttp drops the header when a
 * redirect leaves the host, so a CDN never sees the password.
 */
private class WebDavAuthorization(private val settings: AppSettings) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val config = settings.webDavConfig.value ?: return chain.proceed(request)
        val server = config.serverUrl.toHttpUrlOrNull() ?: return chain.proceed(request)
        if (request.url.host != server.host || request.url.port != server.port || request.header("Authorization") != null) {
            return chain.proceed(request)
        }
        return chain.proceed(request.newBuilder().header("Authorization", Credentials.basic(config.username, config.password, Charsets.UTF_8)).build())
    }
}
