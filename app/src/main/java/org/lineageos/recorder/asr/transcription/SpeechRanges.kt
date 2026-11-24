/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.recorder.asr.transcription

/**
 * A stretch of a recording that has speech, in milliseconds from its start. The interval is half-open,
 * `[startMs, endMs)`: [endMs] is exclusive, the same as [org.lineageos.recorder.asr.transcription.SegmentLookup]
 * and [TranscriptMerge.prefix]. A range has [endMs] > [startMs].
 */
data class SpeechRange(val startMs: Long, val endMs: Long)

/**
 * Turns saved transcript segments into the speech ranges drawn on the seek bar. These come only
 * from the timed segments the recognizer saved. Nothing is inferred from loudness.
 *
 * Kept apart from manual markers ([org.lineageos.recorder.models.MarkerType]): they are a different thing
 * and are drawn in a different way.
 */
object SpeechRanges {
    /**
     * The ranges for a recording of [durationMs]. Empty when the duration is not positive, when no
     * segment has text, or when nothing is left inside the recording.
     *
     * Rules, in order: segments with blank text are dropped; each segment is clamped to
     * `0..durationMs`; a segment with no length left after clamping is dropped (this covers a
     * negative or reversed span and one that starts after the end); the rest are sorted by start,
     * and overlapping ranges are merged. Because [SpeechRange.endMs] is exclusive, `[a, b)` and
     * `[b, c)` cover the same time as `[a, c)`, so touching ranges are merged too.
     */
    fun of(segments: List<TranscriptSegment>, durationMs: Long): List<SpeechRange> {
        if (durationMs <= 0L) return emptyList()
        val clamped = segments
            .asSequence()
            .filter { it.text.isNotBlank() }
            .map {
                SpeechRange(
                    startMs = it.startMs.coerceIn(0L, durationMs),
                    endMs = it.endMs.coerceIn(0L, durationMs),
                )
            }
            .filter { it.endMs > it.startMs }
            .sortedBy { it.startMs }
            .toList()

        val merged = mutableListOf<SpeechRange>()
        for (range in clamped) {
            val last = merged.lastOrNull()
            if (last != null && range.startMs <= last.endMs) {
                merged[merged.lastIndex] = last.copy(endMs = maxOf(last.endMs, range.endMs))
            } else {
                merged += range
            }
        }
        return merged
    }
}
