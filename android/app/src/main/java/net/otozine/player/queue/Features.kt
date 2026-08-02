package net.otozine.player.queue

import net.otozine.player.library.Track
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The feature space the queue engine reasons in.
 *
 * The plan calls for CLAP embeddings here. Those need the ML ingest stages,
 * which do not exist yet -- but the engine does not care *where* its vector
 * comes from, only that similar music lands near similar music. So this builds
 * one from what the DSP stage already measured: tempo, key, energy and
 * danceability. When embeddings arrive they replace [vectorFor] and nothing
 * else changes.
 *
 * Every value is normalised to 0..1 so that plain Euclidean distance is
 * meaningful without per-dimension scaling.
 */
object Features {

    /** Dimensions: valence, energy, danceability, tempo, keyX, keyY. */
    const val DIMENSIONS = 6

    // Weights: what "similar" should mean. Energy and valence dominate because
    // they are what a listener actually notices; key matters only for smooth
    // transitions, so it is weighted low here and applied separately in
    // sequencing.
    private val WEIGHTS = floatArrayOf(1.0f, 1.0f, 0.6f, 0.5f, 0.3f, 0.3f)

    fun vectorFor(track: Track): FloatArray = floatArrayOf(
        valenceOf(track),
        track.energy.coerceIn(0f, 1f),
        track.danceability.coerceIn(0f, 1f),
        tempoNorm(track.bpm),
        keyX(track.keyCamelot),
        keyY(track.keyCamelot),
    )

    /** Weighted Euclidean distance, normalised so 0 = identical, 1 = far apart. */
    fun distance(a: FloatArray, b: FloatArray): Float {
        var sum = 0f
        var norm = 0f
        for (i in 0 until DIMENSIONS) {
            val d = (a[i] - b[i]) * WEIGHTS[i]
            sum += d * d
            norm += WEIGHTS[i] * WEIGHTS[i]
        }
        return sqrt(sum / norm).coerceIn(0f, 1f)
    }

    fun similarity(a: FloatArray, b: FloatArray): Float = 1f - distance(a, b)

    /**
     * Estimated valence (musical "brightness"), 0 = bleak, 1 = joyful.
     *
     * Derived, not measured -- Essentia's valence head is part of the ML stages
     * that are not built. Three signals that genuinely correlate with mood:
     *
     *  - **Mode.** Camelot codes end in B for major and A for minor. Major/minor
     *    is the single strongest cheap predictor of perceived happiness.
     *  - **Tempo.** Faster reads brighter, tapering off past ~150 BPM where it
     *    reads frantic rather than happy.
     *  - **Energy.** Loud and dense reads more positive than sparse and quiet.
     *
     * Labelled as inferred wherever it is shown, because it is a heuristic and
     * will occasionally be confidently wrong about a cheerful song in a minor key.
     */
    fun valenceOf(track: Track): Float {
        var v = 0.5f

        track.keyCamelot?.lastOrNull()?.uppercaseChar()?.let { mode ->
            v += if (mode == 'B') 0.18f else -0.18f
        }

        track.bpm?.let { bpm ->
            // Peaks around 140, falls away either side.
            val warmth = 1f - (abs(bpm - 140f) / 90f).coerceIn(0f, 1f)
            v += (warmth - 0.5f) * 0.22f
        }

        v += (track.energy.coerceIn(0f, 1f) - 0.5f) * 0.20f
        return v.coerceIn(0f, 1f)
    }

    /** 70..180 BPM mapped to 0..1; outside that range is clamped. */
    fun tempoNorm(bpm: Float?): Float {
        if (bpm == null || bpm <= 0f) return 0.5f
        return ((bpm - 70f) / 110f).coerceIn(0f, 1f)
    }

    /**
     * Camelot code as a point on the wheel.
     *
     * Placing the 12 positions on a circle means 12 and 1 are neighbours, which
     * plain numeric distance would get badly wrong. Mode shifts the radius so
     * relative major/minor sit near each other but not on top of each other.
     */
    fun keyX(camelot: String?): Float {
        val (number, major) = parseCamelot(camelot) ?: return 0.5f
        val angle = (number - 1) * (2 * PI / 12)
        val radius = if (major) 1.0 else 0.72
        return ((cos(angle) * radius + 1) / 2).toFloat()
    }

    fun keyY(camelot: String?): Float {
        val (number, major) = parseCamelot(camelot) ?: return 0.5f
        val angle = (number - 1) * (2 * PI / 12)
        val radius = if (major) 1.0 else 0.72
        return ((sin(angle) * radius + 1) / 2).toFloat()
    }

    /**
     * Harmonic distance between two Camelot codes.
     *
     * 0 = same key, 1 = a compatible move (adjacent on the wheel or the
     * relative major/minor), 2+ = increasingly dissonant. Mirrors
     * `camelot.py` on the Librarian side.
     */
    fun harmonicDistance(a: String?, b: String?): Int {
        val pa = parseCamelot(a) ?: return 99
        val pb = parseCamelot(b) ?: return 99
        val (na, majorA) = pa
        val (nb, majorB) = pb

        val steps = minOf((na - nb + 12) % 12, (nb - na + 12) % 12)
        return if (majorA == majorB) steps else if (steps == 0) 1 else steps + 1
    }

    private fun parseCamelot(code: String?): Pair<Int, Boolean>? {
        if (code.isNullOrBlank() || code.length < 2) return null
        val mode = code.last().uppercaseChar()
        if (mode != 'A' && mode != 'B') return null
        val number = code.dropLast(1).toIntOrNull() ?: return null
        if (number !in 1..12) return null
        return number to (mode == 'B')
    }
}
