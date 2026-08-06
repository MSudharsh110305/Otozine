package net.otozine.player.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import net.otozine.player.ui.theme.Depth
import net.otozine.player.ui.theme.Dimens
import net.otozine.player.ui.theme.Oto
import net.otozine.player.ui.theme.neu

/**
 * One choice from a small fixed set.
 *
 * A row of separate chips was the wrong shape for this. Chips read as
 * independent toggles -- any number can be on -- so a scrolling row of them
 * gave no sense that picking one dropped another, and with the row scrolled the
 * selected option could be off screen entirely. A segmented control says
 * "exactly one of these" in its geometry: one recessed track, one raised thumb
 * that slides.
 *
 * The thumb animates between positions rather than jumping, because the
 * movement is what carries the meaning -- something left where it was and
 * arrived where it is now.
 */
@Composable
fun <T> Segmented(
    options: List<Triple<T, String, Icon?>>,
    selected: T,
    modifier: Modifier = Modifier,
    /** Fixed width per segment. Null fills whatever width it is given. */
    segmentWidth: androidx.compose.ui.unit.Dp? = null,
    height: androidx.compose.ui.unit.Dp = Dimens.control,
    onSelect: (T) -> Unit,
) {
    if (options.isEmpty()) return
    val index = options.indexOfFirst { it.first == selected }.coerceAtLeast(0)
    val position by animateFloatAsState(
        targetValue = index.toFloat(),
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "segment",
    )

    BoxWithConstraints(
        modifier
            .then(
                if (segmentWidth != null) Modifier.width(segmentWidth * options.size)
                else Modifier
            )
            .height(height)
            .neu(Depth.Inset, RoundedCornerShape(50)),
    ) {
        val slot = segmentWidth ?: (maxWidth / options.size)

        // The thumb, drawn under the labels so text never sits on an edge.
        Box(
            Modifier
                .offset(x = slot * position)
                .width(slot)
                .fillMaxHeight()
                .padding(2.5.dp)
                .neu(Depth.RaisedSoft, RoundedCornerShape(50))
        )

        Row(Modifier.fillMaxWidth().fillMaxHeight()) {
            options.forEachIndexed { i, option ->
                val (value, label, icon) = option
                val interaction = remember { MutableInteractionSource() }
                val tint = if (i == index) Oto.colors.teal else Oto.colors.ink3
                Box(
                    Modifier
                        .width(slot)
                        .fillMaxHeight()
                        .clickable(
                            interactionSource = interaction,
                            indication = null,
                        ) { onSelect(value) },
                    contentAlignment = Alignment.Center,
                ) {
                    if (icon != null && label.isEmpty()) {
                        // Icon alone, where the shape is unambiguous. A label
                        // beside a grid icon only repeats what the grid says.
                        OtoIcon(icon, tint = tint, size = 16.dp)
                    } else {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp),
                        ) {
                            if (icon != null) OtoIcon(icon, tint = tint, size = 14.dp)
                            Text(
                                label,
                                style = Oto.type.label,
                                color = tint,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                }
            }
        }
    }
}
