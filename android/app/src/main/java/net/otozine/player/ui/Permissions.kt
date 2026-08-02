package net.otozine.player.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

/**
 * Runtime permissions.
 *
 * Two are needed and they fail in opposite ways, which is why both are handled
 * here rather than ad hoc:
 *
 *  - **POST_NOTIFICATIONS** — without it Android suppresses the media
 *    notification, and with no notification the playback service cannot run in
 *    the foreground. Music stops when you leave the app.
 *  - **READ_MEDIA_AUDIO** — without it the MediaStore query returns nothing.
 *    It does not throw, it just comes back empty, so a missing permission looks
 *    exactly like a phone with no music on it. That silence is the reason this
 *    is asked for up front rather than lazily.
 */
object Permissions {

    val AUDIO = Manifest.permission.READ_MEDIA_AUDIO
    val NOTIFICATIONS = Manifest.permission.POST_NOTIFICATIONS

    fun has(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    fun hasAudio(context: Context): Boolean = has(context, AUDIO)
}

/**
 * Ask for what we need on first composition, then report whether audio is
 * readable so the caller can populate the library straight away.
 */
@Composable
fun rememberPermissionGate(onAudioGranted: () -> Unit): () -> Unit {
    val context = LocalContext.current
    var asked by remember { mutableStateOf(false) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result[Permissions.AUDIO] == true) onAudioGranted()
    }

    LaunchedEffect(Unit) {
        if (asked) return@LaunchedEffect
        asked = true

        val wanted = buildList {
            if (!Permissions.has(context, Permissions.NOTIFICATIONS)) add(Permissions.NOTIFICATIONS)
            if (!Permissions.has(context, Permissions.AUDIO)) add(Permissions.AUDIO)
        }

        if (wanted.isEmpty()) {
            // Already granted from a previous run -- still tell the caller, so
            // the library populates without waiting for a dialog that will
            // never appear.
            if (Permissions.hasAudio(context)) onAudioGranted()
        } else {
            launcher.launch(wanted.toTypedArray())
        }
    }

    // Returned so a screen can re-prompt after the user turns the toggle on.
    return {
        if (Permissions.hasAudio(context)) onAudioGranted()
        else launcher.launch(arrayOf(Permissions.AUDIO))
    }
}
