/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.recorder.asr.model

import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * Streams a `.tar.bz2` model archive and writes only the wanted files.
 *
 * Safety rules:
 * - every entry must live under [rootDirectory]; anything else aborts the extraction;
 * - absolute paths, backslashes, NUL bytes and `..` segments are rejected;
 * - symbolic links, hard links and device nodes are rejected;
 * - each written file is checked against its canonical destination path;
 * - the total number of decompressed bytes (including skipped entries) is capped.
 */
object ArchiveExtractor {
    private const val MAX_ENTRIES = 10_000
    private const val COPY_BUFFER = 256 * 1024

    /**
     * @param wanted map from path inside the archive root to the local file name written in [destDir].
     * @return local file name to size in bytes.
     */
    fun extract(
        archive: File,
        rootDirectory: String,
        wanted: Map<String, String>,
        destDir: File,
        maxTotalBytes: Long,
        isCancelled: () -> Boolean,
    ): Map<String, Long> {
        destDir.mkdirs()
        val canonicalDest = destDir.canonicalFile
        val written = LinkedHashMap<String, Long>()
        var entries = 0
        var totalBytes = 0L
        val buffer = ByteArray(COPY_BUFFER)

        TarArchiveInputStream(
            BZip2CompressorInputStream(BufferedInputStream(FileInputStream(archive), COPY_BUFFER)),
        ).use { tar ->
            while (true) {
                val entry: TarArchiveEntry = tar.nextEntry ?: break
                entries++
                if (entries > MAX_ENTRIES) {
                    throw unsafe("too many archive entries")
                }
                val relative = relativeName(entry.name, rootDirectory)
                if (entry.isSymbolicLink || entry.isLink || entry.isCharacterDevice ||
                    entry.isBlockDevice || entry.isFIFO
                ) {
                    throw unsafe("links and special files are not allowed")
                }
                if (entry.isDirectory) {
                    continue
                }
                if (!entry.isFile) {
                    throw unsafe("unsupported entry type")
                }
                val localName = wanted[relative]
                if (localName == null) {
                    totalBytes = skipCounting(tar, buffer, totalBytes, maxTotalBytes, isCancelled)
                    continue
                }
                val target = File(destDir, localName)
                if (!target.canonicalFile.parentFile.let { it == canonicalDest }) {
                    throw unsafe("destination escapes the model directory")
                }
                FileOutputStream(target).use { out ->
                    totalBytes = copyCounting(tar, out, buffer, totalBytes, maxTotalBytes, isCancelled)
                }
                written[localName] = target.length()
            }
        }

        val missing = wanted.values.filterNot { written.containsKey(it) }
        if (missing.isNotEmpty()) {
            throw ModelFailure(
                FailureReason.ARCHIVE_UNSAFE,
                recoverable = false,
                message = "Archive is missing expected files",
            )
        }
        return written
    }

    /** Validates an entry name and returns its path relative to [rootDirectory]. */
    internal fun relativeName(rawName: String, rootDirectory: String): String {
        if (rawName.isEmpty() || rawName.startsWith("/") || rawName.contains('\\') ||
            rawName.contains('\u0000') || rawName.matches(Regex("^[A-Za-z]:.*"))
        ) {
            throw unsafe("unsafe entry name")
        }
        val segments = rawName.trimEnd('/').split('/')
        if (segments.any { it == ".." }) {
            throw unsafe("path traversal in entry name")
        }
        val clean = segments.filter { it.isNotEmpty() && it != "." }
        if (clean.isEmpty() || clean.first() != rootDirectory) {
            throw unsafe("entry outside the model directory")
        }
        return clean.drop(1).joinToString("/")
    }

    private fun copyCounting(
        input: InputStream,
        output: OutputStream,
        buffer: ByteArray,
        soFar: Long,
        maxTotal: Long,
        isCancelled: () -> Boolean,
    ): Long {
        var total = soFar
        while (true) {
            if (isCancelled()) {
                throw ModelFailure(FailureReason.CANCELLED, recoverable = true, message = "Cancelled")
            }
            val read = input.read(buffer)
            if (read < 0) {
                return total
            }
            total += read
            if (total > maxTotal) {
                throw unsafe("archive expands beyond the limit")
            }
            output.write(buffer, 0, read)
        }
    }

    private fun skipCounting(
        input: InputStream,
        buffer: ByteArray,
        soFar: Long,
        maxTotal: Long,
        isCancelled: () -> Boolean,
    ): Long = copyCounting(input, OutputStream.nullOutputStream(), buffer, soFar, maxTotal, isCancelled)

    private fun unsafe(message: String) = ModelFailure(FailureReason.ARCHIVE_UNSAFE, recoverable = false, message = message)
}
