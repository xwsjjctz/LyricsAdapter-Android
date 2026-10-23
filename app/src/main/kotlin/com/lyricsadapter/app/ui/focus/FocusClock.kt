package com.lyricsadapter.app.ui.focus

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import com.lyricsadapter.app.playback.PlayerConnection
import kotlin.math.abs
import kotlin.math.roundToLong

/** A larger difference from the player is a seek or a stall: jump instead of easing (desktop's `CLOCK_RESYNC_THRESHOLD_SECONDS`). */
private const val RESYNC_MS = 250.0

/** Share of the remaining difference from the player taken up each frame. */
private const val SLEW = 0.05

/** The playback position that focus mode draws with, once per frame. */
@Stable
internal class FocusClock {
    var positionMs by mutableLongStateOf(0L)
        internal set
}

/**
 * A steady clock for the lyrics, like desktop's `useFocusPlaybackClock`. The
 * controller's position is an estimate that steps back by 100-150 ms when a
 * fresh report arrives from the player; drawn as is, finished words go dark
 * and light again. While playing, this clock advances with frame time, eases
 * toward the controller's position and never goes backwards; it only jumps
 * for a difference of [RESYNC_MS] or more. Paused, it shows the player's
 * position as is.
 */
@Composable
internal fun rememberFocusClock(player: PlayerConnection, isPlaying: Boolean, trackId: String): FocusClock {
    val clock = remember { FocusClock() }
    val playing by rememberUpdatedState(isPlaying)
    LaunchedEffect(trackId) {
        var position = player.positionMs().toDouble()
        clock.positionMs = position.roundToLong()
        var lastFrame = withFrameNanos { it }
        while (true) {
            withFrameNanos { now ->
                val reported = player.positionMs().toDouble()
                val predicted = position + (now - lastFrame) / 1e6
                lastFrame = now
                val error = reported - predicted
                position = when {
                    !playing || abs(error) >= RESYNC_MS -> reported
                    else -> maxOf(position, predicted + error * SLEW)
                }
                clock.positionMs = position.roundToLong()
            }
        }
    }
    return clock
}
