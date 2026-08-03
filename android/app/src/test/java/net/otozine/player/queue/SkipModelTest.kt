package net.otozine.player.queue

import net.otozine.player.library.PlayHistory
import net.otozine.player.library.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Does the model actually learn something, or only run?
 *
 * A logistic fit will always produce numbers. The tests that matter are whether
 * those numbers separate cases that genuinely differ, and whether it stays
 * silent when it has no business having an opinion.
 */
class SkipModelTest {

    private fun track(id: Long, energy: Float, bpm: Float = 120f) = Track(
        id = id, contentHash = "h$id", title = "Song $id",
        artist = null, composer = null, album = null, year = null, language = null,
        durationMs = 200_000L, opusPath = "a.opus", artPath = null,
        bpm = bpm, keyCamelot = "8A",
        energy = energy, danceability = energy,
        replayGainDb = 0f, introEndMs = 0L, outroStartMs = 0L, hookStartMs = 0L,
    )

    private val calm = track(1, 0.15f, 80f)
    private val loud = track(2, 0.95f, 165f)
    private fun lookup(id: Long) = if (id == 1L) calm else loud

    /** Late at night, loud tracks get skipped and quiet ones do not. */
    private fun nightPattern(count: Int): List<PlayHistory.Outcome> {
        val random = Random(4)
        return (0 until count).map {
            val late = random.nextBoolean()
            val hour = if (late) 23 else 14
            val isLoud = random.nextBoolean()
            PlayHistory.Outcome(
                trackId = if (isLoud) 2L else 1L,
                skipped = late && isLoud,
                hour = hour,
                dayOfWeek = random.nextInt(7),
                output = "Bluetooth",
            )
        }
    }

    @Test
    fun `it learns that loud tracks are skipped late at night`() {
        val model = SkipModel()
        val used = model.train(nightPattern(400), ::lookup)
        assertTrue("expected the events to be usable", used > 300)
        assertTrue("model should have fitted", model.trained)

        val loudAtNight = model.predict(loud, hour = 23, dayOfWeek = 2, output = "Bluetooth")
        val loudMidday = model.predict(loud, hour = 14, dayOfWeek = 2, output = "Bluetooth")
        val calmAtNight = model.predict(calm, hour = 23, dayOfWeek = 2, output = "Bluetooth")

        println(
            "loud@23 %.2f  loud@14 %.2f  calm@23 %.2f"
                .format(loudAtNight, loudMidday, calmAtNight)
        )
        assertTrue(
            "the hour should matter: %.2f at 23:00 against %.2f at 14:00"
                .format(loudAtNight, loudMidday),
            loudAtNight > loudMidday + 0.15f,
        )
        assertTrue(
            "the track should matter: loud %.2f against calm %.2f at the same hour"
                .format(loudAtNight, calmAtNight),
            loudAtNight > calmAtNight + 0.15f,
        )
    }

    @Test
    fun `it stays silent until there is enough history`() {
        val model = SkipModel()
        model.train(nightPattern(10), ::lookup)

        // Ten events can be fitted perfectly and predict nothing. A model
        // guessing from nothing must not be allowed to reorder a queue.
        assertTrue("should not claim to be trained", !model.trained)
        assertEquals(
            "an untrained model must return no opinion",
            0.5f, model.predict(loud, 23, 2, "Bluetooth"), 0.0001f,
        )
    }

    @Test
    fun `weights survive a save and load`() {
        val model = SkipModel()
        model.train(nightPattern(400), ::lookup)
        val before = model.predict(loud, 23, 2, "Bluetooth")

        val restored = SkipModel()
        restored.importWeights(model.exportWeights())

        assertTrue("restored model should be usable", restored.trained)
        assertEquals(
            "a reloaded model must predict identically",
            before, restored.predict(loud, 23, 2, "Bluetooth"), 0.0001f,
        )
    }

    @Test
    fun `an hour late at night is close to one just after midnight`() {
        // Hour is encoded as a sine/cosine pair for exactly this reason: as a
        // raw number 23 and 1 are the furthest apart in the day, when they are
        // in fact two hours apart and behave alike.
        val model = SkipModel()
        model.train(nightPattern(400), ::lookup)

        val elevenPm = model.predict(loud, 23, 2, "Bluetooth")
        val oneAm = model.predict(loud, 1, 2, "Bluetooth")
        val twoPm = model.predict(loud, 14, 2, "Bluetooth")

        assertTrue(
            "23:00 (%.2f) and 01:00 (%.2f) should agree more than 23:00 and 14:00 (%.2f)"
                .format(elevenPm, oneAm, twoPm),
            kotlin.math.abs(elevenPm - oneAm) < kotlin.math.abs(elevenPm - twoPm),
        )
    }
}
