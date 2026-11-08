/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.recorder.utils

import android.content.Context
import android.net.Uri
import org.lineageos.recorder.asr.engine.RecognitionOptions
import org.lineageos.recorder.models.Marker

class PreferencesManager(context: Context) {
    private val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var recordInHighQuality: Boolean
        get() = preferences.getInt(PREF_RECORDING_QUALITY, 1) == 1
        set(value) {
            preferences.edit()
                .putInt(PREF_RECORDING_QUALITY, if (value) 1 else 0)
                .apply()
        }

    var tagWithLocation: Boolean
        get() = preferences.getBoolean(PREF_TAG_WITH_LOCATION, false)
        set(tagWithLocation) {
            preferences.edit()
                .putBoolean(PREF_TAG_WITH_LOCATION, tagWithLocation)
                .apply()
        }

    var onboardSettingsCounter: Int
        get() = preferences.getInt(PREF_ONBOARD_SETTINGS_COUNTER, 0)
        set(value) {
            preferences.edit()
                .putInt(PREF_ONBOARD_SETTINGS_COUNTER, value)
                .apply()
        }

    var onboardListCounter: Int
        get() = preferences.getInt(PREF_ONBOARD_SOUND_LIST_COUNTER, 0)
        set(value) {
            preferences.edit()
                .putInt(PREF_ONBOARD_SOUND_LIST_COUNTER, value)
                .apply()
        }

    var lastItemUri: Uri?
        get() {
            val uriStr = preferences.getString(PREF_LAST_SOUND, null)
            return if (uriStr == null) null else Uri.parse(uriStr)
        }
        set(value) {
            preferences.edit()
                .putString(PREF_LAST_SOUND, value?.toString())
                .apply()
        }

    /** Template for new recording names, see [FileNameTemplate]. */
    var fileNameTemplate: String
        get() = preferences.getString(PREF_FILENAME_TEMPLATE, null)
            ?.takeIf { it.isNotBlank() }
            ?: FileNameTemplate.DEFAULT
        set(value) {
            preferences.edit()
                .putString(PREF_FILENAME_TEMPLATE, value.trim().ifBlank { FileNameTemplate.DEFAULT })
                .apply()
        }

    /** Sub folder under Recordings/ (Android 12+) or Music/ (older) for new recordings. */
    var storageFolder: String
        get() = preferences.getString(PREF_STORAGE_FOLDER, null)
            ?.let { FileNameTemplate.sanitize(it) }
            ?.takeIf { it.isNotBlank() }
            ?: DEFAULT_STORAGE_FOLDER
        set(value) {
            preferences.edit()
                .putString(
                    PREF_STORAGE_FOLDER,
                    FileNameTemplate.sanitize(value).ifBlank { DEFAULT_STORAGE_FOLDER },
                )
                .apply()
        }

    /** Recognition language for the offline model: auto, zh, en, yue, ja or ko. */
    var asrLanguage: String
        get() = preferences.getString(PREF_ASR_LANGUAGE, RecognitionOptions.DEFAULT_LANGUAGE)
            ?.takeIf { it in RecognitionOptions.SUPPORTED_LANGUAGES }
            ?: RecognitionOptions.DEFAULT_LANGUAGE
        set(value) {
            require(value in RecognitionOptions.SUPPORTED_LANGUAGES)
            preferences.edit().putString(PREF_ASR_LANGUAGE, value).apply()
        }

    /** CPU threads for the offline model (1 to 4). Lower when the phone is warm. */
    var asrThreads: Int
        get() = preferences.getInt(PREF_ASR_THREADS, RecognitionOptions.DEFAULT_THREADS)
            .coerceIn(RecognitionOptions.MIN_THREADS, RecognitionOptions.MAX_THREADS)
        set(value) {
            preferences.edit()
                .putInt(PREF_ASR_THREADS, value.coerceIn(RecognitionOptions.MIN_THREADS, RecognitionOptions.MAX_THREADS))
                .apply()
        }

    /** User switch for call recording. Only usable when [SystemAppHelper.isCallRecordingAvailable]. */
    var callRecordingEnabled: Boolean
        get() = preferences.getBoolean(PREF_CALL_RECORDING, false)
        set(value) {
            preferences.edit().putBoolean(PREF_CALL_RECORDING, value).apply()
        }

    /** True once the automatic enable has run, so a user who turns it off keeps it off. */
    var callRecordingAutoEnableDone: Boolean
        get() = preferences.getBoolean(PREF_CALL_RECORDING_AUTO, false)
        set(value) {
            preferences.edit().putBoolean(PREF_CALL_RECORDING_AUTO, value).apply()
        }

    /** True while a recording that was started by a call is running, so it is stopped by the call end. */
    var callRecordingStartedByCall: Boolean
        get() = preferences.getBoolean(PREF_CALL_RECORDING_STARTED, false)
        set(value) {
            preferences.edit().putBoolean(PREF_CALL_RECORDING_STARTED, value).apply()
        }

    /** Markers of a recording, keyed by its MediaStore uri. */
    fun getMarkers(uri: String): List<Marker> {
        val json = preferences.getString(PREF_MARKERS_PREFIX + uri, null) ?: return emptyList()
        return runCatching { Marker.listFromJson(json) }.getOrDefault(emptyList())
    }

    fun saveMarkers(uri: String, markers: List<Marker>) {
        preferences.edit()
            .putString(PREF_MARKERS_PREFIX + uri, Marker.listToJson(markers))
            .apply()
    }

    companion object {
        const val DEFAULT_STORAGE_FOLDER = "录音"

        private const val PREFS = "preferences"
        private const val PREF_TAG_WITH_LOCATION = "tag_with_location"
        private const val PREF_RECORDING_QUALITY = "recording_quality"
        private const val PREF_ONBOARD_SETTINGS_COUNTER = "onboard_settings"
        private const val PREF_ONBOARD_SOUND_LIST_COUNTER = "onboard_list"
        private const val PREF_LAST_SOUND = "sound_last_path"
        private const val PREF_FILENAME_TEMPLATE = "filename_template"
        private const val PREF_STORAGE_FOLDER = "storage_folder"
        private const val PREF_ASR_LANGUAGE = "asr_language"
        private const val PREF_ASR_THREADS = "asr_threads"
        private const val PREF_CALL_RECORDING = "call_recording"
        private const val PREF_CALL_RECORDING_AUTO = "call_recording_auto"
        private const val PREF_CALL_RECORDING_STARTED = "call_recording_started"
        private const val PREF_MARKERS_PREFIX = "markers:"
    }
}
