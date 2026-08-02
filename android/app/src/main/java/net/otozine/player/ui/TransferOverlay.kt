package net.otozine.player.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import net.otozine.player.ui.theme.shimmer
import androidx.compose.ui.unit.dp
import net.otozine.player.library.DriveTransfer
import net.otozine.player.ui.components.NeuCard
import net.otozine.player.ui.components.VSpace
import net.otozine.player.ui.theme.Depth
import net.otozine.player.ui.theme.Oto
import net.otozine.player.ui.theme.scrim
import net.otozine.player.ui.theme.neu

private fun DriveTransfer.Progress.heading(): String = when (kind) {
    DriveTransfer.Kind.TO_DRIVE -> "COPYING TO DRIVE"
    DriveTransfer.Kind.ON_PHONE -> "MOVING AUDIO"
}

/**
 * A counter that reads as an instrument readout rather than a sentence.
 *
 * The done count is padded to the width of the total, so the panel does not
 * reflow every time a digit is added -- a status that twitches draws the eye
 * for no reason.
 */
@Composable
private fun Readout(done: Int, total: Int) {
    Row(verticalAlignment = Alignment.Bottom) {
        Text(
            done.toString().padStart(total.toString().length, ' '),
            style = Oto.type.title,
            color = Oto.colors.ink,
        )
        Text(
            " / $total",
            style = Oto.type.micro,
            color = Oto.colors.ink3,
            modifier = Modifier.padding(bottom = 4.dp),
        )
    }
}

/**
 * Inset track with a teal fill: one progress shape for the whole app.
 *
 * @param working shimmer the filled part while a song is mid-encode. The bar
 *   only moves once a minute, so without it there is nothing on screen to
 *   distinguish "encoding" from "stuck".
 */
@Composable
private fun Bar(fraction: Float, height: Int, working: Boolean = false) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(height.dp)
            .neu(Depth.Inset, RoundedCornerShape(50)),
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            Modifier
                .fillMaxWidth(fraction.coerceIn(0.015f, 1f))
                .height(height.dp)
                .clip(RoundedCornerShape(50))
                .background(Oto.colors.teal)
                .shimmer(active = working, strength = 0.5f)
        )
    }
}

/**
 * Progress while music is copied.
 *
 * Compact on purpose: this covers the whole app, and the more it explains
 * itself the more it reads as a wall rather than a status. The stage line earns
 * its place because the stages differ enormously in cost -- encoding a song
 * takes a minute while archiving it takes a second -- so a bar that sits still
 * is otherwise indistinguishable from a hang.
 */
@Composable
fun TransferOverlay(
    progress: DriveTransfer.Progress,
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
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (progress.finished) {
                    val toDrive = progress.kind == DriveTransfer.Kind.TO_DRIVE
                    Text(
                        when {
                            progress.error != null -> "STOPPED"
                            toDrive -> "ON THE DRIVE"
                            else -> "DONE"
                        },
                        style = Oto.type.label,
                        color = if (progress.error != null) colors.ink2 else colors.teal,
                    )
                    Text(
                        progress.error ?: buildString {
                            append("${progress.copied} copied")
                            if (progress.skipped > 0) append(" · ${progress.skipped} already there")
                            if (progress.failed > 0) append(" · ${progress.failed} failed")
                        },
                        style = Oto.type.body,
                        color = colors.ink2,
                    )
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            progress.heading(),
                            style = Oto.type.label,
                            color = colors.ink3,
                            modifier = Modifier.weight(1f),
                        )
                        Readout(progress.done, progress.total)
                    }
                    Bar(fraction, height = 8, working = true)
                    Text(
                        progress.currentTitle,
                        style = Oto.type.body,
                        color = colors.ink2,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(progress.stage.uppercase(), style = Oto.type.micro, color = colors.teal)
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

/**
 * The copy, minimised to a single line.
 *
 * It sits directly above the now-playing bar, so anything taller starts
 * competing with the thing the app is actually for. Everything needed to judge
 * it at a glance -- how far along, and on what -- fits on one line with the bar
 * beneath.
 */
@Composable
fun TransferStrip(progress: DriveTransfer.Progress, onExpand: () -> Unit) {
    val colors = Oto.colors
    val fraction =
        if (progress.total > 0) progress.done.toFloat() / progress.total else 0f

    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onExpand)
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(6.dp)
                    .clip(RoundedCornerShape(50))
                    .background(colors.teal)
            )
            Text(
                "  ${progress.currentTitle}",
                style = Oto.type.micro,
                color = colors.ink2,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                "${progress.done}/${progress.total}",
                style = Oto.type.micro,
                color = colors.teal,
            )
        }
        Bar(fraction, height = 4, working = true)
    }
}
