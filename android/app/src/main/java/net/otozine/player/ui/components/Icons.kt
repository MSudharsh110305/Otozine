package net.otozine.player.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.CornerRadius
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

enum class Icon {
    PLAY, PAUSE, NEXT, PREVIOUS, SEARCH, LIBRARY, MORE, CHEVRON, CLOSE, GRIP,
    // Added because the interface was talking in words where a shape would do.
    // Every one is drawn on the same box at the same stroke weight with round
    // caps, so they read as one family rather than a collection of parts.
    DRIVE, PHONE, GRID, LIST, COPY, SHUFFLE, SLIDERS, CLOCK, SPARK, INFO,
}

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
            Icon.DRIVE -> drawDrive(tint)
            Icon.PHONE -> drawPhone(tint)
            Icon.GRID -> drawGrid(tint)
            Icon.LIST -> drawList(tint)
            Icon.COPY -> drawCopy(tint)
            Icon.SHUFFLE -> drawShuffle(tint)
            Icon.SLIDERS -> drawSliders(tint)
            Icon.CLOCK -> drawClock(tint)
            Icon.SPARK -> drawSpark(tint)
            Icon.INFO -> drawInfo(tint)
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
// One stroke weight across the whole set. Icons drawn at different weights are
// the quickest way to make a considered interface look assembled from parts.
private val DrawScope.iconStroke: Float get() = size.minDimension * 0.10f

/** A drive: a slab with a connector stub. */
private fun DrawScope.drawDrive(tint: Color) {
    val w = size.width * 0.60f
    val h = size.height * 0.40f
    val left = (size.width - w) / 2f - w * 0.08f
    val top = (size.height - h) / 2f
    drawRoundRect(
        tint, topLeft = Offset(left, top), size = Size(w, h),
        cornerRadius = CornerRadius(h * 0.30f, h * 0.30f),
        style = Stroke(width = iconStroke),
    )
    drawLine(
        tint, Offset(left + w, size.height / 2f),
        Offset(left + w + w * 0.26f, size.height / 2f),
        strokeWidth = iconStroke, cap = StrokeCap.Round,
    )
    drawCircle(tint, radius = iconStroke * 0.62f, center = Offset(left + w * 0.26f, size.height / 2f))
}

/** A phone: a tall rounded body with a speaker slot. */
private fun DrawScope.drawPhone(tint: Color) {
    val w = size.width * 0.46f
    val h = size.height * 0.70f
    val left = (size.width - w) / 2f
    val top = (size.height - h) / 2f
    drawRoundRect(
        tint, topLeft = Offset(left, top), size = Size(w, h),
        cornerRadius = CornerRadius(w * 0.30f, w * 0.30f),
        style = Stroke(width = iconStroke),
    )
    drawLine(
        tint, Offset(left + w * 0.34f, top + h * 0.15f),
        Offset(left + w * 0.66f, top + h * 0.15f),
        strokeWidth = iconStroke * 0.8f, cap = StrokeCap.Round,
    )
}

/** Four tiles: the grouped view. */
private fun DrawScope.drawGrid(tint: Color) {
    val cell = size.minDimension * 0.30f
    val gap = size.minDimension * 0.10f
    val originX = (size.width - (cell * 2 + gap)) / 2f
    val originY = (size.height - (cell * 2 + gap)) / 2f
    for (row in 0..1) for (col in 0..1) {
        drawRoundRect(
            tint,
            topLeft = Offset(originX + col * (cell + gap), originY + row * (cell + gap)),
            size = Size(cell, cell),
            cornerRadius = CornerRadius(cell * 0.30f, cell * 0.30f),
            style = Stroke(width = iconStroke * 0.85f),
        )
    }
}

/** Rows with leading dots: the flat view. */
private fun DrawScope.drawList(tint: Color) {
    val inset = size.width * 0.16f
    val gap = size.height * 0.26f
    val top = size.height / 2f - gap
    for (row in 0..2) {
        val y = top + gap * row
        drawCircle(tint, radius = iconStroke * 0.60f, center = Offset(inset, y))
        drawLine(
            tint, Offset(inset + iconStroke * 2.2f, y), Offset(size.width - inset, y),
            strokeWidth = iconStroke * 0.85f, cap = StrokeCap.Round,
        )
    }
}

/** Two offset sheets: put a copy somewhere else. */
private fun DrawScope.drawCopy(tint: Color) {
    val w = size.width * 0.46f
    val h = size.height * 0.52f
    drawRoundRect(
        tint, topLeft = Offset(size.width * 0.14f, size.height * 0.16f), size = Size(w, h),
        cornerRadius = CornerRadius(w * 0.24f, w * 0.24f),
        style = Stroke(width = iconStroke * 0.85f),
    )
    drawRoundRect(
        tint, topLeft = Offset(size.width * 0.40f, size.height * 0.32f), size = Size(w, h),
        cornerRadius = CornerRadius(w * 0.24f, w * 0.24f),
        style = Stroke(width = iconStroke * 0.85f),
    )
}

/** Crossing paths with arrowheads. */
private fun DrawScope.drawShuffle(tint: Color) {
    val inset = size.width * 0.16f
    val top = size.height * 0.32f
    val bottom = size.height * 0.68f
    val endX = size.width - inset * 1.7f
    drawLine(tint, Offset(inset, top), Offset(endX, bottom),
        strokeWidth = iconStroke, cap = StrokeCap.Round)
    drawLine(tint, Offset(inset, bottom), Offset(endX, top),
        strokeWidth = iconStroke, cap = StrokeCap.Round)
    for (y in listOf(top, bottom)) {
        drawLine(tint, Offset(endX - iconStroke * 1.3f, y - iconStroke * 1.2f),
            Offset(endX + iconStroke * 0.6f, y), strokeWidth = iconStroke, cap = StrokeCap.Round)
        drawLine(tint, Offset(endX - iconStroke * 1.3f, y + iconStroke * 1.2f),
            Offset(endX + iconStroke * 0.6f, y), strokeWidth = iconStroke, cap = StrokeCap.Round)
    }
}

/** Two tracks with handles: the shaping controls. */
private fun DrawScope.drawSliders(tint: Color) {
    val inset = size.width * 0.16f
    val rows = listOf(0.34f to 0.62f, 0.66f to 0.38f)
    rows.forEach { row ->
        val y = size.height * row.first
        drawLine(tint, Offset(inset, y), Offset(size.width - inset, y),
            strokeWidth = iconStroke * 0.85f, cap = StrokeCap.Round)
        drawCircle(tint, radius = iconStroke * 1.2f, center = Offset(size.width * row.second, y))
    }
}

/** A clock face. */
private fun DrawScope.drawClock(tint: Color) {
    val r = size.minDimension * 0.34f
    val c = Offset(size.width / 2f, size.height / 2f)
    drawCircle(tint, radius = r, center = c, style = Stroke(width = iconStroke * 0.9f))
    drawLine(tint, c, Offset(c.x, c.y - r * 0.52f),
        strokeWidth = iconStroke * 0.9f, cap = StrokeCap.Round)
    drawLine(tint, c, Offset(c.x + r * 0.42f, c.y),
        strokeWidth = iconStroke * 0.9f, cap = StrokeCap.Round)
}

/** A four-point star: measured, inferred, automatic. */
private fun DrawScope.drawSpark(tint: Color) {
    val c = Offset(size.width / 2f, size.height / 2f)
    val long = size.minDimension * 0.36f
    val short = size.minDimension * 0.12f
    val path = Path().apply {
        moveTo(c.x, c.y - long)
        quadraticTo(c.x + short, c.y - short, c.x + long, c.y)
        quadraticTo(c.x + short, c.y + short, c.x, c.y + long)
        quadraticTo(c.x - short, c.y + short, c.x - long, c.y)
        quadraticTo(c.x - short, c.y - short, c.x, c.y - long)
        close()
    }
    drawPath(path, tint)
}

/** A circled i. */
private fun DrawScope.drawInfo(tint: Color) {
    val r = size.minDimension * 0.36f
    val c = Offset(size.width / 2f, size.height / 2f)
    drawCircle(tint, radius = r, center = c, style = Stroke(width = iconStroke * 0.9f))
    drawCircle(tint, radius = iconStroke * 0.55f, center = Offset(c.x, c.y - r * 0.42f))
    drawLine(tint, Offset(c.x, c.y), Offset(c.x, c.y + r * 0.46f),
        strokeWidth = iconStroke * 0.9f, cap = StrokeCap.Round)
}

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
