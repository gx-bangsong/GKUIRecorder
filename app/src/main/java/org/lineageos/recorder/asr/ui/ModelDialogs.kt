/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.recorder.asr.ui

import android.content.Context
import android.net.ConnectivityManager
import android.text.format.Formatter
import androidx.annotation.StringRes
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.lineageos.recorder.R
import org.lineageos.recorder.asr.model.ModelDescriptor

/** Text shared by the download confirmation and the first-tap dialog. */
object ModelDialogs {
    /** True when the active connection is metered (cellular or similar). */
    fun isMetered(context: Context): Boolean =
        context.getSystemService(ConnectivityManager::class.java)?.isActiveNetworkMetered == true

    fun languageNames(context: Context, codes: List<String>): String =
        codes.joinToString(", ") { context.getString(languageRes(it)) }

    @StringRes
    fun languageRes(code: String): Int = when (code) {
        "auto" -> R.string.asr_lang_auto
        "zh" -> R.string.asr_lang_zh
        "en" -> R.string.asr_lang_en
        "yue" -> R.string.asr_lang_yue
        "ja" -> R.string.asr_lang_ja
        "ko" -> R.string.asr_lang_ko
        else -> R.string.asr_lang_auto
    }

    /** Body of the first-tap dialog: name, size, languages, first-time and offline notes, cellular warning. */
    fun consentMessage(context: Context, model: ModelDescriptor): String = buildString {
        appendLine(context.getString(R.string.asr_consent_model, model.displayName))
        appendLine(
            context.getString(
                R.string.asr_consent_size,
                Formatter.formatShortFileSize(context, model.downloadBytes),
            ),
        )
        appendLine(
            context.getString(
                R.string.asr_consent_languages,
                languageNames(context, model.supportedLanguages),
            ),
        )
        appendLine()
        appendLine(context.getString(R.string.asr_consent_first_time))
        appendLine(context.getString(R.string.asr_consent_offline))
        if (isMetered(context)) {
            appendLine()
            appendLine(context.getString(R.string.asr_consent_cellular))
        }
    }.trimEnd()

    /** Asks before a download on a metered connection. Calls [onConfirm] otherwise, without asking. */
    fun confirmDownload(context: Context, model: ModelDescriptor, onConfirm: () -> Unit) {
        if (!isMetered(context)) {
            onConfirm()
            return
        }
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.asr_consent_title)
            .setMessage(consentMessage(context, model))
            .setPositiveButton(R.string.asr_action_download) { _, _ -> onConfirm() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
