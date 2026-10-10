/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.recorder.asr

import android.content.Context
import androidx.lifecycle.LiveData
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import org.lineageos.recorder.asr.model.ModelDescriptor
import org.lineageos.recorder.utils.PreferencesManager

/**
 * One transcription per recording. Each recording's work carries a tag built from its URI, so
 * duplicate starts are refused and cancel finds the work even when it is chained after a download.
 */
object TranscriptionScheduler {
    fun tag(uri: String) = "transcribe:$uri"

    /** True while a transcription of [uri] is queued or running. Blocking: call off the main thread. */
    fun isActive(context: Context, uri: String): Boolean =
        WorkManager.getInstance(context).getWorkInfosByTag(tag(uri)).get()
            .any { !it.state.isFinished }

    /** Live state of the transcription work for [uri]. */
    fun liveWork(context: Context, uri: String): LiveData<List<WorkInfo>> =
        WorkManager.getInstance(context).getWorkInfosByTagLiveData(tag(uri))

    /**
     * Starts a transcription. When [downloadFirst] is set the model download is queued first and
     * the transcription runs only after the model is installed.
     */
    fun enqueue(
        context: Context,
        uri: String,
        title: String,
        model: ModelDescriptor,
        downloadFirst: Boolean,
    ) {
        val language = PreferencesManager(context).asrLanguage
        val request: OneTimeWorkRequest = OneTimeWorkRequestBuilder<TranscriptionWorker>()
            .setInputData(
                workDataOf(
                    TranscriptionWorker.KEY_URI to uri,
                    TranscriptionWorker.KEY_TITLE to title,
                    TranscriptionWorker.KEY_MODEL_ID to model.id,
                    TranscriptionWorker.KEY_LANGUAGE to language,
                ),
            )
            .addTag(tag(uri))
            .build()
        val repository = ModelRepository.get(context)
        if (downloadFirst) {
            repository.enqueueDownload(model, afterwards = request)
        } else {
            WorkManager.getInstance(context).enqueueUniqueWork(
                tag(uri),
                ExistingWorkPolicy.KEEP,
                request,
            )
        }
    }

    /** Cancels a queued or running transcription. Finished segments are kept. */
    fun cancel(context: Context, uri: String) {
        WorkManager.getInstance(context).cancelAllWorkByTag(tag(uri))
    }
}
