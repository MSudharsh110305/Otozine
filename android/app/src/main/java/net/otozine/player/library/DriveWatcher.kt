package net.otozine.player.library

import android.content.Context
import android.net.Uri
import android.util.Log

/**
 * Is the drive the library points at actually reachable right now?
 *
 * In LINK mode the audio lives on the drive, so this decides whether anything
 * can play at all. The app has to be able to say "drive not connected" up front
 * rather than letting you press play and get silence -- an unexplained failure
 * is much worse than a stated limitation.
 *
 * There is no broadcast for "a USB drive the user granted me went away", so
 * this probes: hold a persisted permission, and try to open one document. Both
 * have to succeed. The permission alone is not enough -- Android keeps the grant
 * after the drive is unplugged, so a permission check reports success against
 * hardware that is no longer there.
 */
object DriveWatcher {

    private const val TAG = "OtoZineDrive"

    enum class State {
        /** Reachable; audio can be read. */
        CONNECTED,

        /** We have a grant, but the media behind it is gone. Plug it back in. */
        DISCONNECTED,

        /** No drive was ever linked. */
        NONE,
    }

    /**
     * @param probeUri any audio URI from the library, used as the canary.
     */
    fun check(context: Context, treeUri: String, probeUri: String?): State {
        if (treeUri.isBlank()) return State.NONE

        val held = context.contentResolver.persistedUriPermissions.any {
            it.uri.toString() == treeUri && it.isReadPermission
        }
        if (!held) return State.NONE

        // No document to test against: assume reachable rather than claim a
        // fault we have not observed.
        val probe = probeUri?.takeIf { it.startsWith("content://") } ?: return State.CONNECTED

        return try {
            context.contentResolver.openInputStream(Uri.parse(probe))?.use { stream ->
                // Reading one byte is the cheapest proof the medium responds;
                // opening alone can succeed against a stale handle.
                stream.read()
            }
            State.CONNECTED
        } catch (e: Exception) {
            Log.i(TAG, "drive unreachable: ${e.javaClass.simpleName}")
            State.DISCONNECTED
        }
    }
}
