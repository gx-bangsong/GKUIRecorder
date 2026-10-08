/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.recorder.playback

import android.text.format.DateUtils
import org.lineageos.recorder.models.Marker
import org.lineageos.recorder.models.MarkerType

/**
 * Rows shown under the player: chapters (split by segment markers) and the
 * important markers that fall inside each chapter.
 */
sealed interface PlaybackRow {
    data class Chapter(
        val index: Int,
        val startMs: Long,
        val endMs: Long,
        val importantCount: Int,
        /** The segment marker that starts this chapter, null for the first chapter. */
        val boundaryMs: Long?,
    ) : PlaybackRow

    data class Mark(val marker: Marker) : PlaybackRow
}

object PlaybackRows {
    fun formatTime(ms: Long): String = DateUtils.formatElapsedTime(ms.coerceAtLeast(0L) / 1000L)

    fun build(markers: List<Marker>, durationMs: Long): List<PlaybackRow> {
        val sorted = markers.sortedBy { it.timeMs }
        val boundaries = sorted
            .filter { it.type == MarkerType.SEGMENT && it.timeMs > 0L }
            .map { it.timeMs }
            .distinct()
        val starts = listOf(0L) + boundaries
        val total = maxOf(durationMs, sorted.maxOfOrNull { it.timeMs } ?: 0L)

        val rows = mutableListOf<PlaybackRow>()
        starts.forEachIndexed { i, start ->
            val isLast = i == starts.lastIndex
            val end = if (isLast) total else starts[i + 1]
            val important = sorted.filter {
                it.type == MarkerType.IMPORTANT &&
                    it.timeMs >= start &&
                    (isLast || it.timeMs < end)
            }
            rows += PlaybackRow.Chapter(
                index = i + 1,
                startMs = start,
                endMs = end,
                importantCount = important.size,
                boundaryMs = if (start > 0L) start else null,
            )
            rows += important.map { PlaybackRow.Mark(it) }
        }
        return rows
    }
}
