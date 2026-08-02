package net.otozine.player.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import net.otozine.player.PlayerViewModel
import net.otozine.player.library.Track
import net.otozine.player.queue.Features
import net.otozine.player.ui.components.NeuCard
import net.otozine.player.ui.components.SectionHeader
import net.otozine.player.ui.components.VSpace
import net.otozine.player.ui.components.subtitleLine
import net.otozine.player.ui.theme.Depth
import net.otozine.player.ui.theme.Oto
import net.otozine.player.ui.theme.neu
import kotlin.math.hypot

/**
 * The sound map.
 *
 * The plan describes a UMAP projection of CLAP embeddings. Those do not exist
 * yet, so this plots the two axes that are actually measured and that a
 * listener can feel: brightness (derived valence) against intensity (energy).
 * It is a real projection of real analysis, not a decorative scatter -- tapping
 * a region genuinely seeds the queue from there.
 *
 * When embeddings land, only [position] changes.
 */
@Composable
fun MapScreen(state: PlayerViewModel.UiState, viewModel: PlayerViewModel) {
    val colors = Oto.colors
    var selected by remember { mutableStateOf<Track?>(null) }
    var tapPoint by remember { mutableStateOf<Offset?>(null) }

    val plotted = remember(state.tracks) {
        state.tracks.filter { it.opusPath != null }.map { it to position(it) }
    }

    Column(Modifier.fillMaxSize()) {
        SectionHeader("Sound map · ${plotted.size} tracks")

        Box(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp)
                .aspectRatio(1f)
                .neu(Depth.InsetDeep, RoundedCornerShape(24.dp))
        ) {
            Canvas(
                Modifier
                    .fillMaxSize()
                    .padding(18.dp)
                    .pointerInput(plotted) {
                        detectTapGestures { offset ->
                            tapPoint = offset
                            val nearest = plotted.minByOrNull { (_, p) ->
                                hypot(
                                    p.first * size.width - offset.x,
                                    (1f - p.second) * size.height - offset.y,
                                )
                            }
                            selected = nearest?.first
                        }
                    }
            ) {
                // Faint grid, so the axes read as measured space.
                val step = size.width / 4
                for (i in 1..3) {
                    drawLine(colors.line, Offset(step * i, 0f), Offset(step * i, size.height), 1f)
                    drawLine(colors.line, Offset(0f, step * i), Offset(size.width, step * i), 1f)
                }

                plotted.forEach { (track, p) ->
                    val x = p.first * size.width
                    val y = (1f - p.second) * size.height
                    val isCurrent = track.id.toString() == state.nowPlayingId
                    val isSelected = track.id == selected?.id

                    drawCircle(
                        color = when {
                            isCurrent -> colors.teal
                            isSelected -> colors.sky
                            else -> colors.ink3.copy(alpha = 0.55f)
                        },
                        radius = if (isCurrent || isSelected) 9f else 5.5f,
                        center = Offset(x, y),
                    )
                    if (isCurrent) {
                        drawCircle(colors.teal.copy(alpha = 0.22f), 20f, Offset(x, y))
                    }
                }
            }

            // Axis labels sit inside the well, like markings on an instrument.
            Text("INTENSE", style = Oto.type.micro, color = colors.ink3,
                modifier = Modifier.align(Alignment.TopCenter).padding(6.dp))
            Text("CALM", style = Oto.type.micro, color = colors.ink3,
                modifier = Modifier.align(Alignment.BottomCenter).padding(6.dp))
            Text("DARK", style = Oto.type.micro, color = colors.ink3,
                modifier = Modifier.align(Alignment.CenterStart).padding(6.dp))
            Text("BRIGHT", style = Oto.type.micro, color = colors.ink3,
                modifier = Modifier.align(Alignment.CenterEnd).padding(6.dp))
        }

        VSpace(12.dp)

        val chosen = selected
        if (chosen != null) {
            NeuCard(Modifier.fillMaxWidth().padding(horizontal = 14.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(chosen.displayTitle, style = Oto.type.item, color = colors.ink)
                    Text(chosen.subtitleLine(), style = Oto.type.sub, color = colors.ink2)
                    val (v, e) = viewModel.moodOf(chosen)
                    Text(
                        "brightness ${(v * 100).toInt()}%  ·  intensity ${(e * 100).toInt()}%",
                        style = Oto.type.data,
                        color = colors.ink3,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        NeuCard(radius = 50.dp, onClick = { viewModel.playFrom(chosen) }) {
                            Text(
                                "PLAY FROM HERE",
                                style = Oto.type.micro,
                                color = colors.teal,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                            )
                        }
                    }
                }
            }
        } else {
            Column(Modifier.padding(horizontal = 26.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("TAP A POINT", style = Oto.type.label, color = colors.ink3)
                Text(
                    "Each dot is a track, placed by measured intensity and derived " +
                        "brightness. Empty regions are the music you never reach for.",
                    style = Oto.type.body,
                    color = colors.ink2,
                )
            }
        }
    }
}

/** (x, y) in 0..1 -- brightness against intensity. */
private fun position(track: Track): Pair<Float, Float> =
    Features.valenceOf(track) to track.energy.coerceIn(0f, 1f)
