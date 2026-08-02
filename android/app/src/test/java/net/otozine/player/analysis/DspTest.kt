package net.otozine.player.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The DSP is hand-written, so it is checked against signals whose answers are
 * known independently rather than against its own output.
 *
 * The loudness case matters most: the PC measures true EBU R128 via ffmpeg, and
 * if this implementation disagreed, the same track would get two different
 * gains depending on where it was analysed -- reintroducing exactly the volume
 * jumps that loudness normalisation exists to remove.
 */
class DspTest {

    private val sr = LoudnessMeter.SAMPLE_RATE

    private fun sine(freq: Double, seconds: Double, amplitude: Double): FloatArray {
        val n = (sr * seconds).toInt()
        return FloatArray(n) { (amplitude * sin(2.0 * PI * freq * it / sr)).toFloat() }
    }

    // ------------------------------------------------------------------ FFT

    @Test
    fun `fft puts a pure tone in the expected bin`() {
        val size = 4096
        val binHz = sr.toDouble() / size
        val targetBin = 100                       // ~1172 Hz at 48 kHz / 4096
        val freq = targetBin * binHz

        val re = FloatArray(size) { sin(2.0 * PI * freq * it / sr).toFloat() }
        val im = FloatArray(size)
        Dsp.fft(re, im)

        var peakBin = 0
        var peak = 0.0
        for (b in 1 until size / 2) {
            val magnitude = sqrt((re[b] * re[b] + im[b] * im[b]).toDouble())
            if (magnitude > peak) { peak = magnitude; peakBin = b }
        }
        assertEquals("peak should land in the bin the tone was built for", targetBin, peakBin)
    }

    @Test
    fun `fft of a constant signal puts all energy at dc`() {
        val size = 1024
        val re = FloatArray(size) { 1f }
        val im = FloatArray(size)
        Dsp.fft(re, im)

        assertEquals(size.toFloat(), re[0], 0.01f)
        for (b in 1 until size / 2) {
            assertTrue("bin $b should be empty for a constant input", abs(re[b]) < 0.01f)
        }
    }

    // ------------------------------------------------------------- loudness

    @Test
    fun `a 1 kHz sine at -20 dBFS measures about -20 LUFS`() {
        // The EBU R128 reference case. K-weighting is flat near 1 kHz, so the
        // measured loudness should track the signal level directly.
        val amplitude = 0.1 * sqrt(2.0)           // RMS 0.1 => -20 dBFS
        val result = LoudnessMeter.measure(sine(1000.0, 5.0, amplitude))

        assertEquals(
            "1 kHz at -20 dBFS should read about -20 LUFS",
            -20.0, result.integratedLufs.toDouble(), 1.0,
        )
    }

    @Test
    fun `loudness tracks level changes one for one`() {
        val quiet = LoudnessMeter.measure(sine(1000.0, 5.0, 0.1 * sqrt(2.0)))
        val loud = LoudnessMeter.measure(sine(1000.0, 5.0, 0.2 * sqrt(2.0)))

        // Doubling amplitude is +6.02 dB, and loudness must move with it.
        assertEquals(
            6.02,
            (loud.integratedLufs - quiet.integratedLufs).toDouble(),
            0.3,
        )
    }

    @Test
    fun `silence between passages does not drag the measurement down`() {
        // The whole point of R128 gating: a track with a long silent tail must
        // measure like the music, not like the average including the silence.
        val music = sine(1000.0, 4.0, 0.1 * sqrt(2.0))
        val withSilence = music + FloatArray(sr * 6)

        val a = LoudnessMeter.measure(music)
        val b = LoudnessMeter.measure(withSilence)

        assertEquals(
            "gating should exclude the silence",
            a.integratedLufs.toDouble(), b.integratedLufs.toDouble(), 0.5,
        )
    }

    @Test
    fun `true peak is read before weighting is applied`() {
        // The weighting filter changes amplitude, so a peak measured after it
        // would not be the peak the listener hears -- and the clipping ceiling
        // would be computed against the wrong number.
        val result = LoudnessMeter.measure(sine(1000.0, 3.0, 0.5))
        assertEquals(-6.02, result.truePeakDb.toDouble(), 0.2)
    }

    @Test
    fun `gain is limited by headroom rather than clipping the track`() {
        // A quiet-but-clipped master is common in rips: it wants a big boost by
        // loudness, and must not get one.
        val hot = LoudnessMeter.Result(integratedLufs = -20f, truePeakDb = -0.2f)
        val gain = hot.gainFor(target = -14f, ceiling = -1f)

        assertTrue("wanted +6 dB but only -0.8 dB of headroom exists", gain < 0f)
        assertEquals(-0.8, gain.toDouble(), 0.01)
    }

    // ---------------------------------------------------------------- tempo

    @Test
    fun `tempo is recovered from a synthetic pulse train`() {
        // 120 BPM: a click every 0.5 s, with a decaying tone so the onset
        // detector has real spectral change to find.
        val bpm = 120.0
        val seconds = 20.0
        val samples = FloatArray((sr * seconds).toInt())
        val period = (sr * 60.0 / bpm).toInt()

        var click = 0
        while (click * period < samples.size) {
            val start = click * period
            for (i in 0 until minOf(4000, samples.size - start)) {
                val decay = 1.0 - i / 4000.0
                samples[start + i] = (sin(2.0 * PI * 220.0 * i / sr) * decay * 0.5).toFloat()
            }
            click++
        }

        val result = TrackAnalyser.analyse(samples)
        val detected = result.bpm
        assertTrue("no tempo detected", detected != null)

        // Half and double time are acceptable: trackers routinely lock onto
        // them, and the engine folds tempo into a 70-180 range anyway.
        val ratio = detected!! / bpm
        assertTrue(
            "expected ~$bpm BPM (or a 2x relative), got $detected",
            abs(ratio - 1.0) < 0.08 || abs(ratio - 2.0) < 0.16 || abs(ratio - 0.5) < 0.04,
        )
    }

    @Test
    fun `analysis is stable against level changes`() {
        // The failure that made the first PC implementation useless: measuring
        // the mastering rather than the music. The same track at two levels must
        // produce the same character.
        val base = sine(440.0, 12.0, 0.05)
        val loud = FloatArray(base.size) { (base[it] * 4f).coerceIn(-1f, 1f) }

        val a = TrackAnalyser.analyse(base)
        val b = TrackAnalyser.analyse(loud)

        assertEquals("arousal must not follow level", a.arousal.toDouble(), b.arousal.toDouble(), 0.12)
        assertEquals("valence must not follow level", a.valence.toDouble(), b.valence.toDouble(), 0.12)
    }
}
