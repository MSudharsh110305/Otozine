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
enum class Scene { NONE, GLASS_LIGHT, PETALS, INK }

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

/**
 * An opaque backing for anything drawn over the whole screen.
 *
 * Scene themes leave `page` transparent so the backdrop shows through panels --
 * which is right for the app's own background and wrong for every layer stacked
 * on top of it. Now Playing filled itself with a transparent colour and let the
 * screen beneath show through, so two interfaces were legible at once and back
 * navigation looked broken when it was not.
 *
 * Layers therefore paint the scene again rather than a flat colour: opaque, and
 * still the theme you chose.
 */
@Composable
fun SceneSurface(modifier: Modifier = Modifier) {
    val palette = LocalOtoPalette.current
    val colors = LocalOtoColors.current
    // Flat themes have an opaque page already; scene themes repaint the scene.
    if ((palette?.scene ?: Scene.NONE) == Scene.NONE) {
        Canvas(modifier.fillMaxSize()) { drawRect(colors.page) }
    } else {
        ThemeScene(palette, modifier)
    }
}

@Composable
fun ThemeScene(palette: OtoPalette?, modifier: Modifier = Modifier) {
    when (palette?.scene ?: Scene.NONE) {
        Scene.NONE -> Unit
        Scene.GLASS_LIGHT -> GlassBackdrop(modifier)
        Scene.PETALS -> PetalBackdrop(modifier)
        Scene.INK -> InkBackdrop(modifier)
    }
}

// -------------------------------------------------------------------- ink

private class Bloom(random: Random) {
    val x = 0.08f + random.nextFloat() * 0.84f
    val y = 0.08f + random.nextFloat() * 0.84f
    /** Where in its own life it starts, so they are not born together. */
    val offset = random.nextFloat()
    /** Seconds for one full bloom and fade. Slow: this is ink, not fireworks. */
    val period = 14f + random.nextFloat() * 12f
    val maxRadius = 0.22f + random.nextFloat() * 0.30f
    val warm = random.nextFloat() < 0.35f
}

/**
 * Ink dropped into dark water.
 *
 * Each bloom expands and fades on its own slow cycle. The trick to making it
 * read as ink rather than as a pulsing circle is that the edge softens as it
 * grows -- real ink loses definition as it disperses, so opacity falls faster
 * than the radius rises, and the centre thins out first.
 *
 * Very slow on purpose. This sits behind a dark interface where the eye is
 * reading small text, and anything quick in the periphery is read as movement
 * to look at. Fourteen to twenty-six seconds a cycle is slow enough to notice
 * only when you are not doing anything else.
 */
@Composable
private fun InkBackdrop(modifier: Modifier = Modifier) {
    val moving = animationsEnabled()
    val blooms = remember { List(7) { Bloom(Random(it * 5381 + 7)) } }
    val time = if (moving) frameClock() else 4f

    Canvas(modifier.fillMaxSize()) {
        drawRect(
            Brush.verticalGradient(
                0f to Color(0xFF17140F),
                0.6f to Color(0xFF201B14),
                1f to Color(0xFF15120D),
            )
        )

        blooms.forEach { bloom ->
            val life = ((time / bloom.period) + bloom.offset) % 1f
            // Ease out: fast to spread, then almost still, the way ink behaves
            // once it meets the water.
            val spread = 1f - (1f - life) * (1f - life)
            val radius = size.minDimension * bloom.maxRadius * spread
            if (radius <= 1f) return@forEach

            // Fades faster than it grows, and hollows as it goes.
            val alpha = (1f - life).let { it * it } * 0.5f
            val core = if (bloom.warm) Color(0xFFC8A26A) else Color(0xFF6FBFB8)

            drawCircle(
                Brush.radialGradient(
                    listOf(
                        core.copy(alpha = alpha * 0.10f),
                        core.copy(alpha = alpha * 0.42f),
                        core.copy(alpha = 0f),
                    ),
                    center = Offset(size.width * bloom.x, size.height * bloom.y),
                    radius = radius,
                ),
                radius = radius,
                center = Offset(size.width * bloom.x, size.height * bloom.y),
            )
        }
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
                listOf(Color(0xA37AB0FF), Color(0x007AB0FF)),
                center = Offset(size.width * 0.18f, size.height * 0.16f),
                radius = size.minDimension * 0.85f,
            ),
            radius = size.minDimension * 0.85f,
            center = Offset(size.width * 0.18f, size.height * 0.16f),
        )
        drawCircle(
            Brush.radialGradient(
                listOf(Color(0x99C98CFF), Color(0x00C98CFF)),
                center = Offset(size.width * 0.9f, size.height * 0.34f),
                radius = size.minDimension * 0.8f,
            ),
            radius = size.minDimension * 0.8f,
            center = Offset(size.width * 0.9f, size.height * 0.34f),
        )
        drawCircle(
            Brush.radialGradient(
                listOf(Color(0x8F5FD9C4), Color(0x005FD9C4)),
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

    val tone = random.nextInt(3)

    val scale get() = 0.45f + depth * 0.85f
    val speed get() = 0.020f + depth * 0.055f
    val alpha get() = 0.28f + depth * 0.45f
}

private val PetalTones = listOf(
    Color(0xFFF3C2D2),   // pale edge
    Color(0xFFE79BB4),   // mid
    Color(0xFFD97E9E),   // deeper heart
)

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

        drawCircle(
            Brush.radialGradient(
                listOf(Color(0x59FFFFFF), Color(0x00FFFFFF)),
                center = Offset(size.width * 0.72f, -size.height * 0.05f),
                radius = size.minDimension * 0.95f,
            ),
            radius = size.minDimension * 0.95f,
            center = Offset(size.width * 0.72f, -size.height * 0.05f),
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
                    drawPetal(length, PetalTones[petal.tone].copy(alpha = petal.alpha))
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
