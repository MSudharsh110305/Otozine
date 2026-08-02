package net.otozine.player.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import net.otozine.player.PlayerViewModel
import net.otozine.player.ui.components.SectionHeader
import net.otozine.player.ui.components.VSpace
import net.otozine.player.ui.theme.Depth
import net.otozine.player.ui.theme.Oto
import net.otozine.player.ui.theme.neu

/**
 * Your own mood labels for a track.
 *
 * Multi-select, deliberately. A song is rarely one thing -- "calm" and "gentle"
 * and a bit "melancholic" all at once is the normal case, not an edge case, and
 * a single-choice control would quietly discard most of what you meant.
 *
 * The vocabulary is the same one the analyser uses, so your labels and its
 * guesses live in the same space: yours simply outrank its, and the queue can
 * draw on both without translating between two sets of words.
 */
object MoodPalette {
    val CALM = listOf("calm", "gentle", "dreamy", "warm", "romantic", "acoustic")
    val LOW = listOf("melancholic", "sad", "brooding", "dark", "tense")
    val HIGH = listOf("energetic", "driving", "playful", "joyful", "uplifting")
    val PEAK = listOf("intense", "aggressive", "epic", "dense")

    val ALL = CALM + LOW + HIGH + PEAK
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MoodSheet(state: PlayerViewModel.UiState, viewModel: PlayerViewModel, onDismiss: () -> Unit) {
    val track = state.nowPlaying
    var selected by remember(track?.id) { mutableStateOf(state.currentMoods) }

    Sheet(
        title = "How does this feel?",
        subtitle = "Pick as many as fit. Your labels outrank what the app guessed, " +
            "and shape what it plays next.",
        onDismiss = onDismiss,
    ) {
        Column {
            if (track == null) {
                Text("Nothing playing.", style = Oto.type.body, color = Oto.colors.ink2)
                return@Column
            }

            // The app's own reading, shown for comparison rather than hidden.
            // Seeing what it thought is what makes correcting it feel worthwhile.
            if (state.currentGuessedMoods.isNotEmpty()) {
                Text(
                    "The app hears: " + state.currentGuessedMoods.joinToString(", "),
                    style = Oto.type.body,
                    color = Oto.colors.ink3,
                )
                VSpace(12.dp)
            }

            listOf(
                "Calm" to MoodPalette.CALM,
                "Low" to MoodPalette.LOW,
                "Lively" to MoodPalette.HIGH,
                "Peak" to MoodPalette.PEAK,
            ).forEach { (heading, moods) ->
                SectionHeader(heading)
                FlowRow(
                    Modifier.fillMaxWidth().padding(horizontal = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    moods.forEach { mood ->
                        MoodChip(mood, mood in selected) {
                            selected = if (mood in selected) selected - mood else selected + mood
                        }
                    }
                }
                VSpace(10.dp)
            }

            VSpace(4.dp)
            SheetButton(
                if (selected.isEmpty()) "CLEAR MY LABELS" else "SAVE ${selected.size} LABELS",
                accent = true,
                modifier = Modifier.fillMaxWidth(),
            ) {
                viewModel.saveMoods(track.id, selected)
                onDismiss()
            }
        }
    }
}

@Composable
private fun MoodChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .neu(if (selected) Depth.Inset else Depth.RaisedSoft, RoundedCornerShape(50))
            .clip(RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = 15.dp, vertical = 10.dp),
    ) {
        Text(
            label.uppercase(),
            style = Oto.type.micro,
            color = if (selected) Oto.colors.teal else Oto.colors.ink3,
        )
    }
}
