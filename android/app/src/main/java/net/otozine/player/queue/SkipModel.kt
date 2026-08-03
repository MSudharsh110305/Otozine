package net.otozine.player.queue

import net.otozine.player.library.PlayHistory
import net.otozine.player.library.Track
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sin

/**
 * Learns which songs you skip, and when.
 *
 * Until now the engine only knew *what* you had played, never what you had
 * rejected in a given situation -- so a track you reliably skip on the walk to
 * work was treated exactly like one you never reach. Affinity captures the
 * first half of that; this captures the half that depends on context.
 *
 * Logistic regression, fitted by stochastic gradient descent, on purpose. With a
 * couple of hundred tracks and a few thousand events anything larger would
 * memorise the history rather than generalise from it, and a model whose
 * weights can be read and reasoned about is worth more here than one that
 * scores marginally better. It also trains in milliseconds, so it can be
 * refitted whenever the app starts rather than kept in sync incrementally.
 */
class SkipModel(private val weights: FloatArray = FloatArray(FEATURES)) {

    /**
     * Probability that this track would be skipped, in this context.
     *
     * Returns 0.5 -- "no opinion" -- until there is enough history to fit
     * anything. A model guessing from nothing should not be allowed to reorder
     * a queue.
     */
    fun predict(track: Track, hour: Int, dayOfWeek: Int, output: String): Float {
        if (!trained) return 0.5f
        return sigmoid(dot(features(track, hour, dayOfWeek, output)))
    }

    var trained: Boolean = false
        private set

    fun exportWeights(): FloatArray = weights.copyOf()

    fun importWeights(saved: FloatArray) {
        if (saved.size != FEATURES) return
        saved.copyInto(weights)
        trained = true
    }

    /**
     * Fit from recorded listens.
     *
     * @param lookup track features by id; events for tracks that have since
     *   disappeared are skipped rather than filled with zeros, which would
     *   otherwise teach the model that "unknown" predicts whatever those events
     *   happened to be.
     */
    fun train(
        outcomes: List<PlayHistory.Outcome>,
        lookup: (Long) -> Track?,
        passes: Int = 12,
        learningRate: Float = 0.08f,
    ): Int {
        val rows = outcomes.mapNotNull { outcome ->
            val track = lookup(outcome.trackId) ?: return@mapNotNull null
            features(track, outcome.hour, outcome.dayOfWeek, outcome.output) to
                if (outcome.skipped) 1f else 0f
        }

        // Below this there is nothing to learn that is not noise. Twelve events
        // can be fitted perfectly and predict nothing.
        if (rows.size < MIN_EVENTS) {
            trained = false
            return rows.size
        }

        weights.fill(0f)
        repeat(passes) {
            for ((x, y) in rows) {
                val error = sigmoid(dot(x)) - y
                for (i in weights.indices) {
                    // L2 pull toward zero. Without it a feature that appears in
                    // only a handful of events can acquire a huge weight and
                    // dominate every prediction afterwards.
                    weights[i] -= learningRate * (error * x[i] + L2 * weights[i])
                }
            }
        }
        trained = true
        return rows.size
    }

    private fun dot(x: FloatArray): Float {
        var sum = 0f
        for (i in weights.indices) sum += weights[i] * x[i]
        return sum
    }

    /**
     * The feature vector.
     *
     * Hour is encoded as a sine/cosine pair rather than a number, because 23:00
     * and 01:00 are two hours apart and a raw hour makes them the furthest
     * points in the day. Everything is kept roughly in -1..1 so one input cannot
     * dominate the gradient by scale alone.
     */
    private fun features(track: Track, hour: Int, dayOfWeek: Int, output: String): FloatArray {
        val angle = 2f * PI.toFloat() * (hour.coerceIn(0, 23) / 24f)
        val tempo = track.bpm?.let { ln((it / 120f).coerceAtLeast(0.1f)) } ?: 0f
        val lower = output.lowercase()
        return floatArrayOf(
            1f,                                              // bias
            sin(angle),
            cos(angle),
            if (dayOfWeek >= 5) 1f else 0f,                  // weekend
            if ("blue" in lower || "wired" in lower || "head" in lower) 1f else 0f,
            if ("speaker" in lower) 1f else 0f,
            track.energy * 2f - 1f,
            track.danceability * 2f - 1f,
            tempo.coerceIn(-1.5f, 1.5f),
            if (track.keyCamelot?.endsWith("A") == true) 1f else 0f,   // minor
        )
    }

    private fun sigmoid(z: Float): Float = 1f / (1f + exp(-z.coerceIn(-30f, 30f)))

    companion object {
        const val FEATURES = 10
        const val KEY = "skip_model_v1"

        /** Fewer events than this and the fit is memorisation, not learning. */
        const val MIN_EVENTS = 40
        private const val L2 = 0.004f
    }
}
