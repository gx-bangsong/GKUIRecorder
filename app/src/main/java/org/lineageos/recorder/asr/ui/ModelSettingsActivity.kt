/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.recorder.asr.ui

import android.os.Bundle
import android.text.format.Formatter
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.lifecycle.lifecycleScope
import androidx.work.WorkInfo
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.lineageos.recorder.R
import org.lineageos.recorder.asr.ModelRepository
import org.lineageos.recorder.asr.engine.RecognitionOptions
import org.lineageos.recorder.asr.model.FailureReason
import org.lineageos.recorder.asr.model.ModelCatalog
import org.lineageos.recorder.asr.model.ModelDescriptor
import org.lineageos.recorder.asr.model.ModelState
import org.lineageos.recorder.utils.PreferencesManager

/**
 * Settings page for the offline speech model. Shows state, sizes, license and actions. Every
 * action goes through [ModelRepository]. Nothing is downloaded when the page opens.
 */
class ModelSettingsActivity : AppCompatActivity(R.layout.activity_model_settings) {
    private val model: ModelDescriptor = ModelCatalog.default
    private val repository by lazy { ModelRepository.get(this) }
    private val preferences by lazy { PreferencesManager(this) }
    private var work: WorkInfo? = null

    private val nameText by lazy { findViewById<TextView>(R.id.modelNameText) }
    private val infoText by lazy { findViewById<TextView>(R.id.modelInfoText) }
    private val sizeText by lazy { findViewById<TextView>(R.id.modelSizeText) }
    private val stateText by lazy { findViewById<TextView>(R.id.modelStateText) }
    private val progress by lazy { findViewById<LinearProgressIndicator>(R.id.modelProgress) }
    private val primaryButton by lazy { findViewById<MaterialButton>(R.id.primaryButton) }
    private val secondaryButton by lazy { findViewById<MaterialButton>(R.id.secondaryButton) }
    private val languageButton by lazy { findViewById<MaterialButton>(R.id.languageButton) }
    private val threadsButton by lazy { findViewById<MaterialButton>(R.id.threadsButton) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { finish() }

        nameText.text = model.displayName
        infoText.text = getString(
            R.string.asr_info_format,
            ModelDialogs.languageNames(this, model.supportedLanguages),
            model.useCase,
            model.version,
        )
        sizeText.text = getString(
            R.string.asr_download_size_format,
            Formatter.formatShortFileSize(this, model.downloadBytes),
        )
        findViewById<TextView>(R.id.licenseText).text = getString(R.string.asr_licenses_text)

        primaryButton.setOnClickListener { onPrimaryClicked() }
        secondaryButton.setOnClickListener { confirmDelete() }
        languageButton.setOnClickListener { pickLanguage() }
        threadsButton.setOnClickListener { pickThreads() }
        renderOptions()

        repository.downloadWork(model.id).observe(this) { infos ->
            work = infos.firstOrNull()
            refresh()
        }
    }

    override fun onResume() {
        super.onResume()
        lifecycleScope.launch {
            repository.reconcile(model.id)
            refresh()
        }
    }

    private fun refresh() {
        lifecycleScope.launch {
            val currentWork = work
            val state = withContext(Dispatchers.IO) { repository.resolveState(model, currentWork) }
            val busy = withContext(Dispatchers.IO) { repository.isDownloadActive(model.id) }
            render(state, busy)
        }
    }

    private fun render(state: ModelState, busy: Boolean) {
        stateText.text = stateLabel(state)
        when (state) {
            is ModelState.Downloading -> showProgress(
                if (state.totalBytes > 0) (state.downloadedBytes * 100 / state.totalBytes).toInt() else 0,
                indeterminate = state.totalBytes <= 0,
            )
            is ModelState.Verifying, is ModelState.Installing -> showProgress(100, indeterminate = true)
            is ModelState.Queued -> showProgress(0, indeterminate = true)
            else -> progress.visibility = android.view.View.GONE
        }

        // Ready/UpdateAvailable: the installed files are shown in the size line
        val installed = (state as? ModelState.Ready)?.installedBytes
        if (installed != null) {
            sizeText.text = getString(
                R.string.asr_installed_size_format,
                Formatter.formatShortFileSize(this, installed),
            )
        }

        when (state) {
            is ModelState.NotInstalled, is ModelState.UpdateAvailable -> {
                primaryButton.setText(R.string.asr_action_download)
                primaryButton.isEnabled = !busy
                primaryButton.tag = Action.DOWNLOAD
            }
            is ModelState.Failed -> {
                primaryButton.setText(R.string.asr_action_retry)
                primaryButton.tag = Action.DOWNLOAD
                primaryButton.isEnabled = true
            }
            is ModelState.Queued, is ModelState.Downloading,
            is ModelState.Verifying, is ModelState.Installing,
            -> {
                primaryButton.setText(R.string.asr_action_cancel)
                primaryButton.tag = Action.CANCEL
                primaryButton.isEnabled = true
            }
            is ModelState.Ready -> {
                primaryButton.setText(R.string.asr_action_delete)
                primaryButton.tag = Action.DELETE
                primaryButton.isEnabled = true
            }
        }
        secondaryButton.visibility = if (state is ModelState.UpdateAvailable) {
            android.view.View.VISIBLE
        } else {
            android.view.View.GONE
        }
        if (state is ModelState.UpdateAvailable) {
            secondaryButton.setText(R.string.asr_action_delete_old)
        }
    }

    private enum class Action { DOWNLOAD, CANCEL, DELETE }

    private fun showProgress(percent: Int, indeterminate: Boolean) {
        progress.visibility = android.view.View.VISIBLE
        progress.isIndeterminate = indeterminate
        if (!indeterminate) {
            progress.setProgressCompat(percent.coerceIn(0, 100), true)
        }
    }

    private fun onPrimaryClicked() {
        when (primaryButton.tag) {
            Action.DOWNLOAD -> ModelDialogs.confirmDownload(this, model) {
                repository.enqueueDownload(model)
                refresh()
            }
            Action.CANCEL -> {
                repository.cancelDownload(model.id)
                refresh()
            }
            Action.DELETE -> confirmDelete()
            null -> Unit
        }
    }

    private fun confirmDelete() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.asr_delete_title)
            .setMessage(R.string.asr_delete_message)
            .setPositiveButton(R.string.asr_action_delete) { _, _ ->
                lifecycleScope.launch {
                    val deleted = repository.delete(model.id)
                    Toast.makeText(
                        this@ModelSettingsActivity,
                        if (deleted) R.string.asr_delete_done else R.string.asr_delete_busy,
                        Toast.LENGTH_SHORT,
                    ).show()
                    refresh()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun renderOptions() {
        languageButton.text = getString(
            R.string.asr_language_format,
            getString(ModelDialogs.languageRes(preferences.asrLanguage)),
        )
        threadsButton.text = getString(R.string.asr_threads_format, preferences.asrThreads)
    }

    private fun pickLanguage() {
        val codes = RecognitionOptions.SUPPORTED_LANGUAGES.toList()
        val labels = codes.map { getString(ModelDialogs.languageRes(it)) }.toTypedArray()
        val current = codes.indexOf(preferences.asrLanguage).coerceAtLeast(0)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.asr_language_title)
            .setSingleChoiceItems(labels, current) { dialog, which ->
                preferences.asrLanguage = codes[which]
                renderOptions()
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun pickThreads() {
        val options = (RecognitionOptions.MIN_THREADS..RecognitionOptions.MAX_THREADS).toList()
        val labels = options.map { getString(R.string.asr_threads_option, it) }.toTypedArray()
        val current = options.indexOf(preferences.asrThreads).coerceAtLeast(0)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.asr_threads_title)
            .setSingleChoiceItems(labels, current) { dialog, which ->
                preferences.asrThreads = options[which]
                renderOptions()
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    companion object {
        @androidx.annotation.StringRes
        fun reasonRes(reason: FailureReason): Int = when (reason) {
            FailureReason.NETWORK -> R.string.asr_reason_network
            FailureReason.HTTP_STATUS -> R.string.asr_reason_http
            FailureReason.SIZE_MISMATCH -> R.string.asr_reason_size
            FailureReason.CHECKSUM_MISMATCH -> R.string.asr_reason_checksum
            FailureReason.ARCHIVE_UNSAFE -> R.string.asr_reason_archive
            FailureReason.NO_SPACE -> R.string.asr_reason_space
            FailureReason.STORAGE -> R.string.asr_reason_storage
            FailureReason.CANCELLED -> R.string.asr_reason_cancelled
            FailureReason.UNKNOWN -> R.string.asr_reason_unknown
        }
    }

    private fun stateLabel(state: ModelState): String = when (state) {
        is ModelState.NotInstalled -> getString(R.string.asr_state_not_installed)
        is ModelState.Queued -> getString(R.string.asr_state_queued)
        is ModelState.Downloading -> getString(
            R.string.asr_state_downloading,
            Formatter.formatShortFileSize(this, state.downloadedBytes),
            if (state.totalBytes > 0) Formatter.formatShortFileSize(this, state.totalBytes) else "?",
        )
        is ModelState.Verifying -> getString(R.string.asr_state_verifying)
        is ModelState.Installing -> getString(R.string.asr_state_installing)
        is ModelState.Ready -> getString(R.string.asr_state_ready, state.version)
        is ModelState.UpdateAvailable -> getString(
            R.string.asr_state_update,
            state.installedVersion,
            state.availableVersion,
        )
        is ModelState.Failed -> getString(
            R.string.asr_state_failed,
            getString(reasonRes(state.reason)),
            getString(if (state.recoverable) R.string.asr_retry_hint else R.string.asr_retry_no),
        )
    }
}
