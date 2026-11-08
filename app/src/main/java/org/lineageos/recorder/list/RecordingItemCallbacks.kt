/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.recorder.list

import org.lineageos.recorder.models.Recording

interface RecordingItemCallbacks {
    fun onPlay(recording: Recording)
    fun onShare(recording: Recording)
    fun onDelete(recording: Recording)
    fun onRename(recording: Recording)
    fun onEdit(recording: Recording)
    fun onTranscribe(recording: Recording)
    fun onMarkers(recording: Recording)
    fun onQuickShare(recording: Recording)
    fun onExport(recording: Recording)
}
