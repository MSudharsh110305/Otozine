package net.otozine.player.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.otozine.player.PlayerViewModel
import net.otozine.player.library.Track
import net.otozine.player.ui.components.ArtTile
import net.otozine.player.ui.components.ReactiveSphere
import net.otozine.player.ui.components.Icon
import net.otozine.player.ui.components.OtoIcon
import net.otozine.player.ui.components.HSpace
import net.otozine.player.ui.components.NeuPill
import net.otozine.player.ui.components.subtitleLine
import net.otozine.player.ui.theme.Depth
import net.otozine.player.ui.theme.Oto
import net.otozine.player.ui.theme.SceneSurface
import net.otozine.player.ui.theme.neu

/**
 * Three destinations, not four.
 *
 * Search moved into Play and Library because it is an operation on a list
 * rather than a place. The sound map moved into More for the same reason in
 * reverse -- it is somewhere you visit occasionally, and it was taking a
 * quarter of the bottom bar to do it.
 */
enum class Tab(val label: String, val icon: Icon) {
    PLAY("PLAY", Icon.PLAY),
    LIBRARY("LIBRARY", Icon.LIBRARY),
    MORE("MORE", Icon.MORE),
}

@Composable
fun Shell(viewModel: PlayerViewModel, requestAudio: () -> Unit = {}) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val prefs by viewModel.prefs.collectAsStateWithLifecycle()

    var tab by remember { mutableStateOf(Tab.PLAY) }
    var nowPlayingOpen by remember { mutableStateOf(false) }
    var sheet by remember { mutableStateOf<SheetKind?>(null) }
    var mapOpen by remember { mutableStateOf(false) }
    var trackMenu by remember { mutableStateOf<net.otozine.player.library.Track?>(null) }

    val colors = Oto.colors
    val current = state.nowPlaying
    val pickLibrary = rememberLibraryPicker(viewModel::importFrom)

    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                viewModel.refreshDriveState()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Back / swipe-back used to fall straight through to the system and close
    // the app, even with a sheet or Now Playing open. One handler with explicit
    // priority: dismiss whatever is on top, then fall back to the Play tab, and
    // only leave from there.
    BackHandler(enabled = sheet != null || mapOpen || nowPlayingOpen || tab != Tab.PLAY) {
        when {
            sheet != null -> sheet = null
            mapOpen -> mapOpen = false
            nowPlayingOpen -> nowPlayingOpen = false
            else -> tab = Tab.PLAY
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(colors.page)
            .systemBarsPadding()
    ) {
        Column(Modifier.fillMaxSize()) {
            Header(
                trackCount = state.playableCount,
                onStorage = { sheet = SheetKind.STORAGE },
            )

            if (state.driveState == net.otozine.player.library.DriveWatcher.State.DISCONNECTED) {
                DriveBanner(onReconnect = pickLibrary)
            }

            Box(Modifier.weight(1f)) {
                when (tab) {
                    Tab.PLAY -> PlayScreen(state, viewModel) { sheet = SheetKind.VIBE }
                    Tab.LIBRARY -> LibraryScreen(
                        state, viewModel, pickLibrary, requestAudio,
                        onTrackMenu = { trackMenu = it },
                    )
                    Tab.MORE -> MoreScreen(
                        state = state,
                        prefs = prefs,
                        viewModel = viewModel,
                        onOpenMap = { mapOpen = true },
                        onOpenStorage = { sheet = SheetKind.STORAGE },
                        onOpenServer = { sheet = SheetKind.SERVER },
                        onImport = pickLibrary,
                        onRequestAudio = requestAudio,
                        onAnalyse = viewModel::analyseLibrary,
                    )
                }
            }

            // Above the mini player and the tabs: the running-work strip sits
            // where the eye already goes for "something is happening", and the
            // two can coexist because copying does not stop playback.
            state.transferring
                ?.takeIf { state.transferMinimised && !it.finished }
                ?.let { progress ->
                    TransferStrip(progress) { viewModel.minimiseTransfer(false) }
                }

            AnimatedVisibility(
                visible = current != null,
                enter = slideInVertically { it },
                exit = slideOutVertically { it },
            ) {
                if (current != null) {
                    MiniPlayer(
                        track = current,
                        isPlaying = state.isPlaying,
                        artPath = viewModel.artPathFor(current),
                        onToggle = viewModel::togglePlayPause,
                        onNext = { viewModel.next() },
                        onExpand = { nowPlayingOpen = true },
                    )
                }
            }

            BottomNav(tab) { tab = it }
        }

        if (nowPlayingOpen && current != null) {
            NowPlayingScreen(
                track = current,
                isPlaying = state.isPlaying,
                artPath = viewModel.artPathFor(current),
                positionMs = state.positionMs,
                outputLabel = state.output,
                reason = state.currentReason?.headline,
                seekOnDoubleTap = prefs.seekOnDoubleTap,
                queueLabel = viewModel.queueSourceLabel(),
                onToggle = viewModel::togglePlayPause,
                onNext = { viewModel.next() },
                onPrevious = { viewModel.previous() },
                onSeek = viewModel::seekTo,
                onWhy = { sheet = SheetKind.WHY },
                onQueue = { sheet = SheetKind.QUEUE },
                onTimer = { sheet = SheetKind.TIMER },
                onMood = { sheet = SheetKind.MOOD },
                onDismiss = { nowPlayingOpen = false },
            )
        }

        if (mapOpen) {
            Box(Modifier.fillMaxSize()) {
                SceneSurface(Modifier.fillMaxSize())
                Box(Modifier.fillMaxSize().systemBarsPadding()) {
                Column {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Sound map", style = Oto.type.title, color = colors.ink)
                        Box(Modifier.weight(1f))
                        OtoIcon(
                            Icon.CLOSE,
                            tint = colors.teal,
                            size = 18.dp,
                            modifier = Modifier.clickable { mapOpen = false }.padding(6.dp),
                        )
                    }
                    MapScreen(state, viewModel)
                }
                }
            }
        }

        when (sheet) {
            SheetKind.QUEUE -> QueueSheet(state, viewModel) { sheet = null }
            SheetKind.WHY -> WhySheet(state, viewModel) { sheet = null }
            SheetKind.VIBE -> VibeSheet(state, viewModel) { sheet = null }
            SheetKind.TIMER -> TimerSheet(state, viewModel) { sheet = null }
            SheetKind.STORAGE -> StorageSheet(
                state = state,
                onImport = { sheet = null; pickLibrary() },
                onDismiss = { sheet = null },
            )
            SheetKind.SERVER -> ServerSheet(prefs, state, viewModel) { sheet = null }
            SheetKind.MOOD -> MoodSheet(state, viewModel) { sheet = null }
            null -> Unit
        }

        trackMenu?.let { track ->
            TrackMenuSheet(track, state, viewModel) { trackMenu = null }
        }

        state.importing?.let { progress ->
            ImportOverlay(progress, onDismiss = viewModel::dismissImport)
        }

        state.transferring?.takeIf { !state.transferMinimised || it.finished }?.let { progress ->
            TransferOverlay(
                progress,
                onMinimise = { viewModel.minimiseTransfer(true) },
                onCancel = viewModel::cancelTransfer,
            )
        }

        state.analysing?.let { progress ->
            AnalysisOverlay(progress, onCancel = viewModel::cancelAnalysis)
        }

        state.busyMessage?.let { message ->
            BusyToast(message, onDismiss = viewModel::dismissBusy)
        }
    }
}

enum class SheetKind { QUEUE, WHY, VIBE, TIMER, STORAGE, SERVER, MOOD }

@Composable
private fun Header(trackCount: Int, onStorage: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 18.dp, end = 14.dp, top = 6.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("otozine", style = Oto.type.display, color = Oto.colors.ink)
        HSpace(4.dp)
        // The one kanji the subset font retains -- "oto", sound.
        Text("音", style = Oto.type.micro, color = Oto.colors.teal)
        Box(Modifier.weight(1f))
        NeuPill(
            text = "${"%,d".format(trackCount)} TRACKS",
            dot = Oto.colors.teal,
            onClick = onStorage,
        )
    }
}

@Composable
private fun BottomNav(selected: Tab, onSelect: (Tab) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 8.dp)
            .neu(Depth.Raised, RoundedCornerShape(22.dp))
            .padding(vertical = 9.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        Tab.entries.forEach { entry ->
            val active = entry == selected
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .clickable { onSelect(entry) }
                    .let {
                        // Active tab is pressed *into* the bar rather than
                        // highlighted -- depth is the state signal here.
                        if (active) it.neu(Depth.Inset, RoundedCornerShape(14.dp)) else it
                    }
                    .padding(horizontal = 22.dp, vertical = 6.dp),
            ) {
                OtoIcon(
                    entry.icon,
                    tint = if (active) Oto.colors.teal else Oto.colors.ink3,
                    size = 19.dp,
                )
                Text(
                    entry.label,
                    style = Oto.type.micro,
                    color = if (active) Oto.colors.teal else Oto.colors.ink3,
                )
            }
        }
    }
}

@Composable
private fun MiniPlayer(
    track: Track,
    isPlaying: Boolean,
    artPath: String?,
    onToggle: () -> Unit,
    onNext: () -> Unit,
    onExpand: () -> Unit,
) {
    val artPx = with(LocalDensity.current) { 40.dp.roundToPx() }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp)
            .neu(Depth.Raised, RoundedCornerShape(18.dp))
            .clip(RoundedCornerShape(18.dp))
            .clickable(onClick = onExpand)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        // Cover with the sphere behind it: the artwork stays the anchor and the
        // spectrum reacts around it, rather than a separate widget competing
        // for a bar that is only 40dp tall.
        Box(Modifier.size(46.dp), contentAlignment = Alignment.Center) {
            ReactiveSphere(isPlaying = isPlaying, bpm = track.bpm, modifier = Modifier.size(46.dp))
            ArtTile(
                artKey = track.contentHash,
                title = track.displayTitle,
                bitmap = rememberArt(artPath, artPx),
                modifier = Modifier.size(34.dp),
                radius = 9.dp,
            )
        }
        Column(Modifier.weight(1f)) {
            Text(
                track.displayTitle,
                style = Oto.type.item,
                color = Oto.colors.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                track.subtitleLine(),
                style = Oto.type.sub,
                color = Oto.colors.ink2,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        RoundButton(if (isPlaying) Icon.PAUSE else Icon.PLAY, onToggle, size = 40.dp, accent = true)
        RoundButton(Icon.NEXT, onNext, size = 36.dp)
    }
}

@Composable
fun RoundButton(
    icon: Icon,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: androidx.compose.ui.unit.Dp = 44.dp,
    accent: Boolean = false,
) {
    Box(
        modifier
            .size(size)
            .neu(Depth.Raised, CircleShape)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        OtoIcon(
            icon,
            tint = if (accent) Oto.colors.teal else Oto.colors.ink2,
            size = size * 0.42f,
        )
    }
}
