package net.otozine.player.analysis

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Tempo, key and mood from raw PCM.
 *
 * A deliberate port of the Librarian's `dsp.py` and `mood.py`, using the same
 * feature definitions, the same calibrated ranges and the same weights. That
 * matching matters more than elegance: a track analysed on the phone has to sit
 * in the same feature space as one analysed on the PC, or the queue engine
 * would be comparing two incompatible sets of numbers and sequencing badly at
 * the seam between them.
 *
 * Where the two differ, it is documented below.
 */
object TrackAnalyser {

    private const val SR = LoudnessMeter.SAMPLE_RATE

    // Mood families. One pole of one family wins per track, so the labels a
    // song carries cannot contradict each other.
    private const val ENERGY = "energy"
    private const val FEELING = "feeling"
    private const val TEXTURE = "texture"
    private const val FFT = 4096                 // ~85 ms at 48 kHz
    private const val HOP = 1024

    data class Result(
        val bpm: Float?,
        val keyCamelot: String?,
        val keyName: String?,
        val energy: Float,
        val danceability: Float,
        val valence: Float,
        val arousal: Float,
        val acousticness: Float,
        val tension: Float,
        val moods: List<Pair<String, Float>>,
    )

    fun analyse(samples: FloatArray): Result {
        if (samples.size < SR) {
            return Result(null, null, null, 0.5f, 0.5f, 0.5f, 0.5f, 0.5f, 0.5f, emptyList())
        }

        // Level-normalise before measuring anything.
        //
        // Modern masters are limited to within a few LU of each other, so
        // absolute loudness says little about the music but leaks into every
        // spectral measure. Skipping this on the PC side made 13 of 14 tracks
        // come back "intense"; the same trap applies here.
        val work = samples.copyOf()
        val level = Dsp.rms(work)
        if (level > 1e-6f) {
            val scale = 0.1f / level
            for (i in work.indices) work[i] = work[i] * scale
        }

        val frames = spectrogram(work)
        if (frames.isEmpty()) {
            return Result(null, null, null, 0.5f, 0.5f, 0.5f, 0.5f, 0.5f, 0.5f, emptyList())
        }

        val onsetEnvelope = onsetStrength(frames)
        val bpm = estimateTempo(onsetEnvelope)
        val (camelot, keyName) = estimateKey(frames)
        val features = spectralFeatures(work, frames, onsetEnvelope)

        return interpret(features, bpm, camelot, keyName)
    }

    // ------------------------------------------------------------ spectrum

    private fun spectrogram(samples: FloatArray): Array<FloatArray> {
        val window = Dsp.hann(FFT)
        val re = FloatArray(FFT)
        val im = FloatArray(FFT)
        val bins = FFT / 2

        val count = max(0, (samples.size - FFT) / HOP)
        if (count == 0) return emptyArray()

        return Array(count) { frame ->
            val magnitude = FloatArray(bins)
            Dsp.magnitudeSpectrum(samples, frame * HOP, window, re, im, magnitude)
            magnitude
        }
    }

    /** Positive spectral flux: how much energy appeared since the last frame. */
    private fun onsetStrength(frames: Array<FloatArray>): FloatArray {
        val out = FloatArray(frames.size)
        for (f in 1 until frames.size) {
            var sum = 0f
            val prev = frames[f - 1]
            val cur = frames[f]
            for (b in cur.indices) {
                val diff = cur[b] - prev[b]
                if (diff > 0) sum += diff        // rising energy only: onsets, not decays
            }
            out[f] = sum
        }
        return out
    }

    // --------------------------------------------------------------- tempo

    /**
     * Tempo by autocorrelation of the onset envelope.
     *
     * Folded into 70-180 BPM because trackers routinely lock onto half or double
     * time, and for sequencing a 75 BPM ballad and a 150 BPM one should not be
     * treated as opposites.
     */
    private fun estimateTempo(onset: FloatArray): Float? {
        if (onset.size < 64) return null

        val mean = onset.average().toFloat()
        val centred = FloatArray(onset.size) { onset[it] - mean }

        val framesPerSecond = SR.toFloat() / HOP
        val minLag = (framesPerSecond * 60f / 200f).toInt().coerceAtLeast(1)
        val maxLag = (framesPerSecond * 60f / 50f).toInt().coerceAtMost(centred.size / 2)
        if (maxLag <= minLag) return null

        var bestLag = -1
        var bestScore = 0f
        for (lag in minLag..maxLag) {
            var sum = 0f
            for (i in 0 until centred.size - lag) sum += centred[i] * centred[i + lag]
            // Longer lags accumulate fewer terms, so normalise or the search
            // is biased toward slow tempos.
            val score = sum / (centred.size - lag)
            if (score > bestScore) { bestScore = score; bestLag = lag }
        }
        if (bestLag <= 0) return null

        var bpm = 60f * framesPerSecond / bestLag
        while (bpm < 70f) bpm *= 2f
        while (bpm > 180f) bpm /= 2f
        return (bpm * 100).toInt() / 100f
    }

    // ----------------------------------------------------------------- key

    private val MAJOR_PROFILE = floatArrayOf(
        6.35f, 2.23f, 3.48f, 2.33f, 4.38f, 4.09f, 2.52f, 5.19f, 2.39f, 3.66f, 2.29f, 2.88f,
    )
    private val MINOR_PROFILE = floatArrayOf(
        6.33f, 2.68f, 3.52f, 5.38f, 2.60f, 3.53f, 2.54f, 4.75f, 3.98f, 2.69f, 3.34f, 3.17f,
    )
    private val PITCH_NAMES = arrayOf(
        "C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B",
    )
    private val MAJOR_CAMELOT = mapOf(
        0 to "8B", 7 to "9B", 2 to "10B", 9 to "11B", 4 to "12B", 11 to "1B",
        6 to "2B", 1 to "3B", 8 to "4B", 3 to "5B", 10 to "6B", 5 to "7B",
    )
    private val MINOR_CAMELOT = mapOf(
        9 to "8A", 4 to "9A", 11 to "10A", 6 to "11A", 1 to "12A", 8 to "1A",
        3 to "2A", 10 to "3A", 5 to "4A", 0 to "5A", 7 to "6A", 2 to "7A",
    )

    /**
     * Key by correlating a chroma vector against Krumhansl-Kessler profiles.
     *
     * The chroma is built by folding FFT bins onto pitch classes rather than by
     * constant-Q transform, which the Python side uses. CQT tracks pitch better
     * at low frequencies; this is the pragmatic version, and mode -- which is
     * what valence actually depends on -- survives the simplification.
     */
    private fun estimateKey(frames: Array<FloatArray>): Pair<String?, String?> {
        val chroma = FloatArray(12)
        val binHz = SR.toFloat() / FFT

        for (frame in frames) {
            // Normalise per frame so a loud chorus does not outvote the rest of
            // the song. Without this the key is decided by whichever section
            // happens to be mastered hottest.
            var frameEnergy = 0f
            for (b in 1 until frame.size) frameEnergy += frame[b]
            if (frameEnergy <= 1e-9f) continue

            for (b in 1 until frame.size) {
                val hz = b * binHz
                if (hz < 55f || hz > 2000f) continue     // A1..B6, where pitch lives
                val midi = 69.0 + 12.0 * ln(hz / 440.0) / ln(2.0)
                // Round, do not truncate. toInt() rounds toward zero, which put
                // every partial up to a full semitone flat and pulled the whole
                // chroma in one direction -- the tell was 15 of 20 real tracks
                // coming back in the same key.
                val pitchClass = ((Math.round(midi).toInt() % 12) + 12) % 12
                chroma[pitchClass] += frame[b] / frameEnergy
            }
        }

        val total = chroma.sum()
        if (total <= 0f) return null to null
        for (i in chroma.indices) chroma[i] /= total

        var bestCorr = Float.NEGATIVE_INFINITY
        var bestTonic = 0
        var bestMajor = true
        for (tonic in 0 until 12) {
            val rotated = FloatArray(12) { chroma[(it + tonic) % 12] }
            val majorCorr = correlate(rotated, MAJOR_PROFILE)
            val minorCorr = correlate(rotated, MINOR_PROFILE)
            if (majorCorr > bestCorr) { bestCorr = majorCorr; bestTonic = tonic; bestMajor = true }
            if (minorCorr > bestCorr) { bestCorr = minorCorr; bestTonic = tonic; bestMajor = false }
        }

        val camelot = if (bestMajor) MAJOR_CAMELOT[bestTonic] else MINOR_CAMELOT[bestTonic]
        val name = "${PITCH_NAMES[bestTonic]} ${if (bestMajor) "major" else "minor"}"
        return camelot to name
    }

    private fun correlate(a: FloatArray, b: FloatArray): Float {
        val meanA = a.average().toFloat()
        val meanB = b.average().toFloat()
        var num = 0f; var da = 0f; var db = 0f
        for (i in a.indices) {
            val x = a[i] - meanA
            val y = b[i] - meanB
            num += x * y; da += x * x; db += y * y
        }
        val denom = sqrt(da * db)
        return if (denom < 1e-9f) 0f else num / denom
    }

    // ------------------------------------------------------------ features

    private class Features(
        val brightness: Float, val bandwidth: Float, val noisiness: Float,
        val contrast: Float, val zcr: Float, val dynamics: Float,
        val percussiveRatio: Float, val harmonicRatio: Float,
        val onsetRate: Float, val flux: Float,
    )

    private fun spectralFeatures(
        samples: FloatArray,
        frames: Array<FloatArray>,
        onset: FloatArray,
    ): Features {
        val binHz = SR.toFloat() / FFT
        var centroidSum = 0.0
        var bandwidthSum = 0.0
        var flatnessSum = 0.0
        var contrastSum = 0.0
        var counted = 0

        for (frame in frames) {
            var energy = 0.0
            var weighted = 0.0
            for (b in frame.indices) {
                energy += frame[b]
                weighted += frame[b].toDouble() * b * binHz
            }
            if (energy <= 1e-9) continue
            val centroid = weighted / energy
            centroidSum += centroid

            var spread = 0.0
            for (b in frame.indices) {
                val d = b * binHz - centroid
                spread += frame[b] * d * d
            }
            bandwidthSum += sqrt(spread / energy)

            // Flatness: geometric over arithmetic mean. Computed in the log
            // domain because the direct product underflows within a few hundred
            // bins at float precision.
            var logSum = 0.0
            var linSum = 0.0
            for (b in frame.indices) {
                val v = frame[b] + 1e-10f
                logSum += ln(v.toDouble())
                linSum += v
            }
            val geometric = kotlin.math.exp(logSum / frame.size)
            flatnessSum += geometric / (linSum / frame.size)

            // Cheap stand-in for spectral contrast: how far the loud bins sit
            // above the quiet ones, in dB.
            val sorted = frame.sortedArray()
            val low = sorted.take(sorted.size / 5).average()
            val high = sorted.takeLast(sorted.size / 5).average()
            contrastSum += 20.0 * ln((high + 1e-9) / (low + 1e-9)) / ln(10.0)
            counted++
        }
        if (counted == 0) counted = 1

        var crossings = 0
        for (i in 1 until samples.size) {
            if ((samples[i - 1] < 0f) != (samples[i] < 0f)) crossings++
        }
        val zcr = crossings.toFloat() / samples.size

        // Harmonic/percussive split, approximated: percussive frames are the
        // ones whose energy jumps. A full median-filter HPSS would be more
        // faithful but costs several seconds per track on a phone.
        var percussive = 0.0
        var totalEnergy = 0.0
        for (f in frames.indices) {
            val energy = frames[f].sum().toDouble()
            totalEnergy += energy
            if (f > 0 && onset[f] > 0) percussive += min(energy, onset[f].toDouble())
        }
        val percRatio = if (totalEnergy > 0) (percussive / totalEnergy).toFloat() else 0.3f

        // Onset rate: peaks in the envelope that clear a mean-relative bar.
        val onsetMean = onset.average().toFloat()
        var peaks = 0
        for (i in 1 until onset.size - 1) {
            if (onset[i] > onset[i - 1] && onset[i] >= onset[i + 1] && onset[i] > onsetMean * 1.5f) {
                peaks++
            }
        }
        val seconds = samples.size.toFloat() / SR
        val onsetRate = peaks / max(seconds, 1e-6f)

        val peak = samples.maxOf { abs(it) }
        val crest = peak / (Dsp.rms(samples) + 1e-9f)

        // Ranges calibrated against real material on the PC side; reused here
        // so both analysers land in the same space.
        return Features(
            brightness = scale(centroidSum / counted, 1500.0, 4000.0),
            bandwidth = scale(bandwidthSum / counted, 2000.0, 3300.0),
            noisiness = scale(ln(flatnessSum / counted + 1e-8) / ln(10.0), -2.6, -1.0),
            contrast = scale(contrastSum / counted, 14.0, 23.0),
            zcr = scale(zcr.toDouble(), 0.04, 0.17),
            dynamics = scale(crest.toDouble(), 2.5, 7.0),
            percussiveRatio = scale(percRatio.toDouble(), 0.05, 0.65),
            harmonicRatio = 1f - percRatio,
            onsetRate = scale(onsetRate.toDouble(), 2.0, 6.5),
            flux = scale(onset.average() / 500.0, 0.05, 1.0),
        )
    }

    private fun scale(value: Double, low: Double, high: Double): Float {
        if (value.isNaN() || value.isInfinite()) return 0.5f
        return ((value - low) / (high - low)).coerceIn(0.0, 1.0).toFloat()
    }

    // ---------------------------------------------------------- interpretation

    private fun interpret(
        f: Features,
        bpm: Float?,
        camelot: String?,
        keyName: String?,
    ): Result {
        val tempoTerm = bpm?.let { ((it - 65f) / 105f).coerceIn(0f, 1f) } ?: 0.5f

        val arousal = spread(
            0.32f * f.onsetRate + 0.28f * f.percussiveRatio +
                0.20f * f.flux + 0.20f * tempoTerm,
            centre = 0.45f, halfRange = 0.18f,
        )

        val mode = when (camelot?.lastOrNull()?.uppercaseChar()) {
            'B' -> 0.78f
            'A' -> 0.26f
            else -> 0.5f
        }
        val valence = spread(
            0.38f * mode + 0.26f * f.brightness + 0.10f * f.contrast +
                0.14f * tempoTerm - 0.16f * f.noisiness + 0.08f * f.harmonicRatio + 0.08f,
            centre = 0.40f, halfRange = 0.18f,
        )

        val acousticness = spread(
            0.45f * f.harmonicRatio + 0.25f * (1f - f.noisiness) +
                0.15f * f.dynamics + 0.15f * (1f - f.bandwidth),
            centre = 0.45f, halfRange = 0.13f,
        )
        val tension = spread(
            0.40f * f.noisiness + 0.25f * f.zcr + 0.20f * (1f - f.contrast) +
                0.15f * f.bandwidth,
            centre = 0.50f, halfRange = 0.12f,
        )

        return Result(
            bpm = bpm,
            keyCamelot = camelot,
            keyName = keyName,
            energy = arousal,
            danceability = (0.6f * f.percussiveRatio + 0.4f * tempoTerm).coerceIn(0f, 1f),
            valence = valence,
            arousal = arousal,
            acousticness = acousticness,
            tension = tension,
            moods = tags(valence, arousal, acousticness, tension, f, tempoTerm),
        )
    }

    /**
     * Mood labels, each a soft region of the feature space.
     *
     * Several fire at once with different strengths, because music is genuinely
     * several things at once -- calm and gentle and a little melancholy is the
     * normal case, and a single label would throw most of that away.
     */
    private fun tags(
        valence: Float, arousal: Float, acousticness: Float, tension: Float,
        f: Features, tempoTerm: Float,
    ): List<Pair<String, Float>> {
        // family -> pole -> candidates
        val scored = HashMap<String, MutableList<Triple<String, Int, Float>>>()

        // Geometric mean, so every condition has to hold reasonably well and a
        // single strong term cannot carry a label the way a sum would.
        fun add(name: String, family: String, pole: Int, vararg terms: Float) {
            var product = 1.0
            for (t in terms) product *= t.coerceIn(0f, 1f).toDouble()
            val score = product.pow(1.0 / terms.size).toFloat()
            scored.getOrPut(family) { mutableListOf() } += Triple(name, pole, score)
        }

        fun lo(x: Float) = 1f - x
        fun near(v: Float, centre: Float, width: Float) =
            (1f - abs(v - centre) / width).coerceIn(0f, 1f)

        // ENERGY -- how much the track moves. Pole 0 is still, pole 1 is driven.
        add("calm", ENERGY, 0, lo(arousal), lo(f.onsetRate), lo(f.flux))
        add("gentle", ENERGY, 0, lo(arousal), acousticness, lo(tension))
        add("dreamy", ENERGY, 0, lo(arousal), f.brightness, lo(f.onsetRate), lo(tension))
        add("energetic", ENERGY, 1, arousal, f.onsetRate, f.flux)
        add("driving", ENERGY, 1, arousal, f.percussiveRatio, tempoTerm)
        add("intense", ENERGY, 1, arousal, f.flux, f.percussiveRatio)
        add("aggressive", ENERGY, 1, arousal, tension, f.percussiveRatio, f.zcr)

        // FEELING -- which way it leans. Pole 0 is downcast, pole 1 is bright.
        add("melancholic", FEELING, 0, lo(valence), lo(arousal), acousticness)
        add("sad", FEELING, 0, lo(valence), lo(f.brightness), lo(f.contrast))
        add("brooding", FEELING, 0, lo(valence), lo(arousal), lo(f.brightness))
        add("dark", FEELING, 0, lo(valence), lo(f.brightness), lo(f.dynamics))
        add("tense", FEELING, 0, tension, f.noisiness, lo(valence))
        add("joyful", FEELING, 1, valence, f.brightness, f.contrast)
        add("uplifting", FEELING, 1, valence, arousal, f.brightness)
        add("playful", FEELING, 1, valence, near(arousal, 0.62f, 0.3f), f.onsetRate)
        add("romantic", FEELING, 1, lo(arousal), acousticness, near(valence, 0.58f, 0.28f))
        add("warm", FEELING, 1, acousticness, lo(f.noisiness), near(f.brightness, 0.45f, 0.3f))

        // TEXTURE -- what it is made of. Pole 0 is spare, pole 1 is packed.
        add("acoustic", TEXTURE, 0, acousticness, lo(f.noisiness), f.dynamics)
        add("spacious", TEXTURE, 0, f.dynamics, lo(f.bandwidth), lo(f.onsetRate))
        add("dense", TEXTURE, 1, f.bandwidth, f.onsetRate, lo(f.dynamics))
        add("epic", TEXTURE, 1, arousal, f.dynamics, f.contrast)

        // One pole wins per family, and only labels on that side may be kept.
        //
        // Scoring every label independently and taking the top five produced
        // descriptions like "tense, calm, sad, brooding, epic" -- five labels
        // that cannot all be true of one song. Worse, labels built from two
        // terms beat labels built from four almost every time, because a
        // geometric mean is dragged down by its weakest term, so the shallowest
        // labels crowded out the specific ones. Deciding a side first, then
        // allowing close runners-up on that side only, keeps the multi-label
        // description the library needs without the contradictions.
        val out = ArrayList<Pair<String, Float>>(4)
        for (family in listOf(ENERGY, FEELING, TEXTURE)) {
            val candidates = scored[family] ?: continue
            val best = candidates.maxByOrNull { it.third } ?: continue
            if (best.third < 0.42f) continue                  // family says nothing
            out += best.first to round(best.third)
            candidates
                .filter { it.second == best.second && it.first != best.first }
                .filter { it.third >= best.third - 0.06f && it.third >= 0.42f }
                .sortedByDescending { it.third }
                .take(1)
                .forEach { out += it.first to round(it.third) }
        }
        return out.sortedByDescending { it.second }
    }

    /**
     * Expand a blended feature back into a usable range.
     *
     * Each of these is a weighted mean of several 0..1 terms, and averaging
     * concentrates values toward the middle -- that is a property of the mean,
     * not a tuning mistake. Measured across a real library, tension spanned only
     * 0.40..0.59 and acousticness 0.36..0.57, so thresholds written for a 0..1
     * feature could not separate anything and a single label landed on every
     * track.
     *
     * Stretching about the observed centre is monotone: it changes no track's
     * rank against another, only how far apart they sit. The centres come from
     * measuring this library rather than from taste, and would want revisiting
     * for a collection of a very different character.
     */
    private fun spread(value: Float, centre: Float, halfRange: Float): Float =
        (0.5f + (value - centre) / (2f * halfRange)).coerceIn(0f, 1f)

    private fun round(v: Float) = (v * 1000).toInt() / 1000f

}
