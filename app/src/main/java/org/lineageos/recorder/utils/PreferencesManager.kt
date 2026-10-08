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
        private const val PREF_MARKERS_PREFIX = "markers:"
        private const val PREF_TRANSCRIPT_PREFIX = "transcript:"
    }
}
