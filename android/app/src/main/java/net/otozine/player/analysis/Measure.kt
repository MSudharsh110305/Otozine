package net.otozine.player.analysis

import android.content.Context
import android.net.Uri

/** Everything the phone can measure about one file. */
data class Measured(
    val loudness: LoudnessMeter.Result,
    val analysis: TrackAnalyser.Result,
    val durationMs: Long,
)

/**
 * The single definition of "measure this track".
 *
 * Both the library analyser and the drive transfer need exactly this, and two
 * copies would be two things to keep calibrated: a track measured on its way to
 * the drive has to land in the same feature space as one measured in place, or
 * the queue engine ends up comparing numbers that were never comparable.
 */
object Measure {

    /** Enough to characterise a song, and small enough for several in flight. */
    private const val CHARACTER_SECONDS = 90

    fun track(context: Context, uri: Uri): Measured? {
        // One decode, two consumers with different appetites.
        //
        // Loudness must see the whole track -- R128 gating is defined over the
        // programme, and judging a song by 90 seconds of a quiet intro gives a
        // gain that is wrong for the rest of it. Character is the opposite: a
        // representative stretch is better than the whole thing, and cheaper.
        //
        // So loudness accumulates as the audio streams past and only the
        // opening 90 seconds are retained. Peak memory is fixed regardless of
        // track length -- holding whole decoded tracks was an OutOfMemoryError
        // on the first four-minute song this met.
        val rate = LoudnessMeter.SAMPLE_RATE
        val session = LoudnessMeter.Session()
        val keep = FloatArray(CHARACTER_SECONDS * rate)
        var kept = 0
        var total = 0L

        val decoded = AudioDecoder.decodeStreaming(context, uri) { chunk, length ->
            session.accept(chunk, length)
            total += length
            if (kept < keep.size) {
                val room = minOf(keep.size - kept, length)
                System.arraycopy(chunk, 0, keep, kept, room)
                kept += room
            }
        }
        if (!decoded || kept == 0) return null

        val seconds = total.toFloat() / rate
        // Skip the intro when the track is long enough to spare it; otherwise
        // there is nothing to be choosy with and the opening has to do.
        val window = if (seconds > 120f && kept > 60 * rate) {
            keep.copyOfRange(30 * rate, kept)
        } else {
            keep.copyOf(kept)
        }

        return Measured(
            loudness = session.finish(),
            analysis = TrackAnalyser.analyse(window),
            durationMs = (seconds * 1000).toLong(),
        )
    }
}
