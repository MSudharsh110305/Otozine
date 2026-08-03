package net.otozine.player.ui.components

import androidx.compose.animation.core.withInfiniteAnimationFrameNanos
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.unit.dp
import net.otozine.player.ui.theme.Oto
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

/**
 * Bars that move with the beat.
 *
 * Driven by the track's measured tempo and the playback position, not by
 * listening to the output. Android's Visualizer API is the obvious route and a
 * bad trade: it requires RECORD_AUDIO, so a decorative bar would make the app
 * ask for the microphone permission -- which is both alarming to grant and
 * impossible to explain honestly. It also runs an FFT on every buffer for the
 * whole time music plays.
 *
 * The tempo is already known from analysis, and the position is already known
 * from the player, so the beat can simply be computed. It costs nothing, needs
 * no permission, and is *exactly* on the beat rather than a few frames behind
 * it -- an FFT reacts after the transient, this arrives with it.
 *
 * The honest limit: it follows tempo, not dynamics. A quiet passage at 120 BPM
 * animates like a loud one. It is a metronome you can see, not a meter.
 */
@Composable
fun BeatBars(
    bpm: Float?,
    positionMs: Long,
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
    bars: Int = 28,
) {
    val colors = Oto.colors

    // Each bar gets a fixed character so the field reads as a shape rather than
    // a row of identical blinks: a phase offset, a height ceiling, and how
    // sharply it responds.
    val shape = remember(bars) {
        val random = Random(bars * 7919)
        List(bars) {
            Triple(
                random.nextFloat() * 2f * PI.toFloat(),
                0.35f + random.nextFloat() * 0.65f,
                0.5f + random.nextFloat(),
            )
        }
    }

    val time by produceState(0f, isPlaying) {
        if (!isPlaying) { value = 0f; return@produceState }
        val start = withInfiniteAnimationFrameNanos { it }
        while (true) {
            withInfiniteAnimationFrameNanos { now -> value = (now - start) / 1_000_000_000f }
        }
    }

    Canvas(modifier.fillMaxWidth().height(34.dp)) {
        val tempo = (bpm ?: 100f).coerceIn(60f, 190f)
        val beatsPerSecond = tempo / 60f
        // Where we are within the current beat, 0 at the hit.
        val beat = if (isPlaying) {
            ((positionMs / 1000f + time) * beatsPerSecond) % 1f
        } else 0.35f

        val slot = size.width / bars
        val barWidth = slot * 0.52f

        for (i in 0 until bars) {
            val (phase, ceiling, sharpness) = shape[i]
            // A struck-then-decaying envelope: fast attack, exponential fall.
            // A sine alone would swell into the beat, which reads as breathing
            // rather than as being hit.
            val local = (beat + sin(phase) * 0.06f + 1f) % 1f
            val envelope = if (isPlaying) exp(-local * 4.5f * sharpness) else 0.12f
            val wobble = 0.85f + 0.15f * sin(time * 1.7f + phase)

            val h = (size.height * ceiling * envelope * wobble)
                .coerceIn(size.height * 0.06f, size.height)
            val x = i * slot + (slot - barWidth) / 2f

            drawPath(
                Path().apply {
                    addRoundRect(
                        RoundRect(
                            rect = androidx.compose.ui.geometry.Rect(
                                offset = Offset(x, size.height - h),
                                size = Size(barWidth, h),
                            ),
                            cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f),
                        )
                    )
                },
                color = colors.teal.copy(alpha = 0.30f + 0.55f * envelope),
            )
        }
    }
}
