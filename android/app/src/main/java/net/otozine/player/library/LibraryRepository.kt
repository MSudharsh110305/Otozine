package net.otozine.player.library

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import net.otozine.player.playback.PlaybackService
import java.io.File

/**
 * Reads the library the Librarian produced.
 *
 * Deliberately raw SQLite rather than Room: the schema is authored and migrated
 * on the Python side, and pointing Room at a database it does not own means
 * fighting its schema validation for no benefit. We only read.
 *
 * On-device layout mirrors the drive so that paths stored in the database
 * resolve without translation:
 *
 *     filesDir/library/
 *       library.db
 *       audio/opus/ab/<hash>.opus
 *       art/ab/<hash>.jpg
 *
 * Phase 2 populates this by syncing a subset from the pendrive over SAF. Until
 * then it is filled by `adb push`, which is enough to exercise playback.
 */
class LibraryRepository(private val context: Context) {

    private var db: SQLiteDatabase? = null

    val root: File get() = File(context.filesDir, "library")
    private val dbFile: File get() = File(root, "library.db")

    val isPresent: Boolean get() = dbFile.isFile

    /**
     * Open the library. Returns null and logs if absent or unreadable, because
     * a missing library is an ordinary first-run state, not an error.
     */
    fun open(): SQLiteDatabase? {
        db?.let { if (it.isOpen) return it }

        if (!dbFile.isFile) {
            Log.i(TAG, "no library at ${dbFile.path}")
            return null
        }

        return try {
            val opened = SQLiteDatabase.openDatabase(
                dbFile.path, null, SQLiteDatabase.OPEN_READONLY,
            )
            checkSchemaVersion(opened)
            db = opened
            opened
        } catch (e: SQLiteException) {
            Log.e(TAG, "could not open library.db", e)
            null
        }
    }

    private fun checkSchemaVersion(database: SQLiteDatabase) {
        val version = database.rawQuery("SELECT MAX(version) FROM schema_version", null).use {
            if (it.moveToFirst()) it.getInt(0) else 0
        }
        // A newer database may have columns we do not read, which is harmless.
        // An older one may be missing columns we require, which is not.
        require(version >= SCHEMA_VERSION) {
            "library.db is schema v$version but this player needs v$SCHEMA_VERSION; " +
                "re-run the Librarian to upgrade the drive"
        }
    }

    fun tracks(limit: Int = 5000): List<Track> {
        val database = open() ?: return emptyList()
        val out = ArrayList<Track>()

        database.rawQuery(TRACK_QUERY, arrayOf(limit.toString())).use { c ->
            val idx = ColumnIndices(c)
            while (c.moveToNext()) {
                out += Track(
                    id = c.getLong(idx.id),
                    contentHash = c.getString(idx.contentHash),
                    title = c.getStringOrNull(idx.title),
                    artist = c.getStringOrNull(idx.artist),
                    composer = c.getStringOrNull(idx.composer),
                    album = c.getStringOrNull(idx.album),
                    year = c.getIntOrNull(idx.year),
                    language = c.getStringOrNull(idx.language),
                    durationMs = c.getLongOrNull(idx.durationMs) ?: 0L,
                    opusPath = c.getStringOrNull(idx.opusPath),
                    artPath = c.getStringOrNull(idx.artPath),
                    bpm = c.getFloatOrNull(idx.bpm),
                    keyCamelot = c.getStringOrNull(idx.keyCamelot),
                    energy = c.getFloatOrNull(idx.energy) ?: 0.5f,
                    danceability = c.getFloatOrNull(idx.danceability) ?: 0.5f,
                    replayGainDb = c.getFloatOrNull(idx.replayGain) ?: 0f,
                    introEndMs = c.getLongOrNull(idx.introEnd) ?: 0L,
                    outroStartMs = c.getLongOrNull(idx.outroStart) ?: 0L,
                    hookStartMs = c.getLongOrNull(idx.hookStart) ?: 0L,
                )
            }
        }
        return out
    }

    /**
     * Where a track's audio actually is.
     *
     * Two forms live in `opus_path`. A library built by the Librarian stores a
     * drive-relative path; a library the app indexed from a folder on the phone
     * stores a `content://` URI, because those files are referenced in place
     * rather than copied. Callers get a Uri either way.
     */
    fun audioUri(track: Track): android.net.Uri? {
        val stored = track.opusPath ?: return null
        if (stored.startsWith("content://")) return android.net.Uri.parse(stored)
        return File(root, stored).takeIf { it.isFile }?.let { android.net.Uri.fromFile(it) }
    }

    /**
     * Mood tags the Librarian derived from the audio, per track.
     *
     * Read separately from `tracks` because they are many-per-track; joining
     * them into the main query would multiply every row.
     */
    fun moodTags(): Map<Long, Set<String>> {
        val database = open() ?: return emptyMap()
        val out = HashMap<Long, MutableSet<String>>()
        try {
            database.rawQuery(
                "SELECT track_id, tag FROM tags WHERE source = 'mood' ORDER BY confidence DESC",
                null,
            ).use { c ->
                while (c.moveToNext()) {
                    out.getOrPut(c.getLong(0)) { LinkedHashSet() }.add(c.getString(1))
                }
            }
        } catch (e: SQLiteException) {
            // An app-built library has no tags table at all; that is fine.
            Log.i(TAG, "no mood tags available")
        }
        return out
    }

    /** Absolute file, when there is one. Null for referenced content URIs. */
    fun audioFile(track: Track): File? {
        val rel = track.opusPath ?: return null
        if (rel.startsWith("content://")) return null
        return File(root, rel).takeIf { it.isFile }
    }

    fun artFile(track: Track): File? {
        val rel = track.artPath ?: return null
        return File(root, rel).takeIf { it.isFile }
    }

    /**
     * Build the MediaItem the player consumes.
     *
     * The normalisation gain and intro offset ride along in extras so that
     * [net.otozine.player.playback.LoudnessController] and the seek-past-intro
     * logic can act on a transition without a database round trip on the audio
     * thread.
     */
    fun toMediaItem(track: Track): MediaItem? {
        val uri = audioUri(track) ?: return null

        val extras = Bundle().apply {
            putLong(PlaybackService.EXTRA_TRACK_ID, track.id)
            putFloat(PlaybackService.EXTRA_REPLAYGAIN_DB, track.replayGainDb)
            putLong(PlaybackService.EXTRA_INTRO_END_MS, track.introEndMs)
        }

        val metadata = MediaMetadata.Builder()
            .setTitle(track.displayTitle)
            .setArtist(track.displayArtist)
            .setAlbumTitle(track.album)
            .setIsBrowsable(false)
            .setIsPlayable(true)
            .apply {
                artFile(track)?.let { setArtworkUri(Uri.fromFile(it)) }
                track.year?.let { setRecordingYear(it) }
            }
            .setExtras(extras)
            .build()

        return MediaItem.Builder()
            .setMediaId(track.id.toString())
            .setUri(uri)
            .setMediaMetadata(metadata)
            .build()
    }

    /**
     * Drop a track from the index.
     *
     * Only files this app copied are deleted from disk. A track linked to the
     * drive is removed from the library but the file on the drive is left
     * alone -- deleting someone's only copy of a song because they tidied a
     * list is not a trade worth making.
     */
    fun delete(track: Track): Boolean {
        val database = open() ?: return false
        return try {
            audioFile(track)?.delete()
            artFile(track)?.delete()
            // Marked rather than deleted, so play history keyed on this id
            // stays meaningful if the track is imported again later.
            SQLiteDatabase.openDatabase(
                File(root, "library.db").path, null, SQLiteDatabase.OPEN_READWRITE,
            ).use { rw ->
                rw.execSQL("UPDATE tracks SET missing = 1 WHERE id = ?", arrayOf<Any?>(track.id))
            }
            close()
            true
        } catch (e: Exception) {
            Log.e(TAG, "delete failed", e)
            false
        }
    }

    /**
     * Pull a linked track's audio onto the phone.
     *
     * Rewrites the row to the local path, so it keeps playing once the drive
     * is gone.
     */
    fun copyLocal(track: Track): Boolean {
        val stored = track.opusPath ?: return false
        if (!stored.startsWith("content://")) return true    // already local

        return try {
            val target = File(root, "audio/local/${track.contentHash}.opus")
            target.parentFile?.mkdirs()
            context.contentResolver.openInputStream(Uri.parse(stored))?.use { input ->
                target.outputStream().use { output -> input.copyTo(output, 1 shl 16) }
            } ?: return false

            SQLiteDatabase.openDatabase(
                File(root, "library.db").path, null, SQLiteDatabase.OPEN_READWRITE,
            ).use { rw ->
                // Keep the drive URI in source_path before overwriting the
                // playable path. Without it, copying to the phone was a one-way
                // door: the only record of where the track lived on the drive
                // was the value being replaced, so going back to playing from
                // the drive would have meant re-importing the whole library.
                rw.execSQL(
                    "UPDATE tracks SET source_path = CASE " +
                        "WHEN source_path LIKE 'content://%' THEN source_path ELSE ? END, " +
                        "opus_path = ? WHERE id = ?",
                    arrayOf<Any?>(stored, "audio/local/${track.contentHash}.opus", track.id),
                )
            }
            close()
            true
        } catch (e: Exception) {
            Log.e(TAG, "copy to phone failed", e)
            false
        }
    }

    /**
     * Drop a phone copy and go back to playing the track off the drive.
     *
     * The inverse of [copyLocal], and only possible because that one preserves
     * the drive URI it replaces.
     */
    fun freeLocal(track: Track): Boolean {
        val stored = track.opusPath ?: return false
        if (stored.startsWith("content://")) return true     // already on the drive

        return try {
            val drivePath = SQLiteDatabase.openDatabase(
                File(root, "library.db").path, null, SQLiteDatabase.OPEN_READONLY,
            ).use { ro ->
                ro.rawQuery(
                    "SELECT source_path FROM tracks WHERE id = ?",
                    arrayOf(track.id.toString()),
                ).use { if (it.moveToFirst()) it.getString(0) else null }
            }
            // Without a drive path to fall back to, deleting the phone copy
            // would leave a row pointing at nothing. Better to keep the file.
            if (drivePath == null || !drivePath.startsWith("content://")) return false

            SQLiteDatabase.openDatabase(
                File(root, "library.db").path, null, SQLiteDatabase.OPEN_READWRITE,
            ).use { rw ->
                rw.execSQL(
                    "UPDATE tracks SET opus_path = ? WHERE id = ?",
                    arrayOf<Any?>(drivePath, track.id),
                )
            }
            File(root, stored).delete()
            close()
            true
        } catch (e: Exception) {
            Log.e(TAG, "freeing phone copy failed", e)
            false
        }
    }

    fun close() {
        db?.takeIf { it.isOpen }?.close()
        db = null
    }

    /** Resolved once per cursor rather than per row -- this runs 5000 times. */
    private class ColumnIndices(c: android.database.Cursor) {
        val id = c.getColumnIndexOrThrow("id")
        val contentHash = c.getColumnIndexOrThrow("content_hash")
        val title = c.getColumnIndexOrThrow("title")
        val artist = c.getColumnIndexOrThrow("artist")
        val composer = c.getColumnIndexOrThrow("composer")
        val album = c.getColumnIndexOrThrow("album")
        val year = c.getColumnIndexOrThrow("year")
        val language = c.getColumnIndexOrThrow("language")
        val durationMs = c.getColumnIndexOrThrow("duration_ms")
        val opusPath = c.getColumnIndexOrThrow("opus_path")
        val artPath = c.getColumnIndexOrThrow("art_path")
        val bpm = c.getColumnIndexOrThrow("bpm")
        val keyCamelot = c.getColumnIndexOrThrow("key_camelot")
        val energy = c.getColumnIndexOrThrow("energy")
        val danceability = c.getColumnIndexOrThrow("danceability")
        val replayGain = c.getColumnIndexOrThrow("replaygain_db")
        val introEnd = c.getColumnIndexOrThrow("intro_end_ms")
        val outroStart = c.getColumnIndexOrThrow("outro_start_ms")
        val hookStart = c.getColumnIndexOrThrow("hook_start_ms")
    }

    companion object {
        private const val TAG = "OtoZineLibrary"

        /** Must match SCHEMA_VERSION in librarian/otozine/config.py. */
        const val SCHEMA_VERSION = 1

        private const val TRACK_QUERY = """
            SELECT id, content_hash, title, artist, composer, album, year, language,
                   duration_ms, opus_path, art_path, bpm, key_camelot,
                   energy, danceability,
                   replaygain_db, intro_end_ms, outro_start_ms, hook_start_ms
            FROM tracks
            WHERE missing = 0 AND opus_path IS NOT NULL
            ORDER BY artist COLLATE NOCASE, album COLLATE NOCASE, track_no, title
            LIMIT ?
        """
    }
}

private fun android.database.Cursor.getStringOrNull(index: Int): String? =
    if (isNull(index)) null else getString(index)

private fun android.database.Cursor.getIntOrNull(index: Int): Int? =
    if (isNull(index)) null else getInt(index)

private fun android.database.Cursor.getLongOrNull(index: Int): Long? =
    if (isNull(index)) null else getLong(index)

private fun android.database.Cursor.getFloatOrNull(index: Int): Float? =
    if (isNull(index)) null else getFloat(index)
