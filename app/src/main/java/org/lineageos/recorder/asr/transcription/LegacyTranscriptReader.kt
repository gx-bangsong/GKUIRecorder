/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.recorder.asr.transcription

import org.lineageos.recorder.asr.model.MiniJson

/** Minimal key-value storage. The app implements it with SharedPreferences. */
interface KeyValueStore {
    fun get(key: String): String?

    fun put(key: String, value: String)

    fun remove(key: String)
}

/** A transcript found under an old SharedPreferences key. */
data class LegacyEntry(
    val record: TranscriptRecord,
    /** True when the old value was a structured JSON record. False for plain beta3 text. */
    val structured: Boolean,
)

/**
 * Reads the two older storage formats from SharedPreferences. Nothing in the app writes these keys
 * any more. Only this class knows the key prefix, so the migration is the single place that
 * touches them.
 *
 * - beta3: the value is the transcript text itself.
 * - an intermediate beta4 build: the value is a JSON object with the fields listed in [parseStructured].
 */
class LegacyTranscriptReader(private val store: KeyValueStore) {
    fun read(uri: String, stableId: String, nowMillis: Long): LegacyEntry? {
        val raw = store.get(key(uri)) ?: return null
        if (raw.startsWith("{")) {
            val structured = runCatching { parseStructured(raw, stableId, nowMillis) }.getOrNull()
            if (structured != null) {
                return LegacyEntry(structured, structured = true)
            }
        }
        // Not a structured record, so it is the beta3 plain text. The text is kept byte for byte.
        return LegacyEntry(plainText(raw, stableId, nowMillis), structured = false)
    }

    /** Removes the old key. Call only after the new file has been written successfully. */
    fun remove(uri: String) {
        store.remove(key(uri))
    }

    private fun parseStructured(json: String, stableId: String, nowMillis: Long): TranscriptRecord {
        val map = MiniJson.parseObject(json)
        val segments = (map["segments"] as? List<*>).orEmpty().map { item ->
            val segment = item as Map<*, *>
            TranscriptSegment(
                startMs = (segment["startMs"] as Number).toLong(),
                endMs = (segment["endMs"] as Number).toLong(),
                text = segment["text"] as String,
            ).normalized()
        }
        val text = map["text"] as? String ?: TranscriptText.join(segments)
        val status = TranscriptStatus.valueOf(map["status"] as String)
        return TranscriptRecord(
            stableRecordingId = stableId,
            status = status,
            progress = (map["progress"] as Number).toFloat(),
            text = text,
            segments = segments,
            language = map["language"] as? String,
            modelId = map["modelId"] as? String,
            modelVersion = map["modelVersion"] as? String,
            runtimeVersion = null,
            lastCompletedAudioPositionMs = TranscriptMerge.lastEndMs(segments),
            resumeFromMs = 0L,
            errorCode = null,
            errorMessage = map["errorMessage"] as? String,
            createdAtMillis = (map["createdAtMillis"] as? Number)?.toLong() ?: nowMillis,
            updatedAtMillis = (map["updatedAtMillis"] as? Number)?.toLong() ?: nowMillis,
        ).normalized()
    }

    private fun plainText(text: String, stableId: String, nowMillis: Long): TranscriptRecord =
        TranscriptRecord(
            stableRecordingId = stableId,
            status = TranscriptStatus.COMPLETED,
            progress = 1f,
            text = text,
            segments = emptyList(),
            language = null,
            modelId = null,
            modelVersion = null,
            runtimeVersion = null,
            lastCompletedAudioPositionMs = 0L,
            resumeFromMs = 0L,
            errorCode = null,
            errorMessage = null,
            createdAtMillis = nowMillis,
            updatedAtMillis = nowMillis,
        )

    private fun key(uri: String) = LEGACY_KEY_PREFIX + uri

    companion object {
        /** Key prefix used by the older builds. Kept private to this class. */
        private const val LEGACY_KEY_PREFIX = "transcript:"
    }
}
