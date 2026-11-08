/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.recorder.asr

import android.content.Context
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException

/**
 * One recognizer at a time, app-wide. Transcription workers hold this lock for the whole time a
 * model is loaded, and model deletion requires it to be free.
 *
 * It is a file lock on `noBackupFilesDir/asr/asr.lock` rather than a Semaphore or a flag, so it
 * covers every work item (including ones chained after a download) and any other component that
 * takes it. Within one process an overlapping attempt raises [OverlappingFileLockException], which
 * is treated as "busy". The OS releases the lock automatically if the process dies.
 */
object AsrGlobalLock {
    private const val POLL_MS = 500L

    private fun lockFile(context: Context): File =
        File(context.noBackupFilesDir, "asr/asr.lock").also { it.parentFile?.mkdirs() }

    /** One held lock. Closing it releases the lock and the file. */
    class Handle internal constructor(
        private val file: RandomAccessFile,
        private val lock: FileLock,
    ) : AutoCloseable {
        override fun close() {
            runCatching { lock.release() }
            runCatching { file.close() }
        }
    }

    /** Takes the lock now, or returns null when another run holds it. Never blocks. */
    fun tryAcquire(context: Context): Handle? = tryAcquire(lockFile(context))

    /** [tryAcquire] on an explicit lock file. */
    fun tryAcquire(file: File): Handle? {
        file.parentFile?.mkdirs()
        val raf = RandomAccessFile(file, "rw")
        return try {
            val lock = raf.channel.tryLock() ?: run {
                raf.close()
                return null
            }
            Handle(raf, lock)
        } catch (_: OverlappingFileLockException) {
            raf.close()
            null
        }
    }

    /**
     * Waits (polling) until the lock is free. Returns null if [shouldStop] becomes true first, so a
     * cancelled work item does not sit in the queue. Call from a background thread only.
     */
    fun acquire(context: Context, shouldStop: () -> Boolean): Handle? =
        acquire(lockFile(context), shouldStop)

    /** [acquire] on an explicit lock file. */
    fun acquire(file: File, shouldStop: () -> Boolean): Handle? {
        while (true) {
            tryAcquire(file)?.let { return it }
            if (shouldStop()) return null
            Thread.sleep(POLL_MS)
        }
    }

    /** True while some transcription holds the recognizer. */
    fun isHeld(context: Context): Boolean = isHeld(lockFile(context))

    fun isHeld(file: File): Boolean = tryAcquire(file)?.also { it.close() } == null
}
