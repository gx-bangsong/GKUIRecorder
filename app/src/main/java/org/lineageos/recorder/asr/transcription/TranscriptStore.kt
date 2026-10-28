/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.recorder.asr.transcription

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

/** A stored transcript file exists but cannot be used. The message never contains content. */
class TranscriptCorruptException(reason: String) : RuntimeException(reason)

/**
 * Atomic replacement of one file. On failure the previous content must still be readable.
 * The Android app uses [org.lineageos.recorder.asr.AndroidAtomicFileWriter] (android.util.AtomicFile).
 * JVM tests and tools use [NioAtomicFileWriter].
 */
interface AtomicFileWriter {
    fun writeBytes(file: File, bytes: ByteArray)
}

/** Temporary file, fsync, then an atomic rename within the same directory. */
class NioAtomicFileWriter : AtomicFileWriter {
    override fun writeBytes(file: File, bytes: ByteArray) {
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, "${file.name}.tmp-${UUID.randomUUID()}")
        try {
            FileOutputStream(temp).use { out ->
                out.write(bytes)
                out.flush()
                out.fd.sync()
            }
            Files.move(
                temp.toPath(),
                file.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } finally {
            temp.delete()
        }
    }
}

/** Storage of one [TranscriptRecord] per recording id. */
interface TranscriptStore {
    /** The stored record, or null when there is none. Throws [TranscriptCorruptException]. */
    fun read(stableId: String): TranscriptRecord?

    /** Replaces the stored record atomically. Throws on failure, and the old record stays. */
    fun write(record: TranscriptRecord)

    /**
     * Moves an unreadable file aside as `{id}.corrupt-{epochMillis}.json` and keeps at most
     * [TranscriptFileStore.MAX_CORRUPT_KEPT] such files. Returns the new name, or null.
     */
    fun quarantine(stableId: String, nowMillis: Long): String?

    /** Removes the record and its quarantined files. */
    fun delete(stableId: String)
}

/**
 * JSON file per recording under `noBackupFilesDir/transcripts/{stableRecordingId}.json`.
 * Only the id is used in file names. The URI and the transcript text never appear in names or logs.
 */
class TranscriptFileStore(
    private val dir: File,
    private val writer: AtomicFileWriter,
) : TranscriptStore {
    override fun read(stableId: String): TranscriptRecord? {
        requireId(stableId)
        val file = fileFor(stableId)
        if (!file.isFile) {
            return null
        }
        if (file.length() > TranscriptJson.MAX_BYTES) {
            throw TranscriptCorruptException("file too large")
        }
        val record = try {
            TranscriptJson.decode(file.readBytes())
        } catch (e: TranscriptFormatException) {
            throw TranscriptCorruptException(e.message ?: "unreadable")
        }
        if (record.stableRecordingId != stableId) {
            throw TranscriptCorruptException("id mismatch")
        }
        return record
    }

    override fun write(record: TranscriptRecord) {
        requireId(record.stableRecordingId)
        writer.writeBytes(fileFor(record.stableRecordingId), TranscriptJson.encode(record))
    }

    override fun quarantine(stableId: String, nowMillis: Long): String? {
        requireId(stableId)
        val file = fileFor(stableId)
        if (!file.exists()) {
            return null
        }
        val target = File(dir, "$stableId.corrupt-$nowMillis.json")
        val moved = file.renameTo(target)
        if (!moved) {
            // Could not move it aside. Remove it so the same damaged file is not read again.
            file.delete()
            return null
        }
        pruneCorrupt(stableId)
        return target.name
    }

    override fun delete(stableId: String) {
        requireId(stableId)
        fileFor(stableId).delete()
        corruptFiles(stableId).forEach { it.delete() }
    }

    private fun pruneCorrupt(stableId: String) {
        corruptFiles(stableId)
            .sortedByDescending { corruptTimestamp(stableId, it) }
            .drop(MAX_CORRUPT_KEPT)
            .forEach { it.delete() }
    }

    private fun corruptFiles(stableId: String): List<File> =
        dir.listFiles { f -> f.name.startsWith("$stableId.corrupt-") && f.name.endsWith(".json") }
            ?.toList().orEmpty()

    private fun corruptTimestamp(stableId: String, file: File): Long =
        file.name.removePrefix("$stableId.corrupt-").removeSuffix(".json").toLongOrNull() ?: 0L

    private fun fileFor(stableId: String) = File(dir, "$stableId.json")

    private fun requireId(stableId: String) {
        require(ID_REGEX.matches(stableId)) { "Stable recording id must be 64 lowercase hex characters" }
    }

    companion object {
        const val MAX_CORRUPT_KEPT = 3
        private val ID_REGEX = Regex("^[0-9a-f]{64}$")
    }
}
