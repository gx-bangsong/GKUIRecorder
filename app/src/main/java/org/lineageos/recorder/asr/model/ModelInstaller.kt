/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.recorder.asr.model

import java.io.File
import java.io.IOException

/** Receives phase changes and download progress while [ModelInstaller.install] runs. */
interface InstallListener {
    fun onDownloading(downloadedBytes: Long, totalBytes: Long, bytesPerSecond: Long)

    fun onVerifying()

    fun onInstalling()
}

/**
 * Installs a model in this order, and only creates READY at the very end:
 *
 *   1. download the archive to `archive.tar.bz2.part` (resumable)
 *   2. verify the archive SHA-256 (the published digest), then rename it
 *   3. download standalone files (resumable), verify each SHA-256
 *   4. stream-extract only the expected files into `install/` (path-safe, size-capped)
 *   5. verify every expected file, write metadata.json and THIRD_PARTY_NOTICES.txt
 *   6. move `install/` to models/{id}/{version}/ (atomic rename)
 *   7. write READY, then remove older versions and the staging area
 *
 * A failure before step 6 leaves the previously installed version untouched.
 */
class ModelInstaller(
    private val storage: ModelStorage,
    private val downloader: ResumableDownloader,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** Installs [descriptor] and returns the installed version. No-op when it is already ready. */
    fun install(
        descriptor: ModelDescriptor,
        listener: InstallListener,
        allowDownload: Boolean = true,
        isCancelled: () -> Boolean,
    ): String {
        // Fail-closed, before any staging directory or READY marker is created.
        if (descriptor.validateManifest() is ManifestValidationResult.Incomplete) {
            throw ModelFailure(
                FailureReason.MANIFEST_INCOMPLETE,
                recoverable = false,
                message = "Model manifest is incomplete",
            )
        }
        if (storage.isReady(descriptor)) {
            return descriptor.version
        }
        val staging = storage.stagingDir(descriptor.id, descriptor.version)
        staging.mkdirs()
        checkFreeSpace(descriptor, staging)

        // Offline (import): every file must already be staged. Nothing is fetched from the network.
        if (!allowDownload) {
            val missing = missingFiles(descriptor, staging)
            if (missing.isNotEmpty()) {
                throw ModelFailure(
                    FailureReason.FILE_MISSING,
                    recoverable = true,
                    message = "Missing files: ${missing.joinToString()}",
                )
            }
        }
        val archive = downloadArchive(descriptor, staging, allowDownload, listener, isCancelled)
        downloadStandalone(descriptor, staging, allowDownload, listener, isCancelled, completedBefore = descriptor.archiveBytes)

        listener.onInstalling()
        val installDir = File(staging, INSTALL_DIR)
        installDir.deleteRecursively()
        installDir.mkdirs()
        var promoted = false
        try {
            extract(descriptor, archive, installDir, isCancelled)
            for (standalone in descriptor.standaloneDownloads) {
                File(staging, standaloneName(standalone.localName))
                    .copyTo(File(installDir, standalone.localName), overwrite = true)
            }
            val manifest = verifyAndDescribe(descriptor, installDir)
            File(installDir, ModelStorage.METADATA_FILE).writeText(manifest.toJson())
            File(installDir, NOTICE_FILE).writeText(noticeText(descriptor))

            val finalDir = storage.versionDir(descriptor.id, descriptor.version)
            if (finalDir.exists()) {
                // Left behind by an install that never wrote READY
                finalDir.deleteRecursively()
            }
            finalDir.parentFile?.mkdirs()
            if (!installDir.renameTo(finalDir)) {
                throw ModelFailure(FailureReason.STORAGE, recoverable = true, message = "Cannot move install")
            }
            promoted = true
            storage.markReady(finalDir, descriptor.version)
            storage.removeOtherVersions(descriptor.id, descriptor.version)
            staging.deleteRecursively()
            return descriptor.version
        } finally {
            if (!promoted) {
                installDir.deleteRecursively()
            }
        }
    }

    private fun checkFreeSpace(descriptor: ModelDescriptor, staging: File) {
        val alreadyDownloaded = staging.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        val needed = (descriptor.requiredFreeSpaceBytes - alreadyDownloaded).coerceAtLeast(0L)
        val free = storage.freeSpaceBytes()
        if (free < needed) {
            throw ModelFailure(
                FailureReason.NO_SPACE,
                recoverable = true,
                message = "Need ${needed / MB} MB free, have ${free / MB} MB",
            )
        }
    }

    /**
     * Names of the files an install still needs in [staging]: the archive and every standalone
     * file, when absent or of the wrong size. Used to list missing companions before a failure.
     */
    fun missingFiles(descriptor: ModelDescriptor, staging: File): List<String> {
        val missing = mutableListOf<String>()
        val archive = File(staging, ARCHIVE_NAME)
        if (!archive.isFile || archive.length() != descriptor.archiveBytes) {
            missing += descriptor.archiveUrl.substringAfterLast('/')
        }
        for (standalone in descriptor.standaloneDownloads) {
            val file = File(staging, standaloneName(standalone.localName))
            if (!file.isFile || file.length() != standalone.sizeBytes) {
                missing += standalone.localName
            }
        }
        return missing
    }

    private fun downloadArchive(
        descriptor: ModelDescriptor,
        staging: File,
        allowDownload: Boolean,
        listener: InstallListener,
        isCancelled: () -> Boolean,
    ): File {
        val archive = File(staging, ARCHIVE_NAME)
        if (archive.isFile && archive.length() == descriptor.archiveBytes) {
            // A staged copy is reused only after its digest matches the trusted value again
            if (Digests.sha256Of(archive) == descriptor.archiveSha256) {
                return archive
            }
        }
        archive.delete()
        if (!allowDownload) {
            throw ModelFailure(FailureReason.FILE_MISSING, recoverable = true, message = "Archive is not staged")
        }
        val part = File(staging, "$ARCHIVE_NAME.part")
        val meta = File(staging, "$ARCHIVE_NAME.meta.json")
        downloader.download(
            url = descriptor.archiveUrl,
            partFile = part,
            metaFile = meta,
            expectedBytes = descriptor.archiveBytes,
            progress = { downloaded, total, bps ->
                listener.onDownloading(downloaded, total, bps)
            },
            isCancelled = isCancelled,
        )
        listener.onVerifying()
        if (Digests.sha256Of(part) != descriptor.archiveSha256) {
            // A bad archive cannot be resumed: start the download from zero next time
            part.delete()
            meta.delete()
            throw ModelFailure(
                FailureReason.CHECKSUM_MISMATCH,
                recoverable = true,
                message = "Archive checksum mismatch",
            )
        }
        meta.delete()
        if (!part.renameTo(archive)) {
            throw ModelFailure(FailureReason.STORAGE, recoverable = true, message = "Cannot store archive")
        }
        return archive
    }

    private fun downloadStandalone(
        descriptor: ModelDescriptor,
        staging: File,
        allowDownload: Boolean,
        listener: InstallListener,
        isCancelled: () -> Boolean,
        completedBefore: Long,
    ) {
        var completed = completedBefore
        for (standalone in descriptor.standaloneDownloads) {
            val target = File(staging, standaloneName(standalone.localName))
            if (target.isFile && target.length() == standalone.sizeBytes) {
                if (Digests.sha256Of(target) == standalone.sha256) {
                    completed += standalone.sizeBytes
                    continue
                }
            }
            target.delete()
            if (!allowDownload) {
                throw ModelFailure(
                    FailureReason.FILE_MISSING,
                    recoverable = true,
                    message = "File is not staged: ${standalone.localName}",
                )
            }
            val part = File(staging, "${target.name}.part")
            val meta = File(staging, "${target.name}.meta.json")
            val base = completed
            downloader.download(
                url = standalone.url,
                partFile = part,
                metaFile = meta,
                expectedBytes = standalone.sizeBytes,
                progress = { downloaded, total, bps ->
                    listener.onDownloading(base + downloaded, descriptor.downloadBytes, bps)
                },
                isCancelled = isCancelled,
            )
            listener.onVerifying()
            if (Digests.sha256Of(part) != standalone.sha256) {
                part.delete()
                meta.delete()
                throw ModelFailure(
                    FailureReason.CHECKSUM_MISMATCH,
                    recoverable = true,
                    message = "Checksum mismatch for ${standalone.localName}",
                )
            }
            meta.delete()
            if (!part.renameTo(target)) {
                throw ModelFailure(FailureReason.STORAGE, recoverable = true, message = "Cannot store file")
            }
            completed += standalone.sizeBytes
        }
    }

    private fun extract(descriptor: ModelDescriptor, archive: File, installDir: File, isCancelled: () -> Boolean) {
        val wanted = descriptor.expectedFiles
            .filter { it.archivePath != null }
            .associate { it.archivePath!! to it.localName }
        try {
            ArchiveExtractor.extract(
                archive = archive,
                rootDirectory = descriptor.archiveRoot,
                wanted = wanted,
                destDir = installDir,
                maxTotalBytes = descriptor.maxExtractedBytes,
                isCancelled = isCancelled,
            )
        } catch (e: IOException) {
            // Corrupt compressed data. The archive hash already matched, so retry from scratch.
            archive.delete()
            throw ModelFailure(
                FailureReason.CHECKSUM_MISMATCH,
                recoverable = true,
                message = "Archive cannot be decompressed (${e.javaClass.simpleName})",
            )
        } catch (e: ModelFailure) {
            if (e.reason == FailureReason.ARCHIVE_UNSAFE) {
                archive.delete()
            }
            throw e
        }
    }

    private fun verifyAndDescribe(descriptor: ModelDescriptor, installDir: File): InstalledManifest {
        val records = descriptor.expectedFiles.map { expected ->
            val file = File(installDir, expected.localName)
            if (!file.isFile || file.length() == 0L) {
                throw ModelFailure(
                    FailureReason.FILE_MISSING,
                    recoverable = false,
                    message = "Expected file missing: ${expected.localName}",
                )
            }
            val hash = Digests.sha256Of(file)
            if (expected.sha256 != null && hash != expected.sha256) {
                throw ModelFailure(
                    FailureReason.CHECKSUM_MISMATCH,
                    recoverable = true,
                    message = "Checksum mismatch: ${expected.localName}",
                )
            }
            InstalledFile(expected.localName, hash, file.length())
        }
        return InstalledManifest(
            modelId = descriptor.id,
            version = descriptor.version,
            archiveSha256 = descriptor.archiveSha256,
            installedBytes = records.sumOf { it.sizeBytes },
            installedAtMillis = clock(),
            files = records,
        )
    }

    private fun noticeText(descriptor: ModelDescriptor): String = buildString {
        appendLine("${descriptor.displayName} ${descriptor.version}")
        appendLine("License: ${descriptor.license.name}")
        appendLine("License URL: ${descriptor.license.url}")
        appendLine("Attribution: ${descriptor.license.attribution}")
        appendLine("Runtime: ${descriptor.minRuntimeVersion} (sherpa-onnx, Apache-2.0)")
        descriptor.standaloneDownloads.forEach {
            appendLine("Also included: ${it.localName} from ${it.url}")
        }
    }

    companion object {
        const val ARCHIVE_NAME = "archive.tar.bz2"
        const val INSTALL_DIR = "install"
        const val NOTICE_FILE = "THIRD_PARTY_NOTICES.txt"
        private const val MB = 1024L * 1024

        fun standaloneName(localName: String) = "standalone-$localName"
    }
}
