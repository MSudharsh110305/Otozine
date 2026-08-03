package net.otozine.player.library

/**
 * One row of the Librarian's `tracks` table.
 *
 * This mirrors `librarian/otozine/schema.sql`. The Python side is the only
 * writer and the only migrator; the player reads and never alters the schema.
 * Keep the two in sync -- [LibraryRepository.SCHEMA_VERSION] is checked at open
 * time so a mismatch fails loudly instead of silently returning nulls.
 */
data class Track(
    val id: Long,
    val contentHash: String,
    val title: String?,
    val artist: String?,
    val composer: String?,
    val album: String?,
    val year: Int?,
    val language: String?,
    val durationMs: Long,

    /** Drive-relative path to the Opus copy, e.g. `audio/opus/ab/<hash>.opus`. */
    val opusPath: String?,
    val artPath: String?,

    val bpm: Float?,
    val keyCamelot: String?,

    /** Perceived intensity, 0..1. Measured by the DSP stage. */
    val energy: Float,

    /** Rhythmic regularity, 0..1. Measured by the DSP stage. */
    val danceability: Float,

    /** dB to apply at playback so this track matches the target loudness. */
    val replayGainDb: Float,

    /** Where the audio actually starts, past any leading dead air. */
    val introEndMs: Long,

    /** Start of the trailing fade or silence. */
    val outroStartMs: Long,

    /** Best 30 s preview offset, for browse scrubbing. */
    val hookStartMs: Long,
) {
    val displayTitle: String get() = title ?: "(untitled)"

    /**
     * What to show as the artist. For Tamil film music the composer is the
     * headline credit and is often the only name we recovered, so fall back to
     * it rather than showing nothing.
     */
    val displayArtist: String get() = artist ?: composer ?: "Unknown artist"

    /**
     * Identity for "is this the same song as that one".
     *
     * Title and length rather than content hash, because the two sides hash
     * differently -- the Librarian uses blake3 on a PC and the phone SHA-256 --
     * so the same audio on the drive and on the phone never matches by hash.
     *
     * This rule was written out separately in the header count, the search
     * results and the transfer's duplicate check, and the count and the sound
     * map each shipped with it missing. One definition, used everywhere.
     */
    val dedupeKey: String
        get() = displayTitle.lowercase().replace(Regex("[^a-z0-9]+"), " ").trim() +
            "|" + (durationMs / 2000)
}
