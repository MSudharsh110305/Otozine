package net.otozine.player.playback

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import net.otozine.player.MainActivity

/**
 * The playback service.
 *
 * Everything visible outside the app -- lock screen controls, the notification,
 * Bluetooth headset buttons, Android Auto -- comes from [MediaSession]. Building
 * on MediaSessionService rather than rolling our own foreground service means
 * those surfaces stay correct without us maintaining them.
 *
 * Three behaviours are configured here rather than handled manually, because
 * ExoPlayer's implementations are the ones that handle the awkward cases:
 *
 *  - **Audio focus.** Ducking for navigation prompts, pausing for calls, and
 *    resuming afterwards.
 *  - **Becoming noisy.** Auto-pause when headphones are unplugged or Bluetooth
 *    disconnects. Without this, yanking the cable blasts the track out of the
 *    phone speaker, which is the single most-hated bug in any music player.
 *  - **Gapless playback.** ExoPlayer reads the encoder delay/padding from the
 *    Opus and MP3 streams, so album transitions have no click.
 */
class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null
    private lateinit var loudness: LoudnessController
    private lateinit var outputMonitor: AudioOutputMonitor

    override fun onCreate() {
        super.onCreate()

        val player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()

        loudness = LoudnessController(player)
        outputMonitor = AudioOutputMonitor(this)
        outputMonitor.start()

        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(
                mediaItem: androidx.media3.common.MediaItem?,
                reason: Int,
            ) {
                // Re-apply per-track normalisation on every transition. The gain
                // is carried in the MediaItem's extras by LibraryRepository.
                loudness.applyFor(mediaItem)
            }
        })

        // Tapping the notification should reopen the app, not start a new task.
        val sessionActivity = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        mediaSession = MediaSession.Builder(this, player)
            .setSessionActivity(sessionActivity)
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        mediaSession

    /**
     * Stop the service when the user swipes the app away *and* nothing is
     * playing. If audio is still playing we deliberately keep running -- swiping
     * away the task should not kill the music.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaSession?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        outputMonitor.stop()
        loudness.release()
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        super.onDestroy()
    }

    companion object {
        /** MediaItem extra carrying the track's normalisation gain, in dB. */
        const val EXTRA_REPLAYGAIN_DB = "otozine.replaygain_db"

        /** MediaItem extra carrying the database id, for logging play events. */
        const val EXTRA_TRACK_ID = "otozine.track_id"

        /** MediaItem extra: ms offset where the audio actually starts. */
        const val EXTRA_INTRO_END_MS = "otozine.intro_end_ms"
    }
}
