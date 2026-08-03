package net.otozine.player.queue

import net.otozine.player.library.PlayHistory
import net.otozine.player.library.Track
import kotlin.math.abs
import kotlin.math.exp
import kotlin.random.Random

/**
 * The anti-repeat queue engine.
 *
 * This exists to fix the specific complaint the whole project started from:
 * a stock shuffle repeats songs, and worse, repeats *orders*. Song B always
 * seems to follow song A. Most players randomise the set; almost none track
 * which transitions they have already served.
 *
 * Five layers, applied in order:
 *
 *  1. **Candidates** -- everything playable.
 *  2. **Cooldown** -- a track played recently is suppressed, recovering on an
 *     exponential curve rather than a hard cutoff.
 *  3. **Transition blocking** -- any (A -> B) pair served recently is penalised,
 *     so the same path is never walked twice. This is the layer that addresses
 *     "same pattern of shuffles".
 *  4. **Diversity** -- greedy MMR against the recent picks, so the queue spans
 *     the feature space instead of clustering on one vibe.
 *  5. **Sequencing** -- among near-equal candidates, prefer a harmonically
 *     compatible key and a small tempo step, so transitions feel deliberate.
 *
 * The session seed is derived from the clock, so two sessions over an identical
 * library cannot produce the same order even before any history exists.
 */
class QueueEngine(
    private val tracks: List<Track>,
    private val history: PlayHistory.Snapshot,
    /**
     * Mood labels per track: yours where you gave them, the analyser's
     * otherwise. Words rather than coordinates, because "gentle" is a thing a
     * person can say and a point in feature space is not.
     */
    private val moodTags: Map<Long, Set<String>> = emptyMap(),
    seed: Long = System.currentTimeMillis(),
    /** Fitted from your own listening; sits idle until there is enough of it. */
    private val skipModel: SkipModel? = null,
    /** Named `clock` because `now` already means unix seconds in here. */
    private val clock: java.util.Calendar = java.util.Calendar.getInstance(),
    private val output: String = "",
) {

    private val random = Random(seed)
    private val vectors: Map<Long, FloatArray> =
        tracks.associate { it.id to Features.vectorFor(it) }

    data class Reason(val label: String, val weight: Float)

    data class Entry(
        val track: Track,
        val reasons: List<Reason>,
        val score: Float,
    ) {
        /** The single line shown on the "why" chip. */
        val headline: String get() = reasons.maxByOrNull { it.weight }?.label ?: "picked for you"
    }

    /**
     * @param adventure 0 = stay close to the seed, 1 = roam. Exposed in the UI
     *   as the Adventure slider; it is the exploration budget.
     */
    fun build(
        seedTrack: Track?,
        size: Int = 30,
        adventure: Float = 0.35f,
        mood: Mood? = null,
        languageFilter: Set<String> = emptySet(),
        /** Mood words to steer toward, e.g. {"calm", "gentle"}. */
        targetMoods: Set<String> = emptySet(),
    ): List<Entry> {
        val now = System.currentTimeMillis() / 1000

        var pool = tracks.filter { it.opusPath != null }
        if (languageFilter.isNotEmpty()) {
            pool = pool.filter { it.language in languageFilter }
        }
        if (pool.isEmpty()) return emptyList()

        val target: FloatArray = when {
            mood != null -> mood.toVector()
            seedTrack != null -> vectors.getValue(seedTrack.id)
            else -> centroidOfRecentlyLoved() ?: vectors.getValue(pool.random(random).id)
        }

        val chosen = ArrayList<Entry>(size)
        val taken = HashSet<Long>()
        seedTrack?.let { taken.add(it.id) }
        var previous: Track? = seedTrack

        repeat(size) {
            val next = pickNext(
                pool, target, taken, chosen, previous, now, adventure, targetMoods,
            )
                ?: return@repeat
            chosen.add(next)
            taken.add(next.track.id)
            previous = next.track
        }
        return chosen
    }

    private fun pickNext(
        pool: List<Track>,
        target: FloatArray,
        taken: Set<Long>,
        chosen: List<Entry>,
        previous: Track?,
        now: Long,
        adventure: Float,
        targetMoods: Set<String>,
    ): Entry? {
        var best: Entry? = null
        var bestScore = Float.NEGATIVE_INFINITY

        for (track in pool) {
            if (track.id in taken) continue

            val reasons = ArrayList<Reason>(4)
            val vector = vectors.getValue(track.id)

            // --- affinity to where we are heading -------------------------
            val similarity = Features.similarity(target, vector)
            var score = similarity
            // Unanalysed tracks all carry the same neutral vector, so they are
            // all trivially "similar" to everything. Claiming that as a reason
            // would be stating a fact about missing data as if it were taste.
            if (similarity > 0.72f && track.bpm != null) {
                reasons += Reason("similar feel to what's playing", similarity)
            }

            // --- mood match ------------------------------------------------
            // Applied before cooldown so a strongly matching track can still
            // lose to freshness; a mood request should shape the queue, not
            // override the reason the queue exists.
            if (targetMoods.isNotEmpty()) {
                val labels = moodTags[track.id].orEmpty()
                val overlap = labels.count { it in targetMoods }
                if (overlap > 0) {
                    score *= 1f + 0.55f * overlap
                    reasons += Reason(
                        labels.filter { it in targetMoods }.joinToString(" + "),
                        0.85f,
                    )
                } else if (labels.isNotEmpty()) {
                    // Labelled, and not what was asked for.
                    score *= 0.35f
                }
            }

            // --- cooldown: recently played sinks, and recovers with time ---
            val lastPlayed = history.lastPlayedAt[track.id]
            if (lastPlayed != null) {
                val hours = (now - lastPlayed) / 3600.0
                val tau = history.tauHours[track.id] ?: DEFAULT_TAU_HOURS
                // 0 immediately after a play, approaching 1 as time passes.
                val freshness = (1.0 - exp(-hours / tau)).toFloat().coerceIn(0f, 1f)
                score *= (0.15f + 0.85f * freshness)
                if (freshness > 0.9f && hours > 24 * 30) {
                    reasons += Reason("you haven't heard this in ${(hours / 24 / 30).toInt()} months", 0.8f)
                }
            } else {
                // Never played. Given a deliberate boost so new music is not
                // starved by history it has had no chance to accumulate.
                score *= 1.0f + 0.25f * adventure
                reasons += Reason("never played", 0.55f + 0.3f * adventure)
            }

            // --- affinity: what you actually keep listening to --------------
            //
            // Until now nothing in the engine preferred a song you love. A
            // favourite was only *penalised less* by cooldown, which is not the
            // same thing: against a library of tracks never played at all, "a
            // smaller penalty" still loses, so favourites quietly disappeared
            // once there was enough unheard music to crowd them out.
            //
            // Completions minus skips over total plays gives -1..1 from real
            // behaviour, needing no ratings. It is weighted by (1 - adventure)
            // so the dial means something concrete: at 0 the queue leans on what
            // you have proven you like, at 1 it stops caring and goes exploring.
            val plays = (history.completions[track.id] ?: 0)
            val rejects = (history.skips[track.id] ?: 0)
            if (plays + rejects >= 2) {
                val affinity = (plays - rejects).toFloat() / (plays + rejects)
                score *= (1f + 0.6f * affinity * (1f - adventure)).coerceAtLeast(0.2f)
                if (affinity > 0.5f && plays >= 3) {
                    reasons += Reason("you play this a lot", 0.7f * (1f - adventure))
                }
            }

            // --- diversity: do not stack the queue on one vibe --------------
            //
            // Promised in this file's own header and never actually written:
            // `taken` only stopped a track appearing twice, which is not the
            // same as spanning the library. Without this, picking each track for
            // its similarity to the target makes every one of them similar to
            // all the others, and a thirty-track queue converges on a single
            // mood by construction.
            //
            // Greedy MMR: penalise a candidate by how close it already is to
            // what has been chosen. Only the recent picks count -- the queue
            // should be free to come back around later, and comparing against
            // all thirty would flatten the arc rather than vary it.
            if (chosen.isNotEmpty()) {
                var closest = 0f
                for (entry in chosen.takeLast(6)) {
                    val other = vectors[entry.track.id] ?: continue
                    val near = Features.similarity(vector, other)
                    if (near > closest) closest = near
                }
                // Scaled by adventure: at the comfort end a consistent run is
                // the point, at the adventure end variety is.
                score *= 1f - (0.45f * adventure + 0.15f) * closest.coerceIn(0f, 1f)
            }

            // --- learned skip risk -----------------------------------------
            //
            // What the engine could not see before: not whether you like a
            // track, but whether you skip it *here* -- this hour, on these
            // headphones. Returns 0.5 and changes nothing until there is enough
            // history to fit.
            skipModel?.let { model ->
                if (model.trained) {
                    val risk = model.predict(
                        track,
                        clock.get(java.util.Calendar.HOUR_OF_DAY),
                        (clock.get(java.util.Calendar.DAY_OF_WEEK) + 5) % 7,
                        output,
                    )
                    score *= (1f - 0.7f * (risk - 0.5f).coerceIn(-0.5f, 0.5f))
                    if (risk < 0.25f) reasons += Reason("you stay with this one", 0.6f)
                }
            }

            // --- artist/album spacing --------------------------------------
            if (previous != null) {
                if (track.composer != null && track.composer == previous.composer) {
                    score *= 0.55f
                }
                if (track.album != null && track.album == previous.album) {
                    score *= 0.6f
                }
            }

            // --- transition blocking: the anti-pattern layer ----------------
            if (previous != null) {
                val servedAt = history.transitions[previous.id to track.id]
                if (servedAt != null) {
                    val days = (now - servedAt) / 86400.0
                    // A pair served yesterday is nearly banned; one from a month
                    // ago is only mildly discouraged.
                    val penalty = exp(-days / TRANSITION_HALF_LIFE_DAYS).toFloat()
                    score *= (1f - 0.85f * penalty)
                }
            }

            // --- sequencing: reward a musical transition --------------------
            if (previous != null) {
                val harmonic = Features.harmonicDistance(previous.keyCamelot, track.keyCamelot)
                if (harmonic <= 1) {
                    score *= 1.12f
                    reasons += Reason(
                        if (harmonic == 0) "same key as the last track" else "key-compatible",
                        0.5f,
                    )
                }
                val a = previous.bpm
                val b = track.bpm
                if (a != null && b != null && a > 0) {
                    val drift = abs(b - a) / a
                    if (drift < 0.08f) {
                        score *= 1.08f
                        reasons += Reason("${if (b >= a) "+" else ""}${(b - a).toInt()} BPM", 0.4f)
                    }
                }
            }

            // --- exploration ------------------------------------------------
            // Random jitter scaled by the adventure dial. At 0 this is nearly
            // deterministic; at 1 it genuinely wanders.
            score *= 1f + (random.nextFloat() - 0.5f) * adventure

            if (score > bestScore) {
                bestScore = score
                best = Entry(track, reasons.sortedByDescending { it.weight }, score)
            }
        }
        return best
    }

    /** Feature-space centre of what has been completed most, recency-weighted. */
    private fun centroidOfRecentlyLoved(): FloatArray? {
        if (history.completions.isEmpty()) return null
        val sum = FloatArray(Features.DIMENSIONS)
        var weight = 0f
        history.completions.forEach { (trackId, count) ->
            val vector = vectors[trackId] ?: return@forEach
            val w = count.toFloat()
            for (i in sum.indices) sum[i] += vector[i] * w
            weight += w
        }
        if (weight == 0f) return null
        for (i in sum.indices) sum[i] /= weight
        return sum
    }

    /** A point in mood space, as set by the vibe pad. */
    data class Mood(
        /** 0 = bleak, 1 = bright. */
        val valence: Float,
        /** 0 = calm, 1 = intense. */
        val energy: Float,
    ) {
        fun toVector(): FloatArray = floatArrayOf(
            valence.coerceIn(0f, 1f),
            energy.coerceIn(0f, 1f),
            energy.coerceIn(0f, 1f),   // danceability tracks energy absent a better signal
            0.5f, 0.5f, 0.5f,          // tempo and key unconstrained by the pad
        )
    }

    companion object {
        /** Default boredom half-life before a track is fair game again. */
        /**
         * Half-life for a track with no verdict yet.
         *
         * Two days, not three: long enough that an unremarkable track does not
         * come straight back, short enough that the library keeps turning over
         * rather than a first listen effectively benching a song for most of a
         * week.
         */
        const val DEFAULT_TAU_HOURS = 48.0

        /** How fast a served A->B pair stops being penalised. */
        const val TRANSITION_HALF_LIFE_DAYS = 21.0
    }
}
