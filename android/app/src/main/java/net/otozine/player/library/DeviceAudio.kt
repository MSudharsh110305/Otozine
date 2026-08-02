package net.otozine.player.library

import android.content.Context
import android.provider.MediaStore
import android.util.Log

/**
 * Audio already on the phone, read through MediaStore.
 *
 * These tracks have never been through the Librarian, so they carry no
 * analysis: no loudness measurement, no tempo, no key. Two consequences, both
 * worth being explicit about rather than hiding:
 *
 *  - They play at whatever level they were mastered at, so volume jumps between
 *    them and the normalised library.
 *  - The queue engine still sequences them -- cooldown and transition blocking
 *    need no measurements -- but the parts that do need them (similarity, key
 *    matching, tempo steps) contribute nothing rather than being faked.
 *
 * They are identified by negative ids so they can never collide with a library
 * row, and so anything downstream can tell the two apart without a flag.
 */
object DeviceAudio {

    private const val TAG = "OtoZineDevice"

    /** Ignore very short files -- ringtones, notification blips, voice memos. */
    private const val MIN_DURATION_MS = 45_000L

    fun scan(context: Context, limit: Int = 2000): List<Track> {
        val out = ArrayList<Track>()
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.COMPOSER,
            MediaStore.Audio.Media.YEAR,
            MediaStore.Audio.Media.DURATION,
        )

        try {
            context.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection,
                "${MediaStore.Audio.Media.IS_MUSIC} != 0 AND " +
                    "${MediaStore.Audio.Media.DURATION} >= ?",
                arrayOf(MIN_DURATION_MS.toString()),
                "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC",
            )?.use { c ->
                val idIx = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val titleIx = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artistIx = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val albumIx = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                val composerIx = c.getColumnIndexOrThrow(MediaStore.Audio.Media.COMPOSER)
                val yearIx = c.getColumnIndexOrThrow(MediaStore.Audio.Media.YEAR)
                val durationIx = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)

                while (c.moveToNext() && out.size < limit) {
                    val mediaId = c.getLong(idIx)
                    // A MediaStore content URI rather than the raw DATA path.
                    // Under scoped storage the filesystem path is not reliably
                    // openable even with permission, while the content URI
                    // always is -- and DATA is deprecated for exactly this.
                    val uri = android.content.ContentUris.withAppendedId(
                        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, mediaId,
                    ).toString()

                    out += Track(
                        // Negative ids keep device tracks distinguishable from
                        // library rows at a glance and prevent id collisions.
                        id = -(mediaId + 1),
                        contentHash = "device:$mediaId",
                        title = c.getString(titleIx)?.takeIf { it.isNotBlank() }?.let { NameParser.stripSiteJunk(it) },
                        artist = c.getString(artistIx)?.takeIf {
                            it.isNotBlank() && it != "<unknown>"
                        },
                        composer = c.getString(composerIx)?.takeIf { it.isNotBlank() },
                        album = c.getString(albumIx)?.takeIf {
                            it.isNotBlank() && it != "<unknown>"
                        },
                        year = c.getInt(yearIx).takeIf { it in 1900..2100 },
                        language = null,
                        durationMs = c.getLong(durationIx),
                        // Playable content URI. isDevice tells the rest of the
                        // app not to expect a drive-relative path here.
                        opusPath = uri,
                        artPath = null,
                        bpm = null,
                        keyCamelot = null,
                        energy = 0.5f,
                        danceability = 0.5f,
                        // No measurement, so no gain. Playing at unity is the
                        // honest choice; inventing a correction would be worse.
                        replayGainDb = 0f,
                        introEndMs = 0L,
                        outroStartMs = 0L,
                        hookStartMs = 0L,
                    )
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "MediaStore scan failed", e)
        }

        Log.i(TAG, "found ${out.size} device tracks")
        return out
    }
}

/** True for tracks read from the phone rather than the curated library. */
val Track.isDevice: Boolean get() = id < 0

/** True when the track has been through the Librarian and can be sequenced. */
/**
 * Whether the track has been measured.
 *
 * Phone tracks used to be excluded by definition, which made sense only while
 * they could not be analysed at all. Now that they are measured into app data,
 * excluding them would report a fully analysed phone library as having nothing
 * measured, and would hide mood sessions that do in fact have tracks to offer.
 */
val Track.isAnalysed: Boolean get() = bpm != null
