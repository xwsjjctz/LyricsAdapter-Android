package com.lyricsadapter.app.playback

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Sent by the system right after any stream's volume changes; not in the public SDK but long stable. */
private const val VOLUME_CHANGED = "android.media.VOLUME_CHANGED_ACTION"
private const val EXTRA_STREAM_TYPE = "android.media.EXTRA_VOLUME_STREAM_TYPE"

/**
 * The phone's media volume as 0..1. On a phone the volume slider moves the
 * same volume as the hardware keys, rather than a second, app-only one as on
 * desktop, so the two never disagree.
 *
 * Changes made here are published at once. Other changes arrive through the
 * system's volume broadcast: the settings it also writes are saved about half
 * a second later, too late to follow.
 */
class SystemVolume(private val context: Context) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val maxIndex = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
    private val current = MutableStateFlow(readIndex())
    private var beforeMute = 0

    /** Current volume while collected. */
    val level: Flow<Float> = callbackFlow {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.getIntExtra(EXTRA_STREAM_TYPE, -1) == AudioManager.STREAM_MUSIC) current.value = readIndex()
            }
        }
        ContextCompat.registerReceiver(context, receiver, IntentFilter(VOLUME_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
        current.value = readIndex()
        launch { current.collect { send(it.toFloat() / maxIndex) } }
        awaitClose { context.unregisterReceiver(receiver) }
    }

    fun current(): Float = current.value.toFloat() / maxIndex

    /** Sets the volume to the nearest step; repeated calls within one step cost nothing. */
    fun set(fraction: Float) {
        setIndex((fraction.coerceIn(0f, 1f) * maxIndex).roundToInt())
    }

    /** Mutes, or goes back to the volume before muting (half volume if that is unknown). */
    fun toggleMute() {
        val index = current.value
        if (index > 0) {
            beforeMute = index
            setIndex(0)
        } else {
            setIndex(beforeMute.takeIf { it > 0 } ?: (maxIndex / 2))
        }
    }

    private fun setIndex(index: Int) {
        if (index == current.value) return
        current.value = index
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, index, 0)
    }

    private fun readIndex(): Int = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
}
