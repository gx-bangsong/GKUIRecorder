/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.recorder.asr

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.lineageos.recorder.asr.model.FailureReason
import org.lineageos.recorder.asr.model.InstallListener
import org.lineageos.recorder.asr.model.ModelCatalog
import org.lineageos.recorder.asr.model.ModelFailure
import org.lineageos.recorder.asr.model.ModelInstaller
import org.lineageos.recorder.asr.model.ResumableDownloader
import java.io.IOException

/**
 * Downloads and installs one model. Runs as a foreground job with a notification and a cancel
 * button. Retries recoverable failures with exponential backoff (set by [ModelRepository]).
 * Never writes READY itself: [ModelInstaller] does that after every check has passed.
 */
class ModelDownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val modelId = inputData.getString(ModelRepository.KEY_MODEL_ID) ?: return Result.failure()
        val descriptor = ModelCatalog.find(modelId) ?: return Result.failure()
        val notifications = AsrNotifications(applicationContext)
        setForeground(notifications.downloadForeground(id, descriptor.displayName, 0, indeterminate = true))

        val repository = ModelRepository.get(applicationContext)
        val installer = ModelInstaller(repository.storage, ResumableDownloader())
        val listener = object : InstallListener {
            override fun onDownloading(downloadedBytes: Long, totalBytes: Long, bytesPerSecond: Long) {
                val percent = if (totalBytes > 0) (downloadedBytes * 100 / totalBytes).toInt() else 0
                publish(
                    ModelRepository.PHASE_DOWNLOADING,
                    downloadedBytes,
                    totalBytes,
                    bytesPerSecond,
                    percent,
                    notifications,
                    descriptor.displayName,
                )
            }

            override fun onVerifying() {
                publish(ModelRepository.PHASE_VERIFYING, 0L, -1L, 0L, 100, notifications, descriptor.displayName)
            }

            override fun onInstalling() {
                publish(ModelRepository.PHASE_INSTALLING, 0L, -1L, 0L, 100, notifications, descriptor.displayName)
            }
        }

        return try {
            val version = withContext(Dispatchers.IO) {
                installer.install(descriptor, listener) { isStopped }
            }
            Result.success(workDataOf(ModelRepository.KEY_VERSION to version))
        } catch (e: ModelFailure) {
            failure(e.reason, e.recoverable)
        } catch (e: IOException) {
            failure(FailureReason.NETWORK, recoverable = true)
        } catch (e: IllegalStateException) {
            // For example: the READY marker could not be written
            failure(FailureReason.STORAGE, recoverable = true)
        }
    }

    private fun publish(
        phase: String,
        downloaded: Long,
        total: Long,
        bps: Long,
        percent: Int,
        notifications: AsrNotifications,
        modelName: String,
    ) {
        setProgressAsync(
            Data.Builder()
                .putString(ModelRepository.KEY_PHASE, phase)
                .putLong(ModelRepository.KEY_DOWNLOADED, downloaded)
                .putLong(ModelRepository.KEY_TOTAL, total)
                .putLong(ModelRepository.KEY_BPS, bps)
                .build(),
        )
        if (phase == ModelRepository.PHASE_DOWNLOADING && percent % 5 != 0) {
            return
        }
        setForegroundAsync(
            notifications.downloadForeground(id, modelName, percent, indeterminate = total <= 0),
        )
    }

    private fun failure(reason: FailureReason, recoverable: Boolean): Result {
        if (isStopped) {
            // Cancelled by the user: nothing to report, the partial file is kept for resume
            return Result.failure()
        }
        if (recoverable && runAttemptCount < MAX_ATTEMPTS) {
            return Result.retry()
        }
        return Result.failure(
            workDataOf(
                ModelRepository.KEY_REASON to reason.name,
                ModelRepository.KEY_RECOVERABLE to recoverable,
            ),
        )
    }

    companion object {
        private const val MAX_ATTEMPTS = 5
    }
}
