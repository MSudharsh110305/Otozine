package net.otozine.player.library

import android.content.Context
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Imports a staged library from anywhere the system can hand us a folder.
 *
 * This is what makes the app self-sufficient. Without it the library could only
 * be installed with `adb run-as`, because app-private storage is unreachable
 * from a file manager -- so a user who simply installed the APK would be stuck
 * looking at an empty library with no way to fill it.
 *
 * Source is a folder tree picked through the Storage Access Framework, which
 * covers all the cases that matter equally well: a USB-OTG pendrive, internal
 * storage, or an SD card. SAF does not care which, so neither does this.
 *
 * Expects the layout `otozine stage` produces: a `library.db` at the top level,
 * with the audio and artwork trees beside it under `OtoZine/`. The whole tree is
 * copied verbatim, so the relative paths stored in the database keep resolving
 * without rewriting.
 */
class LibraryImporter(private val context: Context) {

    data class Progress(
        val filesCopied: Int = 0,
        val filesTotal: Int = 0,
        val bytesCopied: Long = 0,
        val currentName: String = "",
        val done: Boolean = false,
        val error: String? = null,
    )

    private val destination: File get() = File(context.filesDir, "library")

    /**
     * Copy everything under [treeUri] into app storage.
     *
     * The database is copied **last**. If the process dies mid-import, a
     * partial set of audio files with no database reads as "no library" and the
     * user simply retries; the reverse -- a database referencing audio that was
     * never copied -- would show a full library where every track fails to play.
     */
    suspend fun import(
        treeUri: Uri,
        linkOnly: Boolean,
        onProgress: (Progress) -> Unit,
    ): Progress = withContext(Dispatchers.IO) {
        try {
            val root = DocumentsContract.buildDocumentUriUsingTree(
                treeUri, DocumentsContract.getTreeDocumentId(treeUri)
            )

            onProgress(Progress(currentName = "scanning…"))
            val files = ArrayList<Entry>()
            collect(treeUri, root, "", files)

            // Accept library.db at any depth, not just the top level. Picking
            // the pendrive's root puts it at OtoZine/library.db, while the
            // output of `otozine stage` puts it at the root -- both are things
            // a person will reasonably choose, so both have to work.
            val dbEntry = files
                .filter { it.name.equals("library.db", ignoreCase = true) }
                .minByOrNull { it.relativePath.count { c -> c == '/' } }

            if (dbEntry == null) {
                // No prepared library -- so build one from whatever audio is
                // here. Pointing at a plain music folder is what most people
                // will try first, and failing at that would make the app
                // useless until they had run the PC tool.
                return@withContext buildFromAudio(treeUri, files, onProgress)
            }

            val payload = files.filter { it !== dbEntry }
            val total = payload.size + 1

            // Clear first: a stale track left behind would appear in the list
            // and then fail to play, which looks like a playback bug.
            destination.deleteRecursively()
            destination.mkdirs()

            var copied = 0
            var bytes = 0L

            if (linkOnly) {
                // Reference the audio where it sits instead of duplicating it.
                // Only the database and artwork come across -- both tiny, and
                // artwork is needed constantly while browsing with the drive
                // unplugged, which is exactly when the audio is not.
                takePermission(treeUri)
                onProgress(Progress(0, total, 0, "linking…"))

                for (entry in payload) {
                    if (entry.name.endsWith(".jpg", true) || entry.name.endsWith(".webp", true) ||
                        entry.name.endsWith(".png", true)
                    ) {
                        bytes += copy(entry, destination)
                    }
                    copied++
                    if (copied % 20 == 0) onProgress(Progress(copied, total, bytes, entry.name))
                }
            } else {
                for (entry in payload) {
                    bytes += copy(entry, destination)
                    copied++
                    if (copied % 5 == 0 || copied == payload.size) {
                        onProgress(Progress(copied, total, bytes, entry.name))
                    }
                }
            }

            bytes += copy(dbEntry, destination)
            copied++

            // The reader always opens <root>/library.db.
            if (dbEntry.relativePath.contains('/')) {
                File(destination, dbEntry.relativePath)
                    .copyTo(File(destination, "library.db"), overwrite = true)
            }

            onProgress(Progress(copied, total, bytes, "linking audio…"))
            val linked = if (linkOnly) linkToTree(files) else relinkPaths()

            Progress(
                copied, total, bytes,
                if (linked >= 0) "$linked tracks linked" else "library.db",
                done = true,
            ).also(onProgress)
        } catch (e: Exception) {
            Log.e(TAG, "import failed", e)
            Progress(error = e.message ?: "import failed").also(onProgress)
        }
    }

    private fun takePermission(treeUri: Uri) {
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
    }

    /**
     * Point the database at the audio on the drive, without copying it.
     *
     * Each row gets the SAF document URI of its file. Playback then reads
     * straight off the drive, which is what "the drive is the library" actually
     * means -- the phone holds only the index. The cost is that unplugging the
     * drive stops playback, which the app has to say plainly rather than let
     * you discover mid-song.
     *
     * @return number of tracks linked.
     */
    private fun linkToTree(files: List<Entry>): Int {
        val dbFile = File(destination, "library.db")
        if (!dbFile.isFile) return -1

        val byName = files.associateBy({ it.name }, { it.uri.toString() })
        var linked = 0
        var missing = 0

        val db = try {
            SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READWRITE)
        } catch (e: SQLiteException) {
            Log.e(TAG, "cannot open imported db to link", e)
            return -1
        }

        try {
            db.beginTransaction()
            db.rawQuery("SELECT id, opus_path, art_path FROM tracks", null).use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    val audioName = c.getString(1)?.substringAfterLast('/')
                    val artName = c.getString(2)?.substringAfterLast('/')

                    val audioUri = audioName?.let { byName[it] }
                    // Artwork was copied locally, so it keeps a relative path.
                    val artRel = artName?.let { name ->
                        destination.walkTopDown()
                            .firstOrNull { it.isFile && it.name == name }
                            ?.relativeTo(destination)?.invariantSeparatorsPath
                    }

                    if (audioUri != null) {
                        linked++
                        db.execSQL(
                            "UPDATE tracks SET opus_path = ?, art_path = ?, missing = 0 WHERE id = ?",
                            arrayOf<Any?>(audioUri, artRel, id),
                        )
                    } else {
                        missing++
                        db.execSQL("UPDATE tracks SET missing = 1 WHERE id = ?", arrayOf<Any?>(id))
                    }
                }
            }
            db.setTransactionSuccessful()
        } catch (e: SQLiteException) {
            Log.e(TAG, "link failed", e)
            return -1
        } finally {
            runCatching { db.endTransaction() }
            db.close()
        }

        Log.i(TAG, "linked $linked tracks to the drive, $missing not found")
        return linked
    }

    /**
     * Point the database at where the files actually ended up.
     *
     * Paths in the database are stored relative to the *drive root*, so a row
     * reads `OtoZine/audio/opus/ab/<hash>.opus`. Whether that resolves after
     * import depends entirely on which folder was picked: choosing the drive
     * reproduces the layout, choosing the `OtoZine` folder itself shifts
     * everything up one level and every single path misses. The symptom is
     * nasty precisely because it is silent -- the track list is fully
     * populated, and nothing plays.
     *
     * Rather than guess the offset, match on filename. Names are content
     * hashes, so they are unique and stable, and rewriting to the real relative
     * path makes the result independent of what the user picked.
     *
     * @return number of tracks successfully linked, or -1 if the DB is unusable.
     */
    private fun relinkPaths(): Int {
        val dbFile = File(destination, "library.db")
        if (!dbFile.isFile) return -1

        // basename -> path relative to the library root.
        val index = HashMap<String, String>()
        destination.walkTopDown().forEach { file ->
            if (file.isFile && file.name != "library.db") {
                index[file.name] = file.relativeTo(destination).invariantSeparatorsPath
            }
        }
        if (index.isEmpty()) return 0

        var linked = 0
        var missing = 0
        val db = try {
            SQLiteDatabase.openDatabase(
                dbFile.path, null, SQLiteDatabase.OPEN_READWRITE,
            )
        } catch (e: SQLiteException) {
            Log.e(TAG, "cannot open imported db to relink", e)
            return -1
        }

        try {
            db.beginTransaction()
            db.rawQuery("SELECT id, opus_path, art_path FROM tracks", null).use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    val opus = c.getString(1)
                    val art = c.getString(2)

                    val opusReal = opus?.substringAfterLast('/')?.let { index[it] }
                    val artReal = art?.substringAfterLast('/')?.let { index[it] }

                    if (opusReal != null) {
                        linked++
                        db.execSQL(
                            "UPDATE tracks SET opus_path = ?, art_path = ?, missing = 0 WHERE id = ?",
                            arrayOf<Any?>(opusReal, artReal, id),
                        )
                    } else {
                        // Audio not present -- normal when only part of the
                        // library was staged. Flagged so it is hidden rather
                        // than listed as something that then fails to play.
                        missing++
                        db.execSQL(
                            "UPDATE tracks SET missing = 1 WHERE id = ?", arrayOf<Any?>(id)
                        )
                    }
                }
            }
            db.setTransactionSuccessful()
        } catch (e: SQLiteException) {
            Log.e(TAG, "relink failed", e)
            return -1
        } finally {
            runCatching { db.endTransaction() }
            db.close()
        }

        Log.i(TAG, "relinked $linked tracks, $missing without audio")
        return linked
    }

    /**
     * Index a plain folder of songs, with no prepared database.
     *
     * The files stay where they are and are referenced by URI, so nothing is
     * duplicated. What these tracks lack is analysis -- no loudness levelling,
     * tempo or key -- so they play but stay out of smart queues, the same as
     * device audio. Running the Librarian later upgrades them properly.
     */
    private fun buildFromAudio(
        treeUri: Uri,
        files: List<Entry>,
        onProgress: (Progress) -> Unit,
    ): Progress {
        val builder = LocalLibraryBuilder(context)
        val audio = files.filter { builder.isAudio(it.name) }

        if (audio.isEmpty()) {
            return Progress(
                error = "No audio files and no library.db in that folder. " +
                    "Pick a folder containing songs, or one produced by otozine stage.",
            ).also(onProgress)
        }

        // Hold onto the folder across restarts, since the audio is referenced
        // in place rather than copied.
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }

        onProgress(Progress(0, audio.size, currentName = "reading tags…"))

        destination.deleteRecursively()
        destination.mkdirs()

        val written = builder.build(
            destination = destination,
            files = audio.map { LocalLibraryBuilder.Source(it.uri, it.name) },
            onProgress = { done, total ->
                onProgress(Progress(done, total, currentName = "reading tags…"))
            },
        )

        return if (written == 0) {
            Progress(error = "Found ${audio.size} files but none could be read.").also(onProgress)
        } else {
            Progress(written, audio.size, 0, "$written tracks indexed", done = true).also(onProgress)
        }
    }

    private data class Entry(val uri: Uri, val name: String, val relativePath: String)

    /** Depth-first walk of the SAF tree. */
    private fun collect(treeUri: Uri, parent: Uri, prefix: String, into: MutableList<Entry>) {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
            treeUri, DocumentsContract.getDocumentId(parent)
        )
        context.contentResolver.query(
            childrenUri,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
            ),
            null, null, null,
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getString(0)
                val name = cursor.getString(1) ?: continue
                val mime = cursor.getString(2)
                val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, id)
                val path = if (prefix.isEmpty()) name else "$prefix/$name"

                if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                    collect(treeUri, uri, path, into)
                } else {
                    into += Entry(uri, name, path)
                }
            }
        }
    }

    private fun copy(entry: Entry, destinationRoot: File): Long {
        val target = File(destinationRoot, entry.relativePath)
        target.parentFile?.mkdirs()

        var written = 0L
        context.contentResolver.openInputStream(entry.uri)?.use { input ->
            target.outputStream().use { output ->
                written = input.copyTo(output, DEFAULT_BUFFER_SIZE * 8)
            }
        }
        return written
    }

    /** True when a usable library is already installed. */
    fun hasLibrary(): Boolean = File(destination, "library.db").isFile

    fun clear() {
        destination.deleteRecursively()
    }

    companion object {
        private const val TAG = "OtoZineImport"
    }
}
