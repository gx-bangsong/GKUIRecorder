/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.recorder.ui

import android.content.Context
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.lineageos.recorder.R

/**
 * Small programmatic form dialog used for text based settings and editing parameters.
 */
object FieldDialog {
    data class Field(
        val label: String,
        val value: String = "",
        val hint: String = "",
        val secret: Boolean = false,
    )

    fun show(
        context: Context,
        title: CharSequence,
        fields: List<Field>,
        message: CharSequence? = null,
        onSave: (List<String>) -> Unit,
    ) {
        val padding = (20 * context.resources.displayMetrics.density).toInt()
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding / 2, padding, 0)
        }

        message?.let {
            container.addView(TextView(context).apply {
                text = it
            })
        }

        val inputs = fields.map { field ->
            val label = TextView(context).apply {
                text = field.label
                setPadding(0, padding / 2, 0, 0)
            }
            container.addView(label)

            val input = EditText(context).apply {
                setText(field.value)
                hint = field.hint
                setSingleLine(true)
                inputType = if (field.secret) {
                    InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                } else {
                    InputType.TYPE_CLASS_TEXT
                }
            }
            container.addView(input)
            input
        }

        MaterialAlertDialogBuilder(context)
            .setTitle(title)
            .setView(container)
            .setPositiveButton(R.string.save) { _, _ ->
                onSave(inputs.map { it.text.toString().trim() })
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}
