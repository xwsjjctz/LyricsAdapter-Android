package com.lyricsadapter.spike.lyrics

import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.metrics.performance.JankStats
import kotlinx.coroutines.delay

/**
 * Plays the demo lyrics on a looping clock and reports frame timings every few
 * seconds, on screen and to logcat (tag LASpike). Install the benchmark build
 * to measure: a debuggable build is much slower. Effects can be switched off
 * to compare their cost:
 *
 *   adb shell am start -n com.lyricsadapter.spike/.lyrics.LyricsSpikeActivity --ez blur false --ez background false
 */
class LyricsSpikeActivity : ComponentActivity() {
    private val frames = FrameStats()
    private lateinit var jankStats: JankStats

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val lyrics = loadDemoLyrics(assets)
        val refreshRate = display?.refreshRate ?: 60f
        val effects = LyricsEffects(
            blur = intent.getBooleanExtra("blur", true),
            background = intent.getBooleanExtra("background", true),
        )
        Log.i(TAG, "lyrics effects: $effects")
        setContent { LyricsSpikeScreen(lyrics, effects, frames, refreshRate) }
        jankStats = JankStats.createAndTrack(window) { frames.record(it) }
    }

    override fun onResume() {
        super.onResume()
        jankStats.isTrackingEnabled = true
    }

    override fun onPause() {
        super.onPause()
        jankStats.isTrackingEnabled = false
    }
}

private const val TAG = "LASpike"
private const val REPORT_SECONDS = 5

/** The costly effects, each of which a low-end device could do without. */
private data class LyricsEffects(val blur: Boolean, val background: Boolean)

@Composable
private fun LyricsSpikeScreen(lyrics: DemoLyrics, effects: LyricsEffects, frames: FrameStats, refreshRate: Float) {
    val position = remember { mutableLongStateOf(0L) }
    var summary by remember { mutableStateOf("measuring…") }

    LaunchedEffect(lyrics) {
        val origin = withFrameNanos { it }
        while (true) {
            withFrameNanos { now -> position.longValue = ((now - origin) / 1_000_000) % lyrics.durationMs }
        }
    }
    LaunchedEffect(frames) {
        while (true) {
            delay(REPORT_SECONDS * 1000L)
            frames.drain(REPORT_SECONDS, refreshRate)?.let {
                summary = it
                Log.i(TAG, "lyrics frames: $it")
            }
        }
    }

    val backdrop = if (effects.background) Modifier.flowingBackground { position.longValue } else Modifier.background(Color(0xFF2A1B4D))
    Box(Modifier.fillMaxSize().then(backdrop)) {
        LyricsView(lyrics.lines, { position.longValue }, Modifier.fillMaxSize().safeDrawingPadding(), blurLines = effects.blur)
        BasicText(
            text = summary,
            style = TextStyle(color = Color.White.copy(alpha = 0.7f), fontSize = 11.sp),
            modifier = Modifier.align(Alignment.TopStart).safeDrawingPadding().padding(12.dp),
        )
    }
}
