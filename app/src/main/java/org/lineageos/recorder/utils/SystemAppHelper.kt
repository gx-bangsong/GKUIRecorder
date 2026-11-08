/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.recorder.utils

import android.Manifest
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager

/**
 * Detects whether the app is installed in the system partition and holds the
 * privileged audio capture permission. Call recording needs both: on LineageOS the
 * permission is granted only when the app is in priv-app and whitelisted by a
 * privapp-permissions file in the system image.
 */
object SystemAppHelper {
    fun isSystemApp(context: Context): Boolean {
        val flags = context.applicationInfo.flags
        return (flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0
    }

    fun hasCaptureOutputPermission(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.CAPTURE_AUDIO_OUTPUT) ==
            PackageManager.PERMISSION_GRANTED

    fun isCallRecordingAvailable(context: Context): Boolean =
        isSystemApp(context) && hasCaptureOutputPermission(context)

    /**
     * Turns call recording on the first time the app detects it is eligible.
     * Later changes made by the user are never overridden.
     */
    fun autoEnableCallRecordingOnce(context: Context, preferences: PreferencesManager) {
        if (preferences.callRecordingAutoEnableDone) {
            return
        }
        if (isCallRecordingAvailable(context)) {
            preferences.callRecordingEnabled = true
            preferences.callRecordingAutoEnableDone = true
        }
    }
}
