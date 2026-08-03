package net.otozine.player.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.Canvas
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import net.otozine.player.ui.theme.Depth
import net.otozine.player.ui.theme.Oto
import net.otozine.player.ui.theme.neu

/**
 * A ring showing how far along something is.
 *
 * A proportion is a shape before it is a number: "142 of 199" takes reading,
 * while a ring three-quarters closed is understood before the eye has finished
 * arriving. The number stays inside it for when the exact figure matters.
 */
@Composable
fun StatRing(
    fraction: Float,
    label: String,
    caption: String,
    modifier: Modifier = Modifier,
    size: androidx.compose.ui.unit.Dp = 92.dp,
) {
    val colors = Oto.colors
    val swept by animateFloatAsState(
        targetValue = fraction.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 700),
        label = "ring",
    )

    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(size), contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(size)) {
                val stroke = this.size.minDimension * 0.10f
                val inset = stroke / 2f
                val arc = Size(this.size.width - stroke, this.size.height - stroke)

                drawArc(
                    color = colors.ink3.copy(alpha = 0.22f),
                    startAngle = 135f,
                    sweepAngle = 270f,
                    useCenter = false,
                    topLeft = androidx.compose.ui.geometry.Offset(inset, inset),
                    size = arc,
                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                )
                drawArc(
                    color = colors.teal,
                    startAngle = 135f,
                    sweepAngle = 270f * swept,
                    useCenter = false,
                    topLeft = androidx.compose.ui.geometry.Offset(inset, inset),
                    size = arc,
                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                )
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(label, style = Oto.type.title, color = colors.ink)
                Text(caption, style = Oto.type.micro, color = colors.ink3)
            }
        }
    }
}

/**
 * Where a collection lives, as one bar.
 *
 * Two numbers side by side invite arithmetic; a bar shows the balance without
 * any. Segments below a couple of percent are floored to a visible width, since
 * a source with three songs in it should still be findable on the bar.
 */
@Composable
fun SplitBar(
    parts: List<Triple<String, Int, Color>>,
    modifier: Modifier = Modifier,
) {
    val total = parts.sumOf { it.second }.coerceAtLeast(1)
    val colors = Oto.colors

    Column(modifier) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(12.dp)
                .neu(Depth.Inset, RoundedCornerShape(50))
                .clip(RoundedCornerShape(50)),
        ) {
            parts.filter { it.second > 0 }.forEach { (_, count, colour) ->
                Box(
                    Modifier
                        .weight((count.toFloat() / total).coerceAtLeast(0.03f))
                        .fillMaxWidth()
                        .background(colour)
                        .height(12.dp)
                )
            }
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            parts.filter { it.second > 0 }.forEach { (name, count, colour) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(7.dp)
                            .clip(RoundedCornerShape(50))
                            .background(colour)
                    )
                    Text(
                        "  $name $count",
                        style = Oto.type.micro,
                        color = colors.ink2,
                    )
                }
            }
        }
    }
}
