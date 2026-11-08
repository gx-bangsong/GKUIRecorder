/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.recorder.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.MediaRecorder
import android.telephony.TelephonyManager
import android.util.Log
import org.lineageos.recorder.R
import org.lineageos.recorder.utils.FileNameTemplate
import org.lineageos.recorder.utils.PreferencesManager
import org.lineageos.recorder.utils.SystemAppHelper
import java.time.LocalDateTime

/**
 * Starts recording when a call is answered (OFFHOOK) and stops it when the phone is idle
 * again. Only active when call recording is enabled and the app is a privileged system
 * app holding CAPTURE_AUDIO_OUTPUT. The recording uses the VOICE_CALL audio source.
 */
class CallRecordingReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) {
            return
        }
        when (intent.getStringExtra(TelephonyManager.EXTRA_STATE)) {
            TelephonyManager.EXTRA_STATE_OFFHOOK -> startForCall(context)
            TelephonyManager.EXTRA_STATE_IDLE -> stopForCall(context)
        }
    }

    private fun startForCall(context: Context) {
        val preferences = PreferencesManager(context)
        if (!preferences.callRecordingEnabled ||
            !SystemAppHelper.isCallRecordingAvailable(context)
        ) {
            return
        }

        val name = FileNameTemplate.render(
            template = preferences.fileNameTemplate,
            now = LocalDateTime.now(),
            location = null,
            fallbackName = context.getString(R.string.call_recording_default_name),
        )
        val intent = Intent(context, SoundRecorderService::class.java)
            .setAction(SoundRecorderService.ACTION_START)
            // The service fills in the extension, depending on the recording quality
            .putExtra(SoundRecorderService.EXTRA_FILE_NAME, "$name.%1\$s")
            .putExtra(SoundRecorderService.EXTRA_AUDIO_SOURCE, MediaRecorder.AudioSource.VOICE_CALL)
        try {
            context.startForegroundService(intent)
            preferences.callRecordingStartedByCall = true
        } catch (e: IllegalStateException) {
            // The system refused a background foreground-service start
            Log.e(TAG, "Cannot start call recording", e)
        }
    }

    private fun stopForCall(context: Context) {
        val preferences = PreferencesManager(context)
        if (!preferences.callRecordingStartedByCall) {
            return
        }
        preferences.callRecordingStartedByCall = false
        context.startService(
            Intent(context, SoundRecorderService::class.java)
                .setAction(SoundRecorderService.ACTION_STOP)
        )
    }

    companion object {
        private const val TAG = "CallRecordingReceiver"
    }
}
