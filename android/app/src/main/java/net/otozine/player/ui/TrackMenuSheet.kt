package net.otozine.player.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import net.otozine.player.PlayerViewModel
import net.otozine.player.library.Track
import net.otozine.player.ui.components.VSpace
import net.otozine.player.ui.theme.Oto

/**
 * Long-press menu for a track: play, move it about, remove it.
 *
 * Deleting is the one action here that can lose data, so it asks first and says
 * exactly what it will do. In LINK mode "remove" only drops the entry from the
 * library -- the file on the drive is untouched, because deleting someone's
 * only copy of a song because they tidied a list is not a reasonable default.
 */
@Composable
fun TrackMenuSheet(
    track: Track,
    state: PlayerViewModel.UiState,
    viewModel: PlayerViewModel,
    onDismiss: () -> Unit,
) {
    var confirmDelete by remember { mutableStateOf(false) }
    val onDrive = track.opusPath?.startsWith("content://") == true
    val moods = state.moodsByTrack[track.id].orEmpty()

    Sheet(
        title = track.displayTitle,
        subtitle = buildString {
            append(if (onDrive) "On the drive" else "On this phone")
            if (moods.isNotEmpty()) append("  ·  ").append(moods.take(3).joinToString(", "))
        },
        onDismiss = onDismiss,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
            SheetButton("PLAY FROM HERE", accent = true, modifier = Modifier.fillMaxWidth()) {
                viewModel.playFrom(track); onDismiss()
            }

            if (onDrive) {
                SheetButton("KEEP ON PHONE", modifier = Modifier.fillMaxWidth()) {
                    viewModel.copyToPhone(track); onDismiss()
                }
                Text(
                    "Copies this one track into the app so it keeps playing when " +
                        "the drive is unplugged.",
                    style = Oto.type.body,
                    color = Oto.colors.ink3,
                )
            }

            if (!onDrive) {
                SheetButton("SEND TO DRIVE", modifier = Modifier.fillMaxWidth()) {
                    viewModel.sendToDrive(track); onDismiss()
                }
                Text(
                    "Copies the file to the drive's inbox. Running ingest on a PC " +
                        "then analyses it and builds the Opus copy — the phone " +
                        "cannot do that part itself.",
                    style = Oto.type.body,
                    color = Oto.colors.ink3,
                )
            }

            VSpace(4.dp)

            if (!confirmDelete) {
                SheetButton("REMOVE FROM LIBRARY", modifier = Modifier.fillMaxWidth()) {
                    confirmDelete = true
                }
            } else {
                Text(
                    if (onDrive) {
                        "Remove from the library? The file stays on the drive and will " +
                            "come back next time you import."
                    } else {
                        "Delete this file from the phone? This cannot be undone."
                    },
                    style = Oto.type.body,
                    color = Oto.colors.ink2,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    SheetButton("CANCEL", modifier = Modifier.weight(1f)) {
                        confirmDelete = false
                    }
                    SheetButton(
                        if (onDrive) "REMOVE" else "DELETE",
                        accent = true,
                        modifier = Modifier.weight(1f),
                    ) {
                        viewModel.deleteTrack(track); onDismiss()
                    }
                }
            }
        }
    }
}

/** Shown when the library lives on a drive that is not currently plugged in. */
@Composable
fun DriveBanner(onReconnect: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 4.dp)
            .background(Oto.colors.sunken, RoundedCornerShape(16.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("DRIVE NOT CONNECTED", style = Oto.type.label, color = Oto.colors.teal)
            Text(
                "This library plays from the drive. Plug it back in, or use " +
                    "the music on this phone.",
                style = Oto.type.body,
                color = Oto.colors.ink2,
            )
        }
        Text(
            "RECONNECT",
            style = Oto.type.micro,
            color = Oto.colors.teal,
            modifier = Modifier.clickable(onClick = onReconnect).padding(8.dp),
        )
    }
}

/** Transient status while a copy or delete runs. */
@Composable
fun BusyToast(message: String, onDismiss: () -> Unit) {
    Box(
        Modifier.fillMaxSize().padding(bottom = 120.dp),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Row(
            Modifier
                .padding(horizontal = 20.dp)
                .background(Oto.colors.surface, RoundedCornerShape(50))
                .clickable(onClick = onDismiss)
                .padding(horizontal = 20.dp, vertical = 13.dp),
        ) {
            Text(message, style = Oto.type.body, color = Oto.colors.ink)
        }
    }
}
