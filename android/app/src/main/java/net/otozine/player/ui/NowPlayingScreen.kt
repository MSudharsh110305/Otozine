package net.otozine.player.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import net.otozine.player.library.Track
import net.otozine.player.ui.components.ArtTile
import net.otozine.player.ui.components.Icon
import net.otozine.player.ui.components.OtoIcon
import net.otozine.player.ui.components.DataStrip
import net.otozine.player.ui.components.NeuPill
import net.otozine.player.ui.components.VSpace
import net.otozine.player.ui.components.dataValues
import net.otozine.player.ui.components.formatDuration
import net.otozine.player.ui.components.subtitleLine
import net.otozine.player.ui.theme.Depth
import net.otozine.player.ui.theme.Oto
import net.otozine.player.ui.theme.SceneSurface
import net.otozine.player.ui.theme.neu

@Composable
fun NowPlayingScreen(
    track: Track,
    isPlaying: Boolean,
    artPath: String?,
    positionMs: Long = 0L,
    outputLabel: String = "OUTPUT",
    reason: String? = null,
    seekOnDoubleTap: Boolean = false,
    onToggle: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onSeek: (Long) -> Unit = {},
    onWhy: () -> Unit = {},
    onQueue: () -> Unit = {},
    onTimer: () -> Unit = {},
    onMood: () -> Unit = {},
    onDismiss: () -> Unit,
) {
    val colors = Oto.colors
    val artPx = with(LocalDensity.current) { 300.dp.roundToPx() }

    // Opaque backing, drawn before anything else. Filling with `page` was enough
    // until scene themes made it transparent, at which point the screen behind
    // showed through this one and both were readable at once.
    Box(Modifier.fillMaxSize()) {
    SceneSurface(Modifier.fillMaxSize())
    Column(
        Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .padding(horizontal = 22.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Swipe-down to dismiss, scoped to the top region rather than the whole
        // screen. A full-screen vertical drag would fight the scrubber, and
        // grabbing near the handle is where the gesture is expected anyway.
        val dismissGesture = Modifier.pointerInput(Unit) {
            var travelled = 0f
            detectVerticalDragGestures(
                onDragStart = { travelled = 0f },
                onDragEnd = { if (travelled > 120f) onDismiss() },
                onDragCancel = { travelled = 0f },
            ) { _, delta -> travelled += delta }
        }

        Box(
            Modifier
                .fillMaxWidth()
                .then(dismissGesture)
                .padding(vertical = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            // Grab handle -- the affordance for "this slides away".
            Box(
                Modifier
                    .width(44.dp)
                    .height(5.dp)
                    .neu(Depth.Inset, RoundedCornerShape(50))
                    .clickable(onClick = onDismiss)
            )
        }

        Row(
            Modifier.fillMaxWidth().then(dismissGesture),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("PLAYING FROM", style = Oto.type.label, color = colors.ink3)
            Box(Modifier.weight(1f))
            NeuPill(text = outputLabel, dot = colors.teal)
        }

        VSpace(6.dp)
        Text(
            "ANTI-REPEAT QUEUE",
            style = Oto.type.label,
            color = colors.teal,
            modifier = Modifier.fillMaxWidth().clickable(onClick = onQueue),
        )

        VSpace(18.dp)

        // Art sits in a recessed frame, so it reads as inlaid rather than
        // stuck on top -- the single most characteristic neumorphic move.
        //
        // It takes the leftover height rather than a fixed square of the full
        // width. As a fixed square it was the largest item in a column of
        // otherwise fixed heights, so anything that added height below it
        // pushed the footer off the screen -- and the provenance note does
        // exactly that, on every track with no artist tag. The visible symptom
        // was the QUEUE/WHY/MOOD/TIMER pills losing their labels on some songs
        // and not others. Letting the art give up space keeps the controls
        // reachable whatever else is on screen.
        Box(
            Modifier
                .fillMaxWidth()
                .weight(4f, fill = false)
                .aspectRatio(1f, matchHeightConstraintsFirst = true)
                .neu(Depth.InsetDeep, RoundedCornerShape(28.dp))
                .then(dismissGesture)
                .padding(10.dp)
        ) {
            ArtTile(
                artKey = track.contentHash,
                title = track.displayTitle,
                bitmap = rememberArt(artPath, artPx),
                modifier = Modifier.fillMaxSize(),
                radius = 20.dp,
            )
        }

        VSpace(20.dp)

        Text(
            track.displayTitle,
            style = Oto.type.display,
            color = colors.ink,
            maxLines = 2,
            textAlign = TextAlign.Center,
            overflow = TextOverflow.Ellipsis,
        )
        VSpace(4.dp)
        Text(
            track.subtitleLine(),
            style = Oto.type.sub,
            color = colors.ink2,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        VSpace(8.dp)
        DataStrip(track.dataValues())

        // The "why" chip: one line, always tappable. Trust feature and label
        // collection surface in one.
        if (reason != null) {
            VSpace(12.dp)
            Row(
                Modifier
                    .neu(Depth.Inset, RoundedCornerShape(50))
                    .clip(RoundedCornerShape(50))
                    .clickable(onClick = onWhy)
                    .padding(horizontal = 16.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(reason.uppercase(), style = Oto.type.micro, color = colors.ink2)
                OtoIcon(Icon.CHEVRON, tint = colors.teal, size = 12.dp)
            }
        }

        VSpace(18.dp)
        Scrubber(
            positionMs = positionMs,
            durationMs = track.durationMs,
            seekOnDoubleTap = seekOnDoubleTap,
            onSeek = onSeek,
        )

        VSpace(18.dp)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            RoundButton(Icon.PREVIOUS, onPrevious, size = 52.dp)
            Box(
                Modifier
                    .size(72.dp)
                    .neu(Depth.RaisedHigh, CircleShape)
                    .clip(CircleShape)
                    .clickable(onClick = onToggle),
                contentAlignment = Alignment.Center,
            ) {
                OtoIcon(
                    if (isPlaying) Icon.PAUSE else Icon.PLAY,
                    tint = colors.teal,
                    size = 26.dp,
                )
            }
            RoundButton(Icon.NEXT, onNext, size = 52.dp)
        }

        Box(Modifier.weight(1f))

        // Honest about provenance: this library is mostly untagged rips, and
        // saying so is better than presenting inferred values as facts.
        if (track.artist.isNullOrBlank() && track.composer.isNullOrBlank()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .neu(Depth.Inset, RoundedCornerShape(14.dp))
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "No tags on this file — everything above is inferred from audio.",
                    style = Oto.type.body,
                    color = Oto.colors.ink2,
                    modifier = Modifier.weight(1f),
                )
            }
            VSpace(10.dp)
        }

        Row(
            Modifier.fillMaxWidth().padding(bottom = 16.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            FooterAction("QUEUE", onQueue)
            FooterAction("WHY", onWhy)
            FooterAction("MOOD", onMood)
            FooterAction("TIMER", onTimer)
        }
    }
    }
}

@Composable
private fun FooterAction(label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .neu(Depth.RaisedSoft, RoundedCornerShape(50))
            .clip(RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = 15.dp, vertical = 11.dp),
    ) {
        Text(label, style = Oto.type.micro, color = Oto.colors.ink2)
    }
}

/**
 * Progress bar you can actually move.
 *
 * Three ways in, because they suit different moments:
 *
 *  - **Drag** is the primary one and the reason this is not just a tap target.
 *    Scrubbing is a continuous gesture; tapping to seek is a guess.
 *  - **Tap** jumps straight to a point, which is faster when you know where you
 *    want to be.
 *  - **Double tap** is the same thing behind a deliberate gesture, for people
 *    who keep nudging it by accident. Selectable in More > Playback.
 *
 * While dragging, the bar follows the finger rather than the player, so it does
 * not fight the position updates arriving every 500 ms.
 */
@Composable
private fun Scrubber(
    positionMs: Long,
    durationMs: Long,
    seekOnDoubleTap: Boolean = false,
    onSeek: (Long) -> Unit = {},
) {
    val colors = Oto.colors
    var dragFraction by remember { mutableStateOf<Float?>(null) }

    val playedFraction =
        if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    val fraction = dragFraction ?: playedFraction
    val shownMs = if (dragFraction != null) (fraction * durationMs).toLong() else positionMs

    Column(Modifier.fillMaxWidth()) {
        Box(
            Modifier
                .fillMaxWidth()
                // A 10dp bar is far too thin to hit reliably; the touch target
                // is padded out to a comfortable size while the bar stays slim.
                .height(28.dp)
                .pointerInput(durationMs, seekOnDoubleTap) {
                    detectTapGestures(
                        onTap = {
                            if (!seekOnDoubleTap && durationMs > 0) {
                                onSeek(((it.x / size.width) * durationMs).toLong())
                            }
                        },
                        onDoubleTap = {
                            if (seekOnDoubleTap && durationMs > 0) {
                                onSeek(((it.x / size.width) * durationMs).toLong())
                            }
                        },
                    )
                }
                .pointerInput(durationMs) {
                    detectHorizontalDragGestures(
                        onDragStart = { offset ->
                            dragFraction = (offset.x / size.width).coerceIn(0f, 1f)
                        },
                        onDragEnd = {
                            dragFraction?.let { f ->
                                if (durationMs > 0) onSeek((f * durationMs).toLong())
                            }
                            dragFraction = null
                        },
                        onDragCancel = { dragFraction = null },
                    ) { change, _ ->
                        dragFraction = (change.position.x / size.width).coerceIn(0f, 1f)
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(10.dp)
                    .neu(Depth.Inset, RoundedCornerShape(50)),
                contentAlignment = Alignment.CenterStart,
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(fraction)
                        .height(10.dp)
                        .clip(RoundedCornerShape(50))
                        .background(colors.teal.copy(alpha = 0.85f))
                )
            }
            // Handle appears only while dragging, so the bar stays clean at rest.
            //
            // Positioned by offset rather than by aligning a fractional-width
            // box to its end: that put the handle's *right edge* at the play
            // position, so the knob sat a half-width behind the time readout
            // and the two visibly disagreed while dragging.
            if (dragFraction != null) {
                val handle = 20.dp
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    val travel = maxWidth - handle
                    Box(
                        Modifier
                            .offset(x = travel * fraction)
                            .align(Alignment.CenterStart)
                            .size(handle)
                            .neu(Depth.Raised, CircleShape)
                    )
                }
            }
        }
        VSpace(7.dp)
        Row(Modifier.fillMaxWidth()) {
            Text(
                formatDuration(shownMs),
                style = Oto.type.data,
                color = if (dragFraction != null) colors.teal else colors.ink3,
            )
            Box(Modifier.weight(1f))
            Text(formatDuration(durationMs), style = Oto.type.data, color = colors.ink3)
        }
    }
}
