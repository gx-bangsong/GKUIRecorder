/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.recorder.asr.ui

import android.content.DialogInterface
import android.content.Intent
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.lineageos.recorder.R
import org.lineageos.recorder.asr.ModelRepository
import org.lineageos.recorder.asr.TranscriptionScheduler
import org.lineageos.recorder.asr.model.ManifestValidationResult
import org.lineageos.recorder.asr.model.ModelCatalog
import org.lineageos.recorder.asr.model.ModelDescriptor

/**
 * The one way to start a transcription from the UI. Used by the list and by the transcript view, so
 * both show the same consent and the same refusals.
 */
object TranscriptionStarter {
    /**
     * Starts a run for [uri]. Without a ready model it asks for the download consent first. If the
     * build cannot download the model, a dialog states the reason and stays until dismissed. It is
     * not a Toast, and no transcription is shown as running.
     */
    fun start(activity: AppCompatActivity, uri: String, title: String) {
        val model = ModelCatalog.default
        activity.lifecycleScope.launch {
            val repository = ModelRepository.get(activity)
            val (ready, downloading) = withContext(Dispatchers.IO) {
                (repository.readyDir(model) != null) to repository.isDownloadActive(model.id)
            }
            when {
                ready -> {
                    TranscriptionScheduler.enqueue(activity, uri, title, model, downloadFirst = false)
                    Toast.makeText(activity, R.string.transcribe_running, Toast.LENGTH_SHORT).show()
                }
                downloading -> Toast.makeText(
                    activity,
                    R.string.asr_model_downloading_wait,
                    Toast.LENGTH_LONG,
                ).show()
                model.validateManifest() is ManifestValidationResult.Incomplete ->
                    showUnavailable(activity)
                else -> showConsent(activity, uri, title, model)
            }
        }
    }

    private fun showConsent(
        activity: AppCompatActivity,
        uri: String,
        title: String,
        model: ModelDescriptor,
    ) {
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.asr_consent_title)
            .setMessage(ModelDialogs.consentMessage(activity, model))
            .setPositiveButton(R.string.asr_consent_download) { _: DialogInterface?, _: Int ->
                val started = TranscriptionScheduler.enqueue(
                    activity,
                    uri,
                    title,
                    model,
                    downloadFirst = true,
                )
                if (started) {
                    Toast.makeText(activity, R.string.asr_download_started, Toast.LENGTH_LONG).show()
                } else {
                    showUnavailable(activity)
                }
            }
            // Opens the model page, where the local import is offered. Nothing starts here.
            .setNeutralButton(R.string.asr_consent_import) { _: DialogInterface?, _: Int ->
                activity.startActivity(Intent(activity, ModelSettingsActivity::class.java))
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Persistent reason: the model cannot be downloaded in this build. */
    fun showUnavailable(activity: AppCompatActivity) {
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.transcribe)
            .setMessage(R.string.asr_manifest_incomplete)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }
}
