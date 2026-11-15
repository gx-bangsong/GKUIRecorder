/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.recorder.asr

import android.content.Context

/**
 * The user's own pause of a transcription run, per recording. It only makes the run wait between
 * chunks, the same way the heat pause does. It never forces a run during heat, and clearing it
 * resumes the run. Starting a run clears the flag, so a new run is never paused by accident.
 */
object UserPause {
    private const val PREFS = "asr_user_pause"
    private const val KEY = "paused_uris"

    fun isPaused(context: Context, uri: String): Boolean =
        prefs(context).getStringSet(KEY, emptySet())?.contains(uri) == true

    fun set(context: Context, uri: String, paused: Boolean) {
        val prefs = prefs(context)
        val current = prefs.getStringSet(KEY, emptySet()).orEmpty().toMutableSet()
        val changed = if (paused) current.add(uri) else current.remove(uri)
        if (changed) {
            // A new set object is required, the stored set must not be edited in place
            prefs.edit().putStringSet(KEY, current).apply()
        }
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
