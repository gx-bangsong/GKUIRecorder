/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.recorder.asr.model

import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/** Streaming SHA-256. Files are read in chunks; nothing larger than the buffer is held in memory. */
object Digests {
    private const val BUFFER_SIZE = 256 * 1024

    fun sha256Of(file: File): String = file.inputStream().buffered().use { sha256Of(it) }

    fun sha256Of(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) {
                break
            }
            digest.update(buffer, 0, read)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
