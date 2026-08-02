package net.otozine.player.queue

import net.otozine.player.library.PlayHistory
import net.otozine.player.library.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * The acceptance test for the original complaint.
 *
 * "Shuffle repeats songs, and repeats patterns" is the reason this project
 * exists, and until now it had never been checked as anything but a vibe. This
 * is the plan's stated test: run many consecutive sessions against a realistic
 * library and assert that no (A -> B) pair is ever served twice and that nothing
 * comes back inside its cooldown.
 *
 * Sized at 197 tracks because that is the real library, and the engine's
 * behaviour depends on having room to avoid things -- a test on a dozen tracks
 * would pass by having nothing to prove.
 */
class AntiRepeatTest {

    private val librarySize = 197
    private val sessionLength = 30
    private val sessions = 20

    /** A library with the spread of tempo, key and character real music has. */
    private fun library(size: Int = librarySize): List<Track> {
        val random = Random(20260802)
        val keys = listOf("1A", "2A", "3A", "4A", "5A", "6A", "7A", "8A", "9A", "10A", "11A", "12A",
            "1B", "2B", "3B", "4B", "5B", "6B", "7B", "8B", "9B", "10B", "11B", "12B")
        return (1..size).map { i ->
            Track(
                id = i.toLong(),
                contentHash = "hash$i",
                title = "Song $i",
                artist = null,
                composer = null,
                album = null,
                year = null,
                language = null,
                durationMs = 200_000L,
                opusPath = "audio/opus/$i.opus",
                artPath = null,
                bpm = 70f + random.nextFloat() * 100f,
                keyCamelot = keys[random.nextInt(keys.size)],
                energy = random.nextFloat(),
                danceability = random.nextFloat(),
                replayGainDb = 0f,
                introEndMs = 0L,
                outroStartMs = 0L,
                hookStartMs = 0L,
            )
        }
    }

    @Test
    fun `no transition is ever served twice across twenty sessions`() {
        val tracks = library()
        val servedTransitions = HashMap<Pair<Long, Long>, Long>()
        val lastPlayed = HashMap<Long, Long>()
        var now = System.currentTimeMillis() / 1000

        var repeatedPairs = 0
        var totalPairs = 0

        repeat(sessions) { session ->
            val engine = QueueEngine(
                tracks = tracks,
                history = PlayHistory.Snapshot(
                    lastPlayedAt = HashMap(lastPlayed),
                    transitions = HashMap(servedTransitions),
                ),
                // A fresh seed per session, exactly as the app does: without it
                // an identical library would walk an identical path.
                seed = session.toLong() * 7919,
            )
            val queue = engine.build(seedTrack = null, size = sessionLength)
            assertTrue("session $session came back short", queue.size >= sessionLength / 2)

            queue.map { it.track.id }.zipWithNext().forEach { pair ->
                totalPairs++
                if (servedTransitions.containsKey(pair)) repeatedPairs++
                servedTransitions[pair] = now
            }
            queue.forEach { lastPlayed[it.track.id] = now; now += 200 }
        }

        assertEquals(
            "a served (A->B) pair came back: $repeatedPairs of $totalPairs transitions",
            0, repeatedPairs,
        )
    }

    @Test
    fun `a single session never repeats a track`() {
        val tracks = library()
        val engine = QueueEngine(tracks, PlayHistory.Snapshot(), seed = 42L)
        val queue = engine.build(seedTrack = null, size = sessionLength)

        val ids = queue.map { it.track.id }
        assertEquals("the same song appears twice in one queue", ids.size, ids.distinct().size)
    }

    @Test
    fun `a track just played is not served again while it is still cooling`() {
        val tracks = library()
        val now = System.currentTimeMillis() / 1000
        // Everything heard a minute ago, except a tenth of the library.
        val recent = tracks.drop(librarySize / 10).associate { it.id to now - 60 }

        val engine = QueueEngine(
            tracks = tracks,
            history = PlayHistory.Snapshot(lastPlayedAt = recent),
            seed = 7L,
        )
        val queue = engine.build(seedTrack = null, size = sessionLength)

        // The rested tracks should dominate: cooldown is a strong preference,
        // not an absolute ban, or a small library would run out of anything to
        // play and start returning nothing at all.
        val rested = queue.count { it.track.id !in recent }
        assertTrue(
            "only $rested of ${queue.size} picks avoided recently played tracks",
            rested >= (queue.size * 0.6).toInt(),
        )
    }

    @Test
    fun `blocking is doing the work, not luck`() {
        // The 197-track run has room to avoid collisions by accident: there are
        // nearly 39,000 possible pairs and only a few hundred are ever served,
        // so "no repeats" could be chance rather than design. On a deliberately
        // cramped library the pigeonholes are few enough that random selection
        // would collide constantly, so passing here means the transition table
        // is genuinely being consulted.
        val small = 45
        val tracks = library(small)
        val served = HashMap<Pair<Long, Long>, Long>()
        val lastPlayed = HashMap<Long, Long>()
        var now = System.currentTimeMillis() / 1000
        var repeats = 0
        var total = 0

        repeat(sessions) { session ->
            val queue = QueueEngine(
                tracks = tracks,
                history = PlayHistory.Snapshot(
                    lastPlayedAt = HashMap(lastPlayed),
                    transitions = HashMap(served),
                ),
                seed = session.toLong() * 104729,
            ).build(seedTrack = null, size = sessionLength)

            queue.map { it.track.id }.zipWithNext().forEach { pair ->
                total++
                if (served.containsKey(pair)) repeats++
                served[pair] = now
            }
            queue.forEach { lastPlayed[it.track.id] = now; now += 200 }
        }

        // Random pairing over 45 tracks would be expected to repeat on the order
        // of a hundred times across this many draws.
        val expectedIfRandom = total.toDouble() * total / (2.0 * small * (small - 1))
        assertTrue(
            "the cramped case served too few transitions to prove anything",
            total > 300,
        )
        assertTrue(
            "$repeats repeats of $total transitions; random would give about " +
                "${expectedIfRandom.toInt()}, so blocking is not doing much",
            repeats < expectedIfRandom / 10,
        )
        println("cramped library: $repeats repeats of $total (random ~${expectedIfRandom.toInt()})")
    }

    @Test
    fun `favourites come back between sessions, never twice within one`() {
        // The behaviour asked for in plain words: "if I open the app in the
        // morning I expect my favourites, and again in the evening, but not
        // twice in the same sitting."
        //
        // Asserted as over-representation rather than "at least one came back",
        // because a 30-track queue drawn from 197 is noisy: 12 favourites would
        // appear about twice by chance alone, so a weak result proves nothing
        // either way.
        val tracks = library()
        val favourites = tracks.take(12).map { it.id }.toSet()
        val byChance = sessionLength.toDouble() * favourites.size / librarySize
        // Nine hours ago. The engine reads the wall clock, so an evening
        // session is modelled by putting the morning in the past rather than by
        // handing it a pretend "now" it would ignore.
        val morning = System.currentTimeMillis() / 1000 - 9 * 3600

        val tau = tracks.associate { track ->
            track.id to if (track.id in favourites) 5.0 else QueueEngine.DEFAULT_TAU_HOURS
        }
        // A library actually in use: favourites played through, the rest mixed.
        val completions = tracks.associate { it.id to if (it.id in favourites) 8 else 1 }
        val skips = tracks.associate { it.id to if (it.id in favourites) 0 else 1 }

        fun queueWith(played: Map<Long, Long>, adventure: Float, seed: Long) =
            QueueEngine(
                tracks = tracks,
                history = PlayHistory.Snapshot(
                    lastPlayedAt = played,
                    completions = completions,
                    skips = skips,
                    tauHours = tau,
                ),
                seed = seed,
            ).build(seedTrack = null, size = sessionLength, adventure = adventure)

        val morningQueue = queueWith(emptyMap(), 0.15f, 11L)
        val morningIds = morningQueue.map { it.track.id }
        assertEquals(
            "a track appeared twice in the morning session",
            morningIds.size, morningIds.distinct().size,
        )

        // Nine hours later, everything heard this morning is on cooldown.
        val heard = morningIds.toSet()
        val played = heard.associateWith { morning }

        // Averaged over several evenings: one queue is a coin toss, the tendency
        // is the thing being claimed.
        var returning = 0
        val evenings = 8
        repeat(evenings) { i ->
            val evening = queueWith(played, 0.15f, 100L + i)
            val ids = evening.map { it.track.id }
            assertEquals(
                "a track appeared twice in one evening session",
                ids.size, ids.distinct().size,
            )
            returning += ids.count { it in favourites }
        }
        val perEvening = returning.toDouble() / evenings

        println(
            "comfort: %.1f favourites per evening (chance %.1f)".format(perEvening, byChance)
        )
        assertTrue(
            "favourites are not preferred: %.1f per evening against %.1f by chance"
                .format(perEvening, byChance),
            perEvening > byChance * 1.5,
        )
    }

    @Test
    fun `adventure trades favourites for exploration`() {
        // What the dial is for. At the comfort end the queue should lean on what
        // you have proven you like; at the adventure end it should stop caring.
        // If both ends behave the same the slider is decoration.
        val tracks = library()
        val favourites = tracks.take(12).map { it.id }.toSet()
        val completions = tracks.associate { it.id to if (it.id in favourites) 8 else 1 }
        val skips = tracks.associate { it.id to if (it.id in favourites) 0 else 1 }

        fun favouritesAt(adventure: Float): Double {
            var total = 0
            val runs = 8
            repeat(runs) { i ->
                total += QueueEngine(
                    tracks = tracks,
                    history = PlayHistory.Snapshot(completions = completions, skips = skips),
                    seed = 500L + i,
                ).build(null, sessionLength, adventure = adventure)
                    .count { it.track.id in favourites }
            }
            return total.toDouble() / runs
        }

        val comfort = favouritesAt(0f)
        val adventurous = favouritesAt(1f)
        println("favourites per queue -- comfort %.1f, adventure %.1f".format(comfort, adventurous))
        assertTrue(
            "the dial changes nothing: %.1f at comfort against %.1f at adventure"
                .format(comfort, adventurous),
            comfort > adventurous,
        )
    }

    @Test
    fun `sessions differ from one another`() {
        val tracks = library()
        val first = QueueEngine(tracks, PlayHistory.Snapshot(), seed = 1L)
            .build(null, sessionLength).map { it.track.id }
        val second = QueueEngine(tracks, PlayHistory.Snapshot(), seed = 2L)
            .build(null, sessionLength).map { it.track.id }

        assertTrue("two sessions produced the same order", first != second)
    }
}
