package net.otozine.player.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import net.otozine.player.R

/**
 * Two faces, with a strict division of labour taken from the mockup.
 *
 * Zen Maru Gothic (rounded sans) carries everything a human reads as language:
 * titles, artists, body copy. Its rounded terminals are what make the
 * neumorphic surfaces feel soft rather than merely blurred.
 *
 * DM Mono carries everything machine-derived: BPM, musical key, gain, counts,
 * paths, section labels. Always uppercase and letter-spaced. That split is what
 * stops a screen full of analysis numbers reading as clutter -- the eye learns
 * that mono means "measured".
 *
 * Note on Tamil: Zen Maru Gothic has no Tamil glyphs, so Tamil titles fall back
 * to the platform font. That is correct behaviour and looks fine; it just means
 * a Tamil title will not share the rounded character of a Latin one.
 */
val ZenMaru = FontFamily(
    Font(R.font.zen_maru_gothic_regular, FontWeight.Normal),
    Font(R.font.zen_maru_gothic_medium, FontWeight.Medium),
    Font(R.font.zen_maru_gothic_bold, FontWeight.Bold),
)

val DmMono = FontFamily(
    Font(R.font.dm_mono_regular, FontWeight.Normal),
    Font(R.font.dm_mono_medium, FontWeight.Medium),
)

@Immutable
data class OtoTypography(
    /** Screen titles, now-playing track name. */
    val display: TextStyle,
    /** Section headings, sheet titles. */
    val title: TextStyle,
    /** Track titles in lists. */
    val item: TextStyle,
    /** Artist / album lines. */
    val sub: TextStyle,
    /** Body copy in sheets. */
    val body: TextStyle,
    /** Mono section labels: "ANTI-REPEAT QUEUE". Uppercase, tracked out. */
    val label: TextStyle,
    /** Mono data: "144 BPM · 11B · -2.5 dB". */
    val data: TextStyle,
    /** Smallest mono, for pills and captions. */
    val micro: TextStyle,
)

val Typography = OtoTypography(
    display = TextStyle(
        fontFamily = ZenMaru, fontWeight = FontWeight.Bold,
        fontSize = 21.sp, lineHeight = 25.sp, letterSpacing = (-0.02).em,
    ),
    title = TextStyle(
        fontFamily = ZenMaru, fontWeight = FontWeight.Medium,
        fontSize = 17.sp, lineHeight = 21.sp, letterSpacing = (-0.01).em,
    ),
    item = TextStyle(
        fontFamily = ZenMaru, fontWeight = FontWeight.Medium,
        fontSize = 13.5.sp, lineHeight = 17.sp,
    ),
    sub = TextStyle(
        fontFamily = ZenMaru, fontWeight = FontWeight.Normal,
        fontSize = 11.5.sp, lineHeight = 15.sp,
    ),
    body = TextStyle(
        fontFamily = ZenMaru, fontWeight = FontWeight.Normal,
        fontSize = 12.sp, lineHeight = 18.sp,
    ),
    label = TextStyle(
        fontFamily = DmMono, fontWeight = FontWeight.Medium,
        fontSize = 9.sp, lineHeight = 11.sp, letterSpacing = 0.1.em,
    ),
    data = TextStyle(
        fontFamily = DmMono, fontWeight = FontWeight.Normal,
        fontSize = 10.sp, lineHeight = 13.sp, letterSpacing = 0.04.em,
    ),
    micro = TextStyle(
        fontFamily = DmMono, fontWeight = FontWeight.Medium,
        fontSize = 8.5.sp, lineHeight = 10.sp, letterSpacing = 0.09.em,
    ),
)
