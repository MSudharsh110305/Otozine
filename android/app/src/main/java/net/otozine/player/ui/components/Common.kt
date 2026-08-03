package net.otozine.player.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.ripple.rememberRipple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Text
import net.otozine.player.ui.theme.Depth
import net.otozine.player.ui.theme.Oto
import net.otozine.player.ui.theme.artTintFor
import net.otozine.player.ui.theme.neu

/** A raised panel. The workhorse container. */
@Composable
fun NeuCard(
    modifier: Modifier = Modifier,
    depth: Depth = Depth.Raised,
    radius: Dp = 16.dp,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(radius)
    Box(
        modifier
            .neu(depth, shape)
            .let { if (onClick != null) it.clip(shape).clickable(onClick = onClick) else it }
    ) { content() }
}

/** Recessed well: search fields, track grooves, stat panels. */
@Composable
fun NeuWell(
    modifier: Modifier = Modifier,
    radius: Dp = 14.dp,
    deep: Boolean = false,
    content: @Composable () -> Unit,
) {
    Box(modifier.neu(if (deep) Depth.InsetDeep else Depth.Inset, RoundedCornerShape(radius))) {
        content()
    }
}

/**
 * Mono label pill: "ANTI-REPEAT QUEUE", "USB · 12,481".
 * Optionally carries a glowing status dot.
 */
@Composable
fun NeuPill(
    text: String,
    modifier: Modifier = Modifier,
    dot: Color? = null,
    inset: Boolean = true,
    onClick: (() -> Unit)? = null,
) {
    val shape = RoundedCornerShape(50)
    Row(
        modifier
            .neu(if (inset) Depth.Inset else Depth.RaisedSoft, shape)
            .let { if (onClick != null) it.clip(shape).clickable(onClick = onClick) else it }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        if (dot != null) {
            Box(Modifier.size(6.dp).clip(CircleShape).background(dot))
        }
        Text(text, style = Oto.type.micro, color = Oto.colors.ink2, maxLines = 1)
    }
}

/** Section heading: mono label on the left, optional action on the right. */
@Composable
fun SectionHeader(
    label: String,
    modifier: Modifier = Modifier,
    action: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Row(
        modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label.uppercase(), style = Oto.type.label, color = Oto.colors.ink3)
        Spacer(Modifier.weight(1f))
        if (action != null && onAction != null) {
            Text(
                action.uppercase(),
                style = Oto.type.label,
                color = Oto.colors.teal,
                modifier = if (onAction != null) Modifier.clickable(onClick = onAction) else Modifier,
            )
        }
    }
}

/**
 * Cover art, or a generated tile when there is none.
 *
 * Missing artwork is the common case in this library, so the fallback is a
 * designed state rather than a grey box: a tint derived from the track's
 * content hash (stable per track) with a soft diagonal wash and the title's
 * first character. It reads as intentional at thumbnail size.
 */
@Composable
fun ArtTile(
    artKey: String,
    title: String,
    modifier: Modifier = Modifier,
    radius: Dp = 12.dp,
    bitmap: android.graphics.Bitmap? = null,
) {
    val shape = RoundedCornerShape(radius)
    val tint = remember(artKey) { artTintFor(artKey) }

    Box(modifier.clip(shape), contentAlignment = Alignment.Center) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.linearGradient(
                            listOf(tint, tint.copy(alpha = 0.55f))
                        )
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    title.trim().take(1).uppercase(),
                    style = Oto.type.display,
                    color = Color.Black.copy(alpha = 0.28f),
                )
            }
        }
    }
}

/** Mono data strip: "4:09 · 144 BPM · 11B · -2.5 dB". */
@Composable
fun DataStrip(values: List<String>, modifier: Modifier = Modifier) {
    Text(
        values.filter { it.isNotBlank() }.joinToString("  ·  "),
        style = Oto.type.data,
        color = Oto.colors.ink3,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

@Composable
fun VSpace(height: Dp) = Spacer(Modifier.height(height))

@Composable
fun HSpace(width: Dp) = Spacer(Modifier.width(width))
