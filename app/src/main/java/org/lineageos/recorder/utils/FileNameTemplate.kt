/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.recorder.utils

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Renders user-configurable (e.g. Chinese) recording file name templates.
 *
 * Supported placeholders: {loc} {yyyy} {MM} {dd} {HH} {mm} {ss}
 * Example: "{loc}_{yyyy}{MM}{dd}_{HH}{mm}{ss}" -> "会议室_20261008_093015"
 */
object FileNameTemplate {
    const val DEFAULT = "{loc}_{yyyy}{MM}{dd}_{HH}{mm}{ss}"

    private val YEAR = DateTimeFormatter.ofPattern("yyyy")
    private val MONTH = DateTimeFormatter.ofPattern("MM")
    private val DAY = DateTimeFormatter.ofPattern("dd")
    private val HOUR = DateTimeFormatter.ofPattern("HH")
    private val MINUTE = DateTimeFormatter.ofPattern("mm")
    private val SECOND = DateTimeFormatter.ofPattern("ss")

    private val INVALID_CHARS = Regex("[\\\\/:*?\"<>|%$\\x00-\\x1F]")

    fun render(
        template: String,
        now: LocalDateTime,
        location: String?,
        fallbackName: String,
    ): String {
        val source = template.ifBlank { DEFAULT }
        val rendered = source
            .replace("{loc}", location?.let { sanitize(it) }?.takeIf { it.isNotBlank() } ?: fallbackName)
            .replace("{yyyy}", now.format(YEAR))
            .replace("{MM}", now.format(MONTH))
            .replace("{dd}", now.format(DAY))
            .replace("{HH}", now.format(HOUR))
            .replace("{mm}", now.format(MINUTE))
            .replace("{ss}", now.format(SECOND))

        return sanitize(rendered).ifBlank { sanitize(fallbackName).ifBlank { "Recording" } }
    }

    /** Removes characters that are invalid in file names or format strings. */
    fun sanitize(name: String): String = name
        .replace(INVALID_CHARS, "_")
        .trim()
        .trim('.')
        .take(100)
}
