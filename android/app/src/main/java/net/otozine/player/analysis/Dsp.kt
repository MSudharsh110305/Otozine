package net.otozine.player.analysis

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The signal-processing primitives the on-device analyser needs.
 *
 * Written by hand because Android ships neither an FFT nor any DSP library, and
 * the alternative -- requiring a PC to add a song -- defeats the point of a
 * library that travels on a pendrive.
 *
 * Everything here is deliberately allocation-light: a four-minute track is
 * roughly ten million samples, and a per-frame allocation would spend more time
 * in the collector than in the maths.
 */
object Dsp {

    /**
     * In-place iterative radix-2 FFT.
     *
     * Iterative rather than recursive: the recursive form is prettier but
     * allocates two arrays per level, which at these sizes is most of the
     * runtime.
     *
     * @param real length must be a power of two; overwritten with the result.
     */
    fun fft(real: FloatArray, imag: FloatArray) {
        val n = real.size
        require(n and (n - 1) == 0) { "FFT size must be a power of two, got $n" }

        // Bit-reversal permutation.
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j or bit
            if (i < j) {
                real[i] = real[j].also { real[j] = real[i] }
                imag[i] = imag[j].also { imag[j] = imag[i] }
            }
        }

        var len = 2
        while (len <= n) {
            val angle = -2.0 * PI / len
            val wReal = cos(angle).toFloat()
            val wImag = sin(angle).toFloat()
            var i = 0
            while (i < n) {
                var curReal = 1f
                var curImag = 0f
                for (k in 0 until len / 2) {
                    val uRe = real[i + k]
                    val uIm = imag[i + k]
                    val vRe = real[i + k + len / 2] * curReal - imag[i + k + len / 2] * curImag
                    val vIm = real[i + k + len / 2] * curImag + imag[i + k + len / 2] * curReal
                    real[i + k] = uRe + vRe
                    imag[i + k] = uIm + vIm
                    real[i + k + len / 2] = uRe - vRe
                    imag[i + k + len / 2] = uIm - vIm
                    val nextReal = curReal * wReal - curImag * wImag
                    curImag = curReal * wImag + curImag * wReal
                    curReal = nextReal
                }
                i += len
            }
            len = len shl 1
        }
    }

    /** Hann window, precomputed once and reused across every frame. */
    fun hann(size: Int) = FloatArray(size) {
        (0.5 * (1.0 - cos(2.0 * PI * it / (size - 1)))).toFloat()
    }

    /**
     * Magnitude spectrum of one frame.
     *
     * Returns only the first half: the rest is the mirror image for real input,
     * so computing or storing it is wasted work.
     */
    fun magnitudeSpectrum(
        samples: FloatArray,
        offset: Int,
        window: FloatArray,
        re: FloatArray,
        im: FloatArray,
        out: FloatArray,
    ) {
        val n = window.size
        for (i in 0 until n) {
            val s = if (offset + i < samples.size) samples[offset + i] else 0f
            re[i] = s * window[i]
            im[i] = 0f
        }
        fft(re, im)
        for (i in out.indices) {
            out[i] = sqrt(re[i] * re[i] + im[i] * im[i])
        }
    }

    fun rms(samples: FloatArray, from: Int = 0, to: Int = samples.size): Float {
        if (to <= from) return 0f
        var sum = 0.0
        for (i in from until to) sum += samples[i].toDouble() * samples[i]
        return sqrt(sum / (to - from)).toFloat()
    }

    /** A biquad section, used to build the loudness weighting filter. */
    class Biquad(
        private val b0: Double, private val b1: Double, private val b2: Double,
        private val a1: Double, private val a2: Double,
    ) {
        private var x1 = 0.0; private var x2 = 0.0
        private var y1 = 0.0; private var y2 = 0.0

        fun processInPlace(samples: FloatArray) = processInPlace(samples, samples.size)

        /**
         * Filter the first [length] samples, carrying state across calls so a
         * track can be filtered chunk by chunk without ever being held whole.
         */
        fun processInPlace(samples: FloatArray, length: Int) {
            for (i in 0 until length) {
                val x0 = samples[i].toDouble()
                val y0 = b0 * x0 + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
                x2 = x1; x1 = x0
                y2 = y1; y1 = y0
                samples[i] = y0.toFloat()
            }
        }
    }

    fun linearToDb(value: Float): Float =
        if (value <= 1e-10f) -100f else (20.0 * ln(value.toDouble()) / ln(10.0)).toFloat()
}
