/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.recorder.asr.model

/** Inference engines the app knows how to run. New engines are added here and in the engine package. */
enum class EngineKind(val id: String) {
    SENSEVOICE_SHERPA("sensevoice-sherpa-onnx"),
}

data class ModelLicense(
    val name: String,
    val url: String,
    val attribution: String,
)

/**
 * A file the runtime needs after installation.
 *
 * @property archivePath path inside the model archive, relative to the archive root directory.
 * Null when the file is a standalone download (see [StandaloneDownload]).
 * @property sha256 expected SHA-256 (lowercase hex). Null when the hash is not published upstream;
 * then it is computed from the archive that passed its published SHA-256 and recorded in metadata.json.
 */
data class ExpectedFile(
    val archivePath: String?,
    val localName: String,
    val sha256: String?,
)

/** A file downloaded on its own, outside the model archive (for example the VAD model). */
data class StandaloneDownload(
    val url: String,
    val localName: String,
    val sha256: String,
    val sizeBytes: Long,
)

/**
 * Describes one installable model. Everything the installer needs is here so that a second model
 * is a new catalog entry, not new code.
 */
data class ModelDescriptor(
    val id: String,
    val displayName: String,
    val version: String,
    val engine: EngineKind,
    val supportedLanguages: List<String>,
    val archiveUrl: String,
    val archiveSha256: String,
    val archiveBytes: Long,
    val archiveRoot: String,
    val standaloneDownloads: List<StandaloneDownload>,
    val expectedFiles: List<ExpectedFile>,
    /** Upper bound of decompressed bytes accepted from the archive (zip-bomb guard). */
    val maxExtractedBytes: Long,
    val license: ModelLicense,
    val minRuntimeVersion: String,
    val minRamMb: Int?,
    val useCase: String,
) {
    init {
        require(ID_REGEX.matches(id)) { "Invalid model id" }
        require(ID_REGEX.matches(version)) { "Invalid model version" }
        require(SHA256_REGEX.matches(archiveSha256)) { "archiveSha256 must be 64 hex chars" }
        require(archiveUrl.startsWith("https://")) { "Model URLs must use HTTPS" }
        standaloneDownloads.forEach {
            require(it.url.startsWith("https://")) { "Model URLs must use HTTPS" }
            require(SHA256_REGEX.matches(it.sha256)) { "Standalone sha256 must be 64 hex chars" }
            require(SAFE_FILE_NAME.matches(it.localName)) { "Unsafe local file name" }
        }
        expectedFiles.forEach {
            require(SAFE_FILE_NAME.matches(it.localName)) { "Unsafe local file name" }
            it.sha256?.let { hash -> require(SHA256_REGEX.matches(hash)) { "Bad sha256" } }
        }
        require(expectedFiles.map { it.localName }.toSet().size == expectedFiles.size) {
            "Duplicate expected file names"
        }
    }

    /** Bytes that have to be downloaded: the archive plus every standalone file. */
    val downloadBytes: Long
        get() = archiveBytes + standaloneDownloads.sumOf { it.sizeBytes }

    val downloadUrls: List<String>
        get() = listOf(archiveUrl) + standaloneDownloads.map { it.url }

    /**
     * Free space needed before a download starts: the download itself, the decompression bound and
     * a fixed safety margin. Actual installed size is measured after installation (see metadata.json).
     */
    val requiredFreeSpaceBytes: Long
        get() = downloadBytes + maxExtractedBytes + SAFETY_MARGIN_BYTES

    companion object {
        val ID_REGEX = Regex("^[a-z0-9][a-z0-9._-]{0,63}$")
        val SAFE_FILE_NAME = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$")
        val SHA256_REGEX = Regex("^[0-9a-f]{64}$")
        const val SAFETY_MARGIN_BYTES = 64L * 1024 * 1024
    }
}
