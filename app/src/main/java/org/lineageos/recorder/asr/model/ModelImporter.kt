/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.recorder.asr.model

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest

/** Which trusted file the user is supplying. */
sealed interface ImportTarget {
    /** The official model archive from the catalog. */
    data object Archive : ImportTarget

    /** A companion file listed in the catalog as a standalone download (for example silero_vad.onnx). */
    data class Companion(val localName: String) : ImportTarget
}

interface ImportListener {
    /** Bytes copied so far out of [totalBytes] (the trusted size, never a guess). */
    fun onCopying(copiedBytes: Long, totalBytes: Long)
    fun onVerifying()
    fun onInstalling()
}

/**
 * Local import of a model package. It only replaces where the bytes come from: the picked file is
 * streamed into the same staging area the downloader uses, its SHA-256 and size are checked
 * against the trusted catalog values, and then [ModelInstaller.install] runs with
 * `allowDownload = false`. Validation, extraction, READY and cleanup are the installer's own code.
 *
 * The source is only read. It is never moved, modified or deleted, and nothing is kept that refers
 * to it. Installed files live under app storage, so deleting the source later keeps the model.
 */
class ModelImporter(
    private val storage: ModelStorage,
    private val installer: ModelInstaller,
    private val freeSpace: () -> Long = storage::freeSpaceBytes,
) {
    /** True when this exact version is already installed and ready. Nothing is copied then. */
    fun alreadyInstalled(descriptor: ModelDescriptor): Boolean = storage.isReady(descriptor)

    /**
     * Copies [source] into staging as [target], verifying it on the way. Throws [ModelFailure] and
     * leaves no partial file when the copy is cancelled, unreadable, too large for the free space,
     * or does not match the trusted size and digest. Files already staged for another target are
     * kept, so a missing companion can be supplied later.
     */
    fun stage(
        descriptor: ModelDescriptor,
        target: ImportTarget,
        source: InputStream,
        listener: ImportListener,
        isCancelled: () -> Boolean,
    ): File {
        if (descriptor.validateManifest() is ManifestValidationResult.Incomplete) {
            throw ModelFailure(FailureReason.MANIFEST_INCOMPLETE, recoverable = false, message = "Manifest incomplete")
        }
        val (finalName, expectedBytes, expectedSha) = expectationFor(descriptor, target)
        val staging = storage.stagingDir(descriptor.id, descriptor.version)
        staging.mkdirs()
        val part = File(staging, "$finalName.import.part")
        part.delete()
        if (freeSpace() < expectedBytes + SAFETY_MARGIN_BYTES) {
            throw ModelFailure(FailureReason.NO_SPACE, recoverable = true, message = "Not enough free space")
        }

        val digest = MessageDigest.getInstance("SHA-256")
        var copied = 0L
        try {
            source.use { input ->
                part.outputStream().buffered().use { out ->
                    val buffer = ByteArray(BUFFER_BYTES)
                    while (true) {
                        if (isCancelled()) {
                            throw ModelFailure(FailureReason.CANCELLED, recoverable = true, message = "Cancelled")
                        }
                        val read = input.read(buffer)
                        if (read < 0) break
                        out.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                        copied += read
                        listener.onCopying(copied, expectedBytes)
                    }
                }
            }
        } catch (e: ModelFailure) {
            part.delete()
            throw e
        } catch (e: SecurityException) {
            // The persisted grant for the picked document was revoked
            part.delete()
            throw ModelFailure(FailureReason.SOURCE_UNAVAILABLE, recoverable = false, message = "Permission lost")
        } catch (e: IOException) {
            part.delete()
            val noSpace = e.message?.contains("No space left", ignoreCase = true) == true
            throw if (noSpace) {
                ModelFailure(FailureReason.NO_SPACE, recoverable = true, message = "Disk full while copying")
            } else {
                ModelFailure(FailureReason.SOURCE_UNAVAILABLE, recoverable = true, message = "Read interrupted")
            }
        }

        listener.onVerifying()
        if (copied != expectedBytes) {
            part.delete()
            throw ModelFailure(
                FailureReason.SIZE_MISMATCH,
                recoverable = false,
                message = "Size $copied, expected $expectedBytes",
            )
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (actual != expectedSha) {
            part.delete()
            throw ModelFailure(
                FailureReason.CHECKSUM_MISMATCH,
                recoverable = false,
                message = "Checksum does not match the catalog",
            )
        }
        val finalFile = File(staging, finalName)
        finalFile.delete()
        if (!part.renameTo(finalFile)) {
            part.delete()
            throw ModelFailure(FailureReason.STORAGE, recoverable = true, message = "Cannot store imported file")
        }
        return finalFile
    }

    /**
     * Runs the installer without network. Missing archive or companion files fail with
     * [FailureReason.FILE_MISSING] and their names. Nothing is marked READY unless every check
     * passes.
     */
    fun install(descriptor: ModelDescriptor, listener: ImportListener, isCancelled: () -> Boolean): String =
        installer.install(
            descriptor,
            object : InstallListener {
                override fun onDownloading(downloadedBytes: Long, totalBytes: Long, bytesPerSecond: Long) = Unit
                override fun onVerifying() = listener.onVerifying()
                override fun onInstalling() = listener.onInstalling()
            },
            allowDownload = false,
            isCancelled = isCancelled,
        )

    /** Names of the files still missing in staging for [descriptor]. Empty when all are present. */
    fun missing(descriptor: ModelDescriptor): List<String> =
        installer.missingFiles(descriptor, storage.stagingDir(descriptor.id, descriptor.version))

    private data class Expectation(val fileName: String, val bytes: Long, val sha256: String)

    private fun expectationFor(descriptor: ModelDescriptor, target: ImportTarget): Expectation = when (target) {
        is ImportTarget.Archive -> Expectation(
            ModelInstaller.ARCHIVE_NAME,
            descriptor.archiveBytes,
            descriptor.archiveSha256,
        )
        is ImportTarget.Companion -> {
            val standalone = descriptor.standaloneDownloads.firstOrNull { it.localName == target.localName }
                ?: throw ModelFailure(
                    FailureReason.INTERNAL,
                    recoverable = false,
                    message = "Not a companion file of this model",
                )
            Expectation(
                ModelInstaller.standaloneName(standalone.localName),
                standalone.sizeBytes,
                standalone.sha256,
            )
        }
    }

    companion object {
        private const val BUFFER_BYTES = 64 * 1024
        private const val SAFETY_MARGIN_BYTES = 64L * 1024 * 1024
    }
}
