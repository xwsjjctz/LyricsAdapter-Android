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

/** How long the controls stay after the last touch (desktop's `FOCUS_CONTROLS_IDLE_MS`). */
private const val IDLE_MS = 1_000L

/**
 * Desktop's portrait `FocusControlsIdle`: the controls start hidden, appear
 * when touched, stay while a finger is on them and hide again a second after
 * the last touch. Playback never counts as activity.
 */
@Stable
internal class FocusControlsIdle(private val scope: CoroutineScope) {
    var shown by mutableStateOf(false)
        private set
    private var holds = 0
    private var timer: Job? = null

    fun hold() {
        holds++
        timer?.cancel()
        shown = true
    }

    fun release() {
        holds = (holds - 1).coerceAtLeast(0)
        if (holds > 0) return
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
        idle.hold()
        if (wakeOnly) down.consume()
        try {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (wakeOnly) event.changes.forEach { it.consume() }
                if (event.changes.none { it.pressed }) break
            }
        } finally {
            idle.release()
        }
    }
}
