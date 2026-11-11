/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.recorder.asr

import android.content.Context
import android.net.Uri
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.lineageos.recorder.asr.audio.PcmSource
import org.lineageos.recorder.asr.engine.RecognitionOptions
import org.lineageos.recorder.asr.engine.SpeechEngines
import org.lineageos.recorder.asr.engine.Utterance
import org.lineageos.recorder.asr.model.ModelCatalog
import org.lineageos.recorder.asr.transcription.SegmentMerger
import org.lineageos.recorder.asr.transcription.TranscriptSegment
import org.lineageos.recorder.asr.transcription.TranscriptionPipeline
import org.lineageos.recorder.asr.transcription.TranscriptionRepository
import org.lineageos.recorder.asr.transcription.TranscriptRecord
import org.lineageos.recorder.utils.PreferencesManager

/** Thrown from a control callback when the user cancelled the work. */
class TranscriptionCancelled : RuntimeException("Cancelled")

/**
 * Transcribes one recording on device. Audio is read from the recording's content URI and never
 * leaves the device. The original file is only read, never written.
 *
 * Lifecycle: the model is held in use for the whole run and released in `finally`. The recognizer
 * session is closed by [TranscriptionPipeline] in its own `use` block. Progress is checkpointed so
 * finished segments survive a pause, a cancel or a crash.
 */
class TranscriptionWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext
        val uri = inputData.getString(KEY_URI) ?: return Result.failure()
        val title = inputData.getString(KEY_TITLE).orEmpty()
        val modelId = inputData.getString(KEY_MODEL_ID) ?: ModelCatalog.default.id
        val language = inputData.getString(KEY_LANGUAGE) ?: RecognitionOptions.DEFAULT_LANGUAGE
        val repository = AsrRuntime.transcripts(app)
        val models = ModelRepository.get(app)
        val notifications = AsrNotifications(app)
        val tag = TranscriptRecord.stableRecordingId(uri).take(8)
        Log.i(TAG, "start rec=$tag model=$modelId language=$language attempt=$runAttemptCount")

        // No heavy work while a recording is running. Try again later instead.
        if (AsrRuntime.recordingInProgress) {
            Log.i(TAG, "rec=$tag recording in progress, retry")
            return if (runAttemptCount < MAX_RECORDING_WAITS) Result.retry() else Result.failure()
        }

        setForeground(notifications.transcriptionForeground(id, title, 0, RunPhase.FULL))
        Log.i(TAG, "rec=$tag foreground notification set")

        val descriptor = ModelCatalog.find(modelId)
        if (descriptor == null || models.readyDir(descriptor) == null) {
            Log.w(TAG, "rec=$tag model not ready")
            // Only record the failure when there is nothing saved. A redo must not replace a finished
            // transcript with an empty failure just because the model is missing.
            if (repository.get(uri) == null) {
                repository.fail(uri, null, MESSAGE_MODEL_MISSING, errorCode = TranscriptRecord.ERROR_MODEL_MISSING)
            }
            return Result.failure()
        }
        Log.i(TAG, "rec=$tag model ready, saving RUNNING record")
        val base = repository.start(uri, descriptor.id, descriptor.version, language)
        Log.i(TAG, "rec=$tag RUNNING record saved, waiting for recognizer lock")

        return withContext(Dispatchers.IO) {
            try {
                // One recognizer app-wide: wait here (not in the scheduler) until it is free.
                val segments = AsrGlobalLock.acquire(app) { isStopped }?.use {
                    Log.i(TAG, "rec=$tag recognizer lock acquired")
                    models.useModel(modelId) { _, modelDir ->
                        runPipeline(id, app, uri, modelDir, language, base, repository, notifications, title)
                    }
                } ?: throw TranscriptionCancelled()
                // A recording deleted during the run must not get its transcript written back
                if (AsrRuntime.recordingExists(app, uri)) {
                    repository.complete(uri, base, segments)
                }
                // After the save, and never allowed to change it: a notification error must not
                // reach the catch below, which would store the finished transcript as FAILED.
                Log.i(TAG, "rec=$tag finished segments=${segments.size}")
                runCatching { notifications.transcriptionFinished(id, uri, title, succeeded = true) }
                Result.success()
            } catch (e: TranscriptionCancelled) {
                Log.i(TAG, "rec=$tag cancelled")
                persistPartial(app, uri, base, repository) { r, b -> r.cancel(uri, b, latestSegments(), lastFraction) }
                Result.failure()
            } catch (e: CancellationException) {
                persistPartial(app, uri, base, repository) { r, b -> r.cancel(uri, b, latestSegments(), lastFraction) }
                throw e
            } catch (e: Throwable) {
                Log.e(TAG, "rec=$tag failed: ${e.javaClass.name}", e)
                if (isStopped) {
                    persistPartial(app, uri, base, repository) { r, b -> r.cancel(uri, b, latestSegments(), lastFraction) }
                    return@withContext Result.failure()
                }
                // Only the exception type: no paths, audio or transcript text in messages or logs
                persistPartial(app, uri, base, repository) { r, b ->
                    r.fail(uri, b, "${MESSAGE_FAILED_PREFIX}${e.javaClass.simpleName}", latestSegments(), lastFraction)
                }
                runCatching { notifications.transcriptionFinished(id, uri, title, succeeded = false) }
                Result.failure()
            }
        }
    }

    private fun runPipeline(
        workId: java.util.UUID,
        app: Context,
        uri: String,
        modelDir: java.io.File,
        language: String,
        base: TranscriptRecord,
        repository: TranscriptionRepository,
        notifications: AsrNotifications,
        title: String,
    ): List<TranscriptSegment> {
        val preferences = PreferencesManager(app)
        val threads = effectiveThreads(app, preferences.asrThreads)
        val options = RecognitionOptions(language = language, useItn = true, numThreads = threads)
        Log.i(TAG, "opening audio")
        val source = PcmSource.open(app.contentResolver, Uri.parse(uri))
        Log.i(TAG, "audio opened durationMs=${source.durationMs} threads=$threads")
        val audio = TranscriptionPipeline.Audio(source.durationMs) { onChunk -> source.decode(onChunk) }
        val recTag = TranscriptRecord.stableRecordingId(uri).take(8)
        val state = RunState()
        val metrics = RunMetrics()
        var chunkLogged = false
        latestUtterances = emptyList()

        // The one place that publishes the phase and progress. It always reads the current state,
        // so a progress update cannot replace REDUCED or WAITING with a normal-speed notification.
        fun publish() {
            setProgressAsync(
                androidx.work.Data.Builder()
                    .putFloat(KEY_PROGRESS, state.processedFraction)
                    .putString(KEY_PHASE, state.phase.name)
                    .build(),
            )
            setForegroundAsync(
                notifications.transcriptionForeground(workId, title, state.percent(), state.phase),
            )
        }

        val checkpointer = RunCheckpointer(
            repository = repository,
            uri = uri,
            base = base,
            state = state,
            monotonicMs = { SystemClock.elapsedRealtime() },
            canWrite = { AsrRuntime.recordingExists(app, uri) },
            onPublish = { publish() },
        )
        val gate = TranscriptionGate(
            thermalStatus = { AsrRuntime.thermalStatus(app) },
            recordingInProgress = { AsrRuntime.recordingInProgress },
            monotonicMs = { SystemClock.elapsedRealtime() },
            sleepMs = { Thread.sleep(it) },
            checkCancelled = { if (isStopped) throw TranscriptionCancelled() },
            onEnterWait = { next ->
                // Save what is finished now. Waiting can last a long time.
                checkpointer.persistBeforeWait()
                Log.i(TAG, "rec=$recTag wait phase=$next segments=${checkpointer.completedSegments().size}")
            },
            onPhaseChanged = { next ->
                state.setPhase(next)
                Log.i(TAG, "rec=$recTag phase=$next")
                publish()
            },
            metrics = metrics,
        )
        val control = GatedPipelineControl(
            gate = gate,
            metrics = metrics,
            cancelCheck = { if (isStopped) throw TranscriptionCancelled() },
            progress = { fraction, utterances ->
                if (!chunkLogged) {
                    chunkLogged = true
                    Log.i(TAG, "first chunk decoded fraction=$fraction")
                }
                latestUtterances = utterances
                lastFraction = fraction
                checkpointer.onProgress(fraction, utterances)
            },
        )

        Log.i(TAG, "engine run starting")
        try {
            val utterances = SpeechEngines.forKind(ModelCatalog.default.engine)
                .let { engine -> TranscriptionPipeline(engine).run(modelDir, options, audio, control) }
            Log.i(TAG, "engine run returned utterances=${utterances.size}")
            return SegmentMerger.merge(utterances).map { TranscriptSegment(it.startMs, it.endMs, it.text) }
        } finally {
            // Timings only. No audio, text or file names.
            Log.i(TAG, "rec=$recTag pacing ${metrics.summary()}")
        }
    }

    @Volatile
    private var lastFraction = 0f

    /** Latest utterances of this run. Read when a cancel or failure has to be stored. */
    @Volatile
    private var latestUtterances: List<Utterance> = emptyList()

    private fun latestSegments(): List<TranscriptSegment> =
        SegmentMerger.merge(latestUtterances).map { TranscriptSegment(it.startMs, it.endMs, it.text) }

    /** Stores the partial state, unless the recording was deleted in the meantime. */
    private inline fun persistPartial(
        app: Context,
        uri: String,
        base: TranscriptRecord,
        repository: TranscriptionRepository,
        write: (TranscriptionRepository, TranscriptRecord) -> Unit,
    ) {
        if (AsrRuntime.recordingExists(app, uri)) {
            write(repository, base)
        }
    }

    /** Moderate heat: fewer threads for the rest of the run. Severe heat is handled by pausing. */
    private fun effectiveThreads(app: Context, configured: Int): Int {
        val status = AsrRuntime.thermalStatus(app)
        return if (status >= PowerManager.THERMAL_STATUS_MODERATE) {
            RecognitionOptions.MIN_THREADS
        } else {
            configured.coerceIn(RecognitionOptions.MIN_THREADS, RecognitionOptions.MAX_THREADS)
        }
    }

    companion object {
        private const val TAG = "TranscriptionWorker"
        const val KEY_URI = "uri"
        const val KEY_TITLE = "title"
        const val KEY_MODEL_ID = "model_id"
        const val KEY_LANGUAGE = "language"
        const val KEY_PROGRESS = "progress"
        /** [RunPhase] name, shown by the transcript page and the notification. */
        const val KEY_PHASE = "phase"

        const val MESSAGE_MODEL_MISSING = "Model is not installed"
        const val MESSAGE_FAILED_PREFIX = "Transcription failed: "

        private const val MAX_RECORDING_WAITS = 60
    }
}
