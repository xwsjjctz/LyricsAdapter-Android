package com.lyricsadapter.app.ui.focus

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * How long the controls stay after the last touch. Desktop's
 * `FOCUS_CONTROLS_IDLE_MS` is a second; three at the owner's request
 * (2026-10-30), which a second felt too short for on a phone.
 */
private const val IDLE_MS = 3_000L

private const val TOUCH = "touch"

/**
 * Desktop's portrait `FocusControlsIdle`: the controls start hidden, appear
 * while something holds them (a finger on them, or on the lyrics dragging
 * them) and hide again [IDLE_MS] after the last hold ends. Playback, a track
 * change included, never counts as activity and never cuts a hold short.
 */
@Stable
internal class FocusControlsIdle(private val scope: CoroutineScope) {
    var shown by mutableStateOf(false)
        private set
    private val holds = mutableSetOf<String>()
    private var timer: Job? = null

    fun hold(key: String) {
        holds += key
        timer?.cancel()
        shown = true
    }

    fun release(key: String) {
        if (!holds.remove(key) || holds.isNotEmpty()) return
        timer?.cancel()
        timer = scope.launch {
            delay(IDLE_MS)
            shown = false
        }
    }
}

/**
 * Feeds touches on the controls to [idle]. A touch while they are hidden only
 * reveals them: it is consumed before the buttons see it, so nothing under
 * the finger is pressed by accident.
 */
internal fun Modifier.wakesControls(idle: FocusControlsIdle): Modifier = pointerInput(idle) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        val wakeOnly = !idle.shown
        idle.hold(TOUCH)
        if (wakeOnly) down.consume()
        try {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (wakeOnly) event.changes.forEach { it.consume() }
                if (event.changes.none { it.pressed }) break
            }
        } finally {
            idle.release(TOUCH)
        }
    }
}
