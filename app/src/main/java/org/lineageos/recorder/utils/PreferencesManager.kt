/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.recorder.utils

import android.content.Context
import android.net.Uri
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

    var transcriptionEndpoint: String
        get() = preferences.getString(PREF_TRANSCRIPTION_ENDPOINT, "") ?: ""
        set(value) {
            preferences.edit().putString(PREF_TRANSCRIPTION_ENDPOINT, value.trim()).apply()
        }

    var transcriptionApiKey: String
        get() = preferences.getString(PREF_TRANSCRIPTION_API_KEY, "") ?: ""
        set(value) {
            preferences.edit().putString(PREF_TRANSCRIPTION_API_KEY, value.trim()).apply()
        }

    var transcriptionModel: String
        get() = preferences.getString(PREF_TRANSCRIPTION_MODEL, "") ?: ""
        set(value) {
            preferences.edit().putString(PREF_TRANSCRIPTION_MODEL, value.trim()).apply()
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

    /** Download URL of the offline ASR engine package. Empty means not configured. */
    var engineUrl: String
        get() = preferences.getString(PREF_ENGINE_URL, "") ?: ""
        set(value) {
            preferences.edit().putString(PREF_ENGINE_URL, value.trim()).apply()
        }

    /** Optional SHA-256 of the engine package, checked after download. */
    var engineSha256: String
        get() = preferences.getString(PREF_ENGINE_SHA256, "") ?: ""
        set(value) {
            preferences.edit().putString(PREF_ENGINE_SHA256, value.trim()).apply()
        }

    /** True while a recording that was started by a call is running, so it is stopped by the call end. */
    var callRecordingStartedByCall: Boolean
        get() = preferences.getBoolean(PREF_CALL_RECORDING_STARTED, false)
        set(value) {
            preferences.edit().putBoolean(PREF_CALL_RECORDING_STARTED, value).apply()
        }

    /** URL of the speech model package the engine downloads (zip). Empty means not set. */
    var engineModelUrl: String
        get() = preferences.getString(PREF_ENGINE_MODEL_URL, "") ?: ""
        set(value) {
            preferences.edit().putString(PREF_ENGINE_MODEL_URL, value.trim()).apply()
        }

    /** Optional SHA-256 of the model package. */
    var engineModelSha256: String
        get() = preferences.getString(PREF_ENGINE_MODEL_SHA256, "") ?: ""
        set(value) {
            preferences.edit().putString(PREF_ENGINE_MODEL_SHA256, value.trim()).apply()
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

    /** Cached transcript of a recording, keyed by its MediaStore uri. */
    fun getTranscript(uri: String): String? =
        preferences.getString(PREF_TRANSCRIPT_PREFIX + uri, null)

    fun saveTranscript(uri: String, text: String) {
        preferences.edit().putString(PREF_TRANSCRIPT_PREFIX + uri, text).apply()
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
        private const val PREF_TRANSCRIPTION_ENDPOINT = "transcription_endpoint"
        private const val PREF_TRANSCRIPTION_API_KEY = "transcription_api_key"
        private const val PREF_TRANSCRIPTION_MODEL = "transcription_model"
        private const val PREF_CALL_RECORDING = "call_recording"
        private const val PREF_CALL_RECORDING_AUTO = "call_recording_auto"
        private const val PREF_CALL_RECORDING_STARTED = "call_recording_started"
        private const val PREF_ENGINE_URL = "engine_url"
        private const val PREF_ENGINE_SHA256 = "engine_sha256"
        private const val PREF_ENGINE_MODEL_URL = "engine_model_url"
        private const val PREF_ENGINE_MODEL_SHA256 = "engine_model_sha256"
        private const val PREF_MARKERS_PREFIX = "markers:"
        private const val PREF_TRANSCRIPT_PREFIX = "transcript:"
    }
}
