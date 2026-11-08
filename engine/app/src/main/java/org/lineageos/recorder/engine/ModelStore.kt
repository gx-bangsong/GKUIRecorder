/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.recorder.engine

import android.content.Context
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/**
 * Downloads a model package (zip) once and unpacks it into the engine's private storage.
 * The package must contain tokens.txt and model.int8.onnx (or model.onnx) at its root.
 */
class ModelStore(private val context: Context) {
    private val root = File(context.filesDir, "model")
    private val marker = File(root, READY_MARKER)

    /** The unpacked model directory when [prepare] has completed, otherwise null. */
    fun readyDirectory(): File? = root.takeIf { marker.isFile && it.isDirectory }

    @Synchronized
    fun prepare(url: String, sha256: String) {
        require(url.isNotBlank()) { "Model URL is not set in the Recorder settings" }
        if (marker.isFile && marker.readText().trim() == url) {
            return
        }

        val archive = File(context.cacheDir, "model.zip")
        try {
            download(url, archive, sha256)
            val staging = File(context.filesDir, "model.new").apply {
                deleteRecursively()
                mkdirs()
            }
            unzip(archive, staging)
            check(File(staging, "tokens.txt").isFile) { "Model package has no tokens.txt" }
            check(findModelFile(staging) != null) { "Model package has no model.int8.onnx or model.onnx" }

            root.deleteRecursively()
            check(staging.renameTo(root)) { "Cannot install the model" }
            marker.writeText(url)
            SpeechRecognizerHolder.reset()
        } finally {
            archive.delete()
        }
    }

    private fun download(url: String, target: File, sha256: String) {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 60_000
            connection.instanceFollowRedirects = true
            connection.connect()
            check(connection.responseCode == HttpURLConnection.HTTP_OK) {
                "Model download failed: HTTP ${connection.responseCode}"
            }
            val digest = MessageDigest.getInstance("SHA-256")
            connection.inputStream.use { input -> copyWithDigest(input, target, digest) }
            if (sha256.isNotBlank()) {
                val actual = digest.digest().joinToString("") { "%02x".format(it) }
                if (!actual.equals(sha256.trim(), ignoreCase = true)) {
                    target.delete()
                    error("Model SHA-256 mismatch")
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun copyWithDigest(input: InputStream, target: File, digest: MessageDigest) {
        target.outputStream().use { output ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                output.write(buffer, 0, read)
                digest.update(buffer, 0, read)
            }
        }
    }

    private fun unzip(archive: File, destination: File) {
        val base = destination.canonicalPath + File.separator
        ZipInputStream(archive.inputStream().buffered()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                val out = File(destination, entry.name)
                check(out.canonicalPath.startsWith(base)) { "Unsafe path in model package" }
                if (entry.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()
                    out.outputStream().use { zip.copyTo(it) }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
    }

    companion object {
        private const val READY_MARKER = ".ready"

        fun findModelFile(dir: File): File? =
            listOf("model.int8.onnx", "model.onnx").map { File(dir, it) }.firstOrNull { it.isFile }
    }
}
