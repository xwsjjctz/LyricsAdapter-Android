package com.lyricsadapter.app.playback

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * How loud the music is right now, for the flowing background's beat. The
 * player hands every decoded buffer here on its way to the speaker, so no
 * recording permission is needed; only 16-bit PCM is measured, as the player
 * outputs it.
 */
@OptIn(UnstableApi::class)
object AudioPulse : TeeAudioProcessor.AudioBufferSink {
    /** Root-mean-square level of the latest buffer, 0 to 1. */
    @Volatile
    var level: Float = 0f
        private set

    @Volatile
    private var measurable = false

    override fun flush(sampleRateHz: Int, channelCount: Int, encoding: Int) {
        measurable = encoding == C.ENCODING_PCM_16BIT
        level = 0f
    }

    override fun handleBuffer(buffer: ByteBuffer) {
        if (!measurable) return
        val samples = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val count = samples.remaining()
        if (count == 0) return
        var sum = 0.0
        while (samples.hasRemaining()) {
            val value = samples.get() / 32768.0
            sum += value * value
        }
        level = sqrt(sum / count).toFloat().coerceIn(0f, 1f)
    }
}
