package net.otozine.player.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.otozine.player.PlayerViewModel
import net.otozine.player.QueueMode
import net.otozine.player.library.Track
import net.otozine.player.library.isAnalysed
import net.otozine.player.queue.QueueEngine
import net.otozine.player.ui.components.ArtTile
import net.otozine.player.ui.components.NeuCard
import net.otozine.player.ui.components.SectionHeader
import net.otozine.player.ui.components.VSpace
import net.otozine.player.ui.components.subtitleLine
import net.otozine.player.ui.theme.Depth
import net.otozine.player.ui.theme.Oto
import net.otozine.player.ui.theme.neu

/**
 * The algorithmic surface.
 *
 * Not a file list -- the point of the app is what it chooses, so the home
 * screen is the engine's front door.
 */
@Composable
fun PlayScreen(state: PlayerViewModel.UiState, viewModel: PlayerViewModel, onOpenVibe: () -> Unit) {
    val colors = Oto.colors
    val scroll = rememberScrollState()

    Column(Modifier.verticalScroll(scroll)) {

        SearchBar(state.searchQuery, viewModel::search, placeholder = "Search everything…")

        // Searching turns Play into a result list -- the fastest path from
        // "I want that song" to hearing it, without leaving the screen.
        if (state.searchQuery.isNotBlank()) {
            val hits = remember(state.tracks, state.searchQuery) {
                state.tracks.matching(state.searchQuery)
            }
            SectionHeader("${hits.size} results")
            hits.take(30).forEach { track ->
                net.otozine.player.ui.components.TrackRow(
                    track = track,
                    isCurrent = track.id.toString() == state.nowPlayingId,
                    artPath = viewModel.artPathFor(track),
                    onClick = { viewModel.playFrom(track) },
                )
            }
            VSpace(16.dp)
            return@Column
        }

        // --- continue / start ------------------------------------------
        SectionHeader(if (state.nowPlaying != null) "Continue" else "Start listening")
        val hero = state.nowPlaying ?: state.tracks.firstOrNull()
        if (hero != null) {
            HeroCard(
                track = hero,
                reason = state.currentReason?.headline ?: "first pick of the session",
                artPath = viewModel.artPathFor(hero),
                onPlay = { viewModel.playFrom(hero) },
            )
        }

        // --- how the queue is built ------------------------------------
        VSpace(6.dp)
        SectionHeader("Queue")
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Chip("ANTI-REPEAT", state.queueMode == QueueMode.ANTI_REPEAT) {
                viewModel.setQueueMode(QueueMode.ANTI_REPEAT)
            }
            Chip("JUST SHUFFLE", state.queueMode == QueueMode.SHUFFLE) {
                viewModel.setQueueMode(QueueMode.SHUFFLE)
            }
        }
        Explain(
            if (state.queueMode == QueueMode.ANTI_REPEAT)
                "Avoids what you heard recently and never repeats the same order."
            else
                "Plain random over the whole library. No cooldown, no sequencing."
        )

        // --- adventure -------------------------------------------------
        VSpace(6.dp)
        SectionHeader("Adventure")
        Explain(
            "How far the queue wanders. Left keeps it near what you already " +
                "play; right pulls in things you have not heard. Reshapes what is " +
                "coming up without interrupting this track."
        )
        if (state.queueMode == QueueMode.ANTI_REPEAT) AdventureSlider(
            value = state.adventure,
            onChange = { viewModel.setAdventure(it); viewModel.rebuildTailDebounced() },
            onRelease = { viewModel.rebuildTail() },
        )

        // --- shaped sessions -------------------------------------------
        // These need measured mood to mean anything. Against tracks that were
        // never analysed every value is the same neutral 0.5, so the presets
        // would look like they work and quietly do nothing.
        val analysed = remember(state.libraryTracks) { state.libraryTracks.count { it.isAnalysed } }
        // Only offer sessions that can actually match something in the library.
        val available = remember(state.libraryTracks, state.labelledCount) { viewModel.knownMoods() }

        VSpace(10.dp)
        SectionHeader("Sessions", action = if (analysed > 0) "set vibe" else null, onAction = onOpenVibe)
        if (analysed > 0) {
            Explain(
                if (state.targetMoods.isEmpty())
                    "Start a queue shaped around a mood. Tracks you have labelled " +
                        "yourself count first."
                else
                    "Steering toward: " + state.targetMoods.joinToString(", ") + "."
            )
            LazyRow(
                contentPadding = PaddingValues(horizontal = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                // Sessions are sets of mood words rather than points in feature
                // space, so they match the analyser's labels and yours equally.
                items(SESSIONS, key = { it.first }) { (label, moods) ->
                    Preset(
                        label = label,
                        active = state.targetMoods == moods,
                        enabled = moods.any { it in available },
                    ) {
                        if (state.targetMoods == moods) viewModel.clearSession()
                        else viewModel.startSession(moods)
                    }
                }
            }
        } else {
            Explain(
                "Mood sessions need tracks measured by the Librarian — nothing " +
                    "here has a tempo or key yet, so there is no mood to sort by. " +
                    "Anti-repeat still works: the queue avoids songs you heard " +
                    "recently and never repeats the same order."
            )
        }

        // --- never played ----------------------------------------------
        //
        // Genuinely never played, which it previously was not: the filter only
        // checked that a track was playable and then shuffled, so the shelf
        // happily offered songs played an hour earlier. `historyEvents` is a
        // count and can only say how much listening happened, never to what --
        // answering that needs the per-track history the queue engine already
        // uses for cooldown.
        //
        // Both sources are covered. Phone tracks carry negative ids and drive
        // tracks positive ones, so they cannot collide, and whichever sources
        // are connected are all eligible here.
        val neverPlayed = remember(state.tracks, state.playedTrackIds) {
            state.tracks
                .filter { it.opusPath != null && it.id !in state.playedTrackIds }
                .shuffled()
                .take(10)
        }
        if (neverPlayed.isNotEmpty()) {
            VSpace(8.dp)
            SectionHeader("Never played")
            // LazyRow with contentPadding rather than a scrolling Row with a
            // padding modifier: applied after horizontalScroll the padding sits
            // *inside* the viewport, so the first and last card were clipped
            // mid-letter. contentPadding scrolls with the content instead.
            LazyRow(
                contentPadding = PaddingValues(horizontal = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(11.dp),
            ) {
                items(neverPlayed, key = { it.id }) { track ->
                    CoverCard(track, viewModel.artPathFor(track)) { viewModel.playFrom(track) }
                }
            }
        }

        // --- what the engine knows -------------------------------------
        VSpace(10.dp)
        SectionHeader("On-device")
        NeuCard(Modifier.fillMaxWidth().padding(horizontal = 14.dp)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                // "Cached" was a lie: nothing is cached, these are simply the
                // tracks the app can see right now, and the number changes with
                // what is plugged in. Naming each source says what it is and
                // makes a missing drive obvious at a glance.
                // A summary only. The full breakdown lives under More >
                // Diagnostics; repeating it here in a different shape was what
                // made the two screens look like they disagreed.
                val measured = remember(state.tracks) { state.tracks.count { it.isAnalysed } }
                StatLine("Songs", "${state.playableCount}")
                StatLine("Measured", "$measured of ${state.tracks.size}")
                StatLine("Plays recorded", "${state.historyEvents}")
                VSpace(4.dp)
                Text(
                    if (state.pendingSync > 0)
                        "Listening history is saved here and copied to the drive " +
                            "next time it is plugged in. Nothing leaves your devices."
                    else
                        "Listening history is saved here and goes to the drive when " +
                            "it is plugged in. Nothing leaves your devices.",
                    style = Oto.type.body,
                    color = colors.ink3,
                )
            }
        }
        VSpace(18.dp)
    }
}

/** Short note under a section header, for things that need a word of context. */
@Composable
private fun Explain(text: String) {
    Text(
        text,
        style = Oto.type.body,
        color = Oto.colors.ink3,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 2.dp),
    )
    VSpace(8.dp)
}

@Composable
private fun HeroCard(track: Track, reason: String, artPath: String?, onPlay: () -> Unit) {
    val artPx = with(LocalDensity.current) { 92.dp.roundToPx() }
    NeuCard(
        Modifier.fillMaxWidth().padding(horizontal = 14.dp),
        radius = 22.dp,
        onClick = onPlay,
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(92.dp).neu(Depth.Inset, RoundedCornerShape(18.dp))) {
                ArtTile(
                    artKey = track.contentHash,
                    title = track.displayTitle,
                    bitmap = rememberArt(artPath, artPx),
                    modifier = Modifier.size(92.dp).padding(4.dp),
                    radius = 15.dp,
                )
            }
            Column(Modifier.padding(start = 14.dp)) {
                Text(
                    track.displayTitle,
                    style = Oto.type.title,
                    color = Oto.colors.ink,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    track.subtitleLine(),
                    style = Oto.type.sub,
                    color = Oto.colors.ink2,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                VSpace(8.dp)
                Text(reason.uppercase(), style = Oto.type.micro, color = Oto.colors.teal)
            }
        }
    }
}

/**
 * The exploration budget, as one control.
 *
 * Left keeps the queue near what is already playing; right lets it roam. It
 * feeds straight into the engine's scoring, so moving it visibly changes what
 * comes next rather than being a decorative preference.
 */
@Composable
private fun AdventureSlider(value: Float, onChange: (Float) -> Unit, onRelease: () -> Unit) {
    var width by remember { mutableFloatStateOf(1f) }

    Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp)) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(34.dp)
                .neu(Depth.Inset, RoundedCornerShape(50))
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragEnd = { onRelease() },
                    ) { change, _ ->
                        onChange((change.position.x / size.width).coerceIn(0f, 1f))
                    }
                },
            contentAlignment = Alignment.CenterStart,
        ) {
            Box(
                Modifier
                    .fillMaxWidth(value.coerceIn(0.04f, 1f))
                    .height(34.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Oto.colors.teal.copy(alpha = 0.22f))
            )
            Box(
                Modifier
                    .padding(start = 4.dp)
                    .fillMaxWidth(value.coerceIn(0.04f, 1f))
            ) {
                Box(
                    Modifier
                        .align(Alignment.CenterEnd)
                        .size(26.dp)
                        .neu(Depth.Raised, CircleShape)
                )
            }
        }
        VSpace(6.dp)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("COMFORT", style = Oto.type.micro, color = Oto.colors.ink3)
            Box(Modifier.weight(1f))
            Text(
                when {
                    value < 0.2f -> "FAVOURITES"
                    value < 0.45f -> "MOSTLY KNOWN"
                    value < 0.7f -> "A FEW SURPRISES"
                    value < 0.9f -> "MOSTLY NEW"
                    else -> "ANYTHING"
                },
                style = Oto.type.label,
                color = Oto.colors.teal,
            )
            Box(Modifier.weight(1f))
            Text("ADVENTURE", style = Oto.type.micro, color = Oto.colors.ink3)
        }
    }
}

/** Named sessions, each a set of mood words the queue steers toward. */
private val SESSIONS: List<Pair<String, Set<String>>> = listOf(
    "CALM" to setOf("calm", "gentle", "dreamy"),
    "ROMANTIC" to setOf("romantic", "warm", "gentle"),
    "MELANCHOLY" to setOf("melancholic", "sad", "brooding"),
    "LIVELY" to setOf("energetic", "playful", "joyful"),
    "INTENSE" to setOf("intense", "driving", "aggressive"),
    "DARK" to setOf("dark", "brooding", "tense"),
)

@Composable
private fun Preset(
    label: String,
    active: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    NeuCard(
        radius = 50.dp,
        depth = if (active) Depth.Inset else Depth.RaisedSoft,
        onClick = if (enabled) onClick else null,
    ) {
        Text(
            label,
            style = Oto.type.micro,
            color = when {
                active -> Oto.colors.teal
                enabled -> Oto.colors.ink2
                // Shown but muted: knowing a session exists and has nothing to
                // draw on yet is more useful than it silently disappearing.
                else -> Oto.colors.ink3.copy(alpha = 0.45f)
            },
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
        )
    }
}

@Composable
private fun CoverCard(track: Track, artPath: String?, onClick: () -> Unit) {
    val artPx = with(LocalDensity.current) { 120.dp.roundToPx() }
    Column(
        Modifier.width(120.dp).clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick),
    ) {
        Box(Modifier.size(120.dp).neu(Depth.RaisedSoft, RoundedCornerShape(16.dp))) {
            ArtTile(
                artKey = track.contentHash,
                title = track.displayTitle,
                bitmap = rememberArt(artPath, artPx),
                modifier = Modifier.size(120.dp).padding(5.dp),
                radius = 12.dp,
            )
        }
        VSpace(6.dp)
        Text(
            track.displayTitle,
            style = Oto.type.micro,
            color = Oto.colors.ink,
            maxLines = 2,
            lineHeight = 15.sp,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
        )
    }
}

@Composable
private fun StatLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = Oto.type.body, color = Oto.colors.ink2)
        Box(Modifier.weight(1f))
        Text(value, style = Oto.type.data, color = Oto.colors.ink)
    }
}
