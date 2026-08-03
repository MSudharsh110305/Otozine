package net.otozine.player.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.interaction.MutableInteractionSource
import net.otozine.player.ui.theme.pressable
import androidx.compose.ui.unit.dp
import net.otozine.player.PlayerViewModel
import net.otozine.player.Source
import net.otozine.player.library.DriveWatcher
import net.otozine.player.library.Track
import net.otozine.player.ui.components.NeuCard
import net.otozine.player.ui.components.SectionHeader
import net.otozine.player.ui.components.TrackRow
import net.otozine.player.ui.components.VSpace
import net.otozine.player.ui.theme.Depth
import net.otozine.player.ui.theme.Oto
import net.otozine.player.ui.theme.neu

/** How the list is broken up. */
private enum class GroupBy { ALL, MOOD }

@Composable
fun LibraryScreen(
    state: PlayerViewModel.UiState,
    viewModel: PlayerViewModel,
    onImport: () -> Unit,
    onScanPhone: () -> Unit = {},
    onTrackMenu: (Track) -> Unit = {},
) {
    val available = buildList {
        add(Source.LIBRARY)
        if (state.deviceTracks.isNotEmpty()) add(Source.DEVICE)
        if (state.remoteTracks.isNotEmpty()) add(Source.ONLINE)
    }

    var chosen by remember { mutableStateOf<Source?>(null) }
    val source = chosen?.takeIf { it in available }
        ?: when {
            state.libraryTracks.isNotEmpty() -> Source.LIBRARY
            state.deviceTracks.isNotEmpty() -> Source.DEVICE
            state.remoteTracks.isNotEmpty() -> Source.ONLINE
            else -> Source.LIBRARY
        }

    var groupBy by remember { mutableStateOf(GroupBy.MOOD) }
    var openMood by remember { mutableStateOf<String?>(null) }
    var selecting by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(emptySet<Long>()) }

    val all = when (source) {
        Source.LIBRARY -> state.libraryTracks
        Source.DEVICE -> state.deviceTracks
        Source.ONLINE -> state.remoteTracks
    }
    val shown = remember(all, state.searchQuery) {
        if (state.searchQuery.isBlank()) all else all.matching(state.searchQuery)
    }

    Column(Modifier.fillMaxSize()) {
        SearchBar(state.searchQuery, viewModel::search, placeholder = "Song name…")

        if (available.size > 1) {
            Row(
                Modifier.horizontalScroll(rememberScrollState())
                    .padding(horizontal = 14.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                available.forEach { candidate ->
                    Chip(
                        label = when (candidate) {
                            Source.LIBRARY -> "DRIVE · ${state.libraryTracks.size}"
                            Source.DEVICE -> "PHONE · ${state.deviceTracks.size}"
                            Source.ONLINE -> "ONLINE · ${state.remoteTracks.size}"
                        },
                        selected = candidate == source,
                    ) {
                        chosen = candidate; openMood = null
                        selecting = false; selected = emptySet()
                    }
                }
            }
        }

        // Offered only where it can actually be done: looking at phone music
        // with the drive physically attached. `libraryPresent` was the wrong
        // test -- it only means a library has been imported at some point, which
        // stays true after the drive is unplugged, so the button survived the
        // one condition it depended on.
        val driveAttached = state.driveState == DriveWatcher.State.CONNECTED
        val canCopy = source == Source.DEVICE && driveAttached && shown.isNotEmpty()

        if (canCopy && !selecting) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Chip("CHOOSE SONGS TO COPY", selected = false) {
                    selecting = true; selected = emptySet()
                }
            }
        }

        // Selection gets its own bar rather than more chips beside DRIVE/PHONE.
        // Those chips answer "what am I looking at"; these are actions taken on
        // it, and mixing the two made a row where adjacent controls did
        // completely different kinds of thing.
        if (canCopy && selecting) {
            val allShown = shown.map { it.id }.toSet()
            val everything = selected.containsAll(allShown)
            NeuCard(
                Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp),
                radius = 16.dp,
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        if (selected.isEmpty()) "NONE PICKED"
                        else "${selected.size} PICKED",
                        style = Oto.type.label,
                        color = if (selected.isEmpty()) Oto.colors.ink3 else Oto.colors.teal,
                        modifier = Modifier.weight(1f),
                    )
                    Chip(if (everything) "NONE" else "ALL", selected = false) {
                        selected = if (everything) emptySet() else allShown
                    }
                    Chip("CANCEL", selected = false) {
                        selecting = false; selected = emptySet()
                    }
                    Chip("COPY", selected = selected.isNotEmpty()) {
                        if (selected.isNotEmpty()) {
                            viewModel.copyToDrive(shown.filter { it.id in selected })
                            selecting = false; selected = emptySet()
                        }
                    }
                }
            }
        }

        when {
            state.loading -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                CircularProgressIndicator(color = Oto.colors.teal)
            }

            all.isEmpty() && source == Source.LIBRARY ->
                EmptyLibrary(state.libraryPresent, onImport, onScanPhone)

            state.searchQuery.isNotBlank() -> FlatList(shown, state, viewModel, onTrackMenu)

            selecting -> FlatList(
                shown, state, viewModel, onTrackMenu,
                selection = selected,
                onToggleSelect = { track ->
                    selected = if (track.id in selected) selected - track.id
                    else selected + track.id
                },
            )

            else -> {
                // Grouping tabs. Artist and album are absent on purpose: on a
                // library of rips they are recovered badly, so browsing by them
                // sorts on noise. Mood is measured from the audio, so it is the
                // one axis that stays correct however the files were named.
                Row(
                    Modifier.horizontalScroll(rememberScrollState())
                        .padding(horizontal = 14.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Chip("BY MOOD", groupBy == GroupBy.MOOD) {
                        groupBy = GroupBy.MOOD; openMood = null
                    }
                    Chip("ALL SONGS", groupBy == GroupBy.ALL) {
                        groupBy = GroupBy.ALL; openMood = null
                    }
                }

                when (groupBy) {
                    GroupBy.ALL -> FlatList(shown, state, viewModel, onTrackMenu)
                    GroupBy.MOOD -> MoodBrowser(
                        groups = remember(shown, state.moodsByTrack) {
                            groupByMood(shown, state.moodsByTrack)
                        },
                        open = openMood,
                        onOpen = { openMood = it },
                        state = state, viewModel = viewModel, onTrackMenu = onTrackMenu,
                    )
                }
            }
        }
    }
}

/**
 * Bucket tracks by mood label.
 *
 * A track appears under every mood it carries rather than being forced into
 * one, which is the whole point of multi-label: something calm *and* melancholy
 * should be findable from either. Buckets of one are dropped -- a heading with a
 * single track under it is noise, not structure.
 */
private fun groupByMood(
    tracks: List<Track>,
    moods: Map<Long, List<String>>,
): List<Pair<String, List<Track>>> {
    val buckets = HashMap<String, MutableList<Track>>()
    val unlabelled = ArrayList<Track>()

    tracks.forEach { track ->
        val labels = moods[track.id].orEmpty()
        if (labels.isEmpty()) unlabelled += track
        else labels.take(3).forEach { buckets.getOrPut(it) { ArrayList() } += track }
    }

    val ordered = buckets.filter { it.value.size > 1 }
        .toList()
        .sortedByDescending { it.second.size }
        .map { (mood, list) -> mood.uppercase() to list }

    return if (unlabelled.isEmpty()) ordered else ordered + ("NOT ANALYSED" to unlabelled)
}

/**
 * Browse moods as a grid, then drill into one.
 *
 * The accordion this replaces stacked every mood in a single scrolling column,
 * so a dozen labels plus an expanded group meant hunting through a list to find
 * a list. A grid shows the whole vocabulary at once in about a screen, and
 * opening one gives it the full width instead of a nested strip -- browsing and
 * reading stop competing for the same space.
 */
@Composable
private fun MoodBrowser(
    groups: List<Pair<String, List<Track>>>,
    open: String?,
    onOpen: (String?) -> Unit,
    state: PlayerViewModel.UiState,
    viewModel: PlayerViewModel,
    onTrackMenu: (Track) -> Unit,
) {
    if (groups.isEmpty()) {
        Column(Modifier.padding(26.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("NO LABELS YET", style = Oto.type.label, color = Oto.colors.ink3)
            Text(
                "Moods are measured from the audio. Run Analyse on this phone " +
                    "under More, or tag tracks yourself from Now Playing.",
                style = Oto.type.body,
                color = Oto.colors.ink2,
            )
        }
        return
    }

    val opened = groups.firstOrNull { it.first == open }
    if (opened != null) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Chip("BACK", selected = false) { onOpen(null) }
                Text(
                    opened.first.uppercase(),
                    style = Oto.type.label,
                    color = Oto.colors.teal,
                )
                Text(
                    "${opened.second.size}",
                    style = Oto.type.micro,
                    color = Oto.colors.ink3,
                )
            }
            FlatList(opened.second, state, viewModel, onTrackMenu)
        }
        return
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        contentPadding = PaddingValues(14.dp),
        horizontalArrangement = Arrangement.spacedBy(11.dp),
        verticalArrangement = Arrangement.spacedBy(11.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(groups, key = { it.first }) { (label, tracks) ->
            MoodTile(
                label = label,
                count = tracks.size,
                onOpen = { onOpen(label) },
                onPlay = { viewModel.playFrom(tracks.firstOrNull()) },
            )
        }
    }
}

/** One mood, sized so a dozen of them read as a palette rather than a list. */
@Composable
private fun MoodTile(
    label: String,
    count: Int,
    onOpen: () -> Unit,
    onPlay: () -> Unit,
) {
    NeuCard(Modifier.fillMaxWidth(), radius = 20.dp) {
        Column(
            Modifier
                .clickable(onClick = onOpen)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                label.replaceFirstChar { it.uppercase() },
                style = Oto.type.title,
                color = Oto.colors.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "$count ${if (count == 1) "song" else "songs"}",
                style = Oto.type.micro,
                color = Oto.colors.ink3,
            )
            VSpace(2.dp)
            Chip("PLAY", selected = false, onClick = onPlay)
        }
    }
}

@Composable
private fun GroupedList(
    groups: List<Pair<String, List<Track>>>,
    open: String?,
    onOpen: (String) -> Unit,
    state: PlayerViewModel.UiState,
    viewModel: PlayerViewModel,
    onTrackMenu: (Track) -> Unit,
) {
    if (groups.isEmpty()) {
        Column(Modifier.padding(26.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("NO LABELS YET", style = Oto.type.label, color = Oto.colors.ink3)
            Text(
                "Mood labels come from the Librarian, or from tagging tracks " +
                    "yourself under Now Playing.",
                style = Oto.type.body,
                color = Oto.colors.ink2,
            )
        }
        return
    }

    LazyColumn(Modifier.fillMaxSize()) {
        groups.forEach { (label, tracks) ->
            item(key = "h-$label") {
                GroupHeader(
                    label = label,
                    count = tracks.size,
                    expanded = open == label,
                    onClick = { onOpen(label) },
                    onPlay = { viewModel.playFrom(tracks.firstOrNull()) },
                )
            }
            if (open == label) {
                items(tracks, key = { "$label-${it.id}" }) { track ->
                    TrackRow(
                        track = track,
                        isCurrent = track.id.toString() == state.nowPlayingId,
                        artPath = viewModel.artPathFor(track),
                        moods = state.moodsByTrack[track.id].orEmpty(),
                        onClick = { viewModel.playFrom(track) },
                        onLongPress = { onTrackMenu(track) },
                    )
                }
            }
        }
        item { VSpace(14.dp) }
    }
}

@Composable
private fun GroupHeader(
    label: String,
    count: Int,
    expanded: Boolean,
    onClick: () -> Unit,
    onPlay: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 4.dp)
            .let { if (expanded) it.neu(Depth.Inset, RoundedCornerShape(14.dp)) else it }
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = Oto.type.label,
            color = if (expanded) Oto.colors.teal else Oto.colors.ink,
        )
        Text(
            "  $count",
            style = Oto.type.data,
            color = Oto.colors.ink3,
        )
        Box(Modifier.weight(1f))
        Text(
            "PLAY",
            style = Oto.type.micro,
            color = Oto.colors.teal,
            modifier = Modifier.clickable(onClick = onPlay).padding(6.dp),
        )
    }
}

@Composable
private fun FlatList(
    tracks: List<Track>,
    state: PlayerViewModel.UiState,
    viewModel: PlayerViewModel,
    onTrackMenu: (Track) -> Unit,
    selection: Set<Long>? = null,
    onToggleSelect: (Track) -> Unit = {},
) {
    if (tracks.isEmpty()) {
        Column(Modifier.padding(26.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("NO MATCHES", style = Oto.type.label, color = Oto.colors.ink3)
            Text(
                "Nothing here matches \"${state.searchQuery}\".",
                style = Oto.type.body, color = Oto.colors.ink2,
            )
        }
        return
    }

    LazyColumn(Modifier.fillMaxSize()) {
        item {
            // Play all and Shuffle, where every music app puts them. Getting a
            // list playing previously meant picking one song and hoping the
            // queue engine chose sensibly from there.
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "${tracks.size} SONGS",
                    style = Oto.type.label,
                    color = Oto.colors.ink3,
                    modifier = Modifier.weight(1f),
                )
                Chip("PLAY ALL", selected = false) { viewModel.playList(tracks, shuffle = false) }
                Chip("SHUFFLE", selected = true) { viewModel.playList(tracks, shuffle = true) }
            }
        }
        items(tracks, key = { it.id }) { track ->
            TrackRow(
                track = track,
                selected = selection?.contains(track.id),
                isCurrent = track.id.toString() == state.nowPlayingId,
                artPath = viewModel.artPathFor(track),
                moods = state.moodsByTrack[track.id].orEmpty(),
                onClick = {
                    if (selection != null) onToggleSelect(track) else viewModel.playFrom(track)
                },
                onLongPress = { onTrackMenu(track) },
            )
        }
        item { VSpace(14.dp) }
    }
}

/** Search only on the song name, since that is the only field we trust. */
fun List<Track>.matching(query: String): List<Track> {
    val needle = query.trim().lowercase()
    // Collapse the same song appearing on more than one source. Searching spans
    // drive, phone and server, so anything copied to the drive was listed twice
    // -- identical rows, differing only by which copy happened to have artwork.
    val seen = HashSet<String>()
    return filter { it.displayTitle.lowercase().contains(needle) }
        .filter {
            seen.add(
                it.displayTitle.lowercase().replace(Regex("[^a-z0-9]+"), " ").trim() +
                    "|" + (it.durationMs / 2000)
            )
        }
}

@Composable
fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        Modifier
            .pressable(interaction)
            .neu(if (selected) Depth.Inset else Depth.RaisedSoft, RoundedCornerShape(50))
            .clip(RoundedCornerShape(50))
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 15.dp, vertical = 9.dp),
    ) {
        Text(
            label,
            style = Oto.type.micro,
            color = if (selected) Oto.colors.teal else Oto.colors.ink3,
        )
    }
}

@Composable
private fun EmptyLibrary(libraryPresent: Boolean, onImport: () -> Unit, onScanPhone: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        NeuCard(Modifier.fillMaxWidth(), radius = 22.dp) {
            Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    if (libraryPresent) "NOTHING PLAYABLE" else "NO LIBRARY YET",
                    style = Oto.type.label,
                    color = Oto.colors.ink3,
                )
                Text(
                    if (libraryPresent) {
                        "The index opened but no audio is reachable. If the library " +
                            "lives on a drive, plug it back in."
                    } else {
                        "Import a library from a USB drive, or play what is already " +
                            "on this phone."
                    },
                    style = Oto.type.body,
                    color = Oto.colors.ink2,
                )
            }
        }
        VSpace(16.dp)
        NeuCard(radius = 50.dp, onClick = onImport) {
            Text(
                "IMPORT LIBRARY",
                style = Oto.type.label,
                color = Oto.colors.teal,
                modifier = Modifier.padding(horizontal = 30.dp, vertical = 15.dp),
            )
        }
        VSpace(10.dp)
        NeuCard(radius = 50.dp, depth = Depth.RaisedSoft, onClick = onScanPhone) {
            Text(
                "SCAN THIS PHONE",
                style = Oto.type.micro,
                color = Oto.colors.ink2,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
            )
        }
    }
}
