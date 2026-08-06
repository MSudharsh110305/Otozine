package net.otozine.player.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import net.otozine.player.library.LibraryImporter
import net.otozine.player.ui.components.NeuCard
import net.otozine.player.ui.components.VSpace
import net.otozine.player.ui.theme.Depth
import net.otozine.player.ui.theme.Oto
import net.otozine.player.ui.theme.scrim
import net.otozine.player.ui.theme.neu

/**
 * Folder picker for importing a library.
 *
 * Returns a launcher rather than a button so the same picker can be triggered
 * from the empty state and from the storage sheet without duplicating the
 * permission handling.
 */
@Composable
fun rememberLibraryPicker(onPicked: (Uri) -> Unit): () -> Unit {
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri -> uri?.let(onPicked) }

    return {
        // Null start location lets the picker default to wherever the user was
        // last, which for a repeat import is usually the right folder.
        launcher.launch(null)
    }
}

/** Full-screen progress while files are copied in. */
@Composable
fun ImportOverlay(progress: LibraryImporter.Progress, onDismiss: () -> Unit) {
    val colors = Oto.colors
    val fraction =
        if (progress.filesTotal > 0) progress.filesCopied.toFloat() / progress.filesTotal else 0f

    Box(
        Modifier.fillMaxSize().background(colors.scrim()),
        contentAlignment = Alignment.Center,
    ) {
        NeuCard(Modifier.fillMaxWidth().padding(26.dp), radius = 22.dp) {
            Column(
                Modifier.padding(22.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                when {
                    progress.error != null -> {
                        Text("IMPORT FAILED", style = Oto.type.label, color = colors.ink3)
                        Text(progress.error, style = Oto.type.body, color = colors.ink2)
                        VSpace(4.dp)
                        SheetButton("CLOSE", accent = true, modifier = Modifier.fillMaxWidth()) {
                            onDismiss()
                        }
                    }

                    progress.done -> {
                        Text("IMPORTED", style = Oto.type.label, color = colors.teal)
                        Text(
                            "${progress.filesCopied} files · " +
                                "${progress.bytesCopied / 1024 / 1024} MB",
                            style = Oto.type.body,
                            color = colors.ink2,
                        )
                    }

                    else -> {
                        Text("IMPORTING", style = Oto.type.label, color = colors.ink3)
                        Text(
                            if (progress.filesTotal > 0)
                                "${progress.filesCopied} of ${progress.filesTotal}"
                            else progress.currentName.ifBlank { "scanning…" },
                            style = Oto.type.body,
                            color = colors.ink2,
                        )
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(9.dp)
                                .neu(Depth.Inset, RoundedCornerShape(50)),
                            contentAlignment = Alignment.CenterStart,
                        ) {
                            Box(
                                Modifier
                                    .fillMaxWidth(fraction.coerceIn(0.02f, 1f))
                                    .height(9.dp)
                                    .clip(RoundedCornerShape(50))
                                    .background(colors.teal)
                            )
                        }
                        Text(
                            progress.currentName,
                            style = Oto.type.micro,
                            color = colors.ink3,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}
