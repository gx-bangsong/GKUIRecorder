/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.recorder

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.app.ServiceCompat

/**
 * Keeps a recording playing while the app is in the background. It is a foreground service with
 * a media notification (play or pause, stop). The player itself lives in [PlaybackSession].
 * The service stops when the session is closed.
 */
class PlaybackService : Service() {
    private val sessionListener: () -> Unit = { onSessionChanged() }

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.playback_notification_channel),
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
        }
        PlaybackSession.addListener(sessionListener)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Must call startForeground promptly after a foreground start request
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
        )
        when (intent?.action) {
            ACTION_TOGGLE -> PlaybackSession.toggle()
            ACTION_STOP -> PlaybackSession.stop()
        }
        if (!PlaybackSession.isOpen) {
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        PlaybackSession.removeListener(sessionListener)
        super.onDestroy()
    }

    private fun onSessionChanged() {
        if (!PlaybackSession.isOpen) {
            stopSelf()
            return
        }
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val isPlaying = PlaybackSession.isPlaying()
        val name = PlaybackSession.title.substringBeforeLast('.')
        val openIntent = Intent(this, PlaybackActivity::class.java)
            .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(PlaybackActivity.EXTRA_URI, PlaybackSession.uri?.toString())
            .putExtra(PlaybackActivity.EXTRA_TITLE, PlaybackSession.title)
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_play_circle)
            .setContentTitle(name.ifEmpty { getString(R.string.sound_record_default_name) })
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .addAction(
                if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play_arrow,
                getString(if (isPlaying) R.string.pause else R.string.play),
                actionIntent(ACTION_TOGGLE, 1),
            )
            .addAction(R.drawable.ic_stop, getString(R.string.stop), actionIntent(ACTION_STOP, 2))
            .build()
    }

    private fun actionIntent(action: String, requestCode: Int): PendingIntent =
        PendingIntent.getService(
            this,
            requestCode,
            Intent(this, PlaybackService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    companion object {
        private const val CHANNEL_ID = "playback"
        private const val NOTIFICATION_ID = 0x504C

        const val ACTION_TOGGLE = "org.lineageos.recorder.playback.TOGGLE"
        const val ACTION_STOP = "org.lineageos.recorder.playback.STOP"

        /** Starts the service in the foreground. Call only while the app is visible. */
        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context.applicationContext,
                Intent(context, PlaybackService::class.java),
            )
        }
    }
}
