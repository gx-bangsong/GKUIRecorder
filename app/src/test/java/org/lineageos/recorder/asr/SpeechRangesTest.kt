/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.recorder.asr

import org.junit.Assert.assertEquals
import org.junit.Test
import org.lineageos.recorder.asr.transcription.SpeechRange
import org.lineageos.recorder.asr.transcription.SpeechRanges
import org.lineageos.recorder.asr.transcription.TranscriptSegment

class SpeechRangesTest {
    private fun seg(start: Long, end: Long, text: String = "x") = TranscriptSegment(start, end, text)

    @Test
    fun emptyInputGivesNoRanges() {
        assertEquals(emptyList<SpeechRange>(), SpeechRanges.of(emptyList(), 10_000L))
    }

    @Test
    fun nonPositiveDurationGivesNoRanges() {
        val segments = listOf(seg(0, 1_000))
        assertEquals(emptyList<SpeechRange>(), SpeechRanges.of(segments, 0L))
        assertEquals(emptyList<SpeechRange>(), SpeechRanges.of(segments, -5L))
    }

    @Test
    fun blankTextIsDropped() {
        val segments = listOf(seg(0, 1_000, ""), seg(2_000, 3_000, "   "), seg(4_000, 5_000, "ok"))
        assertEquals(listOf(SpeechRange(4_000, 5_000)), SpeechRanges.of(segments, 10_000L))
    }

    @Test
    fun unsortedInputIsSortedByStart() {
        val segments = listOf(seg(5_000, 6_000), seg(1_000, 2_000), seg(3_000, 4_000))
        assertEquals(
            listOf(SpeechRange(1_000, 2_000), SpeechRange(3_000, 4_000), SpeechRange(5_000, 6_000)),
            SpeechRanges.of(segments, 10_000L),
        )
    }

    @Test
    fun overlappingAndTouchingRangesAreMerged() {
        val segments = listOf(seg(1_000, 3_000), seg(2_500, 4_000), seg(4_000, 5_000), seg(7_000, 8_000))
        assertEquals(
            listOf(SpeechRange(1_000, 5_000), SpeechRange(7_000, 8_000)),
            SpeechRanges.of(segments, 10_000L),
        )
    }

    @Test
    fun nestedRangeDoesNotShortenTheOuterOne() {
        val segments = listOf(seg(1_000, 9_000), seg(2_000, 3_000))
        assertEquals(listOf(SpeechRange(1_000, 9_000)), SpeechRanges.of(segments, 10_000L))
    }

    @Test
    fun endPastTheRecordingIsClampedToItsDuration() {
        val segments = listOf(seg(8_000, 15_000))
        assertEquals(listOf(SpeechRange(8_000, 10_000)), SpeechRanges.of(segments, 10_000L))
    }

    @Test
    fun segmentStartingAfterTheEndIsDropped() {
        val segments = listOf(seg(12_000, 13_000), seg(20_000, 30_000))
        assertEquals(emptyList<SpeechRange>(), SpeechRanges.of(segments, 10_000L))
    }

    @Test
    fun negativeStartIsClampedToZero() {
        val segments = listOf(seg(-500, 1_000))
        assertEquals(listOf(SpeechRange(0, 1_000)), SpeechRanges.of(segments, 10_000L))
    }

    @Test
    fun fullyNegativeSegmentIsDropped() {
        val segments = listOf(seg(-2_000, -1_000))
        assertEquals(emptyList<SpeechRange>(), SpeechRanges.of(segments, 10_000L))
    }

    @Test
    fun zeroLengthSegmentIsDropped() {
        val segments = listOf(seg(3_000, 3_000), seg(4_000, 5_000))
        assertEquals(listOf(SpeechRange(4_000, 5_000)), SpeechRanges.of(segments, 10_000L))
    }

    @Test
    fun reversedSegmentIsDroppedNotReinterpreted() {
        val segments = listOf(seg(5_000, 1_000))
        assertEquals(emptyList<SpeechRange>(), SpeechRanges.of(segments, 10_000L))
    }

    @Test
    fun endIsExclusiveSoTouchingSegmentsCoverTheirUnion() {
        // [1000, 2000) and [2000, 3000) together cover [1000, 3000): one range
        val segments = listOf(seg(1_000, 2_000), seg(2_000, 3_000))
        assertEquals(listOf(SpeechRange(1_000, 3_000)), SpeechRanges.of(segments, 10_000L))
    }

    @Test
    fun segmentEndingExactlyAtTheDurationIsKept() {
        val segments = listOf(seg(9_000, 10_000))
        assertEquals(listOf(SpeechRange(9_000, 10_000)), SpeechRanges.of(segments, 10_000L))
    }

    @Test
    fun segmentStartingExactlyAtTheDurationIsDropped() {
        // Exclusive end: [10000, 11000) clamps to [10000, 10000), which has no length
        val segments = listOf(seg(10_000, 11_000))
        assertEquals(emptyList<SpeechRange>(), SpeechRanges.of(segments, 10_000L))
    }
}
