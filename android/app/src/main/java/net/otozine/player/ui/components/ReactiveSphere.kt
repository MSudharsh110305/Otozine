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
import net.otozine.player.playback.Spectrum
import net.otozine.player.ui.theme.Oto
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * A sphere that breathes with the music.
 *
 * The radius at each angle comes from a real frequency band, measured from the
 * PCM on its way to the speaker -- bass at the top, rising through the spectrum
 * around the circle and mirrored across the vertical so the shape stays
 * symmetrical rather than lopsided.
 *
 * The earlier version derived movement from the track's tempo. It was honest
 * about being a metronome, but a metronome is not what "reactive" means: it
 * moved identically through a silence and a chorus. This one is the signal.
 *
 * Reads a shared array once per frame -- no allocation in the draw loop, and no
 * work at all when nothing is playing.
 */
@Composable
fun ReactiveSphere(
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = Oto.colors
    val bands = remember { FloatArray(Spectrum.BANDS) }

    val frame by produceState(0f, isPlaying) {
        if (!isPlaying) { value = 0f; return@produceState }
        val start = withInfiniteAnimationFrameNanos { it }
        while (true) {
            withInfiniteAnimationFrameNanos { now -> value = (now - start) / 1_000_000_000f }
        }
    }

    Canvas(modifier) {
        if (isPlaying) Spectrum.read(bands) else bands.fill(0f)
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
            val band = (mirrored * (Spectrum.BANDS - 1)).toInt().coerceIn(0, Spectrum.BANDS - 1)
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
