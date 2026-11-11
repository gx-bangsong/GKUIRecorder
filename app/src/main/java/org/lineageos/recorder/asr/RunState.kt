/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.recorder.asr

/**
 * The current state of one run, kept in one place so that the notification, the work progress and
 * the stored record never disagree. A progress update must not reset the phase to FULL.
 *
 * Three positions are tracked on purpose:
 * - [processedFraction]: the newest decoded position (what the user sees as progress);
 * - [persistedFraction]: the decoded position last written to storage;
 * - the safe restart point is not here: it is the end of the last fully recognized segment,
 *   stored in the record as lastCompletedAudioPositionMs. Decoded audio that has not been
 *   recognized yet is never a restart point.
 */
class RunState {
    var phase: RunPhase = RunPhase.FULL
        private set

    var processedFraction: Float = 0f
        private set

    var persistedFraction: Float = 0f
        private set

    /** Returns true when the phase actually changed. */
    fun setPhase(next: RunPhase): Boolean {
        if (next == phase) return false
        phase = next
        return true
    }

    fun onProcessed(fraction: Float) {
        processedFraction = fraction.coerceIn(0f, 1f)
    }

    fun onPersisted(fraction: Float) {
        persistedFraction = fraction.coerceIn(0f, 1f)
    }

    /** Whole percent for the notification and the page, from the processed position. */
    fun percent(): Int = (processedFraction * 100f).toInt().coerceIn(0, 100)
}
