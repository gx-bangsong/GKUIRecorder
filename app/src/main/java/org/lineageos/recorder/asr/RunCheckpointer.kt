/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.recorder.asr

import org.lineageos.recorder.asr.engine.Utterance
import org.lineageos.recorder.asr.transcription.SegmentMerger
import org.lineageos.recorder.asr.transcription.TranscriptRecord
import org.lineageos.recorder.asr.transcription.TranscriptSegment
import org.lineageos.recorder.asr.transcription.TranscriptionRepository

/**
 * Writes the finished segments of a run. Two triggers:
 * - [onProgress]: the regular throttled checkpoint, after each chunk;
 * - [persistBeforeWait]: forced, right before a heat or recording wait, so the finished segments
 *   do not depend on the throttle.
 *
 * Only completed segments are written. A decoded position that is not recognized yet is never a
 * restart point: the record's lastCompletedAudioPositionMs is the end of the last completed segment.
 */
class RunCheckpointer(
    private val repository: TranscriptionRepository,
    private val uri: String,
    private val base: TranscriptRecord,
    private val state: RunState,
    private val monotonicMs: () -> Long,
    /** False when the recording is gone. Nothing is written for a deleted recording. */
    private val canWrite: () -> Boolean,
    private val onPublish: () -> Unit,
    private val intervalMs: Long = CHECKPOINT_MS,
    private val fractionStep: Float = CHECKPOINT_STEP,
) {
    private var lastUtterances: List<Utterance> = emptyList()
    private var lastWrittenFraction: Float? = null
    private var lastWrittenAt: Long = 0L

    /** Called after every chunk. Writes at most every [intervalMs] or every [fractionStep]. */
    fun onProgress(fraction: Float, utterances: List<Utterance>) {
        lastUtterances = utterances
        state.onProcessed(fraction)
        val written = lastWrittenFraction
        val now = monotonicMs()
        val due = written == null ||
            fraction - written >= fractionStep ||
            now - lastWrittenAt >= intervalMs
        if (!due) return
        write(fraction, now)
        onPublish()
    }

    /** Forced write before a wait. Saves the newest completed segments, not the throttled ones. */
    fun persistBeforeWait() {
        if (!canWrite()) return
        write(state.processedFraction, monotonicMs())
    }

    /** The completed segments known so far. */
    fun completedSegments(): List<TranscriptSegment> = toSegments(lastUtterances)

    private fun write(fraction: Float, now: Long) {
        repository.checkpoint(uri, base, fraction, completedSegments())
        state.onPersisted(fraction)
        lastWrittenFraction = fraction
        lastWrittenAt = now
    }

    private fun toSegments(utterances: List<Utterance>): List<TranscriptSegment> =
        SegmentMerger.merge(utterances).map { TranscriptSegment(it.startMs, it.endMs, it.text) }

    companion object {
        const val CHECKPOINT_STEP = 0.02f
        const val CHECKPOINT_MS = 10_000L
    }
}
