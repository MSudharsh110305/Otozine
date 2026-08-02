package net.otozine.player.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.foundation.interaction.MutableInteractionSource
import net.otozine.player.ui.theme.pressable
import androidx.compose.ui.unit.dp
import net.otozine.player.PlayerViewModel
import net.otozine.player.queue.QueueEngine
import net.otozine.player.ui.components.NeuCard
import net.otozine.player.ui.components.SectionHeader
import net.otozine.player.ui.components.VSpace
import net.otozine.player.ui.components.subtitleLine
import net.otozine.player.ui.theme.Depth
import net.otozine.player.ui.theme.Oto
import net.otozine.player.ui.theme.scrim
import net.otozine.player.ui.theme.neu
import kotlin.math.roundToInt

/** Shared chrome: dimmed backdrop, grab handle, title. */
@Composable
fun Sheet(
    title: String,
    subtitle: String? = null,
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Oto.colors.scrim())
            .clickable(onClick = onDismiss)
            .systemBarsPadding(),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(12.dp)
                .neu(Depth.Raised, RoundedCornerShape(26.dp))
                .clickable(enabled = false) {}
                .padding(18.dp),
        ) {
            Box(
                Modifier
                    .align(Alignment.CenterHorizontally)
                    .width(44.dp)
                    .height(5.dp)
                    .neu(Depth.Inset, RoundedCornerShape(50))
            )
            VSpace(14.dp)
            Text(title, style = Oto.type.title, color = Oto.colors.ink)
            if (subtitle != null) {
                VSpace(4.dp)
                Text(subtitle, style = Oto.type.body, color = Oto.colors.ink2)
            }
            VSpace(14.dp)
            content()
        }
    }
}

// ------------------------------------------------------------------- queue

@Composable
fun QueueSheet(state: PlayerViewModel.UiState, viewModel: PlayerViewModel, onDismiss: () -> Unit) {
    Sheet(
        title = "Queue",
        subtitle = "Built on-device. The engine avoids ${state.queue.size} " +
            "transitions it has already served.",
        onDismiss = onDismiss,
    ) {
        Column {
            SectionHeader("Playing next", action = "rebuild", onAction = {
                viewModel.rebuildQueue()
                onDismiss()
            })
            LazyColumn(Modifier.fillMaxWidth().height(360.dp)) {
                itemsIndexed(state.queue, key = { _, e -> e.track.id }) { index, entry ->
                    QueueRow(
                        entry = entry,
                        isCurrent = entry.track.id.toString() == state.nowPlayingId,
                        onClick = { viewModel.playQueueIndex(index) },
                    )
                }
            }
        }
    }
}

@Composable
private fun QueueRow(entry: QueueEngine.Entry, isCurrent: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .let { if (isCurrent) it.neu(Depth.Inset, RoundedCornerShape(13.dp)) else it }
            .clip(RoundedCornerShape(13.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                entry.track.displayTitle,
                style = Oto.type.item,
                color = if (isCurrent) Oto.colors.teal else Oto.colors.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                entry.track.subtitleLine(),
                style = Oto.type.sub,
                color = Oto.colors.ink2,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            VSpace(2.dp)
            // The micro-reason is the whole point: you can see why it is next.
            Text(entry.headline.uppercase(), style = Oto.type.micro, color = Oto.colors.ink3)
        }
    }
}

// -------------------------------------------------------------------- why

@Composable
fun WhySheet(state: PlayerViewModel.UiState, viewModel: PlayerViewModel, onDismiss: () -> Unit) {
    val entry = state.currentReason
    val track = state.nowPlaying

    Sheet(
        title = "Why this track",
        subtitle = "Nudge a factor and the queue rebuilds from here on.",
        onDismiss = onDismiss,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (entry == null || entry.reasons.isEmpty()) {
                Text(
                    "This one was picked directly, so there is nothing to explain. " +
                        "Reasons appear for tracks the engine chose.",
                    style = Oto.type.body,
                    color = Oto.colors.ink2,
                )
            } else {
                entry.reasons.forEach { reason ->
                    ReasonBar(reason.label, reason.weight)
                }
            }

            if (track != null) {
                VSpace(6.dp)
                SectionHeader("What I think this is")
                val (valence, energy) = viewModel.moodOf(track)
                Text(
                    listOfNotNull(
                        track.bpm?.let { "${it.toInt()} BPM" },
                        track.keyCamelot,
                        "brightness ${(valence * 100).toInt()}%",
                        "intensity ${(energy * 100).toInt()}%",
                        track.language?.uppercase(),
                    ).joinToString("  ·  "),
                    style = Oto.type.data,
                    color = Oto.colors.ink2,
                )
                VSpace(4.dp)
                Text(
                    "Brightness is inferred from key, tempo and energy — not measured. " +
                        "It can be confidently wrong about a happy song in a minor key.",
                    style = Oto.type.body,
                    color = Oto.colors.ink3,
                )
            }

            VSpace(8.dp)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SheetButton("MORE LIKE THIS", accent = true, modifier = Modifier.weight(1f)) {
                    viewModel.playFrom(track); onDismiss()
                }
                SheetButton("REBUILD", modifier = Modifier.weight(1f)) {
                    viewModel.rebuildQueue(); onDismiss()
                }
            }
        }
    }
}

@Composable
private fun ReasonBar(label: String, weight: Float) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = Oto.type.body, color = Oto.colors.ink, modifier = Modifier.weight(1f))
            Text("${(weight * 100).toInt()}", style = Oto.type.data, color = Oto.colors.teal)
        }
        VSpace(5.dp)
        Box(
            Modifier.fillMaxWidth().height(7.dp).neu(Depth.Inset, RoundedCornerShape(50)),
            contentAlignment = Alignment.CenterStart,
        ) {
            Box(
                Modifier
                    .fillMaxWidth(weight.coerceIn(0.03f, 1f))
                    .height(7.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Oto.colors.teal.copy(alpha = 0.8f))
            )
        }
    }
}

// ------------------------------------------------------------------- vibe

@Composable
fun VibeSheet(state: PlayerViewModel.UiState, viewModel: PlayerViewModel, onDismiss: () -> Unit) {
    var valence by remember { mutableStateOf(state.mood?.valence ?: 0.5f) }
    var energy by remember { mutableStateOf(state.mood?.energy ?: 0.5f) }

    Sheet(
        title = "Set the vibe",
        subtitle = "Drag the puck. The queue is rebuilt around that point.",
        onDismiss = onDismiss,
    ) {
        Column {
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .neu(Depth.InsetDeep, RoundedCornerShape(22.dp))
            ) {
                val density = LocalDensity.current
                var boxSize by remember { mutableStateOf(1f) }

                Box(
                    Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            boxSize = size.width.toFloat()
                            detectDragGestures { change, _ ->
                                valence = (change.position.x / size.width).coerceIn(0f, 1f)
                                energy = 1f - (change.position.y / size.height).coerceIn(0f, 1f)
                            }
                        }
                ) {
                    val puck = 40.dp
                    Box(
                        Modifier
                            .offset {
                                IntOffset(
                                    ((boxSize - with(density) { puck.toPx() }) * valence).roundToInt(),
                                    ((boxSize - with(density) { puck.toPx() }) * (1f - energy)).roundToInt(),
                                )
                            }
                            .size(puck)
                            .neu(Depth.Raised, CircleShape)
                    )
                }

                Text("INTENSE", style = Oto.type.micro, color = Oto.colors.ink3,
                    modifier = Modifier.align(Alignment.TopCenter).padding(8.dp))
                Text("CALM", style = Oto.type.micro, color = Oto.colors.ink3,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(8.dp))
                Text("DARK", style = Oto.type.micro, color = Oto.colors.ink3,
                    modifier = Modifier.align(Alignment.CenterStart).padding(8.dp))
                Text("BRIGHT", style = Oto.type.micro, color = Oto.colors.ink3,
                    modifier = Modifier.align(Alignment.CenterEnd).padding(8.dp))
            }

            VSpace(14.dp)
            val matching = state.tracks.count { track ->
                val (v, e) = viewModel.moodOf(track)
                kotlin.math.abs(v - valence) < 0.25f && kotlin.math.abs(e - energy) < 0.25f
            }
            Text(
                "$matching tracks near this point",
                style = Oto.type.data,
                color = Oto.colors.ink2,
            )

            VSpace(12.dp)
            SheetButton("BUILD QUEUE", accent = true, modifier = Modifier.fillMaxWidth()) {
                viewModel.setMood(QueueEngine.Mood(valence, energy))
                viewModel.playFrom(null)
                onDismiss()
            }
            VSpace(8.dp)
            SheetButton("CLEAR VIBE", modifier = Modifier.fillMaxWidth()) {
                viewModel.setMood(null); onDismiss()
            }
        }
    }
}

// ------------------------------------------------------------------ timer

@Composable
fun TimerSheet(state: PlayerViewModel.UiState, viewModel: PlayerViewModel, onDismiss: () -> Unit) {
    Sheet(
        title = "Sleep timer",
        subtitle = state.sleepTimerEndsAt?.let {
            val left = ((it - System.currentTimeMillis()) / 60000).coerceAtLeast(0)
            "Playback stops in about $left min."
        } ?: "Playback stops when the timer runs out.",
        onDismiss = onDismiss,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
            listOf(15, 30, 45, 60).forEach { minutes ->
                SheetButton("$minutes MINUTES", modifier = Modifier.fillMaxWidth()) {
                    viewModel.setSleepTimer(minutes); onDismiss()
                }
            }
            if (state.sleepTimerEndsAt != null) {
                SheetButton("CANCEL TIMER", accent = true, modifier = Modifier.fillMaxWidth()) {
                    viewModel.setSleepTimer(null); onDismiss()
                }
            }
        }
    }
}

// ---------------------------------------------------------------- storage

@Composable
fun StorageSheet(
    state: PlayerViewModel.UiState,
    onImport: () -> Unit,
    onDismiss: () -> Unit,
) {
    val cached = state.tracks.count { it.opusPath != null }

    Sheet(
        title = "Library & sync",
        subtitle = "The drive is the source of truth. This device holds a subset.",
        onDismiss = onDismiss,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            StorageRow("Tracks on this device", "$cached")
            StorageRow("Plays recorded here", "${state.historyEvents}")
            StorageRow("Waiting to sync back", "${state.pendingSync}")
            VSpace(6.dp)
            Text(
                "Play history lives in a separate database, so re-importing from the " +
                    "drive cannot erase what the engine has learned. Merging it back " +
                    "onto the drive is not built yet — the events queue safely until it is.",
                style = Oto.type.body,
                color = Oto.colors.ink3,
            )
            VSpace(6.dp)
            SheetButton("IMPORT / REPLACE LIBRARY", accent = true, modifier = Modifier.fillMaxWidth()) {
                onImport()
            }
        }
    }
}

@Composable
private fun StorageRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = Oto.type.body, color = Oto.colors.ink2)
        Box(Modifier.weight(1f))
        Text(value, style = Oto.type.data, color = Oto.colors.ink)
    }
}

@Composable
fun SheetButton(
    label: String,
    modifier: Modifier = Modifier,
    accent: Boolean = false,
    onClick: () -> Unit,
) {
    // No ripple: a neumorphic surface has painted depth, and a Material ripple
    // spreading across it looks like a wet patch rather than a press. The scale
    // dip does the same job in the language the rest of the app speaks.
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier
            .pressable(interaction)
            .neu(if (accent) Depth.Raised else Depth.RaisedSoft, RoundedCornerShape(50))
            .clip(RoundedCornerShape(50))
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = Oto.type.micro,
            color = if (accent) Oto.colors.teal else Oto.colors.ink2,
        )
    }
}
