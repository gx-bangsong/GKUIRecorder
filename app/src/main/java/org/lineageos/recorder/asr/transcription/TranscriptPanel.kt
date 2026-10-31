/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.recorder.asr.transcription

/** What the transcript view shows as its status line. The body text is separate. */
enum class TranscriptPanelState {
    /** No transcript exists for the recording. */
    NONE,

    /** Recognition is queued or running. */
    RUNNING,

    /** The user cancelled. Saved text, if any, is shown. */
    CANCELLED,

    /** The run failed. Saved text, if any, is shown. */
    FAILED,

    /** The run was running when the process died. Saved text, if any, is shown. */
    INTERRUPTED,

    /** Finished with text. */
    COMPLETED,

    /** Finished, but no speech was recognized. */
    COMPLETED_EMPTY,

    /** The stored file was damaged and has been set aside. */
    CORRUPT,
}

/**
 * The view model of the transcript tab. [body] is whatever text is stored, and it is kept for every
 * state that has one. The status never replaces the body.
 */
data class TranscriptPanel(
    val state: TranscriptPanelState,
    val progressPercent: Int,
    val body: String,
    /** The user may start a new run (first transcription, redo, or re-transcribe after damage). */
    val canStart: Boolean,
    /** The user may cancel a run that is still active. */
    val canCancel: Boolean,
) {
    val hasBody: Boolean get() = body.isNotBlank()
}

object TranscriptPanels {
    /**
     * Maps the stored record and whether a run is active right now to the panel. [active] comes
     * from the work scheduler. A RUNNING record without an active run is an interrupted run.
     */
    fun of(record: TranscriptRecord?, active: Boolean): TranscriptPanel {
        if (record == null) {
            return TranscriptPanel(TranscriptPanelState.NONE, 0, "", canStart = true, canCancel = false)
        }
        if (record.errorCode == TranscriptRecord.ERROR_CORRUPT) {
            return TranscriptPanel(TranscriptPanelState.CORRUPT, 0, "", canStart = true, canCancel = false)
        }
        val percent = (record.progress * 100f).toInt().coerceIn(0, 100)
        val body = record.text
        return when (record.status) {
            TranscriptStatus.QUEUED, TranscriptStatus.RUNNING -> if (active) {
                TranscriptPanel(TranscriptPanelState.RUNNING, percent, body, canStart = false, canCancel = true)
            } else {
                TranscriptPanel(TranscriptPanelState.INTERRUPTED, percent, body, canStart = true, canCancel = false)
            }
            TranscriptStatus.CANCELLED ->
                TranscriptPanel(TranscriptPanelState.CANCELLED, percent, body, canStart = true, canCancel = false)
            TranscriptStatus.FAILED ->
                TranscriptPanel(TranscriptPanelState.FAILED, percent, body, canStart = true, canCancel = false)
            TranscriptStatus.COMPLETED -> if (body.isBlank()) {
                TranscriptPanel(TranscriptPanelState.COMPLETED_EMPTY, 100, "", canStart = true, canCancel = false)
            } else {
                TranscriptPanel(TranscriptPanelState.COMPLETED, 100, body, canStart = true, canCancel = false)
            }
        }
    }
}

/** What the transcribe action on a recording does. Viewing never starts a new run. */
enum class TranscriptEntryAction {
    /** Open the transcript view. Used whenever a transcript exists or a run is active. */
    OPEN_VIEW,

    /** No transcript yet: start the first run (after the model consent). */
    START,
}

object TranscriptEntry {
    fun actionFor(hasRecord: Boolean, active: Boolean): TranscriptEntryAction =
        if (hasRecord || active) TranscriptEntryAction.OPEN_VIEW else TranscriptEntryAction.START
}

/** Per-recording identity for result notifications, so each notification opens its own recording. */
object TranscriptNavigation {
    /**
     * Distinct per recording. PendingIntent compares intents without their extras, so this request
     * code is what keeps two notifications from sharing one PendingIntent.
     */
    fun requestCode(uri: String): Int = TranscriptRecord.stableRecordingId(uri).hashCode()
}
