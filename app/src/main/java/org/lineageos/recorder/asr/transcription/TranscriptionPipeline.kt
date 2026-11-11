/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.recorder.asr.transcription

import org.lineageos.recorder.asr.engine.RecognitionOptions
import org.lineageos.recorder.asr.engine.SpeechEngine
import org.lineageos.recorder.asr.engine.Utterance
import java.io.File

/**
 * Runs one recording through the engine: decode in chunks, recognize speech segments as they
 * become final, report progress, and check for cancellation between chunks.
 *
 * The audio source is injected so the pipeline can be tested without Android.
 */
class TranscriptionPipeline(
    private val engine: SpeechEngine,
) {
    /** The decoded audio: total duration and a chunked decoder (see [org.lineageos.recorder.asr.audio.PcmSource]). */
    class Audio(
        val durationMs: Long,
        val decode: ((FloatArray, Long) -> Unit) -> Unit,
    )

    interface Control {
        /** Throws to abort the run (for example when the user cancels). */
        fun checkCancelled()

        /**
         * Called before every inference: each audio chunk and the final flush. May block to wait for
         * a recording to stop or for the device to cool down. The model load is not gated here, only
         * checked for cancellation, because native model creation cannot be interrupted.
         */
        fun beforeChunk()

        /** Progress in 0..1 and every utterance recognized so far. */
        fun onProgress(fraction: Float, utterances: List<Utterance>)

        /** Time spent in one inference call, for run metrics only. */
        fun onChunkComputed(computeMs: Long) {}
    }

    fun run(
        modelDir: File,
        options: RecognitionOptions,
        audio: Audio,
        control: Control,
    ): List<Utterance> {
        // The session owns native memory: closed on every path, including failures
        // Cancel before the native model is created. Creation itself is not interruptible.
        control.checkCancelled()
        return engine.openSession(modelDir, options).use { session ->
            val results = ArrayList<Utterance>()
            audio.decode { samples, endMs ->
                control.checkCancelled()
                control.beforeChunk()
                val started = System.nanoTime()
                results += session.acceptAudio(samples)
                control.onChunkComputed((System.nanoTime() - started) / 1_000_000L)
                val fraction = if (audio.durationMs > 0) {
                    (endMs.toFloat() / audio.durationMs).coerceIn(0f, MAX_PROGRESS_BEFORE_FINISH)
                } else {
                    0f
                }
                control.onProgress(fraction, results)
            }
            // The final flush recognizes the remaining segments in one call. It is gated like a chunk.
            // Segments inside that one call cannot be interrupted.
            control.checkCancelled()
            control.beforeChunk()
            val started = System.nanoTime()
            results += session.finish()
            control.onChunkComputed((System.nanoTime() - started) / 1_000_000L)
            control.onProgress(1f, results)
            SegmentMerger.merge(results)
        }
    }

    companion object {
        /** Progress stays below 1 until the final segment is flushed. */
        private const val MAX_PROGRESS_BEFORE_FINISH = 0.99f
    }
}
