package com.lyricsadapter.spike.lyrics

import androidx.metrics.performance.FrameData
import androidx.metrics.performance.FrameDataApi24
import androidx.metrics.performance.FrameDataApi31

/**
 * Collects JankStats frames and summarises them per reporting window.
 *
 * CPU time (UI thread plus RenderThread) is the app's own drawing cost.
 * Overrun is how far a frame went past its deadline (negative when early); on
 * an emulator it includes the host GPU translation, so read it on a device.
 */
class FrameStats {
    private val lock = Any()
    private val uiNanos = ArrayList<Long>(1024)
    private val cpuNanos = ArrayList<Long>(1024)
    private val overrunNanos = ArrayList<Long>(1024)
    private var jankFrames = 0

    fun record(frame: FrameData) {
        synchronized(lock) {
            uiNanos += frame.frameDurationUiNanos
            cpuNanos += (frame as? FrameDataApi24)?.frameDurationCpuNanos ?: frame.frameDurationUiNanos
            (frame as? FrameDataApi31)?.let { overrunNanos += it.frameOverrunNanos }
            if (frame.isJank) jankFrames++
        }
    }

    /** Summary of the frames since the last call, or null if there were none. */
    fun drain(windowSeconds: Int, refreshRate: Float): String? {
        val (ui, cpu, overrun, janky) = synchronized(lock) {
            val window = Window(uiNanos.sorted(), cpuNanos.sorted(), overrunNanos.sorted(), jankFrames)
            uiNanos.clear()
            cpuNanos.clear()
            overrunNanos.clear()
            jankFrames = 0
            window
        }
        if (cpu.isEmpty()) return null
        val overrunText = if (overrun.isEmpty()) "" else " · overrun p95 ${millis(percentile(overrun, 95))} ms"
        return "%.0f fps of %.0f Hz · jank %.1f%% · ui p50 %s ms, p95 %s ms · cpu p50 %s ms%s".format(
            cpu.size.toFloat() / windowSeconds,
            refreshRate,
            janky * 100f / cpu.size,
            millis(percentile(ui, 50)),
            millis(percentile(ui, 95)),
            millis(percentile(cpu, 50)),
            overrunText,
        )
    }

    private data class Window(val ui: List<Long>, val cpu: List<Long>, val overrun: List<Long>, val janky: Int)

    private fun percentile(sorted: List<Long>, percent: Int): Long = sorted[(sorted.size * percent / 100).coerceAtMost(sorted.size - 1)]

    private fun millis(nanos: Long) = "%.1f".format(nanos / 1_000_000.0)
}
