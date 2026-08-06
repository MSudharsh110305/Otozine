package net.otozine.player.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.ui.unit.dp
import net.otozine.player.PlayerViewModel
import net.otozine.player.Source
import net.otozine.player.library.DriveWatcher
import net.otozine.player.library.Track
import net.otozine.player.ui.components.Icon
import net.otozine.player.ui.components.OtoIcon
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import net.otozine.player.ui.components.ArtTile
import androidx.compose.ui.platform.LocalDensity
import net.otozine.player.ui.components.NeuCard
import net.otozine.player.ui.components.Segmented
import net.otozine.player.ui.components.SectionHeader
import net.otozine.player.ui.components.TrackRow
import net.otozine.player.ui.components.VSpace
import net.otozine.player.ui.theme.Depth
import net.otozine.player.ui.theme.Dimens
import net.otozine.player.ui.theme.Oto
import net.otozine.player.ui.theme.headerFill
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
        // Only offer the drive when its audio is actually reachable.
        if (state.reachableLibraryTracks.isNotEmpty()) add(Source.LIBRARY)
        if (state.deviceTracks.isNotEmpty()) add(Source.DEVICE)
        if (state.remoteTracks.isNotEmpty()) add(Source.ONLINE)
    }

    var chosen by remember { mutableStateOf<Source?>(null) }
    val source = chosen?.takeIf { it in available }
        ?: when {
            state.reachableLibraryTracks.isNotEmpty() -> Source.LIBRARY
            state.deviceTracks.isNotEmpty() -> Source.DEVICE
            state.remoteTracks.isNotEmpty() -> Source.ONLINE
            else -> Source.LIBRARY
        }

    var groupBy by remember { mutableStateOf(GroupBy.MOOD) }
    var openMood by remember { mutableStateOf<String?>(null) }
    var selecting by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(emptySet<Long>()) }

    val all = when (source) {
        Source.LIBRARY -> state.reachableLibraryTracks
        Source.DEVICE -> state.deviceTracks
        Source.ONLINE -> state.remoteTracks
    }
    val shown = remember(all, state.searchQuery) {
        if (state.searchQuery.isBlank()) all else all.matching(state.searchQuery)
    }

    Column(Modifier.fillMaxSize()) {
        SearchBar(state.searchQuery, viewModel::search, placeholder = "Song name…")

        val driveAttached = state.driveState == DriveWatcher.State.CONNECTED
        val canCopy = source == Source.DEVICE && driveAttached && shown.isNotEmpty()

        // One line, not three.
        //
        // Everything here is "what am I looking at" -- where the music is, and
        // how it is grouped -- so it belongs on one line. Stacked, it pushed the
        // list a third of the way down a screen whose entire job is the list.
        // Segments size to their labels rather than stretching, so the row stays
        // as small as the words in it.
        if (!selecting) Row(
            Modifier
                .fillMaxWidth()
                .background(Oto.colors.headerFill())
                .height(Dimens.control)
            .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (available.size > 1) {
                Segmented(
                    options = available.map { candidate ->
                        when (candidate) {
                            Source.LIBRARY -> Triple(candidate, "DRIVE", Icon.DRIVE)
                            Source.DEVICE -> Triple(candidate, "PHONE", Icon.PHONE)
                            Source.ONLINE -> Triple(candidate, "ONLINE", Icon.SPARK)
                        }
                    },
                    selected = source,
                    segmentWidth = 74.dp,
                ) {
                    chosen = it; openMood = null
                    selecting = false; selected = emptySet()
                }
            }

            Segmented(
                options = listOf(
                    Triple(GroupBy.MOOD, "", Icon.GRID),
                    Triple(GroupBy.ALL, "", Icon.LIST),
                ),
                selected = groupBy,
                segmentWidth = 40.dp,
            ) { groupBy = it; openMood = null }

            Box(Modifier.weight(1f))

            if (canCopy) {
                IconAction(Icon.COPY, "Copy songs to the drive") {
                    selecting = true; selected = emptySet()
                }
            }
        }

        // Selecting replaces the header rather than adding a row to it: while
        // choosing songs, changing source or grouping could only lose the
        // selection, so the controls that would do that go away.
        if (canCopy && selecting) {
            val allShown = shown.map { it.id }.toSet()
            val everything = selected.containsAll(allShown)
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(Oto.colors.headerFill())
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (selected.isEmpty()) "PICK SONGS" else "${selected.size} PICKED",
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
                Chip("COPY ${selected.size}", selected = selected.isNotEmpty()) {
                    if (selected.isNotEmpty()) {
                        viewModel.copyToDrive(shown.filter { it.id in selected })
                        selecting = false; selected = emptySet()
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

    LazyColumn(
        contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 8.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(groups, key = { it.first }) { (label, tracks) ->
            MoodTile(label = label, tracks = tracks, viewModel = viewModel) { onOpen(label) }
        }
    }
}

/**
 * One mood, as a row with the music in it.
 *
 * The grid this replaces gave each mood a card the height of a paperback
 * holding one word, a count and a button. Eight of them filled the screen and
 * said almost nothing, because a mood is not a thing you look at -- it is a set
 * of songs. So the row shows the songs: three covers fanned out, which turns an
 * abstract label into something recognisable before it is read.
 *
 * No play button. The row opens the mood, which is what you want nine times out
 * of ten, and the tenth is one more tap. A button on every row asks you to
 * decide before you have seen what is inside.
 */
@Composable
private fun MoodTile(
    label: String,
    tracks: List<Track>,
    viewModel: PlayerViewModel,
    onOpen: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    Row(
        Modifier
            .fillMaxWidth()
            .pressable(interaction)
            .neu(Depth.RaisedSoft, RoundedCornerShape(16.dp))
            .clip(RoundedCornerShape(16.dp))
            .clickable(interactionSource = interaction, indication = null, onClick = onOpen)
            .padding(start = 8.dp, end = 14.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Cover(tracks.firstOrNull(), viewModel)

        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                label.lowercase().replaceFirstChar { it.uppercase() },
                style = Oto.type.item,
                color = Oto.colors.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text("${tracks.size} songs", style = Oto.type.micro, color = Oto.colors.ink3)
        }
        OtoIcon(Icon.CHEVRON, tint = Oto.colors.ink3, size = 14.dp)
    }
}

/**
 * The face of a mood: one cover.
 *
 * Three fanned behind each other was the first attempt, and it only worked
 * where every track had artwork. Most of this library has none, so it drew
 * three generated letter tiles overlapping by nine pixels -- an unreadable
 * smear, worse than showing nothing at all. One cover stays legible whether it
 * is real artwork or a generated tile, which is the only version that survives
 * the library this actually has.
 */
@Composable
private fun Cover(track: Track?, viewModel: PlayerViewModel) {
    val artPx = with(LocalDensity.current) { 52.dp.roundToPx() }
    if (track == null) {
        Box(Modifier.size(46.dp))
        return
    }
    ArtTile(
        artKey = track.contentHash,
        title = track.displayTitle,
        bitmap = rememberArt(viewModel.artPathFor(track), artPx),
        modifier = Modifier.size(46.dp),
        radius = 12.dp,
    )
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
                IconAction(Icon.PLAY, "Play all") { viewModel.playList(tracks, shuffle = false) }
                IconAction(Icon.SHUFFLE, "Shuffle", accent = true) {
                    viewModel.playList(tracks, shuffle = true)
                }
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
        .filter { seen.add(it.dedupeKey) }
}

@Composable
fun IconAction(
    icon: Icon,
    contentDescription: String,
    accent: Boolean = false,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        Modifier
            .pressable(interaction)
            .size(40.dp)
            .neu(if (accent) Depth.Raised else Depth.RaisedSoft, RoundedCornerShape(50))
            .clip(RoundedCornerShape(50))
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        OtoIcon(icon, tint = if (accent) Oto.colors.teal else Oto.colors.ink2, size = 17.dp)
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
            .height(Dimens.control)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
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
