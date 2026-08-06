package net.otozine.player.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import net.otozine.player.ui.theme.Dimens

/**
 * A mood, as colour.
 *
 * The row used to show a track's cover, and where the track had no artwork that
 * meant a generated tile with the first letter of a song title on it -- so a
 * list of moods read as N, N, E, T, N, E, M. A letter from an arbitrary song
 * says nothing about the mood it is standing next to, and seven of them in a
 * column say nothing at all.
 *
 * Colour does the job a cover cannot here. Moods have temperatures everyone
 * already agrees on: calm is cool and pale, tense is hot and tight, brooding is
 * deep. Given a palette you learn "calm is the blue one" in about two glances
 * and stop reading the labels, which is the whole point of a list you scan.
 *
 * Deliberately abstract -- two tones on a diagonal, no glyph. Anything drawn on
 * top would be decoration competing with the name beside it.
 */
@Composable
fun MoodSwatch(mood: String, modifier: Modifier = Modifier, size: Dp = 46.dp) {
    val (from, to) = paletteFor(mood)
    Canvas(
        modifier
            .size(size)
            .clip(RoundedCornerShape(Dimens.radiusThumb)),
    ) {
        drawRect(
            Brush.linearGradient(
                listOf(from, to),
                start = Offset.Zero,
                end = Offset(this.size.width, this.size.height),
            )
        )
    }
}

/**
 * Two tones per mood, warm to cool by temperature.
 *
 * Hand-picked rather than generated from a hash: a hash gives every mood a
 * different colour, which is not the same as giving it the *right* one. Calm
 * landing on orange would be worse than no colour at all, because the reader
 * would have to unlearn it.
 */
private fun paletteFor(mood: String): Pair<Color, Color> = when (mood.lowercase()) {
    // driven, hot
    "energetic" -> Color(0xFFFF9A3C) to Color(0xFFFF5F6D)
    "driving" -> Color(0xFFF75C4E) to Color(0xFFB5273A)
    "intense" -> Color(0xFFFF6B4A) to Color(0xFF9B1B30)
    "aggressive" -> Color(0xFFE23B3B) to Color(0xFF6E0F2A)
    "tense" -> Color(0xFFD9556B) to Color(0xFF6D2440)

    // bright, open
    "uplifting" -> Color(0xFFFFD166) to Color(0xFF60C88A)
    "joyful" -> Color(0xFFFFD75E) to Color(0xFFFF9E42)
    "playful" -> Color(0xFFFFB35C) to Color(0xFFFF7EA8)

    // still, cool
    "calm" -> Color(0xFF9FD3E8) to Color(0xFF5E8FC7)
    "gentle" -> Color(0xFFCFE3F0) to Color(0xFF8FB6D6)
    "dreamy" -> Color(0xFFC3B5F0) to Color(0xFF7E77D6)
    "spacious" -> Color(0xFFBFE6E0) to Color(0xFF6FAFC4)

    // warm, close
    "romantic" -> Color(0xFFF7A8C4) to Color(0xFFD1608F)
    "warm" -> Color(0xFFEFC08A) to Color(0xFFC9835A)
    "acoustic" -> Color(0xFFE3CBA5) to Color(0xFFB08E63)

    // low, deep
    "brooding" -> Color(0xFF6E7FA8) to Color(0xFF33405E)
    "melancholic" -> Color(0xFF8E9BB5) to Color(0xFF4C5A78)
    "sad" -> Color(0xFF8FA0BC) to Color(0xFF54628A)
    "dark" -> Color(0xFF4A4E6B) to Color(0xFF1E2036)
    "dense" -> Color(0xFF5C8A8A) to Color(0xFF29474F)
    "epic" -> Color(0xFF8E7BD6) to Color(0xFF3C2F6B)

    // the bucket for everything unmeasured: deliberately colourless, because it
    // is an absence rather than a character.
    else -> Color(0xFFCDD3DC) to Color(0xFF9BA3B0)
}
