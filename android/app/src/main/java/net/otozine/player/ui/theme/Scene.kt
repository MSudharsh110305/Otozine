package net.otozine.player.ui.theme

import android.provider.Settings
import androidx.compose.animation.core.withInfiniteAnimationFrameNanos
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.rotateRad
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/**
 * What a theme paints behind the interface.
 *
 * Most themes want nothing here -- a neumorphic surface needs a flat page to sit
 * on, and anything busy underneath fights the shadows that carry its depth. The
 * two exceptions both need a backdrop to work at all: glass has nothing to
 * refract without one, and falling petals are the entire point of the blossom
 * theme.
 */
enum class Scene { NONE, GLASS_LIGHT, PETALS }

/**
 * Whether the device has been told to stop animating.
 *
 * Android exposes this as the animator duration scale, which developer options
 * and battery saver both set to zero. Honouring it matters more than usual
 * here: this is ambient motion nobody asked for, running behind everything else
 * on screen.
 */
@Composable
private fun animationsEnabled(): Boolean {
    val context = LocalContext.current
    return remember {
        runCatching {
            Settings.Global.getFloat(
                context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f,
            ) > 0f
        }.getOrDefault(true)
    }
}

/** Seconds since the composition began, advanced once per frame. */
@Composable
private fun frameClock(): Float {
    val time by produceState(0f) {
        val start = withInfiniteAnimationFrameNanos { it }
        while (true) {
            withInfiniteAnimationFrameNanos { now ->
                value = (now - start) / 1_000_000_000f
            }
        }
    }
    return time
}

@Composable
fun ThemeScene(palette: OtoPalette?, modifier: Modifier = Modifier) {
    when (palette?.scene ?: Scene.NONE) {
        Scene.NONE -> Unit
        Scene.GLASS_LIGHT -> GlassBackdrop(modifier)
        Scene.PETALS -> PetalBackdrop(modifier)
    }
}

/**
 * Soft colour fields for glass to sit on.
 *
 * Glassmorphism is a *relationship*, not a surface treatment: a translucent
 * panel over a flat page is just a lighter flat page. The panels need something
 * with colour and variation behind them before transparency reads as glass at
 * all, which is why the dark version of this theme never convinced -- there was
 * nothing behind it to see.
 *
 * Static, and deliberately so. It exists to be looked *through*, and a backdrop
 * that moves would pull attention to the one layer that should never have it.
 */
@Composable
private fun GlassBackdrop(modifier: Modifier = Modifier) {
    Canvas(modifier.fillMaxSize()) {
        drawRect(
            Brush.linearGradient(
                0f to Color(0xFFEFF3FF),
                0.5f to Color(0xFFF7F0FF),
                1f to Color(0xFFEAF7F6),
            )
        )
        // Three wide, low-opacity pools. Big and soft enough that no edge is
        // visible through a panel -- a hard boundary showing through glass reads
        // as a rendering fault rather than as depth.
        drawCircle(
            Brush.radialGradient(
                listOf(Color(0x669BC4FF), Color(0x009BC4FF)),
                center = Offset(size.width * 0.18f, size.height * 0.16f),
                radius = size.minDimension * 0.85f,
            ),
            radius = size.minDimension * 0.85f,
            center = Offset(size.width * 0.18f, size.height * 0.16f),
        )
        drawCircle(
            Brush.radialGradient(
                listOf(Color(0x5CD9A8FF), Color(0x00D9A8FF)),
                center = Offset(size.width * 0.9f, size.height * 0.34f),
                radius = size.minDimension * 0.8f,
            ),
            radius = size.minDimension * 0.8f,
            center = Offset(size.width * 0.9f, size.height * 0.34f),
        )
        drawCircle(
            Brush.radialGradient(
                listOf(Color(0x5C7FE8D2), Color(0x007FE8D2)),
                center = Offset(size.width * 0.35f, size.height * 0.92f),
                radius = size.minDimension * 0.9f,
            ),
            radius = size.minDimension * 0.9f,
            center = Offset(size.width * 0.35f, size.height * 0.92f),
        )
    }
}

// ----------------------------------------------------------------- petals

private class Petal(random: Random) {
    /** 0 far, 1 near. Drives size, speed, opacity and blur together. */
    val depth = random.nextFloat()
    val x = random.nextFloat()
    val phase = random.nextFloat() * (2 * PI).toFloat()
    val sway = 0.02f + random.nextFloat() * 0.05f
    val spin = (random.nextFloat() - 0.5f) * 1.6f
    val tilt = random.nextFloat() * (2 * PI).toFloat()
    val startY = random.nextFloat()

    val scale get() = 0.45f + depth * 0.85f
    val speed get() = 0.020f + depth * 0.055f
    val alpha get() = 0.28f + depth * 0.45f
}

/**
 * Cherry petals falling, with depth.
 *
 * The sense of space comes from binding four things to one depth value: near
 * petals are larger, fall faster, are more opaque and rotate more. Varying them
 * independently is what makes this sort of effect look like scattered confetti
 * rather than a scene with air in it.
 *
 * Kept to 26 petals. Each is a handful of curves and the whole field is one
 * canvas, so the cost is a fraction of a millisecond a frame -- but this runs
 * behind everything, forever, and a background that shortens a listening
 * session is a bad trade however pretty it is.
 */
@Composable
private fun PetalBackdrop(modifier: Modifier = Modifier) {
    val moving = animationsEnabled()
    val petals = remember { List(26) { Petal(Random(it * 7919 + 13)) } }
    val time = if (moving) frameClock() else 0f

    Canvas(modifier.fillMaxSize()) {
        drawRect(
            Brush.verticalGradient(
                0f to Color(0xFFFDF4F7),
                0.55f to Color(0xFFF8E9EE),
                1f to Color(0xFFF3DFE7),
            )
        )

        petals.forEach { petal ->
            // Wrap in [0,1) so a petal leaving the bottom re-enters at the top
            // without a seam, rather than the field slowly emptying.
            val fall = (petal.startY + time * petal.speed) % 1f
            val y = fall * (size.height + 120.dp.toPx()) - 60.dp.toPx()
            val drift = sin(time * 0.6f + petal.phase) * petal.sway
            val x = ((petal.x + drift) % 1f + 1f) % 1f * size.width

            val length = (13.dp.toPx()) * petal.scale
            translate(x, y) {
                rotateRad(petal.tilt + time * petal.spin, pivot = Offset.Zero) {
                    drawPetal(length, Color(0xFFE79BB4).copy(alpha = petal.alpha))
                }
            }
        }
    }
}

/**
 * One petal: a rounded almond with a notched tip.
 *
 * Drawn rather than shipped as an asset so it scales cleanly across depths and
 * costs no memory. The notch is what makes it read as a blossom petal instead
 * of a leaf, and it survives all the way down to the smallest, furthest ones.
 */
private fun DrawScope.drawPetal(length: Float, color: Color) {
    val w = length * 0.62f
    val path = Path().apply {
        moveTo(0f, 0f)
        cubicTo(w * 0.9f, length * 0.12f, w * 0.8f, length * 0.72f, w * 0.14f, length)
        // The notch: a small inward step at the tip.
        lineTo(0f, length * 0.86f)
        lineTo(-w * 0.14f, length)
        cubicTo(-w * 0.8f, length * 0.72f, -w * 0.9f, length * 0.12f, 0f, 0f)
        close()
    }
    drawPath(path, color, style = Fill)
}
