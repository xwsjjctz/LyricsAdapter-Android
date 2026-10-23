package com.lyricsadapter.app.playback

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.lyricsadapter.library.Track
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Desktop's three playback orders (`usePlayback.ts`): the list in order,
 * starting over after the last track; random tracks; or the current track
 * again and again. The mode button steps through them in this order.
 */
enum class PlaybackMode { Order, Shuffle, RepeatOne }

/** What the UI shows about playback; the position is read on demand instead. */
data class PlayerState(
    val currentId: String? = null,
    val isPlaying: Boolean = false,
    val durationMs: Long = 0,
    val mode: PlaybackMode = PlaybackMode.Order,
)

/**
 * The UI's side of the playback service. UI code sends intents here; the
 * service's player stays the single owner of playback state.
 */
class PlayerConnection(private val context: Context) {
    private val _state = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> = _state.asStateFlow()
    private var controller: MediaController? = null

    suspend fun connect() {
        if (controller != null) return
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        val connected = suspendCancellableCoroutine { continuation ->
            future.addListener({
                runCatching { future.get() }.fold(continuation::resume, continuation::resumeWithException)
            }, ContextCompat.getMainExecutor(context))
            continuation.invokeOnCancellation { MediaController.releaseFuture(future) }
        }
        connected.addListener(object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) = publish(player)
        })
        // A fresh player does not repeat; desktop's order mode starts over at the end of the list.
        if (connected.repeatMode == Player.REPEAT_MODE_OFF) connected.repeatMode = Player.REPEAT_MODE_ALL
        controller = connected
        publish(connected)
    }

    /** Plays [tracks] in order from [index]. */
    fun play(tracks: List<Track>, index: Int) {
        val player = controller ?: return
        player.setMediaItems(tracks.map(::mediaItem), index, 0)
        player.prepare()
        player.play()
    }

    fun togglePlay() {
        val player = controller ?: return
        if (player.isPlaying) player.pause() else player.play()
    }

    fun next() {
        controller?.seekToNextMediaItem()
    }

    /** Back to the start of the track, or to the previous one when near the start, like a CD player. */
    fun previous() {
        controller?.seekToPrevious()
    }

    fun seekTo(positionMs: Long) {
        controller?.seekTo(positionMs)
    }

    fun cyclePlaybackMode() {
        val player = controller ?: return
        when (modeOf(player)) {
            PlaybackMode.Order -> player.shuffleModeEnabled = true
            PlaybackMode.Shuffle -> {
                player.shuffleModeEnabled = false
                player.repeatMode = Player.REPEAT_MODE_ONE
            }
            PlaybackMode.RepeatOne -> player.repeatMode = Player.REPEAT_MODE_ALL
        }
    }

    /** The controller's estimate of the current position, cheap enough to read every frame. */
    fun positionMs(): Long = controller?.currentPosition ?: 0

    private fun publish(player: Player) {
        _state.value = PlayerState(
            currentId = player.currentMediaItem?.mediaId,
            isPlaying = player.isPlaying,
            durationMs = player.duration.coerceAtLeast(0),
            mode = modeOf(player),
        )
    }

    private fun modeOf(player: Player): PlaybackMode = when {
        player.repeatMode == Player.REPEAT_MODE_ONE -> PlaybackMode.RepeatOne
        player.shuffleModeEnabled -> PlaybackMode.Shuffle
        else -> PlaybackMode.Order
    }

    private fun mediaItem(track: Track): MediaItem = MediaItem.Builder()
        .setMediaId(track.id)
        .setUri(track.id)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(track.title)
                .setArtist(track.artist)
                .setAlbumTitle(track.album)
                .setArtworkUri(track.coverPath?.let { Uri.fromFile(File(it)) })
                .build(),
        )
        .build()
}
