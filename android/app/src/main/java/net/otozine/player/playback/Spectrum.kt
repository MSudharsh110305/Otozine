package net.otozine.player.playback

import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import net.otozine.player.analysis.Dsp
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * The real spectrum of what is playing.
 *
 * Tapped out of the playback pipeline rather than off the microphone. Android's
 * Visualizer API is the usual route to this and it requires RECORD_AUDIO -- an
 * alarming permission to request for a decoration, and one that cannot be
 * explained honestly. ExoPlayer will hand every PCM buffer to a processor on its
 * way to the speaker, which is the same data with none of that baggage: the
 * exact samples being played, before they leave the app.
 *
 * The processor passes audio through untouched. It only looks.
 *
 * Built on Media3's audio-pipeline API, which the library marks unstable: the
 * signatures here can change between Media3 releases. Accepted knowingly --
 * the alternative is the microphone permission -- but it is the first thing to
 * check if a Media3 upgrade stops compiling.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
object Spectrum {

    /** How many bands the UI draws. */
    const val BANDS = 24

    @Volatile
    private var levels = FloatArray(BANDS)

    /** Latest band levels, 0..1. Safe to read from the UI thread each frame. */
    fun read(into: FloatArray) {
        val snapshot = levels
        for (i in into.indices) into[i] = snapshot.getOrElse(i) { 0f }
    }

    fun clear() {
        levels = FloatArray(BANDS)
    }

    /**
     * Sees the audio on its way to the speaker and measures it.
     *
     * Analysis runs on the audio thread, so it has a hard budget: anything slow
     * here is a dropout, not a slow frame. One 1024-point FFT per ~23 ms of
     * audio is comfortably inside it, and buffers are skipped rather than queued
     * if they arrive faster than that.
     */
    class Processor : BaseAudioProcessor() {

        private val window = Dsp.hann(FFT)
        private val re = FloatArray(FFT)
        private val im = FloatArray(FFT)
        private val mono = FloatArray(FFT)
        private var filled = 0
        private val smoothed = FloatArray(BANDS)

        override fun onConfigure(
            inputAudioFormat: AudioProcessor.AudioFormat,
        ): AudioProcessor.AudioFormat = inputAudioFormat

        override fun queueInput(inputBuffer: ByteBuffer) {
            val channels = inputAudioFormat.channelCount.coerceAtLeast(1)
            val sampleRate = inputAudioFormat.sampleRate
            val position = inputBuffer.position()
            val limit = inputBuffer.limit()

            val shorts = inputBuffer.order(ByteOrder.nativeOrder()).asShortBuffer()
            var i = 0
            while (i + channels <= shorts.remaining()) {
                var sum = 0f
                for (c in 0 until channels) sum += shorts.get(i + c) / 32768f
                mono[filled++] = sum / channels
                i += channels
                if (filled == FFT) {
                    analyse(sampleRate)
                    // Half-overlap: keeps the display responsive without
                    // doubling the work.
                    System.arraycopy(mono, FFT / 2, mono, 0, FFT / 2)
                    filled = FFT / 2
                }
            }

            // Pass through untouched. This processor observes; it must never
            // alter what reaches the speaker.
            inputBuffer.position(position)
            val out = replaceOutputBuffer(limit - position)
            out.put(inputBuffer)
            out.flip()
        }

        private fun analyse(sampleRate: Int) {
            for (n in 0 until FFT) {
                re[n] = mono[n] * window[n]
                im[n] = 0f
            }
            Dsp.fft(re, im)

            // Logarithmic bands. Linear bins would give most of the display to
            // the top two octaves, where music has least of its energy and the
            // ear has least resolution -- the bars would barely move.
            val nyquist = sampleRate / 2f
            val lowest = 40f
            val out = FloatArray(BANDS)
            for (b in 0 until BANDS) {
                val from = lowest * Math.pow(
                    (nyquist / lowest).toDouble(), b.toDouble() / BANDS,
                ).toFloat()
                val to = lowest * Math.pow(
                    (nyquist / lowest).toDouble(), (b + 1.0) / BANDS,
                ).toFloat()

                val first = (from / nyquist * (FFT / 2)).toInt().coerceIn(1, FFT / 2 - 1)
                val last = (to / nyquist * (FFT / 2)).toInt().coerceIn(first + 1, FFT / 2)

                var sum = 0f
                for (bin in first until last) {
                    sum += re[bin] * re[bin] + im[bin] * im[bin]
                }
                val rms = sqrt(sum / (last - first))
                // Decibels, then mapped to 0..1 across a 60 dB window. Music is
                // logarithmic and a linear magnitude spends the whole display on
                // the loudest transient.
                val db = 20f * ln(rms + 1e-9f) / ln(10f)
                out[b] = ((db + 62f) / 62f).coerceIn(0f, 1f)
            }

            // Fast up, slow down. A bar that falls as quickly as it rises reads
            // as flicker; holding the peak briefly is what makes a level meter
            // legible.
            for (b in 0 until BANDS) {
                smoothed[b] =
                    if (out[b] > smoothed[b]) out[b]
                    else smoothed[b] * 0.82f + out[b] * 0.18f
            }
            levels = smoothed.copyOf()
        }

        override fun onFlush() {
            filled = 0
            smoothed.fill(0f)
            clear()
        }
    }

    private const val FFT = 1024
}
