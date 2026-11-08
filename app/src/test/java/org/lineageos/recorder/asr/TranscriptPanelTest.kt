/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.recorder.asr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.lineageos.recorder.asr.transcription.TranscriptEntry
import org.lineageos.recorder.asr.transcription.TranscriptEntryAction
import org.lineageos.recorder.asr.transcription.TranscriptNavigation
import org.lineageos.recorder.asr.transcription.TranscriptPanelState
import org.lineageos.recorder.asr.transcription.TranscriptPanels
import org.lineageos.recorder.asr.transcription.TranscriptRecord
import org.lineageos.recorder.asr.transcription.TranscriptStatus

/**
 * Status and entry decisions of the transcript view. These are the rules the playback screen and
 * the list action follow. Layout and lifecycle wiring are covered on a device, not here.
 */
class TranscriptPanelTest {
    private val uri = "content://media/external/audio/media/42"

    private fun record(
        status: TranscriptStatus,
        text: String = "",
        progress: Float = 0f,
        errorCode: String? = null,
    ) = TranscriptRecord(
        stableRecordingId = TranscriptRecord.stableRecordingId(uri),
        status = status,
        progress = progress,
        text = text,
        segments = emptyList(),
        language = "auto",
        modelId = "sensevoice-small-int8-2024-07-17",
        modelVersion = "2024-07-17",
        runtimeVersion = "sherpa-onnx 1.13.8",
        lastCompletedAudioPositionMs = 0L,
        resumeFromMs = 0L,
        errorCode = errorCode,
        errorMessage = null,
        createdAtMillis = 1L,
        updatedAtMillis = 2L,
    )

    @Test
    fun completedTranscriptShowsItsWholeBody() {
        val panel = TranscriptPanels.of(record(TranscriptStatus.COMPLETED, text = "你好 world"), active = false)
        assertEquals(TranscriptPanelState.COMPLETED, panel.state)
        assertEquals("你好 world", panel.body)
        assertTrue(panel.hasBody)
    }

    @Test
    fun completedWithoutSpeechSaysSoInsteadOfShowingNothing() {
        val panel = TranscriptPanels.of(record(TranscriptStatus.COMPLETED, text = "   "), active = false)
        assertEquals(TranscriptPanelState.COMPLETED_EMPTY, panel.state)
        assertFalse(panel.hasBody)
    }

    @Test
    fun runningShowsProgressAndKeepsTheSegmentsSoFar() {
        val panel = TranscriptPanels.of(
            record(TranscriptStatus.RUNNING, text = "第一段", progress = 0.4f),
            active = true,
        )
        assertEquals(TranscriptPanelState.RUNNING, panel.state)
        assertEquals(40, panel.progressPercent)
        assertEquals("第一段", panel.body)
        assertTrue(panel.canCancel)
        assertFalse(panel.canStart)
    }

    @Test
    fun runningRecordWithoutActiveRunIsInterruptedNotRunning() {
        val panel = TranscriptPanels.of(
            record(TranscriptStatus.RUNNING, text = "已保存", progress = 0.2f),
            active = false,
        )
        assertEquals(TranscriptPanelState.INTERRUPTED, panel.state)
        assertEquals("已保存", panel.body)
        assertFalse(panel.canCancel)
        assertTrue(panel.canStart)
    }

    @Test
    fun queuedWithActiveWorkIsRunning() {
        val panel = TranscriptPanels.of(record(TranscriptStatus.QUEUED), active = true)
        assertEquals(TranscriptPanelState.RUNNING, panel.state)
    }

    @Test
    fun cancelledKeepsItsSavedBody() {
        val panel = TranscriptPanels.of(record(TranscriptStatus.CANCELLED, text = "取消前的文字"), active = false)
        assertEquals(TranscriptPanelState.CANCELLED, panel.state)
        assertEquals("取消前的文字", panel.body)
        assertTrue(panel.canStart)
    }

    @Test
    fun failedKeepsItsSavedBody() {
        val panel = TranscriptPanels.of(record(TranscriptStatus.FAILED, text = "失败前的文字"), active = false)
        assertEquals(TranscriptPanelState.FAILED, panel.state)
        assertEquals("失败前的文字", panel.body)
        assertTrue(panel.canStart)
    }

    @Test
    fun failedWithoutBodyHasNoBody() {
        val panel = TranscriptPanels.of(record(TranscriptStatus.FAILED), active = false)
        assertEquals(TranscriptPanelState.FAILED, panel.state)
        assertFalse(panel.hasBody)
    }

    @Test
    fun corruptRecordIsReportedAndCanBeTranscribedAgain() {
        val panel = TranscriptPanels.of(
            record(TranscriptStatus.FAILED, errorCode = TranscriptRecord.ERROR_CORRUPT),
            active = false,
        )
        assertEquals(TranscriptPanelState.CORRUPT, panel.state)
        assertFalse(panel.hasBody)
        assertTrue(panel.canStart)
    }

    @Test
    fun noRecordOffersOnlyTheStart() {
        val panel = TranscriptPanels.of(null, active = false)
        assertEquals(TranscriptPanelState.NONE, panel.state)
        assertTrue(panel.canStart)
        assertFalse(panel.canCancel)
    }

    @Test
    fun progressIsClampedToAPercentage() {
        val over = TranscriptPanels.of(record(TranscriptStatus.RUNNING, progress = 7f), active = true)
        assertEquals(100, over.progressPercent)
        val under = TranscriptPanels.of(record(TranscriptStatus.RUNNING, progress = -1f), active = true)
        assertEquals(0, under.progressPercent)
    }

    @Test
    fun actionOpensTheViewWheneverAnyRecordOrRunExists() {
        assertEquals(TranscriptEntryAction.START, TranscriptEntry.actionFor(hasRecord = false, active = false))
        assertEquals(TranscriptEntryAction.OPEN_VIEW, TranscriptEntry.actionFor(hasRecord = true, active = false))
        assertEquals(TranscriptEntryAction.OPEN_VIEW, TranscriptEntry.actionFor(hasRecord = false, active = true))
        assertEquals(TranscriptEntryAction.OPEN_VIEW, TranscriptEntry.actionFor(hasRecord = true, active = true))
    }

    @Test
    fun viewingAPartialResultNeverStartsARun() {
        // A cancelled or failed record with saved text is a record: the action opens the view.
        val partialRecords = listOf(
            record(TranscriptStatus.CANCELLED, text = "部分结果"),
            record(TranscriptStatus.FAILED, text = "部分结果"),
            record(TranscriptStatus.RUNNING, text = "部分结果", progress = 0.5f),
        )
        for (partial in partialRecords) {
            assertEquals(
                TranscriptEntryAction.OPEN_VIEW,
                TranscriptEntry.actionFor(hasRecord = true, active = false),
            )
            assertTrue(TranscriptPanels.of(partial, active = false).hasBody)
        }
    }

    @Test
    fun notificationRequestCodeIsStableAndDistinctPerRecording() {
        val a = TranscriptNavigation.requestCode("content://media/external/audio/media/1")
        val b = TranscriptNavigation.requestCode("content://media/external/audio/media/2")
        assertEquals(a, TranscriptNavigation.requestCode("content://media/external/audio/media/1"))
        assertNotEquals(a, b)
    }
}
