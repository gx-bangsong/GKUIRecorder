/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.recorder.asr

import android.content.Context
import android.net.Uri
import android.os.PowerManager
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

        // No heavy work while a recording is running. Try again later instead.
        if (AsrRuntime.recordingInProgress) {
            return if (runAttemptCount < MAX_RECORDING_WAITS) Result.retry() else Result.failure()
        }

        setForeground(notifications.transcriptionForeground(id, title, 0, paused = false))

        val descriptor = ModelCatalog.find(modelId)
        if (descriptor == null || models.readyDir(descriptor) == null) {
            repository.fail(uri, null, MESSAGE_MODEL_MISSING)
            return Result.failure()
        }
        val base = repository.start(uri, descriptor.id, descriptor.version, language)

        return withContext(Dispatchers.IO) {
            try {
                val segments = models.useModel(modelId) { _, modelDir ->
                    runPipeline(app, uri, modelDir, language, base, repository, notifications, title)
                }
                repository.complete(uri, base, segments)
                notifications.transcriptionFinished(id, title, succeeded = true)
                Result.success()
            } catch (e: TranscriptionCancelled) {
                repository.cancel(uri, base)
                Result.failure()
            } catch (e: CancellationException) {
                repository.cancel(uri, base)
                throw e
            } catch (e: Throwable) {
                if (isStopped) {
                    repository.cancel(uri, base)
                    return@withContext Result.failure()
                }
                // Only the exception type: no paths, audio or transcript text in messages or logs
                repository.fail(uri, base, "${MESSAGE_FAILED_PREFIX}${e.javaClass.simpleName}")
                notifications.transcriptionFinished(id, title, succeeded = false)
                Result.failure()
            }
        }
    }

    private fun runPipeline(
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
        val source = PcmSource.open(app.contentResolver, Uri.parse(uri))
        val audio = TranscriptionPipeline.Audio(source.durationMs) { onChunk -> source.decode(onChunk) }
        var lastCheckpoint = 0f
        var lastCheckpointAt = 0L

        val control = object : TranscriptionPipeline.Control {
            override fun checkCancelled() {
                if (isStopped) throw TranscriptionCancelled()
            }

            override fun beforeChunk() {
                // Severe heat: pause and keep finished segments. Resume when it cools down.
                var paused = false
                while (AsrRuntime.thermalStatus(app) >= PowerManager.THERMAL_STATUS_SEVERE) {
                    checkCancelled()
                    if (!paused) {
                        paused = true
                        setForegroundAsync(
                            notifications.transcriptionForeground(id, title, lastPercent(), paused = true),
                        )
                    }
                    Thread.sleep(PAUSE_POLL_MS)
                }
            }

            override fun onProgress(fraction: Float, utterances: List<Utterance>) {
                val now = System.currentTimeMillis()
                if (fraction - lastCheckpoint < CHECKPOINT_STEP && now - lastCheckpointAt < CHECKPOINT_MS) {
                    return
                }
                lastCheckpoint = fraction
                lastCheckpointAt = now
                lastFraction = fraction
                val segments = SegmentMerger.merge(utterances).map {
                    TranscriptSegment(it.startMs, it.endMs, it.text)
                }
                repository.checkpoint(uri, base, fraction, segments)
                setProgressAsync(
                    androidx.work.Data.Builder()
                        .putFloat(KEY_PROGRESS, fraction)
                        .build(),
                )
                setForegroundAsync(
                    notifications.transcriptionForeground(id, title, lastPercent(), paused = false),
                )
            }
        }

        val utterances = SpeechEngines.forKind(ModelCatalog.default.engine)
            .let { engine -> TranscriptionPipeline(engine).run(modelDir, options, audio, control) }
        return SegmentMerger.merge(utterances).map { TranscriptSegment(it.startMs, it.endMs, it.text) }
    }

    @Volatile
    private var lastFraction = 0f

    private fun lastPercent(): Int = (lastFraction * 100).toInt()

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
        const val KEY_URI = "uri"
        const val KEY_TITLE = "title"
        const val KEY_MODEL_ID = "model_id"
        const val KEY_LANGUAGE = "language"
        const val KEY_PROGRESS = "progress"

        const val MESSAGE_MODEL_MISSING = "Model is not installed"
        const val MESSAGE_FAILED_PREFIX = "Transcription failed: "

        private const val CHECKPOINT_STEP = 0.02f
        private const val CHECKPOINT_MS = 10_000L
        private const val PAUSE_POLL_MS = 2_000L
        private const val MAX_RECORDING_WAITS = 60
    }
}
