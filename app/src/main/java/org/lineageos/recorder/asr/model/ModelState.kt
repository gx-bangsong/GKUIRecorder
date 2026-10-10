/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.recorder.asr.model

/** What the user can see about one model. Derived from the file system, never from a stored boolean. */
sealed interface ModelState {
    data object NotInstalled : ModelState

    data object Queued : ModelState

    data class Downloading(
        val downloadedBytes: Long,
        val totalBytes: Long,
        val bytesPerSecond: Long,
    ) : ModelState

    data object Verifying : ModelState

    data object Installing : ModelState

    /** A verified version is installed and can run transcription. */
    data class Ready(
        val version: String,
        val installedBytes: Long?,
    ) : ModelState

    /** An older verified version is usable, and a newer catalog version is available. */
    data class UpdateAvailable(
        val installedVersion: String,
        val availableVersion: String,
    ) : ModelState

    data class Failed(
        val reason: FailureReason,
        val recoverable: Boolean,
    ) : ModelState
}

enum class FailureReason {
    NETWORK,
    HTTP_STATUS,
    SIZE_MISMATCH,
    CHECKSUM_MISMATCH,
    ARCHIVE_UNSAFE,
    NO_SPACE,
    STORAGE,
    CANCELLED,
    UNKNOWN,
}

/**
 * Transitions the repository is allowed to perform. Keeps the state machine honest: an illegal
 * jump (for example Ready -> Downloading) is a bug and is rejected instead of silently applied.
 */
object ModelStateTransitions {
    fun isAllowed(from: ModelState, to: ModelState): Boolean {
        if (from == to) {
            return true
        }
        return when (from) {
            is ModelState.NotInstalled -> to is ModelState.Queued || to is ModelState.Downloading ||
                to is ModelState.Failed || to is ModelState.Ready
            is ModelState.Queued -> to is ModelState.Downloading || to is ModelState.Failed ||
                to is ModelState.NotInstalled || to is ModelState.Verifying
            is ModelState.Downloading -> to is ModelState.Downloading || to is ModelState.Verifying ||
                to is ModelState.Failed || to is ModelState.NotInstalled
            is ModelState.Verifying -> to is ModelState.Installing || to is ModelState.Failed ||
                to is ModelState.Downloading
            is ModelState.Installing -> to is ModelState.Ready || to is ModelState.Failed ||
                to is ModelState.NotInstalled
            is ModelState.Ready -> to is ModelState.UpdateAvailable || to is ModelState.NotInstalled ||
                to is ModelState.Queued
            is ModelState.UpdateAvailable -> to is ModelState.Ready || to is ModelState.NotInstalled ||
                to is ModelState.Queued || to is ModelState.UpdateAvailable
            is ModelState.Failed -> to is ModelState.Queued || to is ModelState.NotInstalled ||
                to is ModelState.Downloading || to is ModelState.Ready
        }
    }

    /** States in which a model must not be deleted or re-installed under the running work. */
    fun isBusy(state: ModelState): Boolean = when (state) {
        is ModelState.Queued, is ModelState.Downloading, is ModelState.Verifying,
        is ModelState.Installing,
        -> true
        else -> false
    }
}
