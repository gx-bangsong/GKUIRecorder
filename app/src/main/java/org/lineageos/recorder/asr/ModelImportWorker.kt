/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.recorder.asr

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.lineageos.recorder.asr.model.FailureReason
import org.lineageos.recorder.asr.model.ImportListener
import org.lineageos.recorder.asr.model.ImportTarget
import org.lineageos.recorder.asr.model.ModelCatalog
import org.lineageos.recorder.asr.model.ModelFailure
import org.lineageos.recorder.asr.model.ModelImporter
import org.lineageos.recorder.asr.model.ModelInstaller
import org.lineageos.recorder.asr.model.ResumableDownloader
import java.io.IOException

/**
 * Imports a local model file (archive or companion) and runs the offline install. Shares the
 * unique work chain with downloads, so the two never run together. It is not retried by itself:
 * a failed import waits for the user, who can pick the file again. The source is only read.
 */
class ModelImportWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val modelId = inputData.getString(ModelRepository.KEY_MODEL_ID) ?: return Result.failure()
        val descriptor = ModelCatalog.find(modelId) ?: return Result.failure()
        val repository = ModelRepository.get(applicationContext)
        val importer = ModelImporter(repository.storage, ModelInstaller(repository.storage, ResumableDownloader()))

        // Duplicate of a verified version: nothing is copied, nothing is replaced
        if (importer.alreadyInstalled(descriptor)) {
            return Result.success(workDataOf(ModelRepository.KEY_ALREADY_INSTALLED to true))
        }
        // An in-use model is never overwritten
        if (repository.isInUse(modelId)) {
            return failure(FailureReason.IN_USE, recoverable = false)
        }
        val target = targetFromInput() ?: return failure(FailureReason.INTERNAL, recoverable = false)
        val uriText = inputData.getString(ModelRepository.KEY_IMPORT_URI)
            ?: return failure(FailureReason.INTERNAL, recoverable = false)

        val notifications = AsrNotifications(applicationContext)
        setForeground(notifications.downloadForeground(id, descriptor.displayName, 0, indeterminate = false))
        val listener = object : ImportListener {
            private var lastPublished = 0L

            override fun onCopying(copiedBytes: Long, totalBytes: Long) {
                // Publish about every 4 MB, not on every buffer
                if (copiedBytes - lastPublished < PUBLISH_STEP_BYTES && copiedBytes != totalBytes) return
                lastPublished = copiedBytes
                publish(ModelRepository.PHASE_COPYING, copiedBytes, totalBytes)
            }

            override fun onVerifying() {
                publish(ModelRepository.PHASE_VERIFYING, 0L, -1L)
            }

            override fun onInstalling() {
                publish(ModelRepository.PHASE_INSTALLING, 0L, -1L)
            }
        }

        return try {
            val version = withContext(Dispatchers.IO) {
                val source = openSource(uriText)
                importer.stage(descriptor, target, source, listener) { isStopped }
                importer.install(descriptor, listener) { isStopped }
            }
            Result.success(workDataOf(ModelRepository.KEY_VERSION to version))
        } catch (e: ModelFailure) {
            val missing = if (e.reason == FailureReason.FILE_MISSING) importer.missing(descriptor) else emptyList()
            failure(e.reason, e.recoverable, missing)
        } catch (e: IOException) {
            failure(FailureReason.STORAGE, recoverable = true)
        } catch (e: IllegalStateException) {
            failure(FailureReason.STORAGE, recoverable = true)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.e(TAG, "Model import ended with ${e.javaClass.name}", e)
            failure(FailureReason.INTERNAL, recoverable = false)
        }
    }

    private fun openSource(uriText: String): java.io.InputStream {
        val stream = try {
            applicationContext.contentResolver.openInputStream(Uri.parse(uriText))
        } catch (e: SecurityException) {
            throw ModelFailure(FailureReason.SOURCE_UNAVAILABLE, recoverable = false, message = "No access")
        } catch (e: IOException) {
            throw ModelFailure(FailureReason.SOURCE_UNAVAILABLE, recoverable = true, message = "Cannot open")
        }
        return stream ?: throw ModelFailure(FailureReason.SOURCE_UNAVAILABLE, recoverable = false, message = "No stream")
    }

    private fun targetFromInput(): ImportTarget? = when (inputData.getString(ModelRepository.KEY_IMPORT_ROLE)) {
        ModelRepository.ROLE_ARCHIVE -> ImportTarget.Archive
        ModelRepository.ROLE_COMPANION -> inputData.getString(ModelRepository.KEY_IMPORT_NAME)
            ?.takeIf { it.isNotBlank() }
            ?.let { ImportTarget.Companion(it) }
        else -> null
    }

    private fun publish(phase: String, copied: Long, total: Long) {
        setProgressAsync(
            Data.Builder()
                .putString(ModelRepository.KEY_PHASE, phase)
                .putLong(ModelRepository.KEY_DOWNLOADED, copied)
                .putLong(ModelRepository.KEY_TOTAL, total)
                .build(),
        )
    }

    private fun failure(reason: FailureReason, recoverable: Boolean, missing: List<String> = emptyList()): Result {
        if (isStopped) {
            // Cancelled by the user: the target copy was already removed, the old model is untouched
            return Result.failure()
        }
        return Result.failure(
            workDataOf(
                ModelRepository.KEY_REASON to reason.name,
                ModelRepository.KEY_RECOVERABLE to recoverable,
                ModelRepository.KEY_MISSING to missing.joinToString(","),
            ),
        )
    }

    companion object {
        private const val TAG = "ModelImportWorker"
        private const val PUBLISH_STEP_BYTES = 4L * 1024 * 1024
    }
}
