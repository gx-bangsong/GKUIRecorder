/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.recorder

import android.os.Bundle
import android.view.View
import android.widget.CompoundButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import org.lineageos.recorder.ui.FieldDialog
import org.lineageos.recorder.utils.EngineClient
import org.lineageos.recorder.utils.EngineDownloader
import org.lineageos.recorder.utils.EngineInstaller
import org.lineageos.recorder.utils.FileNameTemplate
import org.lineageos.recorder.utils.PermissionManager
import org.lineageos.recorder.utils.PreferencesManager
import org.lineageos.recorder.utils.SystemAppHelper

class DialogActivity : AppCompatActivity() {
    // Views
    private lateinit var highQualitySwitch: MaterialSwitch
    private lateinit var locationSwitch: MaterialSwitch

    private val permissionManager: PermissionManager by lazy { PermissionManager(this) }

    private val preferences by lazy { PreferencesManager(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setFinishOnTouchOutside(true)

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.settings_title)
            .setView(R.layout.dialog_content_settings)
            .setOnDismissListener { finish() }
            .show()

        val isRecording = intent.getBooleanExtra(EXTRA_IS_RECORDING, false)
        locationSwitch = dialog.findViewById(R.id.locationSwitch)!!

        setupLocationSwitch(locationSwitch, isRecording)

        highQualitySwitch = dialog.findViewById(R.id.highQualitySwitch)!!
        setupHighQualitySwitch(highQualitySwitch, isRecording)

        dialog.findViewById<View>(R.id.filenameTemplateButton)?.setOnClickListener {
            showFilenameTemplateSettings()
        }
        dialog.findViewById<View>(R.id.storageFolderButton)?.setOnClickListener {
            showStorageFolderSettings()
        }
        dialog.findViewById<View>(R.id.transcriptionButton)?.setOnClickListener {
            showTranscriptionSettings()
        }

        settingsDialog = dialog
        setupCallRecordingSwitch(dialog)

        dialog.findViewById<View>(R.id.engineButton)?.setOnClickListener {
            showEngineSettings()
        }
        // Download on opening settings, if a URL is configured and the engine is missing
        refreshEngineStatus(dialog)
        startEngineDownloadIfNeeded(dialog)
    }

    private fun setupCallRecordingSwitch(dialog: android.app.Dialog) {
        val available = SystemAppHelper.isCallRecordingAvailable(this)
        val switch = dialog.findViewById<MaterialSwitch>(R.id.callRecordingSwitch) ?: return
        val status = dialog.findViewById<TextView>(R.id.callRecordingStatusText)
        switch.isChecked = available && preferences.callRecordingEnabled
        switch.isEnabled = available
        status?.setText(
            if (available) R.string.call_recording_status_available
            else R.string.call_recording_status_unavailable
        )
        switch.setOnCheckedChangeListener { _: CompoundButton?, isChecked: Boolean ->
            preferences.callRecordingEnabled = isChecked
        }
    }

    private fun refreshEngineStatus(dialog: android.app.Dialog) {
        val text = dialog.findViewById<TextView>(R.id.engineStatusText) ?: return
        val installed = EngineClient.isInstalled(this)
        val pending = EngineDownloader.isDownloaded(this) && !installed
        dialog.findViewById<View>(R.id.engineInstallButton)?.apply {
            visibility = if (pending) View.VISIBLE else View.GONE
            setOnClickListener { installEngine() }
        }
        text.setText(
            when {
                installed -> R.string.engine_status_installed
                pending -> R.string.engine_status_downloaded
                preferences.engineUrl.isBlank() -> R.string.engine_status_unset
                else -> R.string.engine_status_missing
            }
        )
    }

    private var engineDownloading = false
    private var settingsDialog: android.app.Dialog? = null

    private fun startEngineDownloadIfNeeded(dialog: android.app.Dialog) {
        val url = preferences.engineUrl
        if (url.isBlank() || engineDownloading || EngineClient.isInstalled(this) ||
            EngineDownloader.isDownloaded(this)
        ) {
            return
        }
        engineDownloading = true
        val text = dialog.findViewById<TextView>(R.id.engineStatusText)
        lifecycleScope.launch {
            val result = EngineDownloader.download(
                this@DialogActivity,
                url,
                preferences.engineSha256,
            ) { percent ->
                runOnUiThread {
                    text?.text = getString(R.string.engine_status_downloading, percent)
                }
            }
            engineDownloading = false
            result.onSuccess {
                settingsDialog?.let { refreshEngineStatus(it) }
            }.onFailure {
                text?.setText(R.string.engine_status_failed)
            }
        }
    }

    private fun installEngine() {
        EngineInstaller.install(this, EngineDownloader.installedFile(this))?.let { error ->
            Toast.makeText(
                this,
                getString(R.string.engine_install_failed, error),
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    private fun showEngineSettings() {
        FieldDialog.show(
            this,
            getString(R.string.settings_engine),
            listOf(
                FieldDialog.Field(
                    label = getString(R.string.engine_url),
                    value = preferences.engineUrl,
                    hint = "https://example.com/recorder-engine.apk",
                ),
                FieldDialog.Field(
                    label = getString(R.string.engine_sha256),
                    value = preferences.engineSha256,
                    hint = getString(R.string.engine_sha256_hint),
                ),
                FieldDialog.Field(
                    label = getString(R.string.engine_model_url),
                    value = preferences.engineModelUrl,
                    hint = "https://example.com/sensevoice-zh.zip",
                ),
                FieldDialog.Field(
                    label = getString(R.string.engine_model_sha256),
                    value = preferences.engineModelSha256,
                    hint = getString(R.string.engine_sha256_hint),
                ),
            ),
            message = getString(R.string.engine_hint),
        ) { values ->
            preferences.engineUrl = values[0]
            preferences.engineSha256 = values[1]
            preferences.engineModelUrl = values[2]
            preferences.engineModelSha256 = values[3]
            // Show the new status and start the download right away
            settingsDialog?.let {
                refreshEngineStatus(it)
                startEngineDownloadIfNeeded(it)
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PermissionManager.REQUEST_CODE) {
            if (permissionManager.hasLocationPermission()) {
                toggleAfterPermissionRequest()
            } else {
                permissionManager.onLocationPermissionDenied()
                locationSwitch.isChecked = false
            }
        }
    }

    private fun setupLocationSwitch(
        locationSwitch: MaterialSwitch,
        isRecording: Boolean
    ) {
        val tagWithLocation = if (preferences.tagWithLocation) {
            if (permissionManager.hasLocationPermission()) {
                true
            } else {
                // Permission revoked -> disabled feature
                preferences.tagWithLocation = false
                false
            }
        } else {
            false
        }
        locationSwitch.isChecked = tagWithLocation
        if (isRecording) {
            locationSwitch.isEnabled = false
        } else {
            locationSwitch.setOnCheckedChangeListener { _: CompoundButton?, isChecked: Boolean ->
                if (isChecked) {
                    if (permissionManager.hasLocationPermission()) {
                        preferences.tagWithLocation = true
                    } else {
                        permissionManager.requestLocationPermission()
                    }
                } else {
                    preferences.tagWithLocation = false
                }
            }
        }
    }

    private fun setupHighQualitySwitch(
        highQualitySwitch: MaterialSwitch,
        isRecording: Boolean
    ) {
        val highQuality = preferences.recordInHighQuality
        highQualitySwitch.isChecked = highQuality
        if (isRecording) {
            highQualitySwitch.isEnabled = false
        } else {
            highQualitySwitch.setOnCheckedChangeListener { _: CompoundButton?, isChecked: Boolean ->
                preferences.recordInHighQuality = isChecked
            }
        }
    }

    private fun toggleAfterPermissionRequest() {
        locationSwitch.isChecked = true
        preferences.tagWithLocation = true
    }

    private fun showFilenameTemplateSettings() {
        FieldDialog.show(
            this,
            getString(R.string.settings_filename_template),
            listOf(
                FieldDialog.Field(
                    label = getString(R.string.settings_filename_template_label),
                    value = preferences.fileNameTemplate,
                    hint = FileNameTemplate.DEFAULT,
                ),
            ),
            message = getString(R.string.settings_filename_template_hint),
        ) { values ->
            preferences.fileNameTemplate = values[0]
        }
    }

    private fun showStorageFolderSettings() {
        FieldDialog.show(
            this,
            getString(R.string.settings_storage_folder),
            listOf(
                FieldDialog.Field(
                    label = getString(R.string.settings_storage_folder_label),
                    value = preferences.storageFolder,
                    hint = PreferencesManager.DEFAULT_STORAGE_FOLDER,
                ),
            ),
            message = getString(R.string.settings_storage_folder_hint),
        ) { values ->
            preferences.storageFolder = values[0]
        }
    }

    private fun showTranscriptionSettings() {
        FieldDialog.show(
            this,
            getString(R.string.settings_transcription),
            listOf(
                FieldDialog.Field(
                    label = getString(R.string.transcription_endpoint),
                    value = preferences.transcriptionEndpoint,
                    hint = "http://192.168.1.10:8000/v1",
                ),
                FieldDialog.Field(
                    label = getString(R.string.transcription_api_key),
                    value = preferences.transcriptionApiKey,
                    secret = true,
                ),
                FieldDialog.Field(
                    label = getString(R.string.transcription_model),
                    value = preferences.transcriptionModel,
                    hint = "FunAudioLLM/SenseVoiceSmall",
                ),
            ),
            message = getString(R.string.transcription_hint),
        ) { values ->
            preferences.transcriptionEndpoint = values[0]
            preferences.transcriptionApiKey = values[1]
            preferences.transcriptionModel = values[2]
        }
    }

    companion object {
        const val EXTRA_IS_RECORDING = "is_recording"
    }
}
