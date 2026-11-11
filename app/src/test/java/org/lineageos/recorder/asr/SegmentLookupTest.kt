/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.recorder.asr

import org.junit.Assert.assertEquals
import org.junit.Test
import org.lineageos.recorder.asr.transcription.SegmentLookup
import org.lineageos.recorder.asr.transcription.TranscriptSegment

class SegmentLookupTest {
    private val segments = listOf(
        TranscriptSegment(1_000L, 2_000L, "a"),
        TranscriptSegment(3_000L, 4_500L, "b"),
        TranscriptSegment(6_000L, 6_000L, "zero-length"),
        TranscriptSegment(7_000L, 8_000L, "c"),
    )

    @Test
    fun emptyListHasNoSegment() {
        assertEquals(-1, SegmentLookup.indexAt(emptyList(), 1_500L))
    }

    @Test
    fun positionInsideSegmentFindsIt() {
        assertEquals(0, SegmentLookup.indexAt(segments, 1_500L))
        assertEquals(1, SegmentLookup.indexAt(segments, 4_000L))
    }

    @Test
    fun startIsInclusiveEndIsExclusive() {
        assertEquals(0, SegmentLookup.indexAt(segments, 1_000L))
        assertEquals(-1, SegmentLookup.indexAt(segments, 2_000L))
        assertEquals(1, SegmentLookup.indexAt(segments, 3_000L))
    }

    @Test
    fun silentGapsAndEdgesHaveNoSegment() {
        assertEquals(-1, SegmentLookup.indexAt(segments, 0L))
        assertEquals(-1, SegmentLookup.indexAt(segments, 2_500L))
        assertEquals(-1, SegmentLookup.indexAt(segments, 5_000L))
        assertEquals(-1, SegmentLookup.indexAt(segments, 8_500L))
    }

    @Test
    fun zeroLengthSegmentNeverMatches() {
        assertEquals(-1, SegmentLookup.indexAt(segments, 6_000L))
    }

    @Test
    fun resultIsStableAcrossRepeatedCalls() {
        // Seeking back and forth must give the same answer for the same position
        repeat(3) {
            assertEquals(3, SegmentLookup.indexAt(segments, 7_500L))
            assertEquals(0, SegmentLookup.indexAt(segments, 1_000L))
        }
    }
}
