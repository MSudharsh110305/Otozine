package net.otozine.player.library

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import java.io.File

/**
 * Builds a library database from a plain folder of audio files.
 *
 * The Librarian is the proper path -- it measures loudness, tempo and key, and
 * only tracks it has analysed can be sequenced by the queue engine. But
 * requiring a PC before the app does anything at all is a bad first run, and
 * "point me at my music folder" is what most people will try first.
 *
 * So this produces a database in the same schema with everything that can be
 * known without decoding audio: embedded tags where they exist, the filename
 * parser where they do not, and duration from the container. The analysis
 * columns stay null, which is exactly how the rest of the app tells these
 * tracks apart from properly ingested ones.
 *
 * Files are referenced in place through their content URI rather than copied.
 * Someone pointing at a folder they already have does not want a second copy of
 * it, and the tree permission is persisted so the reference survives a restart.
 */
class LocalLibraryBuilder(private val context: Context) {

    private val audioExtensions = setOf(
        "mp3", "m4a", "aac", "flac", "wav", "ogg", "oga", "opus", "wma", "aiff", "aif",
    )

    data class Source(val uri: Uri, val name: String)

    fun isAudio(name: String): Boolean =
        name.substringAfterLast('.', "").lowercase() in audioExtensions

    /**
     * Write a library database describing [files].
     *
     * @return the number of tracks written.
     */
    fun build(
        destination: File,
        files: List<Source>,
        onProgress: (Int, Int) -> Unit,
    ): Int {
        destination.mkdirs()
        val dbFile = File(destination, "library.db")
        dbFile.delete()

        val db = SQLiteDatabase.openOrCreateDatabase(dbFile, null)
        try {
            createSchema(db)

            val retriever = MediaMetadataRetriever()
            var written = 0

            db.beginTransaction()
            try {
                files.forEachIndexed { index, source ->
                    val row = describe(retriever, source)
                    if (row != null) {
                        insert(db, row)
                        written++
                    }
                    if (index % 10 == 0 || index == files.lastIndex) {
                        onProgress(index + 1, files.size)
                    }
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
                runCatching { retriever.release() }
            }
            return written
        } finally {
            db.close()
        }
    }

    /**
     * Schema matching the Librarian's, so the reader cannot tell the difference.
     *
     * Only the columns the player actually selects are declared -- adding the
     * event and playlist tables here would be inventing structure that nothing
     * on this path writes.
     */
    private fun createSchema(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE schema_version (version INTEGER NOT NULL, applied_at INTEGER NOT NULL)")
        db.execSQL(
            "INSERT INTO schema_version (version, applied_at) VALUES " +
                "(${LibraryRepository.SCHEMA_VERSION}, ${System.currentTimeMillis() / 1000})"
        )
        db.execSQL(
            """
            CREATE TABLE tracks (
                id              INTEGER PRIMARY KEY,
                content_hash    TEXT NOT NULL UNIQUE,
                source_path     TEXT NOT NULL,
                master_path     TEXT,
                opus_path       TEXT,
                art_path        TEXT,
                lyrics_path     TEXT,
                mbid            TEXT,
                acoustid        TEXT,
                title           TEXT,
                artist          TEXT,
                album_artist    TEXT,
                composer        TEXT,
                album           TEXT,
                track_no        INTEGER,
                year            INTEGER,
                language        TEXT,
                meta_source     TEXT,
                meta_confidence REAL,
                duration_ms     INTEGER,
                sample_rate     INTEGER,
                channels        INTEGER,
                src_codec       TEXT,
                src_bitrate     INTEGER,
                bpm             REAL,
                key_camelot     TEXT,
                key_name        TEXT,
                key_confidence  REAL,
                loudness_lufs   REAL,
                loudness_range  REAL,
                true_peak_db    REAL,
                replaygain_db   REAL,
                energy          REAL,
                valence         REAL,
                arousal         REAL,
                danceability    REAL,
                is_instrumental INTEGER,
                approachability REAL,
                engagement      REAL,
                intro_end_ms    INTEGER,
                outro_start_ms  INTEGER,
                hook_start_ms   INTEGER,
                vec_index       INTEGER,
                tau_hours       REAL NOT NULL DEFAULT 168.0,
                added_at        INTEGER NOT NULL,
                analyzed_at     INTEGER,
                missing         INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_tracks_artist ON tracks(artist)")
        db.execSQL("CREATE INDEX idx_tracks_album ON tracks(album)")
    }

    private data class Row(
        val hash: String,
        val uri: String,
        val title: String?,
        val artist: String?,
        val composer: String?,
        val album: String?,
        val year: Int?,
        val trackNo: Int?,
        val durationMs: Long,
        val metaSource: String,
    )

    private fun describe(retriever: MediaMetadataRetriever, source: Source): Row? {
        var tagTitle: String? = null
        var tagArtist: String? = null
        var tagAlbum: String? = null
        var tagComposer: String? = null
        var tagYear: Int? = null
        var duration = 0L

        try {
            retriever.setDataSource(context, source.uri)
            tagTitle = retriever.clean(MediaMetadataRetriever.METADATA_KEY_TITLE)
            tagArtist = retriever.clean(MediaMetadataRetriever.METADATA_KEY_ARTIST)
            tagAlbum = retriever.clean(MediaMetadataRetriever.METADATA_KEY_ALBUM)
            tagComposer = retriever.clean(MediaMetadataRetriever.METADATA_KEY_COMPOSER)
            tagYear = retriever.clean(MediaMetadataRetriever.METADATA_KEY_YEAR)?.take(4)?.toIntOrNull()
            duration = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
        } catch (e: Exception) {
            Log.w(TAG, "unreadable: ${source.name} (${e.message})")
            // Keep going: a file we cannot probe may still play, and dropping it
            // silently would be worse than listing it with a filename-only label.
        }

        if (duration <= 0L) return null

        val parsed = NameParser.parse(source.name)

        // Embedded tags win when they look real. A "title" longer than any
        // plausible song name is a video description, which YouTube rips carry
        // constantly -- in that case the filename parse is the better answer.
        val tagTitleUsable = tagTitle != null && tagTitle.length in 2..80
        val metaSource = if (tagTitleUsable) "embedded" else "filename"

        return Row(
            hash = "local:${source.uri}".hashCode().toUInt().toString(16) + "-" + source.name.hashCode().toUInt().toString(16),
            uri = source.uri.toString(),
            title = (if (tagTitleUsable) tagTitle?.let { NameParser.stripSiteJunk(it) } else null)
                ?: parsed.title
                ?: source.name.substringBeforeLast('.'),
            artist = tagArtist ?: parsed.artist,
            composer = tagComposer ?: parsed.composer,
            album = tagAlbum ?: parsed.album,
            year = tagYear ?: parsed.year,
            trackNo = parsed.trackNo,
            durationMs = duration,
            metaSource = metaSource,
        )
    }

    private fun insert(db: SQLiteDatabase, row: Row) {
        db.execSQL(
            "INSERT OR IGNORE INTO tracks (content_hash, source_path, opus_path, title, artist, " +
                "composer, album, track_no, year, duration_ms, meta_source, meta_confidence, " +
                "added_at, missing) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,0)",
            arrayOf<Any?>(
                row.hash, row.uri, row.uri, row.title, row.artist, row.composer, row.album,
                row.trackNo, row.year, row.durationMs, row.metaSource,
                if (row.metaSource == "embedded") 0.7 else 0.4,
                System.currentTimeMillis() / 1000,
            ),
        )
    }

    private fun MediaMetadataRetriever.clean(key: Int): String? =
        extractMetadata(key)?.trim()?.takeIf {
            it.isNotBlank() && !it.equals("<unknown>", true) && it != "0"
        }

    companion object {
        private const val TAG = "OtoZineLocalLib"
    }
}
