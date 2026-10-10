/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.recorder.asr.transcription

import org.lineageos.recorder.asr.engine.Utterance
import org.lineageos.recorder.asr.model.MiniJson

enum class TranscriptStatus {
    QUEUED,
    RUNNING,
    COMPLETED,
    FAILED,
    CANCELLED,
}

data class TranscriptSegment(
    val startMs: Long,
    val endMs: Long,
    val text: String,
)

/**
 * Transcription state and result of one recording. Stored per recording URI and never written
 * into the recording file itself.
 */
data class TranscriptRecord(
    val status: TranscriptStatus,
    val progress: Float,
    val text: String,
    val segments: List<TranscriptSegment>,
    val language: String?,
    val modelId: String?,
    val modelVersion: String?,
    val errorMessage: String?,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
) {
    fun toJson(): String = MiniJson.write(
        mapOf(
            "status" to status.name,
            "progress" to progress.toDouble(),
            "text" to text,
            "segments" to segments.map {
                mapOf("startMs" to it.startMs, "endMs" to it.endMs, "text" to it.text)
            },
            "language" to language,
            "modelId" to modelId,
            "modelVersion" to modelVersion,
            "errorMessage" to errorMessage,
            "createdAtMillis" to createdAtMillis,
            "updatedAtMillis" to updatedAtMillis,
        ),
    )

    companion object {
        /** Stored values written by the app before structured records existed (plain text). */
        fun legacyText(text: String, nowMillis: Long): TranscriptRecord = TranscriptRecord(
            status = TranscriptStatus.COMPLETED,
            progress = 1f,
            text = text,
            segments = emptyList(),
            language = null,
            modelId = null,
            modelVersion = null,
            errorMessage = null,
            createdAtMillis = nowMillis,
            updatedAtMillis = nowMillis,
        )

        @Suppress("UNCHECKED_CAST")
        fun fromJson(json: String): TranscriptRecord {
            val map = MiniJson.parseObject(json)
            val segments = (map["segments"] as? List<Map<String, Any?>>).orEmpty().map {
                TranscriptSegment(
                    startMs = (it["startMs"] as Number).toLong(),
                    endMs = (it["endMs"] as Number).toLong(),
                    text = it["text"] as String,
                )
            }
            return TranscriptRecord(
                status = TranscriptStatus.valueOf(map["status"] as String),
                progress = (map["progress"] as Number).toFloat(),
                text = map["text"] as String,
                segments = segments,
                language = map["language"] as String?,
                modelId = map["modelId"] as String?,
                modelVersion = map["modelVersion"] as String?,
                errorMessage = map["errorMessage"] as String?,
                createdAtMillis = (map["createdAtMillis"] as Number).toLong(),
                updatedAtMillis = (map["updatedAtMillis"] as Number).toLong(),
            )
        }
    }
}

object TranscriptText {
    /**
     * Joins utterances into one text. Adds a space only between two Latin letters or digits, so
     * Chinese text is not split by spaces while English words stay separated.
     */
    fun join(utterances: List<Utterance>): String {
        val out = StringBuilder()
        for (utterance in utterances) {
            val piece = utterance.text.trim()
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
