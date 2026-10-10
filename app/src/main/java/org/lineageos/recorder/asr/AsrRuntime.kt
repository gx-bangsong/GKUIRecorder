/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.recorder.asr

import android.content.Context
import android.content.SharedPreferences
import android.os.PowerManager
import org.lineageos.recorder.asr.transcription.KeyValueStore
import org.lineageos.recorder.asr.transcription.TranscriptionRepository

/** Stores transcript records in the app's existing preferences file, under the `transcript:` prefix. */
class SharedPrefsKeyValueStore(private val prefs: SharedPreferences) : KeyValueStore {
    override fun get(key: String): String? = prefs.getString(key, null)

    override fun put(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }

    override fun remove(key: String) {
        prefs.edit().remove(key).apply()
    }
}

/** Small shared helpers for the ASR feature. Nothing here loads native code. */
object AsrRuntime {
    const val PREFS = "preferences"

    /** Set while the recorder service is recording. Background transcription waits for it to stop. */
    @Volatile
    var recordingInProgress: Boolean = false

    fun transcripts(context: Context): TranscriptionRepository {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return TranscriptionRepository(SharedPrefsKeyValueStore(prefs))
    }

    /** Current thermal status (API 29+, always available at the app's minSdk). */
    fun thermalStatus(context: Context): Int =
        context.getSystemService(PowerManager::class.java)?.currentThermalStatus
            ?: PowerManager.THERMAL_STATUS_NONE
}
