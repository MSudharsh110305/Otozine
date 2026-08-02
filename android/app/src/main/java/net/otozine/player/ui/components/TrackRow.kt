package net.otozine.player.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import net.otozine.player.library.Track
import net.otozine.player.ui.rememberArt
import net.otozine.player.ui.theme.Depth
import net.otozine.player.ui.theme.Oto
import net.otozine.player.ui.theme.neu

/**
 * Secondary line for a track.
 *
 * Deliberately the mood labels rather than artist and album.
 *
 * Artist and album are recovered from filenames and online lookup, and on a
 * library of YouTube rips they are wrong often enough to be worse than useless
 * -- a confidently incorrect artist is more misleading than no artist. Mood is
 * measured from the audio, so it is right regardless of how the file was named,
 * and it is what you would actually browse by here.
 *
 * Falls back to artist only when there are no labels at all.
 */
fun Track.subtitleLine(moods: List<String> = emptyList()): String {
    if (moods.isNotEmpty()) return moods.take(3).joinToString(" · ")
    return listOfNotNull(
        artist?.takeIf { it.isNotBlank() },
        album?.takeIf { it.isNotBlank() },
    ).joinToString("  ·  ")
}

/** Analysis values, shown in mono so the eye reads them as measured, not authored. */
fun Track.dataValues(): List<String> = buildList {
    add(formatDuration(durationMs))
    bpm?.let { add("${it.toInt()} BPM") }
    keyCamelot?.let { add(it) }
    if (replayGainDb != 0f) add(String.format("%+.1f dB", replayGainDb))
}

fun formatDuration(ms: Long): String {
    val total = ms / 1000
    return "%d:%02d".format(total / 60, total % 60)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TrackRow(
    track: Track,
    isCurrent: Boolean,
    artPath: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    moods: List<String> = emptyList(),
    onLongPress: (() -> Unit)? = null,
    /** Null outside selection mode; a tick box is only drawn when selecting. */
    selected: Boolean? = null,
) {
    val colors = Oto.colors
    val artPx = with(LocalDensity.current) { 46.dp.roundToPx() }
    val bitmap = rememberArt(artPath, artPx)

    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 5.dp)
            .let {
                // The playing row is raised out of the page; the rest sit flat
                // on it. Depth carries the state, so no accent bar is needed.
                if (isCurrent) it.neu(Depth.RaisedSoft, RoundedCornerShape(15.dp)) else it
            }
            .clip(RoundedCornerShape(15.dp))
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongPress,
            )
            .padding(horizontal = 10.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (selected != null) {
            // Sits where the eye already scans for state, and only exists while
            // selecting -- a permanent checkbox on every row would be clutter
            // paid for by every ordinary listen.
            Box(
                Modifier
                    .size(22.dp)
                    .neu(
                        if (selected) Depth.RaisedSoft else Depth.Inset,
                        RoundedCornerShape(7.dp),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                if (selected) {
                    Box(
                        Modifier
                            .size(11.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(colors.teal)
                    )
                }
            }
        }

        Box(Modifier.size(46.dp).neu(Depth.Inset, RoundedCornerShape(12.dp))) {
            ArtTile(
                artKey = track.contentHash,
                title = track.displayTitle,
                bitmap = bitmap,
                modifier = Modifier.size(46.dp).padding(2.dp),
                radius = 10.dp,
            )
        }

        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                track.displayTitle,
                style = Oto.type.item,
                color = if (isCurrent) colors.teal else colors.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val subtitle = track.subtitleLine(moods)
            if (subtitle.isNotBlank()) {
                Text(
                    subtitle,
                    style = Oto.type.sub,
                    // Moods read as a property of the sound, so they are tinted
                    // toward the accent; a fallback artist stays neutral.
                    color = if (moods.isNotEmpty()) colors.teal.copy(alpha = 0.75f)
                            else colors.ink2,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            DataStrip(track.dataValues())
        }
    }
}
