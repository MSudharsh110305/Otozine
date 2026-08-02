package net.otozine.player.library

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import net.otozine.player.MainActivity
import net.otozine.player.R

/**
 * Keeps a copy running while the app is in the background.
 *
 * Encoding a library takes minutes, and without a foreground service Android is
 * free to freeze the process the moment you switch away -- so a job you left
 * running would silently stop and you would come back to a half-copied drive
 * with no indication of where it stopped. The notification is not decoration;
 * it is what buys the work the right to continue.
 *
 * The service holds no state of its own. The transfer runs in the view model,
 * which pushes progress here to be drawn.
 */
class TransferService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val text = intent?.getStringExtra(EXTRA_TEXT).orEmpty()
        val done = intent?.getIntExtra(EXTRA_DONE, 0) ?: 0
        val total = intent?.getIntExtra(EXTRA_TOTAL, 0) ?: 0

        val notification = build(this, text, done, total)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(ID, notification)
        }
        return START_NOT_STICKY
    }

    companion object {
        private const val ID = 4711
        private const val CHANNEL = "otozine.transfer"
        private const val EXTRA_TEXT = "text"
        private const val EXTRA_DONE = "done"
        private const val EXTRA_TOTAL = "total"

        /** Start, or update the notification of an already running copy. */
        fun update(context: Context, text: String, done: Int, total: Int) {
            val intent = Intent(context, TransferService::class.java)
                .putExtra(EXTRA_TEXT, text)
                .putExtra(EXTRA_DONE, done)
                .putExtra(EXTRA_TOTAL, total)
            runCatching { context.startForegroundService(intent) }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, TransferService::class.java)) }
        }

        private fun build(
            context: Context,
            text: String,
            done: Int,
            total: Int,
        ): Notification {
            val manager = context.getSystemService(NotificationManager::class.java)
            if (manager.getNotificationChannel(CHANNEL) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL, "Copying music", NotificationManager.IMPORTANCE_LOW,
                    ).apply {
                        description = "Progress while music is copied to the drive."
                        setShowBadge(false)
                    },
                )
            }

            // Tapping returns to the app rather than opening a fresh copy of it,
            // so the full progress view is one tap from the notification.
            val open = PendingIntent.getActivity(
                context, 0,
                Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )

            return NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_otozine)
                .setContentTitle(
                    if (total > 0) "Copying to drive — $done of $total" else "Copying to drive",
                )
                .setContentText(text)
                .setProgress(total.coerceAtLeast(1), done, total == 0)
                .setOngoing(true)
                .setSilent(true)
                .setContentIntent(open)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .build()
        }
    }
}
