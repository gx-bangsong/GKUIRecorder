/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.recorder.asr

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.work.ForegroundInfo
import androidx.work.WorkManager
import org.lineageos.recorder.R
import java.util.UUID

/** Notifications for model downloads and long transcriptions. */
class AsrNotifications(private val context: Context) {
    private val manager = context.getSystemService(NotificationManager::class.java)

    init {
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.asr_channel_name),
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
        }
    }

    fun downloadForeground(workId: UUID, modelName: String, percent: Int, indeterminate: Boolean): ForegroundInfo =
        ForegroundInfo(
            notificationId(workId),
            build(
                workId = workId,
                title = context.getString(R.string.asr_download_notification_title, modelName),
                text = context.getString(R.string.asr_download_notification_text),
                percent = percent,
                indeterminate = indeterminate,
            ),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )

    fun transcriptionForeground(workId: UUID, title: String, percent: Int, paused: Boolean): ForegroundInfo =
        ForegroundInfo(
            notificationId(workId),
            build(
                workId = workId,
                title = context.getString(R.string.asr_transcribe_notification_title, title),
                text = context.getString(
                    if (paused) R.string.asr_transcribe_paused else R.string.asr_transcribe_notification_text,
                ),
                percent = percent,
                indeterminate = false,
            ),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )

    /** Result notification after a transcription ends. Not ongoing, so the user can dismiss it. */
    fun transcriptionFinished(workId: UUID, title: String, succeeded: Boolean) {
        val text = context.getString(
            if (succeeded) R.string.asr_transcribe_done else R.string.asr_transcribe_failed_notification,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle(context.getString(R.string.asr_transcribe_notification_title, title))
            .setContentText(text)
            .setAutoCancel(true)
            .build()
        manager.notify(notificationId(workId) + 1, notification)
    }

    private fun build(
        workId: UUID,
        title: String,
        text: String,
        percent: Int,
        indeterminate: Boolean,
    ): Notification = NotificationCompat.Builder(context, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_mic)
        .setContentTitle(title)
        .setContentText(text)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setProgress(100, percent.coerceIn(0, 100), indeterminate)
        .addAction(
            0,
            context.getString(android.R.string.cancel),
            WorkManager.getInstance(context).createCancelPendingIntent(workId),
        )
        .build()

    private fun notificationId(workId: UUID): Int = 0x4153 + (workId.hashCode() and 0xFFFF) * 2

    companion object {
        const val CHANNEL_ID = "offline_asr"
    }
}
