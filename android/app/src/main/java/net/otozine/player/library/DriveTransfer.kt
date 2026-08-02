package net.otozine.player.library

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.provider.DocumentsContract
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
import net.otozine.player.analysis.Measure
import net.otozine.player.analysis.Measured
import java.io.File
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

/**
 * Copy music from the phone onto the drive, properly.
 *
 * The old path put a file in the drive's inbox and left the real work for a PC,
 * because the phone could not build the Opus tier. It can now, so this does the
 * whole job: archives the original, encodes the phone tier, measures the track
 * and writes the row -- the same end state `otozine ingest` would produce.
 *
 * Everything lands on the drive. The phone keeps no copy of the audio and no
 * lasting record of the track, so unplugging the drive takes the library with
 * it, which is the arrangement the vault is for.
 */
object DriveTransfer {

    private const val TAG = "OtoZineTransfer"

    /** What the progress belongs to, so the UI never has to infer it. */
    enum class Kind { TO_DRIVE, ON_PHONE }

    data class Progress(
        val kind: Kind = Kind.TO_DRIVE,
        val done: Int = 0,
        val total: Int = 0,
        val currentTitle: String = "",
        val stage: String = "",
        val finished: Boolean = false,
        val copied: Int = 0,
        val skipped: Int = 0,
        val failed: Int = 0,
        val error: String? = null,
    )

    /**
     * @param resolve where to read each track's audio from.
     * @param localDb the app's working copy of the drive database. Rows are
     *   added here and the file is pushed back to the drive at the end.
     */
    suspend fun copyToDrive(
        context: Context,
        treeUri: String,
        tracks: List<Track>,
        localDb: File,
        resolve: (Track) -> Uri?,
        onProgress: (Progress) -> Unit,
    ): Progress = withContext(Dispatchers.Default) {
        if (treeUri.isBlank()) {
            return@withContext Progress(
                finished = true, error = "No drive linked. Import from it first.",
            ).also(onProgress)
        }
        if (tracks.isEmpty()) {
            return@withContext Progress(finished = true).also(onProgress)
        }

        val tree = Uri.parse(treeUri)
        val otozine = findOtoZine(context, tree)
            ?: return@withContext Progress(
                finished = true,
                error = "No OtoZine folder on the drive. Run 'otozine init' once on a PC.",
            ).also(onProgress)

        val audio = ensureDir(context, tree, otozine, "audio")
            ?: return@withContext Progress(
                finished = true, error = "Could not write to the drive.",
            ).also(onProgress)
        val masterRoot = ensureDir(context, tree, audio, "master")
        val opusRoot = ensureDir(context, tree, audio, "opus")
        if (masterRoot == null || opusRoot == null) {
            return@withContext Progress(
                finished = true, error = "Could not create the audio folders.",
            ).also(onProgress)
        }

        // Warn once rather than per track: without an encoder every track would
        // report the same failure and the reason would be buried.
        val canEncode = OpusTranscoder.isAvailable()
        if (!canEncode) Log.w(TAG, "no Opus encoder; masters only")

        var copied = 0
        var failed = 0
        var skipped = 0
        val tally = Mutex()
        val driveLock = Mutex()
        var started = 0

        // Read the existing library once. Checking per track would reopen the
        // database a couple of hundred times to answer the same question.
        val existing = existingEntries(localDb)

        // Two songs at a time, not more.
        //
        // Unlike decoding -- which was waiting on the codec service and scaled
        // to three lanes for free -- Opus encoding is real CPU work, so each
        // lane is a core genuinely spent. Two roughly halves a long copy while
        // leaving the phone responsive and the battery intact; eight would make
        // the device unpleasant to hold for a job that runs in the background
        // anyway.
        val lanes = minOf(2, Runtime.getRuntime().availableProcessors())
        val gate = Semaphore(lanes)

        coroutineScope {
        tracks.map { track -> async { gate.withPermit {
            coroutineContext.ensureActive()

            suspend fun report(stage: String) = tally.withLock {
                onProgress(
                    Progress(
                        done = started, total = tracks.size,
                        currentTitle = track.displayTitle, stage = stage,
                        copied = copied, skipped = skipped, failed = failed,
                    )
                )
            }
            tally.withLock { started++ }

            val source = resolve(track)
            if (source == null) {
                tally.withLock { failed++ }
                return@withPermit
            }

            try {
                report("hashing")
                val hash = sha256(context, source)
                if (hash == null) {
                    tally.withLock { failed++ }
                    return@withPermit
                }

                // Skip what the drive already has, before paying for it: the
                // encode is over a minute a song, so re-doing known tracks is
                // the difference between a quick top-up and a long wait.
                if (existing.contains(hash, track.displayTitle, track.durationMs)) {
                    tally.withLock { skipped++ }
                    return@withPermit
                }
                val shard = hash.take(2)
                // From the MIME type, not the title: a display title is a song
                // name and may well contain a dot without being a filename.
                val extension = sourceExtension(context, source)

                // Content-addressed, sharded two levels deep, matching the
                // layout the Librarian writes. exFAT slows badly past roughly a
                // thousand entries in one directory, which a flat audio folder
                // would reach at a thousand songs.
                report("archiving original")
                val masterShard = driveLock.withLock { ensureDir(context, tree, masterRoot, shard) }
                    ?: run {
                        tally.withLock { failed++ }
                        return@withPermit
                    }
                val masterName = "$hash.$extension"
                if (child(context, tree, masterShard, masterName) == null) {
                    val target = DocumentsContract.createDocument(
                        context.contentResolver, masterShard, "audio/*", masterName,
                    ) ?: run {
                        tally.withLock { failed++ }
                        return@withPermit
                    }
                    context.contentResolver.openInputStream(source)?.use { input ->
                        context.contentResolver.openOutputStream(target)?.use { output ->
                            input.copyTo(output, 1 shl 16)
                        }
                    }
                }

                var opusRelative: String? = null
                if (canEncode) {
                    report("encoding phone copy")
                    val opusShard = driveLock.withLock { ensureDir(context, tree, opusRoot, shard) }
                    val opusName = "$hash.opus"
                    if (opusShard != null) {
                        val existing = child(context, tree, opusShard, opusName)
                        val target = existing ?: DocumentsContract.createDocument(
                            context.contentResolver, opusShard, "audio/ogg", opusName,
                        )
                        if (target != null) {
                            val ok = existing != null || context.contentResolver
                                .openFileDescriptor(target, "rw")
                                ?.use { OpusTranscoder.transcode(context, source, it) } == true
                            if (ok) opusRelative = "OtoZine/audio/opus/$shard/$opusName"
                            else DocumentsContract.deleteDocument(context.contentResolver, target)
                        }
                    }
                }

                report("measuring")
                val measured = Measure.track(context, source)

                // The Opus copy is what the player reaches for; fall back to the
                // archived original so a track without an encoder is still
                // playable rather than a row pointing at nothing.
                val playable = opusRelative ?: "OtoZine/audio/master/$shard/$masterName"

                // Publish after every song rather than once at the end.
                //
                // The audio is already on the drive at this point, so a row
                // written now can only point at a file that exists -- the
                // failure this ordering was guarding against cannot happen. And
                // the database is a hundred kilobytes against a minute of
                // encoding, so the cost is nothing next to the benefit: pull the
                // drive at any moment and it holds a complete, consistent
                // library of everything copied so far.
                // One writer for the database and the drive: SQLite would
                // serialise the insert anyway, and two lanes rewriting
                // library.db at once would race to leave a torn file on the one
                // artefact the whole library depends on.
                val landed = driveLock.withLock {
                    insert(localDb, track, hash, playable, measured)
                    // Read the row back before claiming the song was copied.
                    //
                    // The insert is `OR IGNORE`, which is silent about
                    // constraint failures by design, and the outer catch cannot
                    // see a statement that did nothing. Without this check the
                    // counter would happily climb to 196 while the drive gained
                    // nothing -- which is exactly the failure that is impossible
                    // to diagnose from the progress bar.
                    val ok = rowExists(localDb, hash)
                    if (ok) pushDatabase(context, tree, otozine, localDb)
                    else Log.w(TAG, "row did not land for ${track.displayTitle}")
                    ok
                }
                if (!landed) {
                    tally.withLock { failed++ }
                    return@withPermit
                }
                tally.withLock {
                    copied++
                    onProgress(
                        Progress(
                            done = copied + skipped + failed, total = tracks.size,
                            currentTitle = track.displayTitle, stage = "added",
                            copied = copied, skipped = skipped, failed = failed,
                        )
                    )
                }
            } catch (e: Exception) {
                Log.w(TAG, "could not copy ${track.displayTitle}", e)
                tally.withLock { failed++ }
            }
        } } }.awaitAll()
        }

        // A final push covers the case where every track was skipped and the
        // per-track pushes never ran.
        val saved = pushDatabase(context, tree, otozine, localDb)

        Progress(
            done = tracks.size, total = tracks.size, finished = true,
            copied = copied, failed = failed, skipped = skipped,
            error = if (saved) null else "Copied, but the drive database could not be updated.",
        ).also(onProgress)
    }

    /**
     * Append this phone's play history to the drive's event log.
     *
     * Written as a new file per sync rather than appended to one, because SAF
     * has no reliable append and a partial rewrite of a growing log would lose
     * history rather than duplicate it. The Librarian unions the files, and the
     * events carry ids, so an extra file costs nothing and a lost one costs
     * everything.
     *
     * @return true if the drive has the events and they can be marked synced.
     */
    fun syncEvents(
        context: Context,
        treeUri: String,
        deviceId: String,
        jsonl: String,
    ): Boolean {
        if (treeUri.isBlank() || jsonl.isBlank()) return false
        return try {
            val tree = Uri.parse(treeUri)
            val otozine = findOtoZine(context, tree) ?: return false
            val events = ensureDir(context, tree, otozine, "events") ?: return false

            val name = "phone-${deviceId.take(8)}-${System.currentTimeMillis() / 1000}.jsonl"
            val target = DocumentsContract.createDocument(
                context.contentResolver, events, "application/json", name,
            ) ?: return false

            context.contentResolver.openOutputStream(target)?.use { out ->
                out.write(jsonl.toByteArray())
                true
            } ?: false
        } catch (e: Exception) {
            Log.w(TAG, "could not sync events to the drive", e)
            false
        }
    }

    /**
     * What the drive already holds, for duplicate detection.
     *
     * Two tests, because one is not enough. Content hash is exact and catches
     * anything this phone copied before. It does *not* catch tracks the
     * Librarian ingested on a PC, because that side hashes with blake3 and this
     * side with SHA-256 -- the same bytes produce different strings, so those
     * would reimport forever.
     *
     * So title and duration are the second test. Same name and a length within
     * two seconds is the same song in practice; different songs sharing a title
     * almost never share a runtime that closely, and the cost of a rare false
     * skip is one track missing from the drive rather than a corrupted library.
     */
    private class Existing(
        private val hashes: Set<String>,
        private val titled: Map<String, List<Long>>,
    ) {
        fun contains(hash: String, title: String, durationMs: Long): Boolean {
            if (hash in hashes) return true
            val candidates = titled[normalise(title)] ?: return false
            if (durationMs <= 0) return candidates.isNotEmpty()
            return candidates.any { it > 0 && kotlin.math.abs(it - durationMs) <= 2_000 }
        }

        companion object {
            fun normalise(title: String) = title.lowercase()
                .replace(Regex("[^a-z0-9]+"), " ")
                .trim()
        }
    }

    private fun existingEntries(dbFile: File): Existing {
        if (!dbFile.isFile) return Existing(emptySet(), emptyMap())
        return try {
            val hashes = HashSet<String>()
            val titled = HashMap<String, MutableList<Long>>()
            SQLiteDatabase.openDatabase(
                dbFile.path, null, SQLiteDatabase.OPEN_READONLY,
            ).use { db ->
                db.rawQuery("SELECT content_hash, title, duration_ms FROM tracks", null).use { c ->
                    while (c.moveToNext()) {
                        c.getString(0)?.let { hashes += it }
                        val title = c.getString(1)
                        if (!title.isNullOrBlank()) {
                            titled.getOrPut(Existing.normalise(title)) { ArrayList() } +=
                                c.getLong(2)
                        }
                    }
                }
            }
            Existing(hashes, titled)
        } catch (e: Exception) {
            Log.w(TAG, "could not read existing tracks; nothing will be skipped", e)
            Existing(emptySet(), emptyMap())
        }
    }

    // ------------------------------------------------------------------ rows

    private fun insert(
        dbFile: File,
        track: Track,
        hash: String,
        playablePath: String,
        measured: Measured?,
    ) {
        if (!dbFile.isFile) return
        val db = SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READWRITE)
        try {
            db.beginTransaction()
            val a = measured?.analysis
            db.execSQL(
                "INSERT OR IGNORE INTO tracks (content_hash, source_path, opus_path, title, " +
                    "duration_ms, meta_source, meta_confidence, added_at, missing) " +
                    "VALUES (?,?,?,?,?,'filename',0.5,?,0)",
                arrayOf<Any?>(
                    hash, playablePath, playablePath, track.displayTitle,
                    measured?.durationMs ?: track.durationMs,
                    System.currentTimeMillis() / 1000,
                ),
            )

            if (measured != null && a != null) {
                db.execSQL(
                    """
                    UPDATE tracks SET
                        bpm = ?, key_camelot = ?, key_name = ?,
                        loudness_lufs = ?, true_peak_db = ?, replaygain_db = ?,
                        energy = ?, danceability = ?, valence = ?, arousal = ?,
                        approachability = ?, engagement = ?, analyzed_at = ?
                    WHERE content_hash = ?
                    """.trimIndent(),
                    arrayOf<Any?>(
                        a.bpm, a.keyCamelot, a.keyName,
                        measured.loudness.integratedLufs, measured.loudness.truePeakDb,
                        measured.loudness.gainFor(),
                        a.energy, a.danceability, a.valence, a.arousal,
                        a.acousticness, a.tension,
                        System.currentTimeMillis() / 1000, hash,
                    ),
                )

                val id = db.rawQuery(
                    "SELECT id FROM tracks WHERE content_hash = ?", arrayOf(hash),
                ).use { if (it.moveToFirst()) it.getLong(0) else null }

                if (id != null) {
                    db.execSQL(
                        "DELETE FROM tags WHERE track_id = ? AND source = 'mood'",
                        arrayOf<Any?>(id),
                    )
                    for ((tag, confidence) in a.moods) {
                        db.execSQL(
                            "INSERT OR REPLACE INTO tags (track_id, tag, kind, source, " +
                                "confidence) VALUES (?,?,'mood','mood',?)",
                            arrayOf<Any?>(id, tag, confidence),
                        )
                    }
                }
            }
            db.setTransactionSuccessful()
        } finally {
            runCatching { db.endTransaction() }
            db.close()
        }
    }

    /** Did the row actually land, and is it visible to the reader's filters? */
    private fun rowExists(dbFile: File, hash: String): Boolean = try {
        SQLiteDatabase.openDatabase(
            dbFile.path, null, SQLiteDatabase.OPEN_READONLY,
        ).use { db ->
            db.rawQuery(
                "SELECT 1 FROM tracks WHERE content_hash = ? AND missing = 0 " +
                    "AND opus_path IS NOT NULL",
                arrayOf(hash),
            ).use { it.moveToFirst() }
        }
    } catch (e: Exception) {
        Log.w(TAG, "could not confirm the row", e)
        false
    }

    private fun pushDatabase(context: Context, tree: Uri, otozine: Uri, localDb: File): Boolean =
        try {
            val existing = child(context, tree, otozine, "library.db")
            val target = existing ?: DocumentsContract.createDocument(
                context.contentResolver, otozine, "application/octet-stream", "library.db",
            )
            if (target == null) false
            else {
                context.contentResolver.openOutputStream(target, "wt")?.use { output ->
                    localDb.inputStream().use { it.copyTo(output, 1 shl 16) }
                    true
                } ?: false
            }
        } catch (e: Exception) {
            Log.w(TAG, "could not write library.db back to the drive", e)
            false
        }

    // ------------------------------------------------------------------ SAF

    private fun sha256(context: Context, uri: Uri): String? = try {
        val digest = MessageDigest.getInstance("SHA-256")
        context.contentResolver.openInputStream(uri)?.use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    } catch (e: Exception) {
        null
    }

    private fun sourceExtension(context: Context, uri: Uri): String =
        when (context.contentResolver.getType(uri)) {
            "audio/mpeg" -> "mp3"
            "audio/flac", "audio/x-flac" -> "flac"
            "audio/mp4", "audio/aac" -> "m4a"
            "audio/ogg", "audio/opus" -> "opus"
            "audio/wav", "audio/x-wav" -> "wav"
            else -> "audio"
        }

    private fun findOtoZine(context: Context, tree: Uri): Uri? {
        val root = DocumentsContract.buildDocumentUriUsingTree(
            tree, DocumentsContract.getTreeDocumentId(tree),
        )
        // The linked folder may be the drive root or the OtoZine folder itself.
        // Picking the wrong one shifts every path by a level, which is how a
        // previous import produced a library where nothing would play.
        return child(context, tree, root, "OtoZine")
            ?: root.takeIf { child(context, tree, it, "audio") != null }
    }

    private fun ensureDir(context: Context, tree: Uri, parent: Uri, name: String): Uri? =
        child(context, tree, parent, name) ?: try {
            DocumentsContract.createDocument(
                context.contentResolver, parent, DocumentsContract.Document.MIME_TYPE_DIR, name,
            )
        } catch (e: Exception) {
            null
        }

    private fun child(context: Context, tree: Uri, parent: Uri, name: String): Uri? {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(
            tree, DocumentsContract.getDocumentId(parent),
        )
        context.contentResolver.query(
            children,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            ),
            null, null, null,
        )?.use { c ->
            while (c.moveToNext()) {
                if (c.getString(1).equals(name, ignoreCase = true)) {
                    return DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0))
                }
            }
        }
        return null
    }
}
