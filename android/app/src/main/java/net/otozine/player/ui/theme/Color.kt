package net.otozine.player.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver

/**
 * Colour tokens, lifted verbatim from the design mockup.
 *
 * Neumorphism does not fit Material's colour model: it has no elevation
 * overlays and only one real surface colour, with depth carried entirely by a
 * paired light/dark shadow. So we carry our own palette rather than bending
 * [androidx.compose.material3.ColorScheme] into a shape it was not built for.
 */
/**
 * How a surface is drawn.
 *
 * Not a colour choice. Neumorphism carries depth with paired light and dark
 * shadows against an opaque page; glass carries it with translucency and a lit
 * edge; neon carries it with emission against near-black. Recolouring one into
 * another produces mud -- soft beige shadows on a black page read as smudges,
 * and a glow on cream reads as a printing fault. So the palette says which
 * language it speaks and the surface modifier obeys.
 */
enum class Finish { SOFT, GLASS, NEON }

@Immutable
data class OtoColors(
    /** Page background. Every surface sits on this. */
    val page: Color,
    /** Raised surface (cards, buttons at rest). */
    val surface: Color,
    /** Recessed surface (wells, track areas, inputs). */
    val sunken: Color,
    /** Light edge of the neumorphic bevel (top-left). */
    val highlight: Color,
    /** Dark edge of the neumorphic bevel (bottom-right). */
    val shadow: Color,
    /** Primary text. */
    val ink: Color,
    /** Secondary text and mono labels. */
    val ink2: Color,
    /** Tertiary text, disabled states. */
    val ink3: Color,
    /** Accent: active state, playing indicator, links. */
    val teal: Color,
    /** Secondary accent. */
    val sky: Color,
    /** Hairline dividers. */
    val line: Color,
    val isDark: Boolean,
    /** Which visual language surfaces are drawn in. */
    val finish: Finish = Finish.SOFT,
    /** Emission colour for NEON, and the lit edge for GLASS. */
    val glow: Color = teal,
)

val PaperColors = OtoColors(
    page = Color(0xFFE8E2D5),
    surface = Color(0xFFF4EFE4),
    sunken = Color(0xFFDED7C7),
    highlight = Color(0xF7FFFEF9),
    shadow = Color(0x4F8C7A5E),
    ink = Color(0xFF3A352C),
    ink2 = Color(0xFF7B7264),
    ink3 = Color(0xFFA79E8E),
    // Deep pine rather than bright teal: an accent that looks printed on the
    // page instead of lit from behind it.
    teal = Color(0xFF3F6B54),
    sky = Color(0xFF8A6A46),
    line = Color(0x2E8C7A5E),
    isDark = false,
)

val InkColors = OtoColors(
    page = Color(0x00000000),
    surface = Color(0xF2332E27),
    sunken = Color(0xFF201C18),
    highlight = Color(0x12FFF6E6),
    shadow = Color(0x99000000),
    ink = Color(0xFFF0E8DA),
    ink2 = Color(0xFFA69B8A),
    ink3 = Color(0xFF7A7163),
    teal = Color(0xFF4FC7C2),
    sky = Color(0xFF7EC2EA),
    line = Color(0x17FFF4E2),
    isDark = true,
)

/**
 * Deep black with electric edges. OLED-friendly and the least tiring at night.
 *
 * True black rather than dark grey: on this phone's OLED an unlit pixel costs
 * nothing, and the contrast is what makes a thin neon edge read as emission
 * rather than as a border.
 */
val DarkNeoColors = OtoColors(
    page = Color(0xFF07070B),
    surface = Color(0xFF101018),
    sunken = Color(0xFF05050A),
    highlight = Color(0x1A7DF9FF),
    shadow = Color(0xCC000000),
    ink = Color(0xFFEAF2FF),
    ink2 = Color(0xFF98A0BC),
    ink3 = Color(0xFF5E6480),
    teal = Color(0xFF4DE8E0),
    sky = Color(0xFFB07CFF),
    line = Color(0x2E7DF9FF),
    isDark = true,
    finish = Finish.NEON,
    glow = Color(0xFF4DE8E0),
)

/** Warm pinks and paper. Softer than Paper, and the only palette with a hue. */
val CherryBlossomColors = OtoColors(
    page = Color(0x00000000),
    surface = Color(0xF7FFF9FB),
    sunken = Color(0xF0EBD8DF),
    highlight = Color(0xFFFFFFFF),
    shadow = Color(0x3DA87288),
    ink = Color(0xFF4A3540),
    ink2 = Color(0xFF97798A),
    ink3 = Color(0xFFBFA3B2),
    teal = Color(0xFFD96A93),
    sky = Color(0xFF9C8AC4),
    line = Color(0x33B07A8E),
    isDark = false,
    finish = Finish.SOFT,
    glow = Color(0xFFD96A93),
)

/**
 * Real glass: light, and genuinely transparent.
 *
 * The page is left fully transparent so the scene painted behind it shows
 * through every panel -- that colour is the whole effect. The dark version this
 * replaces was opaque charcoal with a bright edge, which is a description of
 * glass rather than glass itself.
 *
 * Ink is near-black rather than the mid-grey that would look more delicate.
 * Text sits over a moving field of pastels here, and only a very dark ink holds
 * 4.5:1 against the lightest part of it.
 */
val GlassColors = OtoColors(
    page = Color(0x00000000),
    surface = Color(0x8CFFFFFF),
    sunken = Color(0x3D8FA0C4),
    highlight = Color(0xF2FFFFFF),
    shadow = Color(0x543A4A6B),
    ink = Color(0xFF17203A),
    ink2 = Color(0xFF4B5878),
    ink3 = Color(0xFF7C89A6),
    teal = Color(0xFF00897B),
    sky = Color(0xFF4257C4),
    line = Color(0x99FFFFFF),
    isDark = false,
    finish = Finish.GLASS,
    glow = Color(0xFFFFFFFF),
)

/**
 * The themes offered in settings.
 *
 * Stored by name rather than ordinal, so reordering this list or inserting a
 * theme in the middle cannot silently change what someone is already using.
 */
enum class OtoPalette(
    val label: String,
    val blurb: String,
    val colors: OtoColors,
    /** What is painted behind the interface, if anything. */
    val scene: Scene = Scene.NONE,
) {
    PAPER("Paper", "Warm off-white, soft shadows", PaperColors),
    INK("Ink", "Ink in dark water", InkColors, Scene.INK),
    DARK_NEO("Dark Neo", "True black, electric edges", DarkNeoColors),
    CHERRY("Cherry Blossom", "Falling petals", CherryBlossomColors, Scene.PETALS),
    GLASS("Glass", "Clear panels over colour", GlassColors, Scene.GLASS_LIGHT);

    companion object {
        fun of(name: String?): OtoPalette? = entries.firstOrNull { it.name == name }
    }
}

/**
 * A veil for covering the app behind a modal.
 *
 * Not `page`: themes that paint a scene leave the page transparent so the
 * backdrop shows through, and a scrim built from it would vanish precisely
 * where it is needed. Built from the ink's opposite instead, which every
 * palette defines and which is always the right lightness to hide against.
 */
fun OtoColors.scrim(): Color =
    if (isDark) Color(0xF2000000).compositeOver(surface)
    else Color(0xF7FFFFFF).compositeOver(surface)

/**
 * Fill for a bar that stays put while content scrolls under it.
 *
 * Opaque, always. Translucency here needs a backdrop blur to work, and Compose
 * cannot sample what is painted behind a composable without rendering the
 * background into a layer every frame -- for a scrolling list that is a
 * full-screen readback per frame. Without the blur the list simply shows
 * through, and a half-visible song title behind the controls reads as a
 * rendering fault.
 *
 * Scene themes leave `page` transparent, so the surface is composited over an
 * opaque base drawn from the same palette rather than over nothing.
 */
fun OtoColors.headerFill(): Color =
    if (isDark) surface.compositeOver(Color(0xFF14161C))
    else surface.compositeOver(Color(0xFFF3F1F6))

/**
 * Tints for generated cover art.
 *
 * Most of the library has no artwork -- YouTube rips arrive bare, and online
 * lookup only fills some of the gaps. So a missing cover is the normal case,
 * not an error state, and gets a generated tile keyed off the track's content
 * hash: stable per track, and never the same colour twice in a row on screen.
 */
val ArtTints = listOf(
    Color(0xFFCFE0DE),
    Color(0xFFE6DCC8),
    Color(0xFFD8E2D6),
    Color(0xFFF0DCD6),
    Color(0xFFD6E0EC),
    Color(0xFFE4D8E6),
)

/** Deterministic tint for a track, so a given song always looks the same. */
fun artTintFor(key: String): Color {
    if (key.isEmpty()) return ArtTints[0]
    // Content hashes are hex, so a plain sum spreads well enough across 6 bins.
    val n = key.fold(0) { acc, c -> (acc * 31 + c.code) and 0x7FFFFFFF }
    return ArtTints[n % ArtTints.size]
}
