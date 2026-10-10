/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.recorder.asr.transcription

import org.lineageos.recorder.asr.engine.Utterance

/** Minimal key-value storage. The app implements it with SharedPreferences. */
interface KeyValueStore {
    fun get(key: String): String?

    fun put(key: String, value: String)

    fun remove(key: String)
}

/**
 * Reads and writes [TranscriptRecord]s per recording URI.
 *
 * Migration: values written by older builds are plain text. They are read as COMPLETED records
 * and rewritten in the structured format the next time the record changes. Nothing is deleted.
 */
class TranscriptionRepository(
    private val store: KeyValueStore,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    fun get(uri: String): TranscriptRecord? {
        val raw = store.get(key(uri)) ?: return null
        return if (raw.startsWith("{")) {
            runCatching { TranscriptRecord.fromJson(raw) }.getOrNull() ?: TranscriptRecord(
                status = TranscriptStatus.FAILED,
                progress = 0f,
                text = "",
                segments = emptyList(),
                language = null,
                modelId = null,
                modelVersion = null,
                errorMessage = "Stored transcript is unreadable",
                createdAtMillis = clock(),
                updatedAtMillis = clock(),
            )
        } else {
            TranscriptRecord.legacyText(raw, clock())
        }
    }

    fun put(uri: String, record: TranscriptRecord) {
        store.put(key(uri), record.toJson())
    }

    /** Completed transcript text, or null when there is no completed transcript. */
    fun completedText(uri: String): String? =
        get(uri)?.takeIf { it.status == TranscriptStatus.COMPLETED }?.text

    /**
     * Marks a recording as running, keeping any previous completed text out of the way. The
     * status and progress are updated; the segments of an earlier run are cleared.
     */
    fun start(uri: String, modelId: String, modelVersion: String, language: String): TranscriptRecord {
        val now = clock()
        val previous = get(uri)
        val record = TranscriptRecord(
            status = TranscriptStatus.RUNNING,
            progress = 0f,
            text = "",
            segments = emptyList(),
            language = language,
            modelId = modelId,
            modelVersion = modelVersion,
            errorMessage = null,
            createdAtMillis = previous?.createdAtMillis ?: now,
            updatedAtMillis = now,
        )
        put(uri, record)
        return record
    }

    /** Stores partial results so a pause or a crash keeps the segments already recognized. */
    fun checkpoint(uri: String, base: TranscriptRecord, progress: Float, segments: List<TranscriptSegment>) {
        put(
            uri,
            base.copy(
                progress = progress.coerceIn(0f, 1f),
                segments = segments,
                text = TranscriptText.join(segments.map { Utterance(it.startMs, it.endMs, it.text) }),
                updatedAtMillis = clock(),
            ),
        )
    }

    fun complete(uri: String, base: TranscriptRecord, segments: List<TranscriptSegment>) {
        put(
            uri,
            base.copy(
                status = TranscriptStatus.COMPLETED,
                progress = 1f,
                segments = segments,
                text = TranscriptText.join(segments.map { Utterance(it.startMs, it.endMs, it.text) }),
                errorMessage = null,
                updatedAtMillis = clock(),
            ),
        )
    }

    fun fail(uri: String, base: TranscriptRecord?, message: String) {
        val now = clock()
        val record = base ?: TranscriptRecord(
            status = TranscriptStatus.FAILED,
            progress = 0f,
            text = "",
            segments = emptyList(),
            language = null,
            modelId = null,
            modelVersion = null,
            errorMessage = null,
            createdAtMillis = now,
            updatedAtMillis = now,
        )
        put(
            uri,
            record.copy(
                status = TranscriptStatus.FAILED,
                errorMessage = message,
                updatedAtMillis = now,
            ),
        )
    }

    fun cancel(uri: String, base: TranscriptRecord?) {
        val now = clock()
        val record = base ?: return
        put(
            uri,
            record.copy(status = TranscriptStatus.CANCELLED, errorMessage = null, updatedAtMillis = now),
        )
    }

    /** Removes the transcript of one recording. Only used when the recording itself is deleted. */
    fun remove(uri: String) {
        store.remove(key(uri))
    }

    private fun key(uri: String) = PREFIX + uri

    companion object {
        const val PREFIX = "transcript:"
    }
}
