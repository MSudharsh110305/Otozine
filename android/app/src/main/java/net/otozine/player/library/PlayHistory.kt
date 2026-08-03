package net.otozine.player.library

import android.content.Context
import android.content.SharedPreferences
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Play history: what the queue engine learns from.
 *
 * Writes go to a **separate** database from the synced library, deliberately.
 * `library.db` is replaced wholesale on every sync from the drive; if events
 * lived there they would be destroyed every time. Keeping them apart also makes
 * the eventual merge-back trivial -- this file *is* the outbox.
 *
 * The schema mirrors `play_events` and `transitions` in the Librarian's
 * schema.sql, so a future sync can insert these rows directly.
 */
class PlayHistory(private val context: Context) {

    /** Everything the engine needs, read once per queue build. */
    data class Snapshot(
        val lastPlayedAt: Map<Long, Long> = emptyMap(),
        val completions: Map<Long, Int> = emptyMap(),
        val skips: Map<Long, Int> = emptyMap(),
        /** (from, to) -> unix seconds it was last served. */
        val transitions: Map<Pair<Long, Long>, Long> = emptyMap(),
        /** Learned per-track boredom half-life, in hours. */
        val tauHours: Map<Long, Double> = emptyMap(),
        val totalEvents: Int = 0,
    )

    private val prefs: SharedPreferences =
        context.getSharedPreferences("otozine.history", Context.MODE_PRIVATE)

    /** Stable per-install id, so merged events can be attributed to this phone. */
    val deviceId: String
        get() = prefs.getString(KEY_DEVICE_ID, null) ?: UUID.randomUUID().toString()
            .also { prefs.edit().putString(KEY_DEVICE_ID, it).apply() }

    private val dbFile: File get() = File(context.filesDir, "history.db")

    private var db: SQLiteDatabase? = null

    private fun open(): SQLiteDatabase? {
        db?.let { if (it.isOpen) return it }
        return try {
            SQLiteDatabase.openOrCreateDatabase(dbFile, null).also {
                it.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS play_events (
                        event_id      INTEGER PRIMARY KEY AUTOINCREMENT,
                        track_id      INTEGER NOT NULL,
                        session_id    TEXT NOT NULL,
                        prev_track_id INTEGER,
                        started_at    INTEGER NOT NULL,
                        ms_played     INTEGER NOT NULL,
                        pct_played    REAL NOT NULL,
                        outcome       TEXT NOT NULL,
                        ctx_hour      INTEGER,
                        ctx_dow       INTEGER,
                        ctx_output    TEXT,
                        synced        INTEGER NOT NULL DEFAULT 0
                    )
                    """.trimIndent()
                )
                it.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS transitions (
                        from_track     INTEGER NOT NULL,
                        to_track       INTEGER NOT NULL,
                        last_served_at INTEGER NOT NULL,
                        serve_count    INTEGER NOT NULL DEFAULT 1,
                        PRIMARY KEY (from_track, to_track)
                    )
                    """.trimIndent()
                )
                it.execSQL("CREATE INDEX IF NOT EXISTS idx_events_track ON play_events(track_id)")
                // Analysis of the phone's own music.
                //
                // It lives here rather than in library.db because that file is
                // replaced wholesale every time the drive syncs -- measurements
                // of tracks that only exist on the phone would be destroyed on
                // every plug-in. This database is app data and survives.
                it.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS device_analysis (
                        track_id     INTEGER PRIMARY KEY,
                        bpm          REAL,
                        key_camelot  TEXT,
                        loudness     REAL,
                        replaygain   REAL,
                        energy       REAL,
                        danceability REAL,
                        valence      REAL,
                        arousal      REAL,
                        duration_ms  INTEGER,
                        moods        TEXT,
                        analysed_at  INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                // Your own mood labels. Multi-label on purpose: a track can be
                // calm *and* gentle *and* a little melancholy, and forcing one
                // label throws away most of what you actually said.
                it.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS mood_feedback (
                        track_id  INTEGER NOT NULL,
                        mood      TEXT    NOT NULL,
                        weight    REAL    NOT NULL DEFAULT 1.0,
                        set_at    INTEGER NOT NULL,
                        PRIMARY KEY (track_id, mood)
                    )
                    """.trimIndent()
                )
                db = it
            }
        } catch (e: SQLiteException) {
            Log.e(TAG, "cannot open history db", e)
            null
        }
    }

    // ------------------------------------------------------------- writing

    /**
     * Record a finished (or abandoned) play.
     *
     * `outcome` is derived from how far through the track got, because *when*
     * you skip means very different things: bailing in the first few seconds is
     * a rejection of the track, while stopping at 80% is essentially a complete
     * listen. Collapsing both into "skipped" is what makes naive players learn
     * the wrong lesson.
     */
    fun record(
        trackId: Long,
        previousTrackId: Long?,
        sessionId: String,
        startedAt: Long,
        msPlayed: Long,
        durationMs: Long,
        output: String,
    ) {
        val database = open() ?: return
        val pct = if (durationMs > 0) (msPlayed.toDouble() / durationMs) else 0.0
        val outcome = when {
            pct >= 0.9 -> "completed"
            pct < 0.05 && msPlayed < 5_000 -> "skipped"      // hard reject
            pct < 0.5 -> "skipped"
            else -> "abandoned"                              // most of the way through
        }

        val calendar = java.util.Calendar.getInstance()
        try {
            database.execSQL(
                "INSERT INTO play_events (track_id, session_id, prev_track_id, started_at, " +
                    "ms_played, pct_played, outcome, ctx_hour, ctx_dow, ctx_output) " +
                    "VALUES (?,?,?,?,?,?,?,?,?,?)",
                arrayOf<Any?>(
                    trackId, sessionId, previousTrackId, startedAt, msPlayed, pct, outcome,
                    calendar.get(java.util.Calendar.HOUR_OF_DAY),
                    (calendar.get(java.util.Calendar.DAY_OF_WEEK) + 5) % 7,   // 0 = Monday
                    output,
                ),
            )
        } catch (e: SQLiteException) {
            Log.w(TAG, "could not record play event", e)
        }
    }

    /**
     * Record that a transition was *served* -- not that it was played.
     *
     * Serving is the right trigger: the point is to avoid building the same
     * path twice, whether or not you sat through it.
     */
    fun recordTransitions(pairs: List<Pair<Long, Long>>) {
        if (pairs.isEmpty()) return
        val database = open() ?: return
        val now = System.currentTimeMillis() / 1000
        database.beginTransaction()
        try {
            pairs.forEach { (from, to) ->
                database.execSQL(
                    "INSERT INTO transitions (from_track, to_track, last_served_at, serve_count) " +
                        "VALUES (?,?,?,1) " +
                        "ON CONFLICT(from_track, to_track) DO UPDATE SET " +
                        "last_served_at = excluded.last_served_at, serve_count = serve_count + 1",
                    arrayOf<Any?>(from, to, now),
                )
            }
            database.setTransactionSuccessful()
        } catch (e: SQLiteException) {
            Log.w(TAG, "could not record transitions", e)
        } finally {
            database.endTransaction()
        }
    }

    // ------------------------------------------------------- mood feedback

    /**
     * Replace your mood labels for one track.
     *
     * A full replace rather than an append, because deselecting a mood has to
     * actually remove it -- otherwise a mislabel can never be taken back and
     * the labels only ever accumulate.
     */
    fun setMoods(trackId: Long, moods: Set<String>) {
        val database = open() ?: return
        val now = System.currentTimeMillis() / 1000
        database.beginTransaction()
        try {
            database.execSQL("DELETE FROM mood_feedback WHERE track_id = ?", arrayOf<Any?>(trackId))
            moods.forEach { mood ->
                database.execSQL(
                    "INSERT OR REPLACE INTO mood_feedback (track_id, mood, weight, set_at) " +
                        "VALUES (?,?,1.0,?)",
                    arrayOf<Any?>(trackId, mood.lowercase(), now),
                )
            }
            database.setTransactionSuccessful()
        } catch (e: SQLiteException) {
            Log.w(TAG, "could not save moods", e)
        } finally {
            database.endTransaction()
        }
    }

    fun moodsFor(trackId: Long): Set<String> {
        val database = open() ?: return emptySet()
        val out = HashSet<String>()
        try {
            database.rawQuery(
                "SELECT mood FROM mood_feedback WHERE track_id = ?",
                arrayOf(trackId.toString()),
            ).use { c -> while (c.moveToNext()) out.add(c.getString(0)) }
        } catch (e: SQLiteException) {
            Log.w(TAG, "mood read failed", e)
        }
        return out
    }

    /** Every track you have labelled, for the queue engine. */
    fun allMoods(): Map<Long, Set<String>> {
        val database = open() ?: return emptyMap()
        val out = HashMap<Long, MutableSet<String>>()
        try {
            database.rawQuery("SELECT track_id, mood FROM mood_feedback", null).use { c ->
                while (c.moveToNext()) {
                    out.getOrPut(c.getLong(0)) { HashSet() }.add(c.getString(1))
                }
            }
        } catch (e: SQLiteException) {
            Log.w(TAG, "mood read failed", e)
        }
        return out
    }

    fun labelledCount(): Int {
        val database = open() ?: return 0
        return try {
            database.rawQuery(
                "SELECT COUNT(DISTINCT track_id) FROM mood_feedback", null,
            ).use { if (it.moveToFirst()) it.getInt(0) else 0 }
        } catch (e: SQLiteException) {
            0
        }
    }

    // ------------------------------------------------------------- reading

    fun snapshot(): Snapshot {
        val database = open() ?: return Snapshot()

        val lastPlayed = HashMap<Long, Long>()
        val completions = HashMap<Long, Int>()
        val skips = HashMap<Long, Int>()
        var total = 0

        try {
            database.rawQuery(
                "SELECT track_id, MAX(started_at) AS last, " +
                    "SUM(CASE WHEN outcome='completed' THEN 1 ELSE 0 END) AS done, " +
                    "SUM(CASE WHEN outcome='skipped' THEN 1 ELSE 0 END) AS skipped, " +
                    "COUNT(*) AS n " +
                    "FROM play_events GROUP BY track_id",
                null,
            ).use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    lastPlayed[id] = c.getLong(1)
                    completions[id] = c.getInt(2)
                    skips[id] = c.getInt(3)
                    total += c.getInt(4)
                }
            }
        } catch (e: SQLiteException) {
            Log.w(TAG, "history read failed", e)
        }

        val transitions = HashMap<Pair<Long, Long>, Long>()
        try {
            database.rawQuery(
                "SELECT from_track, to_track, last_served_at FROM transitions", null,
            ).use { c ->
                while (c.moveToNext()) {
                    transitions[c.getLong(0) to c.getLong(1)] = c.getLong(2)
                }
            }
        } catch (e: SQLiteException) {
            Log.w(TAG, "transition read failed", e)
        }

        return Snapshot(
            lastPlayedAt = lastPlayed,
            completions = completions,
            skips = skips,
            transitions = transitions,
            tauHours = learnTau(completions, skips),
            totalEvents = total,
        )
    }

    /**
     * Per-track boredom half-life.
     *
     * A track you replay happily earns a short cooldown; one you skip earns a
     * long one. Without this a single global cooldown treats a favourite and a
     * track you tolerate identically.
     */
    private fun learnTau(
        completions: Map<Long, Int>,
        skips: Map<Long, Int>,
    ): Map<Long, Double> {
        val ids = completions.keys + skips.keys
        return ids.associateWith { id ->
            val done = completions[id] ?: 0
            val skipped = skips[id] ?: 0
            // Half-lives chosen against how a day is actually listened to.
            //
            // The intent is that a favourite comes back *between* sessions but
            // never twice *within* one: open the app in the morning and hear it,
            // open it again in the evening and hear it again. A queue never
            // repeats a track inside itself, so the within-session half of that
            // is already guaranteed and cooldown only has to govern the gap.
            //
            // These were far too long. A favourite on a 24 h half-life is still
            // at 31% freshness nine hours later, so the song you like most came
            // back weakest in the evening -- the opposite of what it should do.
            when {
                done >= 3 && skipped == 0 -> 5.0         // favourite: back by tonight
                done > skipped -> 14.0                   // liked: back tomorrow
                skipped > done * 2 -> 24.0 * 21          // you do not want this
                else -> QueueDefaults.TAU_HOURS
            }
        }
    }

    /** Rows waiting to be merged back onto the drive. */
    fun pendingSyncCount(): Int {
        val database = open() ?: return 0
        return try {
            database.rawQuery("SELECT COUNT(*) FROM play_events WHERE synced = 0", null).use {
                if (it.moveToFirst()) it.getInt(0) else 0
            }
        } catch (e: SQLiteException) {
            0
        }
    }

    /** One unsynced play event, as a line for the drive's event log. */
    data class Pending(val ids: List<Long>, val jsonl: String)

    /**
     * Everything the drive has not seen yet.
     *
     * The log is append-only and keyed by device, so merging on the other side
     * is a union with nothing to resolve -- which is the whole reason the
     * history is stored as events rather than as running totals.
     */
    fun unsynced(deviceId: String, limit: Int = 5000): Pending {
        val database = open() ?: return Pending(emptyList(), "")
        val ids = ArrayList<Long>()
        val lines = StringBuilder()
        try {
            database.rawQuery(
                "SELECT event_id, track_id, session_id, prev_track_id, started_at, ms_played, " +
                    "pct_played, outcome, ctx_hour, ctx_dow, ctx_output FROM play_events " +
                    "WHERE synced = 0 ORDER BY id LIMIT ?",
                arrayOf(limit.toString()),
            ).use { c ->
                while (c.moveToNext()) {
                    ids += c.getLong(0)
                    // Built with JSONObject rather than string concatenation:
                    // song titles and output names are user data and will
                    // eventually contain a quote or a backslash, which hand-rolled
                    // JSON turns into a corrupt log line nobody notices until the
                    // merge fails.
                    val row = JSONObject()
                        .put("device", deviceId)
                        .put("id", c.getLong(0))
                        .put("track_id", c.getLong(1))
                        .put("session_id", c.getString(2))
                        .put("prev_track_id", if (c.isNull(3)) JSONObject.NULL else c.getLong(3))
                        .put("started_at", c.getLong(4))
                        .put("ms_played", c.getLong(5))
                        .put("pct_played", c.getFloat(6).toDouble())
                        .put("outcome", c.getString(7))
                        .put("ctx_hour", c.getInt(8))
                        .put("ctx_dow", c.getInt(9))
                        .put("ctx_output", c.getString(10) ?: "")
                    lines.append(row.toString()).append('\n')
                }
            }
        } catch (e: SQLiteException) {
            Log.w(TAG, "could not read pending events", e)
        }
        return Pending(ids, lines.toString())
    }

    /** Called only once the drive has the events, so a failed write retries. */
    fun markSynced(ids: List<Long>) {
        if (ids.isEmpty()) return
        val database = open() ?: return
        try {
            // Ids come from our own cursor, never from outside, so inlining
            // them is safe -- and IN (?) cannot be parameterised as a list.
            database.execSQL(
                "UPDATE play_events SET synced = 1 WHERE event_id IN (" +
                    ids.joinToString(",") + ")"
            )
        } catch (e: SQLiteException) {
            Log.w(TAG, "could not mark events synced", e)
        }
    }

    /** One finished listen, as the skip model needs it. */
    data class Outcome(
        val trackId: Long,
        val skipped: Boolean,
        val hour: Int,
        val dayOfWeek: Int,
        val output: String,
    )

    /**
     * Every recorded listen, oldest first.
     *
     * Ordered because the model is fitted by passing over them in sequence, and
     * later evidence should carry more weight than the first thing you ever
     * played.
     */
    fun outcomes(limit: Int = 20_000): List<Outcome> {
        val database = open() ?: return emptyList()
        val out = ArrayList<Outcome>()
        try {
            database.rawQuery(
                "SELECT track_id, outcome, ctx_hour, ctx_dow, ctx_output FROM play_events " +
                    "ORDER BY event_id LIMIT ?",
                arrayOf(limit.toString()),
            ).use { c ->
                while (c.moveToNext()) {
                    out += Outcome(
                        trackId = c.getLong(0),
                        skipped = c.getString(1) == "skipped",
                        hour = c.getInt(2),
                        dayOfWeek = c.getInt(3),
                        output = c.getString(4) ?: "",
                    )
                }
            }
        } catch (e: SQLiteException) {
            Log.w(TAG, "could not read outcomes", e)
        }
        return out
    }

    /** Model weights, kept beside the events they were fitted from. */
    fun saveWeights(key: String, weights: FloatArray) {
        val database = open() ?: return
        try {
            database.execSQL(
                "CREATE TABLE IF NOT EXISTS model_state (key TEXT PRIMARY KEY, value TEXT)"
            )
            database.execSQL(
                "INSERT OR REPLACE INTO model_state (key, value) VALUES (?,?)",
                arrayOf<Any?>(key, weights.joinToString(",")),
            )
        } catch (e: SQLiteException) {
            Log.w(TAG, "could not save model weights", e)
        }
    }

    fun loadWeights(key: String, size: Int): FloatArray? {
        val database = open() ?: return null
        return try {
            database.execSQL(
                "CREATE TABLE IF NOT EXISTS model_state (key TEXT PRIMARY KEY, value TEXT)"
            )
            database.rawQuery(
                "SELECT value FROM model_state WHERE key = ?", arrayOf(key),
            ).use { c ->
                if (!c.moveToFirst()) return null
                val parts = c.getString(0).split(",").mapNotNull { it.toFloatOrNull() }
                if (parts.size == size) parts.toFloatArray() else null
            }
        } catch (e: SQLiteException) {
            null
        }
    }

    // ---------------------------------------------- analysis of phone music

    /** What the phone measured about one of its own tracks. */
    data class DeviceAnalysis(
        val bpm: Float?,
        val keyCamelot: String?,
        val replayGainDb: Float,
        val energy: Float,
        val danceability: Float,
        val valence: Float,
        val arousal: Float,
        val durationMs: Long,
        val moods: List<String>,
    )

    fun saveDeviceAnalysis(trackId: Long, a: DeviceAnalysis, loudness: Float) {
        val database = open() ?: return
        try {
            database.execSQL(
                "INSERT OR REPLACE INTO device_analysis (track_id, bpm, key_camelot, loudness, " +
                    "replaygain, energy, danceability, valence, arousal, duration_ms, moods, " +
                    "analysed_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
                arrayOf<Any?>(
                    trackId, a.bpm, a.keyCamelot, loudness, a.replayGainDb, a.energy,
                    a.danceability, a.valence, a.arousal, a.durationMs,
                    a.moods.joinToString(","), System.currentTimeMillis() / 1000,
                ),
            )
        } catch (e: SQLiteException) {
            Log.w(TAG, "could not save device analysis", e)
        }
    }

    fun deviceAnalysis(): Map<Long, DeviceAnalysis> {
        val database = open() ?: return emptyMap()
        val out = HashMap<Long, DeviceAnalysis>()
        try {
            database.rawQuery(
                "SELECT track_id, bpm, key_camelot, replaygain, energy, danceability, " +
                    "valence, arousal, duration_ms, moods FROM device_analysis", null,
            ).use { c ->
                while (c.moveToNext()) {
                    out[c.getLong(0)] = DeviceAnalysis(
                        bpm = if (c.isNull(1)) null else c.getFloat(1),
                        keyCamelot = c.getString(2),
                        replayGainDb = c.getFloat(3),
                        energy = c.getFloat(4),
                        danceability = c.getFloat(5),
                        valence = c.getFloat(6),
                        arousal = c.getFloat(7),
                        durationMs = c.getLong(8),
                        moods = c.getString(9)?.split(",")?.filter { it.isNotBlank() }.orEmpty(),
                    )
                }
            }
        } catch (e: SQLiteException) {
            Log.w(TAG, "could not read device analysis", e)
        }
        return out
    }

    fun close() {
        db?.takeIf { it.isOpen }?.close()
        db = null
    }

    private object QueueDefaults {
        const val TAU_HOURS = 72.0
    }

    companion object {
        private const val TAG = "OtoZineHistory"
        private const val KEY_DEVICE_ID = "device_id"
    }
}
