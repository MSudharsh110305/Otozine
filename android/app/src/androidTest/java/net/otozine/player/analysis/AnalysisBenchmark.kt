package net.otozine.player.analysis

import android.content.ContentUris
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Times the on-device analysis against real music on a real phone, using the
 * same three-lane arrangement as AnalysisWorker.
 *
 * The unit tests prove the DSP is *correct*; they say nothing about whether it
 * is fast enough to be usable, nor whether its labels are informative. Both of
 * those only show up against a real library: the first run here found that a
 * four-minute song exhausted the heap, that 15 of 20 tracks came back in the
 * same key, and that nearly every track was labelled "tense".
 */
class AnalysisBenchmark {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun measureRealTracks() = runBlocking(Dispatchers.Default) {
        // Granted here rather than by an adb command: the test package is
        // installed and removed around each run, so a grant issued from outside
        // has nothing to apply to. Without it MediaStore returns an empty
        // cursor, which looks exactly like a phone with no music on it.
        InstrumentationRegistry.getInstrumentation().uiAutomation
            .grantRuntimePermission(context.packageName, "android.permission.READ_MEDIA_AUDIO")

        val tracks = findTracks().take(20)
        assertTrue("no audio found under Download/songs", tracks.isNotEmpty())

        Log.i(TAG, "=== analysing ${tracks.size} tracks, 3 lanes ===")
        val lock = Mutex()
        val gate = Semaphore(3)
        var ok = 0
        val keys = mutableListOf<String>()
        val moodCounts = HashMap<String, Int>()
        val feat = mutableListOf<FloatArray>()
        val wallStart = System.currentTimeMillis()

        coroutineScope {
            tracks.map { (name, uri) ->
                async {
                    gate.withPermit {
                        val started = System.currentTimeMillis()

                        // Mirrors AnalysisWorker: stream once, accumulate
                        // loudness, retain only the opening 90 s for character.
                        val rate = LoudnessMeter.SAMPLE_RATE
                        val session = LoudnessMeter.Session()
                        val keep = FloatArray(90 * rate)
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
                        if (!decoded || kept == 0) {
                            Log.w(TAG, "DECODE FAILED  $name")
                            return@withPermit
                        }

                        val loudness = session.finish()
                        val seconds = total.toFloat() / rate
                        val window = if (seconds > 120f && kept > 60 * rate) {
                            keep.copyOfRange(30 * rate, kept)
                        } else keep.copyOf(kept)

                        val analysis = TrackAnalyser.analyse(window)
                        val elapsed = System.currentTimeMillis() - started

                        lock.withLock {
                            ok++
                            analysis.keyCamelot?.let { keys += it }
                            feat += floatArrayOf(
                                analysis.valence, analysis.arousal,
                                analysis.acousticness, analysis.tension,
                            )
                            for ((tag, _) in analysis.moods) {
                                moodCounts[tag] = (moodCounts[tag] ?: 0) + 1
                            }
                            Log.i(
                                TAG,
                                "%-30s %5.0fs %6dms | %6.1f LUFS %4s bpm %-3s | %s".format(
                                    name.take(30), seconds, elapsed,
                                    loudness.integratedLufs,
                                    analysis.bpm?.let { b -> "%.0f".format(b) } ?: "--",
                                    analysis.keyCamelot ?: "--",
                                    analysis.moods.joinToString(" ") { (t, _) -> t },
                                ),
                            )
                        }
                    }
                }
            }.awaitAll()
        }

        val wall = System.currentTimeMillis() - wallStart
        Log.i(
            TAG,
            "=== %d tracks | %.1fs wall | %.1fs per track ===".format(
                ok, wall / 1000.0, wall / 1000.0 / maxOf(ok, 1)
            ),
        )
        Log.i(TAG, "distinct keys: ${keys.distinct().sorted().joinToString(" ")}")
        val commonest = keys.groupingBy { it }.eachCount().maxByOrNull { it.value }
        Log.i(TAG, "commonest key: $commonest of $ok")
        // Feature ranges decide whether a label can ever fire; if a feature
        // never leaves the top of its range no threshold below it can discriminate.
        val names = listOf("valence", "arousal", "acoustic", "tension")
        for (i in names.indices) {
            val col = feat.map { it[i] }.sorted()
            Log.i(TAG, "%-9s min %.2f  p50 %.2f  max %.2f".format(
                names[i], col.first(), col[col.size / 2], col.last()))
        }
        Log.i(
            TAG,
            "mood spread: " + moodCounts.entries.sortedByDescending { it.value }
                .joinToString(" ") { "${it.key}=${it.value}" },
        )

        assertTrue("nothing decoded", ok > 0)
    }

    private fun findTracks(): List<Pair<String, Uri>> {
        val out = mutableListOf<Pair<String, Uri>>()
        val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        context.contentResolver.query(
            collection,
            arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.DISPLAY_NAME),
            "${MediaStore.Audio.Media.RELATIVE_PATH} LIKE ?",
            arrayOf("%songs%"),
            MediaStore.Audio.Media.DISPLAY_NAME,
        )?.use { c ->
            while (c.moveToNext()) {
                out += c.getString(1) to ContentUris.withAppendedId(collection, c.getLong(0))
            }
        }
        return out
    }

    companion object {
        private const val TAG = "OtoZineBench"
    }
}
