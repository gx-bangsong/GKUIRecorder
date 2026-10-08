/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.lineageos.recorder.utils

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

/**
 * Hands the downloaded engine APK to the system installer. The system always asks the
 * user to confirm, and the APK is checked to be the expected engine package first.
 */
object EngineInstaller {
    private fun authority(context: Context) = "${context.packageName}.engine.fileprovider"

    /** Returns null if [apk] is the engine package, otherwise an error message. */
    fun verify(context: Context, apk: File): String? {
        val info = context.packageManager.getPackageArchiveInfo(apk.path, 0)
            ?: return "The downloaded file is not a valid APK"
        return if (info.packageName == EngineClient.PACKAGE) {
            null
        } else {
            "The downloaded APK is not the speech engine (${info.packageName})"
        }
    }

    /** Starts the installer. Returns an error message, or null when the installer opened. */
    fun install(context: Context, apk: File): String? {
        verify(context, apk)?.let { return it }
        val uri = FileProvider.getUriForFile(context, authority(context), apk)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            null
        } catch (e: ActivityNotFoundException) {
            e.message
        }
    }
}
