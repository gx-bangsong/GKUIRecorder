/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.recorder.utils

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Downloads the offline speech engine package on demand. The package is stored in the
 * app's private files directory, so it does not change the APK size. Nothing is
 * downloaded unless the user has configured a URL.
 */
object EngineDownloader {
    private const val DIR = "engine"
    private const val FILE = "engine.pkg"
    private const val BUFFER_SIZE = 64 * 1024

    fun installedFile(context: Context): File = File(File(context.filesDir, DIR), FILE)

    fun isInstalled(context: Context): Boolean = installedFile(context).isFile

    /**
     * Downloads [url] into the engine file. [onProgress] receives 0..100 when the
     * server reports a length. When [sha256] is not blank the download must match it.
     * Runs on IO; [onProgress] is called from that thread.
     */
    suspend fun download(
        context: Context,
        url: String,
        sha256: String,
        onProgress: (Int) -> Unit,
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val dir = File(context.filesDir, DIR).apply { mkdirs() }
            val part = File(dir, "$FILE.part")
            val connection = URL(url).openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 15_000
                connection.readTimeout = 30_000
                connection.instanceFollowRedirects = true
                connection.connect()
                check(connection.responseCode == HttpURLConnection.HTTP_OK) {
                    "HTTP ${connection.responseCode}"
                }

                val total = connection.contentLengthLong
                val digest = MessageDigest.getInstance("SHA-256")
                connection.inputStream.use { input ->
                    part.outputStream().use { output ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        var done = 0L
                        var lastPercent = -1
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            digest.update(buffer, 0, read)
                            done += read
                            if (total > 0) {
                                val percent = (done * 100 / total).toInt()
                                if (percent != lastPercent) {
                                    lastPercent = percent
                                    onProgress(percent)
                                }
                            }
                        }
                    }
                }

                if (sha256.isNotBlank()) {
                    val actual = digest.digest().joinToString("") { "%02x".format(it) }
                    if (!actual.equals(sha256.trim(), ignoreCase = true)) {
                        part.delete()
                        error("SHA-256 mismatch")
                    }
                }

                val target = File(dir, FILE)
                target.delete()
                check(part.renameTo(target)) { "Cannot move the downloaded engine into place" }
                target
            } finally {
                connection.disconnect()
            }
        }
    }
}
