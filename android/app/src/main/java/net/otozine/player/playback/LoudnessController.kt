package net.otozine.player.playback

import android.media.audiofx.LoudnessEnhancer
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import kotlin.math.pow

/**
 * Applies the per-track normalisation gain the Librarian computed.
 *
 * This is the payoff for measuring EBU R128 loudness at ingest: a downloaded
 * library routinely spans 15+ LU between tracks, and without this every third
 * song either whispers or shouts.
 *
 * The gain is applied at playback rather than baked into the file, which needs
 * two different mechanisms because they pull in opposite directions:
 *
 *  - **Attenuation** (the common case -- most modern masters are louder than our
 *    -14 LUFS target) uses [ExoPlayer.setVolume], which is clean, exact and free.
 *  - **Amplification** (quiet or classical material) cannot use volume at all,
 *    because ExoPlayer's range is capped at 1.0. It needs [LoudnessEnhancer],
 *    a platform audio effect that takes positive gain in millibels.
 *
 * Boost is capped at [MAX_BOOST_DB]. LoudnessEnhancer applies dynamic range
 * compression as it amplifies, and past roughly 15 dB that becomes audible as
 * pumping -- worse than the track simply being quiet.
 */
class LoudnessController(private val player: ExoPlayer) {

    private var enhancer: LoudnessEnhancer? = null
    private var enhancerFailed = false

    /** Read the gain from a MediaItem's extras and apply it. */
    fun applyFor(mediaItem: MediaItem?) {
        val extras = mediaItem?.mediaMetadata?.extras
        val gainDb = extras?.getFloat(PlaybackService.EXTRA_REPLAYGAIN_DB, 0f) ?: 0f
        apply(gainDb)
    }

    fun apply(gainDb: Float) {
        if (!gainDb.isFinite()) {
            reset()
            return
        }

        if (gainDb <= 0f) {
            // dB -> linear amplitude.
            val volume = 10f.pow(gainDb / 20f).coerceIn(0f, 1f)
            player.volume = volume
            setBoost(0f)
            Log.i(TAG, "gain ${fmt(gainDb)} dB -> attenuate, volume=${fmt(volume)}")
        } else {
            player.volume = 1f
            val boost = gainDb.coerceAtMost(MAX_BOOST_DB)
            setBoost(boost)
            val capped = if (boost < gainDb) " (capped from ${fmt(gainDb)})" else ""
            Log.i(TAG, "gain ${fmt(gainDb)} dB -> boost ${fmt(boost)} dB$capped, " +
                "enhancer=${if (enhancer != null) "active" else "unavailable"}")
        }
    }

    private fun fmt(value: Float) = String.format("%.2f", value)

    fun reset() {
        player.volume = 1f
        setBoost(0f)
    }

    private fun setBoost(gainDb: Float) {
        if (enhancerFailed) return

        if (gainDb <= 0f) {
            enhancer?.runCatching { enabled = false }
            return
        }

        val effect = enhancer ?: createEnhancer() ?: return
        runCatching {
            effect.setTargetGain((gainDb * 100).toInt())   // millibels
            effect.enabled = true
            Log.i(TAG, "LoudnessEnhancer on session ${player.audioSessionIdOrUnset()}: " +
                "targetGain=${effect.targetGain} mB, enabled=${effect.enabled}")
        }.onFailure {
            Log.w(TAG, "LoudnessEnhancer rejected gain ${gainDb}dB", it)
        }
    }

    // ExoPlayer.audioSessionId is part of Media3's @UnstableApi surface. We need
    // it because LoudnessEnhancer attaches to an audio session, and there is no
    // stable equivalent.
    @OptIn(UnstableApi::class)
    private fun createEnhancer(): LoudnessEnhancer? {
        val sessionId = player.audioSessionId
        if (sessionId == C.AUDIO_SESSION_ID_UNSET) return null

        return runCatching { LoudnessEnhancer(sessionId) }
            .onSuccess { enhancer = it }
            .onFailure {
                // Some devices and some audio routes (notably certain offloaded
                // Bluetooth paths) refuse the effect. Degrade to attenuation-only
                // rather than crashing playback over a loudness nicety.
                enhancerFailed = true
                Log.w(TAG, "LoudnessEnhancer unavailable; boost disabled", it)
            }
            .getOrNull()
    }

    /** Drop the effect when the audio session changes underneath us. */
    fun onAudioSessionChanged() {
        enhancer?.runCatching { release() }
        enhancer = null
        enhancerFailed = false
    }

    fun release() {
        enhancer?.runCatching { release() }
        enhancer = null
    }

    @OptIn(UnstableApi::class)
    private fun ExoPlayer.audioSessionIdOrUnset(): Int = audioSessionId

    companion object {
        private const val TAG = "OtoZineLoudness"

        /** Past this, LoudnessEnhancer's compression is audible as pumping. */
        const val MAX_BOOST_DB = 15f
    }
}
