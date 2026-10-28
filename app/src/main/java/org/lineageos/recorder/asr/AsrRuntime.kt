/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.recorder.asr

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.os.PowerManager
import android.provider.MediaStore
import android.util.AtomicFile
import org.lineageos.recorder.asr.transcription.AtomicFileWriter
import org.lineageos.recorder.asr.transcription.KeyValueStore
import org.lineageos.recorder.asr.transcription.LegacyTranscriptReader
import org.lineageos.recorder.asr.transcription.TranscriptFileStore
import org.lineageos.recorder.asr.transcription.TranscriptionRepository
import java.io.File

/**
 * Atomic file replacement on Android, using the platform [AtomicFile]. startWrite, then finishWrite
 * on success, or failWrite on any error. The old file stays readable if the write fails.
 */
class AndroidAtomicFileWriter : AtomicFileWriter {
    override fun writeBytes(file: File, bytes: ByteArray) {
        file.parentFile?.mkdirs()
        val atomic = AtomicFile(file)
        val stream = atomic.startWrite()
        try {
            stream.write(bytes)
            atomic.finishWrite(stream)
        } catch (t: Throwable) {
            atomic.failWrite(stream)
            throw t
        }
    }
}

/** Reads the older SharedPreferences values. The new code never writes to this store. */
class SharedPrefsKeyValueStore(private val prefs: SharedPreferences) : KeyValueStore {
    override fun get(key: String): String? = prefs.getString(key, null)

    override fun put(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }

    override fun remove(key: String) {
        prefs.edit().remove(key).apply()
    }
}

/** Small shared helpers for the ASR feature. Nothing here loads native code. */
object AsrRuntime {
    const val PREFS = "preferences"

    /** Set while the recorder service is recording. Background transcription waits for it to stop. */
    @Volatile
    var recordingInProgress: Boolean = false

    /** Transcript repository: JSON files in noBackupFilesDir/transcripts, plus migration from prefs. */
    fun transcripts(context: Context): TranscriptionRepository {
        val app = context.applicationContext
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return TranscriptionRepository(
            files = TranscriptFileStore(File(app.noBackupFilesDir, "transcripts"), AndroidAtomicFileWriter()),
            legacy = LegacyTranscriptReader(SharedPrefsKeyValueStore(prefs)),
        )
    }

    /**
     * True when the recording still exists, or when that cannot be told. Only a confirmed absence
     * returns false, so transcript data is never removed because a query failed.
     */
    fun recordingExists(context: Context, uri: String): Boolean {
        return try {
            context.applicationContext.contentResolver.query(
                Uri.parse(uri),
                arrayOf(MediaStore.MediaColumns._ID),
                null,
                null,
                null,
            )?.use { it.count > 0 } ?: true
        } catch (e: SecurityException) {
            true
        } catch (e: IllegalArgumentException) {
            true
        }
    }

    /** Current thermal status (API 29+, always available at the app's minSdk). */
    fun thermalStatus(context: Context): Int =
        context.getSystemService(PowerManager::class.java)?.currentThermalStatus
            ?: PowerManager.THERMAL_STATUS_NONE
}
