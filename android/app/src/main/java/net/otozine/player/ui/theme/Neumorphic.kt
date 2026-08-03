package net.otozine.player.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Neumorphic depth.
 *
 * Compose has `Modifier.shadow`, but it draws one ambient shadow and cannot
 * draw *inside* a shape. Neumorphism needs both: a dark shadow offset one way
 * and a light one offset the other, and the recessed variant needs them within
 * the bounds. So this drops to the framework canvas and uses `setShadowLayer`,
 * which is the only Android API that blurs a shadow independently of its
 * source. Hardware-accelerated since API 28; our floor is 33.
 *
 * Offsets and blur radii are the mockup's CSS values used directly as dp -- the
 * design was drawn at 397 px wide, near enough to a phone's ~411 dp that the
 * numbers transfer without rescaling.
 */

/** Depth presets, matching the recipes used in the mockup. */
enum class Depth {
    /** Cards, buttons at rest: `4px 4px 10px sh, -3px -3px 8px hi`. */
    Raised,

    /** Subtler lift for rows and small chips. */
    RaisedSoft,

    /** Big floating elements: art, primary buttons. */
    RaisedHigh,

    /** Wells, inputs, track grooves: `inset 2px 2px 5px sh`. */
    Inset,

    /** Pressed state and deep grooves: `inset 4px 4px 9px sh`. */
    InsetDeep,
}

private data class Recipe(
    val darkOffset: Dp,
    val darkBlur: Dp,
    val lightOffset: Dp,
    val lightBlur: Dp,
    val inset: Boolean,
)

private fun recipeFor(depth: Depth) = when (depth) {
    Depth.Raised -> Recipe(4.dp, 10.dp, 3.dp, 8.dp, inset = false)
    Depth.RaisedSoft -> Recipe(3.dp, 7.dp, 2.dp, 5.dp, inset = false)
    Depth.RaisedHigh -> Recipe(5.dp, 12.dp, 4.dp, 10.dp, inset = false)
    Depth.Inset -> Recipe(2.dp, 5.dp, 2.dp, 4.dp, inset = true)
    Depth.InsetDeep -> Recipe(4.dp, 9.dp, 4.dp, 8.dp, inset = true)
}

/**
 * Draw a neumorphic surface: the fill plus its paired shadows.
 *
 * The fill is drawn by this modifier rather than by a separate `.background()`,
 * because an inset shadow has to be painted *over* the fill and under the
 * content -- splitting them across two modifiers puts them in the wrong order.
 */
@Composable
fun Modifier.neu(
    depth: Depth = Depth.Raised,
    shape: Shape = RoundedCornerShape(16.dp),
    color: Color? = null,
): Modifier {
    val colors = LocalOtoColors.current
    val recipe = recipeFor(depth)
    val fill = color ?: if (recipe.inset) colors.sunken else colors.surface
    val shadow = colors.shadow
    val highlight = colors.highlight

    val glow = colors.glow
    val finish = colors.finish

    return drawBehind {
        val outline = shape.createOutline(size, layoutDirection, this)
        when (finish) {
            Finish.SOFT ->
                if (recipe.inset) drawInsetSurface(outline, fill, shadow, highlight, recipe)
                else drawRaisedSurface(outline, fill, shadow, highlight, recipe)
            Finish.GLASS -> drawGlassSurface(outline, fill, highlight, shadow, recipe)
            Finish.NEON -> drawNeonSurface(outline, fill, glow, shadow, recipe)
        }
    }
}

/**
 * Frosted panel: translucent fill, a lit top edge, and a soft drop.
 *
 * There is no real backdrop blur here. Compose's `RenderEffect` blur cannot
 * sample what is painted behind a composable without rendering the background
 * into a layer first, which for a scrolling list means a full-screen readback
 * every frame. The convincing part of glass is not the blur anyway -- it is the
 * bright leading edge and the fact that the ground shows through, and both of
 * those are nearly free.
 */
private fun DrawScope.drawGlassSurface(
    outline: Outline,
    fill: Color,
    highlight: Color,
    shadow: Color,
    recipe: Recipe,
) {
    val path = Path().apply { addOutline(outline) }
    // Depth is carried by transparency, not only by shadow.
    //
    // Real glass gets more opaque as it thickens, so a raised panel should sit
    // further from the backdrop than a recessed one. Drawing every surface at
    // one opacity is what made the first version read as flat sheets of fog at
    // varying heights -- the shadows said depth and the material contradicted
    // them.
    val thickness = when {
        recipe.inset -> 0.72f
        recipe.darkOffset.value >= 5f -> 1.18f      // RaisedHigh: nearest the eye
        recipe.darkOffset.value >= 4f -> 1.05f
        else -> 0.92f
    }
    val glass = fill.copy(alpha = (fill.alpha * thickness).coerceIn(0f, 1f))
    if (!recipe.inset) {
        drawIntoCanvas { canvas ->
            val paint = Paint().asFrameworkPaint().apply {
                isAntiAlias = true
                color = android.graphics.Color.TRANSPARENT
                setShadowLayer(
                    recipe.darkBlur.toPx(), 0f, recipe.darkOffset.toPx(), shadow.toArgb(),
                )
            }
            canvas.nativeCanvas.drawPath(path.asAndroidPath(), paint)
        }
    }
    drawPath(path, glass)

    // A vertical gradient inside the panel: brighter where it faces the light,
    // settling toward the fill below. Flat translucency has no orientation, and
    // orientation is most of what tells the eye a surface is a surface.
    drawPath(
        path,
        brush = Brush.verticalGradient(
            0f to Color.White.copy(alpha = if (recipe.inset) 0.05f else 0.16f),
            0.55f to Color.Transparent,
        ),
    )

    // The lit edge. Brighter at the top where light would land, fading out by
    // the middle -- a uniform border reads as a stroke, not as a surface.
    drawPath(
        path,
        brush = Brush.verticalGradient(
            0f to highlight,
            0.45f to highlight.copy(alpha = highlight.alpha * 0.18f),
            1f to Color.Transparent,
        ),
        style = Stroke(width = 1.2.dp.toPx()),
    )
}

/**
 * Emissive panel: flat fill, and a glow that leaks outward from the edge.
 *
 * Neumorphic shadows are meaningless here -- against near-black there is no
 * darker colour to cast one in. Depth comes from light instead, so a raised
 * surface glows and a recessed one is simply darker than the page.
 */
private fun DrawScope.drawNeonSurface(
    outline: Outline,
    fill: Color,
    glow: Color,
    shadow: Color,
    recipe: Recipe,
) {
    val path = Path().apply { addOutline(outline) }
    if (!recipe.inset) {
        drawIntoCanvas { canvas ->
            val paint = Paint().asFrameworkPaint().apply {
                isAntiAlias = true
                color = android.graphics.Color.TRANSPARENT
                setShadowLayer(
                    recipe.darkBlur.toPx() * 0.9f, 0f, 0f,
                    glow.copy(alpha = 0.22f).toArgb(),
                )
            }
            canvas.nativeCanvas.drawPath(path.asAndroidPath(), paint)
        }
    }
    drawPath(path, fill)
    drawPath(
        path,
        color = if (recipe.inset) shadow.copy(alpha = 0.5f) else glow.copy(alpha = 0.32f),
        style = Stroke(width = 1.dp.toPx()),
    )
}

private fun DrawScope.drawRaisedSurface(
    outline: Outline,
    fill: Color,
    shadow: Color,
    highlight: Color,
    recipe: Recipe,
) {
    val path = Path().apply { addOutline(outline) }

    drawIntoCanvas { canvas ->
        val paint = Paint()
        val frameworkPaint = paint.asFrameworkPaint()
        frameworkPaint.isAntiAlias = true
        // A transparent fill still casts its shadow layer -- that is the trick
        // that lets us draw a blurred shadow with no visible source shape.
        frameworkPaint.color = android.graphics.Color.TRANSPARENT

        frameworkPaint.setShadowLayer(
            recipe.darkBlur.toPx(),
            recipe.darkOffset.toPx(),
            recipe.darkOffset.toPx(),
            shadow.toArgb(),
        )
        canvas.nativeCanvas.drawPath(path.asAndroidPath(), frameworkPaint)

        frameworkPaint.setShadowLayer(
            recipe.lightBlur.toPx(),
            -recipe.lightOffset.toPx(),
            -recipe.lightOffset.toPx(),
            highlight.toArgb(),
        )
        canvas.nativeCanvas.drawPath(path.asAndroidPath(), frameworkPaint)
        frameworkPaint.clearShadowLayer()
    }

    drawPath(path, fill)
}

private fun DrawScope.drawInsetSurface(
    outline: Outline,
    fill: Color,
    shadow: Color,
    highlight: Color,
    recipe: Recipe,
) {
    val shapePath = Path().apply { addOutline(outline) }
    drawPath(shapePath, fill)

    // An inner shadow is the shadow cast by everything *outside* the shape.
    // Build a ring -- a generous rectangle with the shape punched out of it --
    // clip to the shape, then let the ring's shadow fall inward.
    val spread = 40f
    val ring = Path().apply {
        addRect(Rect(-spread, -spread, size.width + spread, size.height + spread))
        addOutline(outline)
        fillType = PathFillType.EvenOdd
    }

    clipPath(shapePath) {
        drawIntoCanvas { canvas ->
            val paint = Paint()
            val frameworkPaint = paint.asFrameworkPaint()
            frameworkPaint.isAntiAlias = true
            frameworkPaint.color = android.graphics.Color.TRANSPARENT

            frameworkPaint.setShadowLayer(
                recipe.darkBlur.toPx(),
                recipe.darkOffset.toPx(),
                recipe.darkOffset.toPx(),
                shadow.toArgb(),
            )
            canvas.nativeCanvas.drawPath(ring.asAndroidPath(), frameworkPaint)

            frameworkPaint.setShadowLayer(
                recipe.lightBlur.toPx(),
                -recipe.lightOffset.toPx(),
                -recipe.lightOffset.toPx(),
                highlight.toArgb(),
            )
            canvas.nativeCanvas.drawPath(ring.asAndroidPath(), frameworkPaint)
            frameworkPaint.clearShadowLayer()
        }
    }
}

/** Compose Path -> framework Path, needed for `drawPath` on the native canvas. */
private fun Path.asAndroidPath(): android.graphics.Path =
    (this as androidx.compose.ui.graphics.AndroidPath).internalPath

/** Convenience: a soft circular glow, used for the "active" dot and accents. */
fun DrawScope.glow(center: Offset, radius: Float, color: Color) {
    drawIntoCanvas { canvas ->
        val paint = Paint()
        val fw = paint.asFrameworkPaint()
        fw.isAntiAlias = true
        fw.color = color.toArgb()
        fw.setShadowLayer(radius * 1.6f, 0f, 0f, color.toArgb())
        canvas.nativeCanvas.drawCircle(center.x, center.y, radius, fw)
        fw.clearShadowLayer()
    }
}

internal val UnusedSize = Size.Zero
