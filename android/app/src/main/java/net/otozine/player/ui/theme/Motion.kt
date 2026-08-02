package net.otozine.player.ui.theme

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color

/**
 * Motion for the small things.
 *
 * Everything here is deliberately brief. The house rule is that motion has to
 * report something -- a touch registering, work still running, a value that
 * changed -- because animation that only decorates costs battery on every frame
 * and stops being noticed within a day of use.
 *
 * Durations sit in the 120-300 ms band. Below that a change is not perceived as
 * movement, just as a jump; above it the interface starts feeling like it is
 * waiting for permission.
 */

/**
 * A sweep of light across a surface, for content that is still loading.
 *
 * Distinct from a spinner: a shimmer sits in the shape of the thing that is
 * coming, so the layout does not move when it arrives. Use it where the final
 * size is known -- a row, a tile -- and a spinner where it is not.
 */
fun Modifier.shimmer(
    active: Boolean = true,
    highlight: Color = Color.White,
    strength: Float = 0.35f,
): Modifier = if (!active) this else composed {
    val transition = rememberInfiniteTransition(label = "shimmer")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            // Slow, with a pause. A fast continuous sweep reads as an error
            // state; this should say "working", not "something is wrong".
            animation = tween(durationMillis = 1400, delayMillis = 350),
            repeatMode = RepeatMode.Restart,
        ),
        label = "sweep",
    )

    drawWithContent {
        drawContent()
        // Travels from fully off one edge to fully off the other, so the band
        // never appears to start or stop mid-surface.
        val span = size.width * 2f
        val x = -size.width + progress * span
        drawRect(
            brush = Brush.linearGradient(
                0f to Color.Transparent,
                0.5f to highlight.copy(alpha = strength),
                1f to Color.Transparent,
                start = Offset(x, 0f),
                end = Offset(x + size.width * 0.6f, size.height),
            ),
        )
    }
}

/**
 * A brief specular catch, for something that just became true.
 *
 * Where shimmer repeats to say "still working", glimmer fires once to say
 * "done" -- a track finished analysing, a song landed on the drive. One pass,
 * then nothing.
 */
fun Modifier.glimmer(
    key: Any?,
    highlight: Color = Color.White,
): Modifier = composed {
    val progress by animateFloatAsState(
        targetValue = 1f,
        animationSpec = tween(durationMillis = 620),
        label = "glimmer-$key",
    )

    drawWithContent {
        drawContent()
        if (progress < 1f) {
            val x = -size.width + progress * size.width * 2.4f
            drawRect(
                brush = Brush.linearGradient(
                    0f to Color.Transparent,
                    0.5f to highlight.copy(alpha = 0.45f * (1f - progress)),
                    1f to Color.Transparent,
                    start = Offset(x, 0f),
                    end = Offset(x + size.width * 0.5f, size.height),
                ),
                blendMode = BlendMode.Plus,
            )
        }
    }
}

/**
 * Shrink slightly while held.
 *
 * The one piece of feedback a neumorphic surface cannot give by itself: its
 * depth is painted, so a pressed button looks identical to a resting one. A
 * couple of percent is enough -- the point is to confirm the touch landed, not
 * to perform.
 *
 * Spring rather than tween, because a press can be released before the
 * animation finishes and a spring reverses from wherever it got to instead of
 * snapping back to the start.
 */
@Composable
fun Modifier.pressable(
    interactionSource: InteractionSource,
    scaleTo: Float = 0.97f,
): Modifier {
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) scaleTo else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessHigh,
        ),
        label = "press",
    )
    return this.scale(scale)
}
