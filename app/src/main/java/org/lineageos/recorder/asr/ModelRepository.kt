/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.recorder.asr

import android.content.Context
import androidx.lifecycle.LiveData
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.lineageos.recorder.asr.model.FailureReason
import org.lineageos.recorder.asr.model.ModelCatalog
import org.lineageos.recorder.asr.model.ModelDescriptor
import org.lineageos.recorder.asr.model.ModelState
import org.lineageos.recorder.asr.model.ModelStorage
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** Thrown when a transcription asks for a model that is not installed and ready. */
class ModelNotReadyException(message: String) : IllegalStateException(message)

/**
 * The only entry point the UI and the workers use for models.
 *
 * - Nothing runs at app start. State is read from the file system on demand.
 * - Downloads and installs are WorkManager jobs with one unique name per model, so there is at
 *   most one per model at a time.
 * - A model is "in use" while a transcription runs with it. Delete is refused while it is in use
 *   or while a download is queued or running.
 */
class ModelRepository private constructor(context: Context) {
    private val app = context.applicationContext
    private val workManager: WorkManager by lazy { WorkManager.getInstance(app) }

    val storage = ModelStorage(app.noBackupFilesDir)

    private val inUse = ConcurrentHashMap<String, Int>()

    /** Work name of the download and install chain for a model. */
    fun downloadWorkName(modelId: String) = "model-download-$modelId"

    /** Live work info for the download chain of a model. Drives progress in the UI. */
    fun downloadWork(modelId: String): LiveData<List<WorkInfo>> =
        workManager.getWorkInfosForUniqueWorkLiveData(downloadWorkName(modelId))

    /**
     * What the UI shows. Derived from the disk, plus the latest work info when a job is active.
     * Never reads native code and never touches the network.
     */
    fun resolveState(descriptor: ModelDescriptor, work: WorkInfo?): ModelState {
        val disk = storage.diskState(descriptor)
        if (work != null) {
            when (work.state) {
                WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> return ModelState.Queued
                WorkInfo.State.RUNNING -> return runningState(work.progress)
                WorkInfo.State.FAILED -> {
                    if (disk is ModelState.Ready) return disk
                    val reason = work.outputData.getString(KEY_REASON)
                        ?.let { runCatching { FailureReason.valueOf(it) }.getOrNull() }
                        ?: FailureReason.UNKNOWN
                    return ModelState.Failed(reason, work.outputData.getBoolean(KEY_RECOVERABLE, true))
                }
                else -> Unit
            }
        }
        return disk
    }

    private fun runningState(progress: Data): ModelState = when (progress.getString(KEY_PHASE)) {
        PHASE_VERIFYING -> ModelState.Verifying
        PHASE_INSTALLING -> ModelState.Installing
        PHASE_DOWNLOADING -> ModelState.Downloading(
            downloadedBytes = progress.getLong(KEY_DOWNLOADED, 0L),
            totalBytes = progress.getLong(KEY_TOTAL, -1L),
            bytesPerSecond = progress.getLong(KEY_BPS, 0L),
        )
        else -> ModelState.Queued
    }

    /** True while a download or install of the model is queued or running. */
    fun isDownloadActive(modelId: String): Boolean {
        val infos = workManager.getWorkInfosForUniqueWork(downloadWorkName(modelId)).get()
        return infos.any { !it.state.isFinished }
    }

    /** True when the model is being downloaded or a transcription uses it. */
    fun isBusy(modelId: String): Boolean = (inUse[modelId] ?: 0) > 0 || isDownloadActive(modelId)

    /**
     * Queues the download (and install) of [descriptor]. If [afterwards] is given it runs once the
     * model is ready; it is not run when the download fails or is cancelled.
     */
    fun enqueueDownload(descriptor: ModelDescriptor, afterwards: OneTimeWorkRequest? = null) {
        val download = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
            .setInputData(workDataOf(KEY_MODEL_ID to descriptor.id))
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
            )
            .setBackoffCriteria(
                androidx.work.BackoffPolicy.EXPONENTIAL,
                BACKOFF_SECONDS,
                TimeUnit.SECONDS,
            )
            .build()
        val chain = workManager.beginUniqueWork(
            downloadWorkName(descriptor.id),
            ExistingWorkPolicy.KEEP,
            download,
        )
        // then() returns a new continuation; the chain must be built from its result
        val full = if (afterwards != null) chain.then(afterwards) else chain
        full.enqueue()
    }

    /** Cancels a queued or running download. The partial file stays so the next try resumes it. */
    fun cancelDownload(modelId: String) {
        workManager.cancelUniqueWork(downloadWorkName(modelId))
    }

    /**
     * Deletes every installed version and staging file of a model. Refused (returns false) while
     * the model is busy. Transcript text is never touched.
     */
    suspend fun delete(modelId: String): Boolean = withContext(Dispatchers.IO) {
        synchronized(this@ModelRepository) {
            if (isBusy(modelId)) {
                return@withContext false
            }
            storage.deleteModel(modelId)
            true
        }
    }

    /** Removes interrupted installs of a model. Only when nothing is running for it. */
    suspend fun reconcile(modelId: String) = withContext(Dispatchers.IO) {
        if (!isDownloadActive(modelId)) {
            storage.removeIncompleteVersions(modelId)
        }
    }

    /** The ready version directory of [descriptor], or null if the model cannot be used now. */
    fun readyDir(descriptor: ModelDescriptor): File? = storage.readyVersionDir(descriptor)

    /**
     * Runs [block] with the model directory and holds the model in use for its duration.
     * The model is released in `finally`, even when [block] throws.
     */
    fun <T> useModel(modelId: String, block: (ModelDescriptor, File) -> T): T {
        val descriptor = ModelCatalog.find(modelId)
            ?: throw ModelNotReadyException("Unknown model")
        // Same lock as delete(): the model cannot be removed between the check and the increment
        val dir = synchronized(this) {
            inUse[modelId] = (inUse[modelId] ?: 0) + 1
            readyDir(descriptor) ?: run {
                release(modelId)
                throw ModelNotReadyException("Model is not installed")
            }
        }
        try {
            return block(descriptor, dir)
        } finally {
            release(modelId)
        }
    }

    private fun release(modelId: String) {
        synchronized(this) {
            val left = (inUse[modelId] ?: 1) - 1
            if (left <= 0) inUse.remove(modelId) else inUse[modelId] = left
        }
    }

    companion object {
        const val KEY_MODEL_ID = "model_id"
        const val KEY_VERSION = "version"
        const val KEY_REASON = "reason"
        const val KEY_RECOVERABLE = "recoverable"

        const val KEY_PHASE = "phase"
        const val KEY_DOWNLOADED = "downloaded"
        const val KEY_TOTAL = "total"
        const val KEY_BPS = "bps"

        const val PHASE_DOWNLOADING = "downloading"
        const val PHASE_VERIFYING = "verifying"
        const val PHASE_INSTALLING = "installing"

        private const val BACKOFF_SECONDS = 30L

        @Volatile
        private var instance: ModelRepository? = null

        fun get(context: Context): ModelRepository =
            instance ?: synchronized(this) {
                instance ?: ModelRepository(context).also { instance = it }
            }
    }
}
