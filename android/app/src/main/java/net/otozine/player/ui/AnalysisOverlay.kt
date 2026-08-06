package net.otozine.player.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import net.otozine.player.analysis.AnalysisWorker
import net.otozine.player.ui.components.NeuCard
import net.otozine.player.ui.components.VSpace
import net.otozine.player.ui.theme.Depth
import net.otozine.player.ui.theme.Oto
import net.otozine.player.ui.theme.scrim
import net.otozine.player.ui.theme.neu

/**
 * Progress while the phone measures the library.
 *
 * Says what is being measured rather than just spinning: this takes a few
 * seconds per track, and "analysing…" for two minutes with no explanation reads
 * as a hang. It also states plainly that leaving is safe, because the work is
 * saved per track and re-running skips whatever is already done.
 */
@Composable
fun AnalysisOverlay(
    progress: AnalysisWorker.Progress,
    onMinimise: (() -> Unit)? = null,
    onCancel: () -> Unit,
) {
    val colors = Oto.colors
    val fraction =
        if (progress.total > 0) progress.done.toFloat() / progress.total else 0f

    Box(
        Modifier.fillMaxSize().background(colors.scrim()),
        contentAlignment = Alignment.Center,
    ) {
        NeuCard(Modifier.fillMaxWidth().padding(26.dp), radius = 22.dp) {
            Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(11.dp)) {
                if (progress.finished) {
                    Text("MEASURED", style = Oto.type.label, color = colors.teal)
                    Text(
                        if (progress.total == 0) {
                            "Everything was already measured."
                        } else {
                            "${progress.done - progress.failed} of ${progress.total} tracks. " +
                                "Loudness, tempo, key and mood are now set."
                        },
                        style = Oto.type.body,
                        color = colors.ink2,
                    )
                    if (progress.failed > 0) {
                        Text(
                            "${progress.failed} could not be decoded and were skipped.",
                            style = Oto.type.body,
                            color = colors.ink3,
                        )
                    }
                } else {
                    Text("MEASURING ON THIS PHONE", style = Oto.type.label, color = colors.ink3)
                    Text(
                        "${progress.done} of ${progress.total}",
                        style = Oto.type.title,
                        color = colors.ink,
                    )
                    Text(
                        progress.currentTitle,
                        style = Oto.type.body,
                        color = colors.ink2,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
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
                        "Saved as it goes — stopping keeps what is done.",
                        style = Oto.type.body,
                        color = colors.ink3,
                    )
                    VSpace(2.dp)
                    Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                        if (onMinimise != null) {
                            SheetButton(
                                "KEEP USING APP",
                                accent = true,
                                modifier = Modifier.weight(1f),
                            ) { onMinimise() }
                        }
                        SheetButton("STOP", modifier = Modifier.weight(1f)) { onCancel() }
                    }
                }
            }
        }
    }
}
