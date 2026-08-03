package net.otozine.player.ui.components

import androidx.compose.animation.core.withInfiniteAnimationFrameNanos
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import net.otozine.player.ui.theme.Oto
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

/**
 * A sphere that pulses with the beat.
 *
 * Driven by the track's measured tempo and the playback position, not by the
 * audio itself. Reading the real signal needs either the microphone permission
 * or a tap in the playback pipeline; the tap was tried and it stopped playback
 * dead, and a decoration is not worth a player that will not play.
 *
 * So this is honest about what it is: a metronome you can see. It pulses on the
 * beat of whatever is playing, and it does not know loud from quiet.
 */
@Composable
fun ReactiveSphere(
    isPlaying: Boolean,
    bpm: Float?,
    modifier: Modifier = Modifier,
) {
    val colors = Oto.colors
    val bandCount = 24
    val bands = remember { FloatArray(bandCount) }

    val frame by produceState(0f, isPlaying) {
        if (!isPlaying) { value = 0f; return@produceState }
        val start = withInfiniteAnimationFrameNanos { it }
        while (true) {
            withInfiniteAnimationFrameNanos { now -> value = (now - start) / 1_000_000_000f }
        }
    }

    Canvas(modifier) {
        // Beat envelope: struck on the beat, decaying after it. Each band is
        // offset a little so the shape ripples rather than breathing as one
        // circle.
        val tempo = (bpm ?: 100f).coerceIn(60f, 190f)
        val beat = if (isPlaying) ((frame * tempo / 60f) % 1f) else 1f
        for (b in bands.indices) {
            val offset = (beat + b * 0.035f) % 1f
            bands[b] = if (isPlaying) exp(-offset * 4.2f) * (0.55f + 0.45f * sin(b * 1.3f)) else 0f
        }
        val centre = Offset(size.width / 2f, size.height / 2f)
        val base = size.minDimension * 0.28f
        val reach = size.minDimension * 0.20f

        // Overall level drives a slow pulse of the whole body, so quiet
        // passages sit small and a chorus fills the space.
        var mean = 0f
        for (b in bands) mean += b
        mean /= bands.size.coerceAtLeast(1)

        val points = 72
        val path = Path()
        for (i in 0..points) {
            val t = i.toFloat() / points
            val angle = t * 2f * PI.toFloat() - PI.toFloat() / 2f

            // Mirror the spectrum across the vertical axis: the same band
            // appears left and right, so the shape reads as one object.
            val mirrored = if (t <= 0.5f) t * 2f else (1f - t) * 2f
            val band = (mirrored * (bandCount - 1)).toInt().coerceIn(0, bandCount - 1)
            val level = bands[band]

            // A little drift so it is never perfectly still, even in silence.
            val drift = 0.03f * sin(frame * 1.1f + i * 0.21f)
            val r = base * (1f + mean * 0.35f) + reach * level + base * drift

            val x = centre.x + cos(angle) * r
            val y = centre.y + sin(angle) * r
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()

        drawPath(
            path,
            brush = Brush.radialGradient(
                listOf(
                    colors.teal.copy(alpha = 0.42f + mean * 0.35f),
                    colors.teal.copy(alpha = 0.10f),
                ),
                center = centre,
                radius = base + reach,
            ),
        )
        drawPath(path, color = colors.teal.copy(alpha = 0.55f), style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.6f))
    }
}
