package net.otozine.player.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Drawn icons rather than font glyphs.
 *
 * The first version used characters -- U+25B6 for play, U+275A for pause and so
 * on. Six of them turned out to be absent from the subset UI font, so Android
 * silently substituted a system face: they rendered, but at the wrong weight
 * and alignment, and the mismatch was visible next to real text.
 *
 * Drawing them removes the dependency entirely. They are simple shapes, they
 * scale exactly, and they inherit the accent colour like everything else.
 */

enum class Icon { PLAY, PAUSE, NEXT, PREVIOUS, SEARCH, LIBRARY, MORE, CHEVRON, CLOSE, GRIP }

@Composable
fun OtoIcon(
    icon: Icon,
    tint: Color,
    modifier: Modifier = Modifier,
    size: Dp = 18.dp,
) {
    Canvas(modifier.size(size)) {
        when (icon) {
            Icon.PLAY -> drawTriangle(tint)
            Icon.PAUSE -> drawPause(tint)
            Icon.NEXT -> drawSkip(tint, forward = true)
            Icon.PREVIOUS -> drawSkip(tint, forward = false)
            Icon.SEARCH -> drawSearch(tint)
            Icon.LIBRARY -> drawLibrary(tint)
            Icon.MORE -> drawMore(tint)
            Icon.CHEVRON -> drawChevron(tint)
            Icon.CLOSE -> drawClose(tint)
            Icon.GRIP -> drawGrip(tint)
        }
    }
}

private fun DrawScope.drawTriangle(tint: Color, scale: Float = 1f, shift: Float = 0f) {
    val w = size.width * 0.62f * scale
    val h = size.height * 0.68f * scale
    // Nudged right by an eighth: a triangle centred on its bounding box reads
    // as sitting left of centre, which is very obvious inside a round button.
    val left = (size.width - w) / 2f + w * 0.10f + shift
    val top = (size.height - h) / 2f
    drawPath(
        Path().apply {
            moveTo(left, top)
            lineTo(left + w, top + h / 2f)
            lineTo(left, top + h)
            close()
        },
        tint,
    )
}

private fun DrawScope.drawPause(tint: Color) {
    val barWidth = size.width * 0.17f
    val gap = size.width * 0.16f
    val height = size.height * 0.62f
    val top = (size.height - height) / 2f
    val left = (size.width - (barWidth * 2 + gap)) / 2f
    drawRoundRect(
        tint,
        topLeft = Offset(left, top),
        size = Size(barWidth, height),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(barWidth * 0.3f),
    )
    drawRoundRect(
        tint,
        topLeft = Offset(left + barWidth + gap, top),
        size = Size(barWidth, height),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(barWidth * 0.3f),
    )
}

private fun DrawScope.drawSkip(tint: Color, forward: Boolean) {
    val w = size.width * 0.34f
    val h = size.height * 0.52f
    val top = (size.height - h) / 2f
    val centre = size.width / 2f
    val barWidth = size.width * 0.10f

    fun triangle(left: Float) = Path().apply {
        if (forward) {
            moveTo(left, top); lineTo(left + w, top + h / 2f); lineTo(left, top + h)
        } else {
            moveTo(left + w, top); lineTo(left, top + h / 2f); lineTo(left + w, top + h)
        }
        close()
    }

    if (forward) {
        drawPath(triangle(centre - w), tint)
        drawRoundRect(
            tint,
            topLeft = Offset(centre + w * 0.15f, top),
            size = Size(barWidth, h),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(barWidth * 0.4f),
        )
    } else {
        drawRoundRect(
            tint,
            topLeft = Offset(centre - w * 0.15f - barWidth, top),
            size = Size(barWidth, h),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(barWidth * 0.4f),
        )
        drawPath(triangle(centre), tint)
    }
}

private fun DrawScope.drawSearch(tint: Color) {
    val stroke = size.minDimension * 0.10f
    val radius = size.minDimension * 0.28f
    val centre = Offset(size.width * 0.44f, size.height * 0.44f)
    drawCircle(tint, radius, centre, style = Stroke(stroke))
    val start = Offset(
        centre.x + radius * 0.72f,
        centre.y + radius * 0.72f,
    )
    drawLine(
        tint,
        start,
        Offset(size.width * 0.84f, size.height * 0.84f),
        strokeWidth = stroke,
        cap = StrokeCap.Round,
    )
}

private fun DrawScope.drawLibrary(tint: Color) {
    val stroke = size.minDimension * 0.09f
    val left = size.width * 0.20f
    val right = size.width * 0.80f
    listOf(0.28f, 0.44f, 0.60f, 0.76f).forEach { fraction ->
        drawLine(
            tint,
            Offset(left, size.height * fraction),
            Offset(right, size.height * fraction),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
    }
}

private fun DrawScope.drawMore(tint: Color) {
    val radius = size.minDimension * 0.075f
    val y = size.height / 2f
    listOf(0.26f, 0.5f, 0.74f).forEach { fraction ->
        drawCircle(tint, radius, Offset(size.width * fraction, y))
    }
}

private fun DrawScope.drawChevron(tint: Color) {
    val stroke = size.minDimension * 0.11f
    val x = size.width * 0.40f
    drawLine(
        tint,
        Offset(x, size.height * 0.28f),
        Offset(x + size.width * 0.22f, size.height * 0.5f),
        strokeWidth = stroke, cap = StrokeCap.Round,
    )
    drawLine(
        tint,
        Offset(x + size.width * 0.22f, size.height * 0.5f),
        Offset(x, size.height * 0.72f),
        strokeWidth = stroke, cap = StrokeCap.Round,
    )
}

/**
 * Three stacked bars: the universal "hold here to move me".
 *
 * Drawn rather than shipped as an asset, like every other icon here, so it
 * inherits the tint and scales without a second file.
 */
private fun DrawScope.drawGrip(tint: Color) {
    val stroke = size.minDimension * 0.10f
    val inset = size.minDimension * 0.22f
    val gap = size.height * 0.26f
    val top = size.height / 2f - gap
    for (row in 0..2) {
        drawLine(
            tint,
            Offset(inset, top + gap * row),
            Offset(size.width - inset, top + gap * row),
            strokeWidth = stroke, cap = StrokeCap.Round,
        )
    }
}

private fun DrawScope.drawClose(tint: Color) {
    val stroke = size.minDimension * 0.11f
    val inset = size.minDimension * 0.30f
    drawLine(
        tint, Offset(inset, inset), Offset(size.width - inset, size.height - inset),
        strokeWidth = stroke, cap = StrokeCap.Round,
    )
    drawLine(
        tint, Offset(size.width - inset, inset), Offset(inset, size.height - inset),
        strokeWidth = stroke, cap = StrokeCap.Round,
    )
}
