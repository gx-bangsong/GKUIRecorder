/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.recorder.asr.transcription

import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/** Where a loaded record came from. */
enum class TranscriptSource { FILE, LEGACY_STRUCTURED, LEGACY_TEXT }

/** Result of [TranscriptionRepository.load]. */
sealed interface TranscriptLoad {
    data class Found(val record: TranscriptRecord, val source: TranscriptSource) : TranscriptLoad

    data object Missing : TranscriptLoad

    /**
     * The stored data was damaged and no older copy could be recovered. The damaged file was moved
     * aside as [quarantinedName] when possible. Transcribing again regenerates it.
     */
    data class RecoverableCorruption(val quarantinedName: String?) : TranscriptLoad
}

/**
 * The only entry point for transcript reads and writes.
 *
 * Reads: the JSON file first. If it is missing, the older SharedPreferences value is migrated into a
 * new file. The old key is removed only after that file has been written. If the file is damaged,
 * it is quarantined and the older value is tried.
 *
 * Writes: always go to the JSON file. Nothing writes transcript text to SharedPreferences.
 * Operations for one recording are mutually exclusive. Different recordings are independent.
 *
 * Callers must run this off the main thread, because it does file I/O.
 */
class TranscriptionRepository(
    private val files: TranscriptStore,
    private val legacy: LegacyTranscriptReader? = null,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val locks = ConcurrentHashMap<String, Any>()

    fun load(uri: String): TranscriptLoad {
        val id = TranscriptRecord.stableRecordingId(uri)
        return locked(id) {
            var corrupt = false
            var quarantined: String? = null
            val fromFile = try {
                files.read(id)
            } catch (e: TranscriptCorruptException) {
                corrupt = true
                quarantined = files.quarantine(id, clock())
                null
            }
            if (fromFile != null) {
                return@locked TranscriptLoad.Found(fromFile, TranscriptSource.FILE)
            }

            val entry = legacy?.read(uri, id, clock())
            if (entry != null) {
                val source = if (entry.structured) {
                    TranscriptSource.LEGACY_STRUCTURED
                } else {
                    TranscriptSource.LEGACY_TEXT
                }
                val written = try {
                    files.write(entry.record)
                    true
                } catch (e: IOException) {
                    false
                } catch (e: IllegalStateException) {
                    false
                }
                // The old key goes only after the new file is safely on disk. On failure it stays,
                // and the next read tries the migration again.
                if (written) {
                    legacy?.remove(uri)
                }
                return@locked TranscriptLoad.Found(entry.record, source)
            }

            if (corrupt) {
                TranscriptLoad.RecoverableCorruption(quarantined)
            } else {
                TranscriptLoad.Missing
            }
        }
    }

    /**
     * The record for [uri], or null when there is none. Damaged data is reported as a FAILED
     * record with [TranscriptRecord.ERROR_CORRUPT], so the UI can offer to transcribe again.
     */
    fun get(uri: String): TranscriptRecord? = when (val result = load(uri)) {
        is TranscriptLoad.Found -> result.record
        is TranscriptLoad.Missing -> null
        is TranscriptLoad.RecoverableCorruption -> {
            val now = clock()
            TranscriptRecord(
                stableRecordingId = TranscriptRecord.stableRecordingId(uri),
                status = TranscriptStatus.FAILED,
                progress = 0f,
                text = "",
                segments = emptyList(),
                language = null,
                modelId = null,
                modelVersion = null,
                runtimeVersion = null,
                lastCompletedAudioPositionMs = 0L,
                resumeFromMs = 0L,
                errorCode = TranscriptRecord.ERROR_CORRUPT,
                errorMessage = null,
                createdAtMillis = now,
                updatedAtMillis = now,
            )
        }
    }

    /** Completed transcript text, or null when there is no completed transcript. */
    fun completedText(uri: String): String? =
        get(uri)?.takeIf { it.status == TranscriptStatus.COMPLETED }?.text

    /**
     * Starts a run. A previous run of the same model that was cancelled, failed or interrupted keeps
     * the segments that finished before its last checkpoint. The new run only adds what comes after.
     * Other cases start from nothing. Writes RUNNING and returns the base record for checkpoints.
     */
    fun start(uri: String, modelId: String, modelVersion: String, language: String): TranscriptRecord {
        val id = TranscriptRecord.stableRecordingId(uri)
        return locked(id) {
            val now = clock()
            val previous = (load(uri) as? TranscriptLoad.Found)?.record
            val reusable: TranscriptRecord? = previous?.takeIf {
                it.modelId == modelId && it.modelVersion == modelVersion && it.status in RESUMABLE
            }
            val resumeFrom = reusable?.lastCompletedAudioPositionMs ?: 0L
            val kept = reusable?.let { TranscriptMerge.prefix(it.segments, resumeFrom) }.orEmpty()
            val record = TranscriptRecord(
                stableRecordingId = id,
                status = TranscriptStatus.RUNNING,
                progress = reusable?.progress ?: 0f,
                text = TranscriptText.join(kept),
                segments = kept,
                language = language,
                modelId = modelId,
                modelVersion = modelVersion,
                runtimeVersion = RUNTIME_VERSION,
                lastCompletedAudioPositionMs = TranscriptMerge.lastEndMs(kept),
                resumeFromMs = resumeFrom,
                errorCode = null,
                errorMessage = null,
                createdAtMillis = previous?.createdAtMillis ?: now,
                updatedAtMillis = now,
            )
            put(record)
            record
        }
    }

    /**
     * Stores partial results. The base record's kept prefix is merged with [runSegments]. A failed
     * write is logged and ignored, so one slow disk write does not stop the recognition.
     */
    fun checkpoint(uri: String, base: TranscriptRecord, progress: Float, runSegments: List<TranscriptSegment>) {
        val id = TranscriptRecord.stableRecordingId(uri)
        locked(id) {
            val merged = TranscriptMerge.merge(base.segments, base.resumeFromMs, runSegments)
            try {
                put(
                    base.copy(
                        status = TranscriptStatus.RUNNING,
                        progress = progress,
                        segments = merged,
                        text = TranscriptText.join(merged),
                        lastCompletedAudioPositionMs = TranscriptMerge.lastEndMs(merged),
                        updatedAtMillis = clock(),
                    ),
                )
            } catch (e: IOException) {
                // Keep going. The next checkpoint or the final write tries again.
            }
        }
    }

    fun complete(uri: String, base: TranscriptRecord, runSegments: List<TranscriptSegment>) {
        val id = TranscriptRecord.stableRecordingId(uri)
        locked(id) {
            val merged = TranscriptMerge.merge(base.segments, base.resumeFromMs, runSegments)
            put(
                base.copy(
                    status = TranscriptStatus.COMPLETED,
                    progress = 1f,
                    segments = merged,
                    text = TranscriptText.join(merged),
                    lastCompletedAudioPositionMs = TranscriptMerge.lastEndMs(merged),
                    errorCode = null,
                    errorMessage = null,
                    updatedAtMillis = clock(),
                ),
            )
        }
    }

    /**
     * Records a failure. [runSegments] and [progress] are the state at the moment of failure, so the
     * segments recognized so far are kept. They are forced into the file here. Pass null to keep the base.
     */
    fun fail(
        uri: String,
        base: TranscriptRecord?,
        message: String,
        runSegments: List<TranscriptSegment>? = null,
        progress: Float? = null,
        errorCode: String = TranscriptRecord.ERROR_FAILED,
    ) {
        val id = TranscriptRecord.stableRecordingId(uri)
        locked(id) {
            val now = clock()
            val partial = partial(id, base, runSegments, progress, now)
            put(
                partial.copy(
                    status = TranscriptStatus.FAILED,
                    errorCode = errorCode,
                    errorMessage = message,
                    updatedAtMillis = now,
                ),
            )
        }
    }

    fun cancel(uri: String, base: TranscriptRecord?, runSegments: List<TranscriptSegment>? = null, progress: Float? = null) {
        val id = TranscriptRecord.stableRecordingId(uri)
        locked(id) {
            val now = clock()
            val partial = partial(id, base, runSegments, progress, now)
            put(
                partial.copy(
                    status = TranscriptStatus.CANCELLED,
                    errorCode = null,
                    errorMessage = null,
                    updatedAtMillis = now,
                ),
            )
        }
    }

    /**
     * Removes the transcript of one recording: its JSON file, its quarantined files and any older
     * SharedPreferences value. Only call this after the recording itself has been deleted.
     */
    fun remove(uri: String) {
        val id = TranscriptRecord.stableRecordingId(uri)
        locked(id) {
            files.delete(id)
            legacy?.remove(uri)
        }
    }

    private fun partial(
        id: String,
        base: TranscriptRecord?,
        runSegments: List<TranscriptSegment>?,
        progress: Float?,
        now: Long,
    ): TranscriptRecord {
        val start = base ?: return TranscriptRecord(
            stableRecordingId = id,
            status = TranscriptStatus.FAILED,
            progress = progress ?: 0f,
            text = "",
            segments = runSegments.orEmpty(),
            language = null,
            modelId = null,
            modelVersion = null,
            runtimeVersion = RUNTIME_VERSION,
            lastCompletedAudioPositionMs = TranscriptMerge.lastEndMs(runSegments.orEmpty()),
            resumeFromMs = 0L,
            errorCode = null,
            errorMessage = null,
            createdAtMillis = now,
            updatedAtMillis = now,
        ).let { it.copy(text = TranscriptText.join(it.segments)) }
        if (runSegments == null && progress == null) {
            return start
        }
        val merged = if (runSegments != null) {
            TranscriptMerge.merge(start.segments, start.resumeFromMs, runSegments)
        } else {
            start.segments
        }
        return start.copy(
            progress = progress ?: start.progress,
            segments = merged,
            text = TranscriptText.join(merged),
            lastCompletedAudioPositionMs = TranscriptMerge.lastEndMs(merged),
        )
    }

    private fun put(record: TranscriptRecord) {
        files.write(record.normalized())
    }

    private inline fun <T> locked(id: String, block: () -> T): T =
        synchronized(locks.getOrPut(id) { Any() }) { block() }

    companion object {
        /** Runs that can be continued from their last checkpoint. */
        private val RESUMABLE = setOf(
            TranscriptStatus.RUNNING,
            TranscriptStatus.CANCELLED,
            TranscriptStatus.FAILED,
        )

        /** Version of the sherpa-onnx runtime that produced the segments. */
        const val RUNTIME_VERSION = "sherpa-onnx 1.13.8"
    }
}
