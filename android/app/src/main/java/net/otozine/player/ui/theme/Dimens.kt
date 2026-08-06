package net.otozine.player.ui.theme

import androidx.compose.ui.unit.dp

/**
 * The sizes the interface is allowed to use.
 *
 * Before this there were eleven corner radii in play (12, 13, 14, 15, 16, 18,
 * 20, 22, 24, 26, 28) and eight vertical paddings. None of it was decided --
 * each value was whatever looked right in the component being written that
 * hour. The result is the thing that reads as "almost right" without being
 * nameable: a chip 4dp shorter than the button beside it, a card whose corner
 * is a degree softer than the one below.
 *
 * Four radii and one control height, chosen so that anything appearing on the
 * same row shares a silhouette.
 */
object Dimens {

    /** Thumbnails and other small square art. */
    val radiusThumb = 12.dp

    /** List rows, tiles, input wells -- anything you scroll past. */
    val radiusRow = 16.dp

    /** Cards and sheets: surfaces that hold other things. */
    val radiusCard = 22.dp

    /** The one big surface on a screen, currently the now-playing art. */
    val radiusHero = 28.dp

    /**
     * One height for every inline control.
     *
     * Segments, chips and round actions all sit on the same rows, so they get
     * the same height or the row has a ragged baseline. 38dp plus the 6dp of
     * padding around it clears the 44pt touch guidance.
     */
    val control = 38.dp

    /** Spacing steps. Everything is one of these; nothing is 7 or 9 or 13. */
    val gapTight = 6.dp
    val gap = 10.dp
    val gapWide = 14.dp
    val gapSection = 18.dp
}
