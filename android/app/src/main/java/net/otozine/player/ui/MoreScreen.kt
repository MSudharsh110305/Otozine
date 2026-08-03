package net.otozine.player.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.remember
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import net.otozine.player.library.isAnalysed
import net.otozine.player.ui.theme.OtoPalette
import net.otozine.player.ui.theme.pressable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.graphics.Color
import net.otozine.player.ui.theme.InkColors
import net.otozine.player.ui.theme.PaperColors
import net.otozine.player.ui.theme.Scene
import androidx.compose.ui.unit.dp
import net.otozine.player.PlayerViewModel
import net.otozine.player.Prefs
import net.otozine.player.StorageMode
import net.otozine.player.ThemeMode
import net.otozine.player.ui.components.Icon
import net.otozine.player.ui.components.NeuCard
import net.otozine.player.ui.components.OtoIcon
import net.otozine.player.ui.components.SectionHeader
import net.otozine.player.ui.components.VSpace
import net.otozine.player.ui.theme.Depth
import net.otozine.player.ui.theme.Oto
import net.otozine.player.ui.theme.neu

/**
 * Everything that is not listening: appearance, sources, tools, diagnostics.
 *
 * The sound map lives here rather than in the tab bar. It is a thing you go and
 * look at occasionally, not a place you navigate to while choosing music, and
 * giving it a quarter of the bottom bar overstated how often it gets used.
 */
@Composable
fun MoreScreen(
    state: PlayerViewModel.UiState,
    prefs: Prefs,
    viewModel: PlayerViewModel,
    onOpenMap: () -> Unit,
    onOpenStorage: () -> Unit,
    onOpenServer: () -> Unit,
    onImport: () -> Unit,
    onRequestAudio: () -> Unit = {},
    onAnalyse: () -> Unit = {},
) {
    Column(Modifier.verticalScroll(rememberScrollState())) {

        SectionHeader("Appearance")
        Group {
            Column(Modifier.padding(vertical = 12.dp)) {
                VSpace(10.dp)
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(OtoPalette.entries.toList(), key = { it.name }) { palette ->
                        PaletteSwatch(
                            palette = palette,
                            selected = prefs.palette == palette.name,
                        ) { viewModel.setPalette(palette.name) }
                    }
                }
            }
        }

        SectionHeader("About")
        Group {
            Column(Modifier.padding(16.dp)) {
                val context = LocalContext.current
                val installed = remember {
                    runCatching {
                        val info = context.packageManager.getPackageInfo(context.packageName, 0)
                        java.text.SimpleDateFormat("d MMM yyyy, HH:mm", java.util.Locale.getDefault())
                            .format(java.util.Date(info.lastUpdateTime))
                    }.getOrDefault("unknown")
                }
                Text("OtoZine", style = Oto.type.item, color = Oto.colors.ink)
                Text(
                    "Installed $installed",
                    style = Oto.type.sub,
                    color = Oto.colors.ink2,
                )
                }
        }

        SectionHeader("Playback")
        Group {
            Column {
                Row(
                    Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f).padding(end = 12.dp)) {
                        Text("Seek by", style = Oto.type.item, color = Oto.colors.ink)
                        Text(
                            "Dragging the bar always works. This is for jumping " +
                                "straight to a point.",
                            style = Oto.type.sub,
                            color = Oto.colors.ink2,
                        )
                    }
                }
                Row(
                    Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(9.dp),
                ) {
                    ThemeOption(
                        label = "SINGLE TAP",
                        selected = !prefs.seekOnDoubleTap,
                        modifier = Modifier.weight(1f),
                    ) { viewModel.setSeekOnDoubleTap(false) }
                    ThemeOption(
                        label = "DOUBLE TAP",
                        selected = prefs.seekOnDoubleTap,
                        modifier = Modifier.weight(1f),
                    ) { viewModel.setSeekOnDoubleTap(true) }
                }
            }
        }

        SectionHeader("Tools")
        Group {
            Column {
                NavRow("Sound map", "${state.tracks.size} tracks by brightness and intensity", onOpenMap)
                NavRow("Library & sync", "${state.historyEvents} plays recorded here", onOpenStorage)
                NavRow("Import library", "From a USB drive or internal storage", onImport)
                run {
                    val unmeasured = (state.libraryTracks + state.deviceTracks)
                        .count { it.bpm == null && it.opusPath != null }
                    NavRow(
                        title = "Analyse on this phone",
                        subtitle = if (unmeasured == 0) {
                            "Everything is measured. Loudness, tempo, key and mood."
                        } else {
                            "$unmeasured tracks not measured yet — no volume " +
                                "levelling and no mood until they are."
                        },
                        onClick = onAnalyse,
                    )
                }
            }
        }

        SectionHeader("Storage")
        Group {
            Column {
                Row(
                    Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Imported libraries", style = Oto.type.item, color = Oto.colors.ink)
                        Text(
                            when (prefs.storageMode) {
                                StorageMode.LINK ->
                                    "Played straight off the drive. Uses no phone " +
                                        "storage, but the drive must be plugged in."
                                StorageMode.COPY ->
                                    "Copied onto the phone. Plays with the drive " +
                                        "unplugged, but uses phone storage."
                            },
                            style = Oto.type.sub,
                            color = Oto.colors.ink2,
                        )
                    }
                }
                Row(
                    Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(9.dp),
                ) {
                    ThemeOption(
                        label = "PLAY FROM DRIVE",
                        selected = prefs.storageMode == StorageMode.LINK,
                        modifier = Modifier.weight(1f),
                    ) { viewModel.setStorageMode(StorageMode.LINK) }
                    ThemeOption(
                        label = "COPY TO PHONE",
                        selected = prefs.storageMode == StorageMode.COPY,
                        modifier = Modifier.weight(1f),
                    ) { viewModel.setStorageMode(StorageMode.COPY) }
                }
                Text(
                    "Moves the audio now, and applies to future imports.",
                    style = Oto.type.micro,
                    color = Oto.colors.ink3,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp),
                )
            }
        }

        SectionHeader("Sources")
        Group {
            Column {
                ToggleRow(
                    title = "Audio on this phone",
                    subtitle = if (prefs.includeDeviceAudio) {
                        "${state.deviceTracks.size} files found. Not analysed, so they " +
                            "play at their own volume and stay out of smart queues."
                    } else {
                        "Play music already on the device, outside the curated library"
                    },
                    checked = prefs.includeDeviceAudio,
                    onToggle = { enabled ->
                        // Turning this on without the permission would scan
                        // and quietly find nothing, which looks like a bug.
                        if (enabled) onRequestAudio()
                        viewModel.setIncludeDeviceAudio(enabled)
                    },
                )
                NavRow(
                    title = "Streaming server",
                    subtitle = if (prefs.serverConfigured) {
                        "${prefs.serverUrl}  ·  ${state.serverStatus ?: "not checked"}"
                    } else {
                        "Not set up. Connect a Navidrome server to stream everything."
                    },
                    onClick = onOpenServer,
                )
            }
        }

        SectionHeader("Diagnostics")
        Group {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                // The same figures the Play screen shows, from the same
                // fields. Two screens computing "how many songs" differently is
                // how you end up with two wrong answers.
                Stat("Songs on the drive", "${state.libraryTracks.size}")
                Stat("Songs on this phone", "${state.deviceTracks.size}")
                Stat("Distinct songs", "${state.playableCount}")
                Stat("Measured", "${state.tracks.count { it.isAnalysed }}")
                Stat("Plays recorded", "${state.historyEvents}")
                Stat("Queued now", "${state.queue.size}")
                Stat("Waiting for the drive", "${state.pendingSync}")
            }
        }

        VSpace(10.dp)
        Text(
            "OtoZine 0.1.0 · everything on this screen stays on the device",
            style = Oto.type.micro,
            color = Oto.colors.ink3,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        )
        VSpace(16.dp)
    }
}

@Composable
private fun Group(content: @Composable () -> Unit) {
    NeuCard(Modifier.fillMaxWidth().padding(horizontal = 14.dp), radius = 20.dp) { content() }
}

/**
 * A theme shown in its own colours rather than described in words.
 *
 * The swatch paints the page, a raised surface and the accent using that
 * palette's values, so the choice is made by looking rather than by reading a
 * name and guessing. Names like "Dark Neo" mean nothing until you have seen it
 * once.
 */
@Composable
private fun PaletteSwatch(
    palette: OtoPalette?,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    // Auto shows the palette it would currently resolve to, so the swatch is
    // never a blank square that explains nothing.
    val c = palette?.colors
        ?: if (isSystemInDarkTheme()) InkColors else PaperColors
    val label = palette?.label ?: "Auto"
    val blurb = palette?.blurb ?: "Follows the system"

    Column(
        Modifier
            .width(96.dp)
            .pressable(interaction)
            .clip(RoundedCornerShape(16.dp))
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(62.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(
                    // Scene themes leave the page transparent, so preview the
                    // colour their backdrop actually paints.
                    when (palette?.scene) {
                        Scene.PETALS -> Color(0xFFF8E9EE)
                        Scene.GLASS_LIGHT -> Color(0xFFF2F0FF)
                        else -> c.page
                    }
                )
                .border(
                    width = if (selected) 2.dp else 1.dp,
                    color = if (selected) Oto.colors.teal else Oto.colors.line,
                    shape = RoundedCornerShape(14.dp),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(26.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(c.surface)
                )
                Box(
                    Modifier
                        .size(14.dp)
                        .clip(RoundedCornerShape(50))
                        .background(c.teal)
                )
                Box(
                    Modifier
                        .size(10.dp)
                        .clip(RoundedCornerShape(50))
                        .background(c.sky)
                )
            }
        }
        VSpace(6.dp)
        Text(
            label,
            style = Oto.type.micro,
            color = if (selected) Oto.colors.teal else Oto.colors.ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            blurb,
            style = Oto.type.micro,
            color = Oto.colors.ink3,
            maxLines = 2,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun ThemeOption(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier
            .neu(if (selected) Depth.Inset else Depth.RaisedSoft, RoundedCornerShape(14.dp))
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp),
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
private fun NavRow(title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = Oto.type.item, color = Oto.colors.ink)
            Text(subtitle, style = Oto.type.sub, color = Oto.colors.ink2)
        }
        OtoIcon(Icon.CHEVRON, tint = Oto.colors.ink3, size = 16.dp)
    }
}

@Composable
private fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable { onToggle(!checked) }.padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = Oto.type.item, color = Oto.colors.ink)
            Text(subtitle, style = Oto.type.sub, color = Oto.colors.ink2)
        }
        // Neumorphic switch: the track is a groove, the knob sits proud of it.
        Box(
            Modifier
                .size(width = 48.dp, height = 28.dp)
                .neu(Depth.Inset, RoundedCornerShape(50)),
            contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart,
        ) {
            Box(
                Modifier
                    .padding(3.dp)
                    .size(22.dp)
                    .neu(
                        Depth.RaisedSoft,
                        CircleShape,
                        color = if (checked) Oto.colors.teal else null,
                    )
            )
        }
    }
}

@Composable
private fun Stat(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = Oto.type.body, color = Oto.colors.ink2)
        Box(Modifier.weight(1f))
        Text(value, style = Oto.type.data, color = Oto.colors.ink)
    }
}
