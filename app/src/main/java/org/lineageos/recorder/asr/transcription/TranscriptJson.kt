/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.recorder.asr.transcription

import org.lineageos.recorder.asr.model.MiniJson

/** The stored bytes cannot be read as a [TranscriptRecord]. The message never contains content. */
class TranscriptFormatException(reason: String) : RuntimeException(reason)

/**
 * JSON codec for [TranscriptRecord] files. Unknown fields are ignored. Optional fields fall back to
 * safe defaults. Anything else is rejected.
 */
object TranscriptJson {
    /** Upper bound for one transcript file. Larger files are treated as damaged. */
    const val MAX_BYTES = 8 * 1024 * 1024

    fun encode(record: TranscriptRecord): ByteArray {
        val normalized = record.normalized()
        val bytes = MiniJson.write(
            mapOf(
                "schemaVersion" to normalized.schemaVersion,
                "stableRecordingId" to normalized.stableRecordingId,
                "status" to normalized.status.name,
                "progress" to normalized.progress.toDouble(),
                "text" to normalized.text,
                "segments" to normalized.segments.map {
                    mapOf(
                        "startMs" to it.startMs,
                        "endMs" to it.endMs,
                        "text" to it.text,
                        "language" to it.language,
                        "confidence" to it.confidence?.toDouble(),
                    )
                },
                "language" to normalized.language,
                "modelId" to normalized.modelId,
                "modelVersion" to normalized.modelVersion,
                "runtimeVersion" to normalized.runtimeVersion,
                "lastCompletedAudioPositionMs" to normalized.lastCompletedAudioPositionMs,
                "resumeFromMs" to normalized.resumeFromMs,
                "errorCode" to normalized.errorCode,
                "errorMessage" to normalized.errorMessage,
                "createdAtMillis" to normalized.createdAtMillis,
                "updatedAtMillis" to normalized.updatedAtMillis,
            ),
        ).toByteArray(Charsets.UTF_8)
        check(bytes.size <= MAX_BYTES) { "Transcript is too large to store" }
        return bytes
    }

    fun decode(bytes: ByteArray): TranscriptRecord {
        if (bytes.size > MAX_BYTES) {
            throw TranscriptFormatException("file too large")
        }
        return try {
            decodeMap(MiniJson.parseObject(String(bytes, Charsets.UTF_8)))
        } catch (e: TranscriptFormatException) {
            throw e
        } catch (e: Exception) {
            throw TranscriptFormatException("unparseable")
        }
    }

    private fun decodeMap(map: Map<String, Any?>): TranscriptRecord {
        val schema = (map["schemaVersion"] as? Number)?.toInt() ?: 1
        if (schema > TranscriptRecord.SCHEMA_VERSION) {
            throw TranscriptFormatException("newer schema")
        }
        val id = map["stableRecordingId"] as? String
            ?: throw TranscriptFormatException("missing id")
        val status = TranscriptStatus.valueOf(
            map["status"] as? String ?: throw TranscriptFormatException("missing status"),
        )
        val segments = (map["segments"] as? List<*>).orEmpty().map { decodeSegment(it) }
        return TranscriptRecord(
            stableRecordingId = id,
            status = status,
            progress = (map["progress"] as? Number)?.toFloat() ?: 0f,
            text = map["text"] as? String ?: "",
            segments = segments,
            language = map["language"] as? String,
            modelId = map["modelId"] as? String,
            modelVersion = map["modelVersion"] as? String,
            runtimeVersion = map["runtimeVersion"] as? String,
            lastCompletedAudioPositionMs = (map["lastCompletedAudioPositionMs"] as? Number)?.toLong() ?: 0L,
            resumeFromMs = (map["resumeFromMs"] as? Number)?.toLong() ?: 0L,
            errorCode = map["errorCode"] as? String,
            errorMessage = map["errorMessage"] as? String,
            createdAtMillis = (map["createdAtMillis"] as? Number)?.toLong() ?: 0L,
            updatedAtMillis = (map["updatedAtMillis"] as? Number)?.toLong() ?: 0L,
            schemaVersion = TranscriptRecord.SCHEMA_VERSION,
        ).normalized()
    }

    private fun decodeSegment(item: Any?): TranscriptSegment {
        val map = item as? Map<*, *> ?: throw TranscriptFormatException("bad segment")
        val start = (map["startMs"] as? Number)?.toLong() ?: throw TranscriptFormatException("bad segment")
        val end = (map["endMs"] as? Number)?.toLong() ?: throw TranscriptFormatException("bad segment")
        val text = map["text"] as? String ?: throw TranscriptFormatException("bad segment")
        return TranscriptSegment(
            startMs = start,
            endMs = end,
            text = text,
            language = map["language"] as? String,
            confidence = (map["confidence"] as? Number)?.toFloat(),
        ).normalized()
    }
}
