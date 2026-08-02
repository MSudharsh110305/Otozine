package net.otozine.player.analysis

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import net.otozine.player.library.Track
import net.otozine.player.library.isDevice
import java.io.File
import kotlin.coroutines.coroutineContext

/**
 * Run the analysis on the phone and write the results into the library.
 *
 * This is what removes the PC from the loop. The Librarian still does more --
 * acoustic fingerprinting, online metadata, the dual-tier transcode -- but the
 * parts that decide how the app *behaves* (loudness levelling, tempo, key,
 * mood) can all be measured here.
 *
 * Deliberately not a WorkManager job. Analysis is something you start and watch
 * finish, with the screen on; handing it to a background scheduler would make
 * it slower to start, harder to show progress for, and prone to being deferred
 * exactly when the user is standing there waiting.
 */
class AnalysisWorker(
    private val context: Context,
    /** Where phone-only tracks keep their measurements. */
    private val history: net.otozine.player.library.PlayHistory? = null,
) {

    data class Progress(
        val done: Int = 0,
        val total: Int = 0,
        val currentTitle: String = "",
        val finished: Boolean = false,
        val failed: Int = 0,
    )

    private val root: File get() = File(context.filesDir, "library")

    /**
     * Analyse every track that has not been measured yet.
     *
     * @param tracks candidates; already-analysed ones are skipped so this can be
     *   re-run freely after adding music.
     */
    suspend fun analyseMissing(
        tracks: List<Track>,
        resolve: (Track) -> Uri?,
        onProgress: (Progress) -> Unit,
    ): Progress = withContext(Dispatchers.Default) {
        // Phone tracks are measured too, but their results go elsewhere.
        //
        // They are built on the fly from MediaStore and carry synthetic negative
        // ids rather than rows in library.db, so an UPDATE against one would
        // match nothing and the run would report success having changed
        // nothing. They are written to the history database instead, which is
        // app data and is not replaced when the drive syncs.
        val pending = tracks.filter {
            it.bpm == null && it.opusPath != null && (!it.isDevice || history != null)
        }
        if (pending.isEmpty()) {
            return@withContext Progress(finished = true).also(onProgress)
        }

        val dbFile = File(root, "library.db")
        // Only library tracks need the file; phone tracks do not.
        if (!dbFile.isFile && pending.none { it.isDevice }) {
            return@withContext Progress(finished = true).also(onProgress)
        }

        var done = 0
        var failed = 0
        val progressLock = Mutex()
        val dbLock = Mutex()

        // Analyse several tracks at once.
        //
        // Measured on a real phone, decoding is not CPU-bound: reading the
        // packets takes 0.4 s while decoding them takes 9 s, because every
        // compressed frame is a round trip to the codec service and a song is
        // thousands of frames. Time spent waiting on that boundary is time
        // another track can be decoding, so a few in flight nearly multiplies
        // throughput -- where more threads on a CPU-bound job would not.
        //
        // Three, not eight: each track in flight holds a decode buffer, and the
        // heap is the binding constraint long before the cores are.
        val lanes = minOf(3, Runtime.getRuntime().availableProcessors())
        val gate = Semaphore(lanes)

        coroutineScope {
            pending.map { track ->
                async {
                    gate.withPermit {
                        // Cancellable at track granularity, so leaving the
                        // screen stops the run promptly rather than at the end.
                        coroutineContext.ensureActive()
                        progressLock.withLock {
                            onProgress(Progress(done, pending.size, track.displayTitle))
                        }

                        val uri = resolve(track)
                        val result =
                            if (uri == null) null
                            else runCatching { Measure.track(context, uri) }.getOrNull()

                        if (result != null) {
                            // One writer at a time: SQLite would serialise these
                            // anyway, and doing it here keeps the failure mode a
                            // wait rather than a locked-database error.
                            dbLock.withLock {
                                runCatching {
                                    if (track.isDevice) persistDevice(track.id, result)
                                    else persist(dbFile, track.id, result)
                                }.onFailure {
                                    Log.w(TAG, "could not save analysis for ${track.id}", it)
                                }
                            }
                        }

                        progressLock.withLock {
                            done++
                            if (result == null) failed++
                        }
                    }
                }
            }.awaitAll()
        }

        Progress(done, pending.size, "", finished = true, failed = failed).also(onProgress)
    }

    private fun persistDevice(trackId: Long, result: Measured) {
        val a = result.analysis
        history?.saveDeviceAnalysis(
            trackId,
            net.otozine.player.library.PlayHistory.DeviceAnalysis(
                bpm = a.bpm,
                keyCamelot = a.keyCamelot,
                replayGainDb = result.loudness.gainFor(),
                energy = a.energy,
                danceability = a.danceability,
                valence = a.valence,
                arousal = a.arousal,
                durationMs = result.durationMs,
                moods = a.moods.map { it.first },
            ),
            loudness = result.loudness.integratedLufs,
        )
    }

    private fun persist(dbFile: File, trackId: Long, result: Measured) {
        val db = SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READWRITE)
        try {
            db.beginTransaction()
            val a = result.analysis
            db.execSQL(
                """
                UPDATE tracks SET
                    bpm = ?, key_camelot = ?, key_name = ?,
                    loudness_lufs = ?, true_peak_db = ?, replaygain_db = ?,
                    energy = ?, danceability = ?, valence = ?, arousal = ?,
                    approachability = ?, engagement = ?,
                    duration_ms = COALESCE(NULLIF(duration_ms, 0), ?),
                    analyzed_at = ?
                WHERE id = ?
                """.trimIndent(),
                arrayOf<Any?>(
                    a.bpm, a.keyCamelot, a.keyName,
                    result.loudness.integratedLufs, result.loudness.truePeakDb,
                    result.loudness.gainFor(),
                    a.energy, a.danceability, a.valence, a.arousal,
                    a.acousticness, a.tension,
                    result.durationMs,
                    System.currentTimeMillis() / 1000,
                    trackId,
                ),
            )

            // Mood tags live in the same table and under the same source name as
            // the Librarian writes, so the reader cannot tell which produced
            // them and nothing downstream needs to care.
            ensureTagsTable(db)
            db.execSQL(
                "DELETE FROM tags WHERE track_id = ? AND source = 'mood'",
                arrayOf<Any?>(trackId),
            )
            for ((tag, confidence) in a.moods) {
                db.execSQL(
                    "INSERT OR REPLACE INTO tags (track_id, tag, kind, source, confidence) " +
                        "VALUES (?,?,'mood','mood',?)",
                    arrayOf<Any?>(trackId, tag, confidence),
                )
            }
            db.setTransactionSuccessful()
        } finally {
            runCatching { db.endTransaction() }
            db.close()
        }
    }

    /** A library the app built itself has no tags table until now. */
    private fun ensureTagsTable(db: SQLiteDatabase) {
        try {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS tags (
                    track_id   INTEGER NOT NULL,
                    tag        TEXT    NOT NULL,
                    kind       TEXT    NOT NULL,
                    source     TEXT    NOT NULL,
                    confidence REAL    NOT NULL DEFAULT 1.0,
                    PRIMARY KEY (track_id, tag, source)
                )
                """.trimIndent()
            )
        } catch (e: SQLiteException) {
            Log.w(TAG, "could not ensure tags table", e)
        }
    }


    companion object {
        private const val TAG = "OtoZineAnalysis"

    }
}
