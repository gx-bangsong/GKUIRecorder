/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.recorder.asr.transcription

import java.security.MessageDigest

enum class TranscriptStatus {
    QUEUED,
    RUNNING,
    COMPLETED,
    FAILED,
    CANCELLED,
}

/** One recognized span. Times are milliseconds from the start of the recording. */
data class TranscriptSegment(
    val startMs: Long,
    val endMs: Long,
    val text: String,
    val language: String? = null,
    val confidence: Float? = null,
) {
    /** Clamps times so that `0 <= startMs <= endMs`. */
    fun normalized(): TranscriptSegment {
        val start = startMs.coerceAtLeast(0L)
        return copy(startMs = start, endMs = endMs.coerceAtLeast(start))
    }
}

/**
 * Transcription state and result of one recording. Stored as a JSON file per recording (see
 * [TranscriptFileStore]), never inside SharedPreferences and never inside the recording itself.
 *
 * [resumeFromMs] is the audio position where the current run starts re-decoding. Segments of an
 * earlier run that end at or before this position are kept as they are, so a resumed run does not
 * repeat them. The current run re-decodes from the start of the recording and only adds segments
 * after this position. Exact seek is not implemented.
 */
data class TranscriptRecord(
    val stableRecordingId: String,
    val status: TranscriptStatus,
    val progress: Float,
    val text: String,
    val segments: List<TranscriptSegment>,
    val language: String?,
    val modelId: String?,
    val modelVersion: String?,
    val runtimeVersion: String?,
    val lastCompletedAudioPositionMs: Long,
    val resumeFromMs: Long,
    val errorCode: String?,
    val errorMessage: String?,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
    val schemaVersion: Int = SCHEMA_VERSION,
) {
    /** Same record with progress in 0..1, valid segments and non-negative positions. */
    fun normalized(): TranscriptRecord = copy(
        progress = if (progress.isNaN()) 0f else progress.coerceIn(0f, 1f),
        segments = segments.map { it.normalized() },
        lastCompletedAudioPositionMs = lastCompletedAudioPositionMs.coerceAtLeast(0L),
        resumeFromMs = resumeFromMs.coerceAtLeast(0L),
    )

    companion object {
        const val SCHEMA_VERSION = 1

        /** Errors that the UI shows as "damaged, can be generated again". */
        const val ERROR_CORRUPT = "CORRUPT"
        const val ERROR_MODEL_MISSING = "MODEL_MISSING"
        const val ERROR_FAILED = "FAILED"

        /** Recording id: SHA-256 of the URI as UTF-8, 64 lowercase hex characters. */
        fun stableRecordingId(recordingUri: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(recordingUri.toByteArray(Charsets.UTF_8))
            return digest.joinToString("") { "%02x".format(it) }
        }
    }
}

object TranscriptText {
    /**
     * Joins utterances into one text. Adds a space only between two Latin letters or digits, so
     * Chinese text is not split by spaces while English words stay separated.
     */
    fun join(segments: List<TranscriptSegment>): String {
        val out = StringBuilder()
        for (segment in segments) {
            val piece = segment.text.trim()
            if (piece.isEmpty()) {
                continue
            }
            if (out.isNotEmpty() && needsSpace(out.last(), piece.first())) {
                out.append(' ')
            }
            out.append(piece)
        }
        return out.toString()
    }

    /** Space between Latin words, and between Latin and CJK text. CJK-to-CJK never gets a space. */
    private fun needsSpace(prev: Char, next: Char): Boolean {
        val a = scriptOf(prev)
        val b = scriptOf(next)
        return (a == Script.LATIN && (b == Script.LATIN || b == Script.CJK)) ||
            (a == Script.CJK && b == Script.LATIN)
    }

    private enum class Script { LATIN, CJK, OTHER }

    private fun scriptOf(c: Char): Script = when {
        c.code >= 0x2E80 && c.isLetter() -> Script.CJK
        c.isLetterOrDigit() -> Script.LATIN
        else -> Script.OTHER
    }
}

/** Pure merge rules for checkpoints and resumed runs. */
object TranscriptMerge {
    /** Segments that end at or before [resumeFromMs]. These are kept from the earlier run. */
    fun prefix(segments: List<TranscriptSegment>, resumeFromMs: Long): List<TranscriptSegment> =
        segments.filter { it.endMs <= resumeFromMs }

    /**
     * The earlier run's prefix, followed by the current run's segments that end after
     * [resumeFromMs]. Segments of the current run that are fully inside the prefix are dropped, so
     * re-decoding the start of the recording does not duplicate text. A segment that straddles the
     * boundary is kept, so no audio between the two runs is lost.
     */
    fun merge(
        earlier: List<TranscriptSegment>,
        resumeFromMs: Long,
        current: List<TranscriptSegment>,
    ): List<TranscriptSegment> {
        val kept = prefix(earlier, resumeFromMs)
        val added = current.map { it.normalized() }.filter { it.endMs > resumeFromMs }
        return (kept + added).sortedWith(compareBy({ it.startMs }, { it.endMs }))
    }

    /** End of the last segment, or 0 when there are none. */
    fun lastEndMs(segments: List<TranscriptSegment>): Long = segments.maxOfOrNull { it.endMs } ?: 0L
}
