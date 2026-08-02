package net.otozine.player.library

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log

/**
 * Copy a track from the phone into the drive's inbox.
 *
 * The phone cannot build the master/Opus split -- that needs ffmpeg, so it needs
 * a PC. What it *can* do is put the file somewhere the Librarian will find it,
 * which is what the inbox exists for: drop songs in from anywhere, and the next
 * ingest analyses, normalises and splits them.
 *
 * So this is deliberately a dumb copy. Pretending to "add to the library" from
 * the phone would produce an entry with no loudness, tempo or key, sitting
 * alongside properly analysed tracks and quietly degrading every queue it
 * appeared in.
 */
object DriveExporter {

    private const val TAG = "OtoZineExport"
    private const val INBOX = "inbox"

    sealed interface Result {
        data class Ok(val name: String) : Result
        data class Failed(val reason: String) : Result
    }

    fun send(context: Context, treeUri: String, source: Uri, filename: String): Result {
        if (treeUri.isBlank()) {
            return Result.Failed("No drive linked. Import a library from it first.")
        }

        return try {
            val tree = Uri.parse(treeUri)
            val inbox = findInbox(context, tree)
                ?: return Result.Failed(
                    "No OtoZine/inbox folder on the drive. Run 'otozine init' on a PC."
                )

            val target = DocumentsContract.createDocument(
                context.contentResolver, inbox, "audio/*", filename,
            ) ?: return Result.Failed("Could not create the file. Is the drive writable?")

            context.contentResolver.openInputStream(source)?.use { input ->
                context.contentResolver.openOutputStream(target)?.use { output ->
                    input.copyTo(output, 1 shl 16)
                } ?: return Result.Failed("Could not write to the drive.")
            } ?: return Result.Failed("Could not read the track.")

            Result.Ok(filename)
        } catch (e: Exception) {
            Log.e(TAG, "export failed", e)
            Result.Failed(e.message ?: "Copy failed. Is the drive still connected?")
        }
    }

    /** Locate OtoZine/inbox under whichever folder was linked. */
    private fun findInbox(context: Context, tree: Uri): Uri? {
        val root = DocumentsContract.buildDocumentUriUsingTree(
            tree, DocumentsContract.getTreeDocumentId(tree),
        )
        // The linked folder may be the drive root or the OtoZine folder itself,
        // so try both rather than assuming which one was picked.
        return child(context, tree, root, "OtoZine")?.let { child(context, tree, it, INBOX) }
            ?: child(context, tree, root, INBOX)
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
