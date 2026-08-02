package net.otozine.player.analysis

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow

/**
 * EBU R128 integrated loudness, on device.
 *
 * A plain RMS average would have been a fraction of this code, and it would
 * have been wrong in a way that matters: the Librarian on the PC measures true
 * R128 via ffmpeg, so a phone using a different measure would give the same
 * track two different gains. Tracks analysed on the phone would then jump in
 * volume against tracks analysed on the PC -- the exact problem loudness
 * normalisation exists to solve.
 *
 * So this implements the specification: K-weighting, 400 ms blocks at 75%
 * overlap, an absolute gate at -70 LUFS and a relative gate 10 LU below the
 * ungated mean.
 *
 * Coefficients are the standard ones defined at 48 kHz, which is why the
 * decoder is asked for 48 kHz rather than resampling afterwards.
 */
object LoudnessMeter {

    const val SAMPLE_RATE = 48000

    data class Result(
        val integratedLufs: Float,
        val truePeakDb: Float,
    ) {
        /**
         * Gain to reach [target] without exceeding [ceiling] true peak.
         *
         * Peak-limited rather than naive: a quiet-but-clipped master -- very
         * common in YouTube rips -- would otherwise be pushed into distortion.
         */
        fun gainFor(target: Float = -14f, ceiling: Float = -1f): Float {
            val wanted = target - integratedLufs
            val headroom = ceiling - truePeakDb
            return minOf(wanted, headroom)
        }
    }

    /** @param samples mono PCM at 48 kHz, nominally -1..1. */
    fun measure(samples: FloatArray): Result {
        val session = Session()
        session.accept(samples, samples.size)
        return session.finish()
    }

    /**
     * A measurement in progress, fed the track a chunk at a time.
     *
     * R128 is defined over the whole programme, which naively means holding the
     * whole decoded track: a four-minute stereo song is around 100 MB of floats,
     * and on a real phone that is an OutOfMemoryError, not a slow path. But the
     * measurement never actually needs the samples again once a block's energy
     * has been accumulated -- so this keeps only the filter state, a 400 ms tail,
     * and one double per block (a few thousand for a long track).
     *
     * Memory is therefore flat regardless of track length.
     */
    class Session {
        // K-weighting: a high-shelf approximating the head's response, then a
        // high-pass removing rumble the ear largely ignores. Both carry state
        // between chunks, so filtering in pieces matches filtering at once.
        private val shelf = Dsp.Biquad(
            1.53512485958697, -2.69169618940638, 1.19839281085285,
            -1.69065929318241, 0.73248077421585,
        )
        private val highPass = Dsp.Biquad(
            1.0, -2.0, 1.0,
            -1.99004745483398, 0.99007225036621,
        )

        private val blockSize = (SAMPLE_RATE * 0.4).toInt()   // 400 ms
        private val step = blockSize / 4                      // 75% overlap

        private val blocks = ArrayList<Double>()
        private val ring = FloatArray(blockSize)
        private var writeIndex = 0
        private var filled = 0
        private var sinceEmit = 0

        private var peak = 0f
        private var totalSamples = 0L

        /** @param length how much of [chunk] is valid. */
        fun accept(chunk: FloatArray, length: Int) {
            if (length <= 0) return
            totalSamples += length

            // Peak comes from the untouched signal -- weighting changes
            // amplitude, so a peak measured after it is not one anybody hears.
            for (i in 0 until length) {
                val v = abs(chunk[i])
                if (v > peak) peak = v
            }

            val weighted = chunk.copyOf(length)
            shelf.processInPlace(weighted, length)
            highPass.processInPlace(weighted, length)

            for (i in 0 until length) {
                ring[writeIndex] = weighted[i]
                writeIndex = if (writeIndex + 1 == blockSize) 0 else writeIndex + 1
                if (filled < blockSize) filled++
                sinceEmit++
                if (filled == blockSize && sinceEmit >= step) {
                    emitBlock()
                    sinceEmit = 0
                }
            }
        }

        /**
         * A block's loudness is a sum of squares over the last 400 ms, so the
         * ring can be summed in storage order -- where the write cursor happens
         * to sit makes no difference to the total.
         */
        private fun emitBlock() {
            var sum = 0.0
            for (i in 0 until blockSize) sum += ring[i].toDouble() * ring[i]
            val meanSquare = sum / blockSize
            // -0.691 is the R128 calibration offset relating mean square to LKFS.
            blocks += if (meanSquare > 0) -0.691 + 10.0 * log10(meanSquare) else -200.0
        }

        fun finish(): Result {
            if (totalSamples < SAMPLE_RATE / 2) return Result(-70f, -100f)
            val truePeak = Dsp.linearToDb(peak)
            if (blocks.isEmpty()) return Result(-70f, truePeak)

            // Absolute gate: silence must not drag the average down.
            val aboveAbsolute = blocks.filter { it > -70.0 }
            if (aboveAbsolute.isEmpty()) return Result(-70f, truePeak)

            val ungatedMean = meanLoudness(aboveAbsolute)

            // Relative gate: quiet passages within an otherwise loud track are
            // excluded, so a track with a long fade is not judged by the fade.
            val gated = aboveAbsolute.filter { it > ungatedMean - 10.0 }
            val integrated = if (gated.isEmpty()) ungatedMean else meanLoudness(gated)

            return Result(integrated.toFloat(), truePeak)
        }
    }

    /** Energy-domain mean; averaging decibels directly would be wrong. */
    private fun meanLoudness(blocks: List<Double>): Double {
        var sum = 0.0
        for (block in blocks) sum += 10.0.pow((block + 0.691) / 10.0)
        return -0.691 + 10.0 * log10(sum / blocks.size)
    }

    private fun log10(x: Double) = ln(x) / ln(10.0)
}
