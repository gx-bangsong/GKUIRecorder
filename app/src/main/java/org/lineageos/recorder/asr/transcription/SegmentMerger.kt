/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.recorder.asr.transcription

import org.lineageos.recorder.asr.engine.Utterance

/**
 * Merges utterances produced by overlapping audio windows.
 *
 * Windows overlap so that a word cut at a boundary is recognized in full by one window. The same
 * sentence can then come back twice. Two utterances are duplicates when their normalized text is
 * equal and their time ranges overlap by at least half of the shorter one. The first occurrence
 * is kept. The output is sorted by start time.
 */
object SegmentMerger {
    private const val RECENT_WINDOW = 8

    fun merge(utterances: List<Utterance>): List<Utterance> {
        val sorted = utterances
            .filter { normalize(it.text).isNotEmpty() }
            .sortedWith(compareBy<Utterance> { it.startMs }.thenBy { it.endMs })
        val kept = ArrayList<Utterance>(sorted.size)
        for (candidate in sorted) {
            val key = normalize(candidate.text)
            val duplicate = kept.takeLast(RECENT_WINDOW).any { existing ->
                normalize(existing.text) == key && overlapRatio(existing, candidate) >= 0.5
            }
            if (!duplicate) {
                kept += candidate
            }
        }
        return kept
    }

    internal fun overlapRatio(a: Utterance, b: Utterance): Double {
        val overlap = minOf(a.endMs, b.endMs) - maxOf(a.startMs, b.startMs)
        if (overlap <= 0) {
            return 0.0
        }
        val shorter = minOf(a.endMs - a.startMs, b.endMs - b.startMs).coerceAtLeast(1L)
        return overlap.toDouble() / shorter
    }

    internal fun normalize(text: String): String = buildString {
        for (c in text) {
            if (c.isLetterOrDigit()) {
                append(c.lowercaseChar())
            }
        }
    }
}
