/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.recorder.asr.transcription

/** Finds which saved speech segment the playback position is in. */
object SegmentLookup {
    /**
     * Index of the segment that contains [positionMs], or -1 when the position is in a silent gap,
     * before the first segment, after the last one, or the segments are empty. A segment includes
     * its start and excludes its end, so two neighbours never both match one position.
     */
    fun indexAt(segments: List<TranscriptSegment>, positionMs: Long): Int =
        segments.indexOfFirst { positionMs >= it.startMs && positionMs < it.endMs }

    /**
     * The segment to highlight. Inside a segment, the same as [indexAt]. In a silent gap, the last
     * non-empty segment that has already started stays highlighted, so the highlight does not vanish
     * between sentences. Before the first segment (and for an empty list) there is none: -1.
     */
    fun highlightAt(segments: List<TranscriptSegment>, positionMs: Long): Int {
        val exact = indexAt(segments, positionMs)
        if (exact >= 0) return exact
        return segments.indexOfLast { it.endMs > it.startMs && positionMs >= it.startMs }
    }
}
