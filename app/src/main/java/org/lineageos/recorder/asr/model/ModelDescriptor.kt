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
    /** Exact byte size. Null when not verified yet, which makes the manifest incomplete. */
    val sizeBytes: Long? = null,
)

/** Result of checking that a catalog entry can be downloaded and verified. */
sealed interface ManifestValidationResult {
    data object Complete : ManifestValidationResult

    data class Incomplete(val problems: List<String>) : ManifestValidationResult
}

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

    /**
     * Fail-closed check of the key resources. Every key file needs a non-empty name, a positive
     * byte size and a SHA-256 in hex form that is not all zeros. Anything missing makes the entry
     * unusable for download and installation, so no staging directory or READY marker is created.
     */
    fun validateManifest(): ManifestValidationResult {
        val problems = mutableListOf<String>()
        fun checkHash(label: String, hash: String?) {
            when {
                hash == null -> problems.add("$label: sha256 missing")
                !HEX_SHA256_REGEX.matches(hash) -> problems.add("$label: sha256 malformed")
                hash.all { it == '0' } -> problems.add("$label: sha256 is all zeros")
            }
        }
        fun checkSize(label: String, bytes: Long?) {
            if (bytes == null || bytes <= 0L) problems.add("$label: size missing")
        }
        checkHash("archive", archiveSha256)
        checkSize("archive", archiveBytes)
        standaloneDownloads.forEach {
            checkHash(it.localName, it.sha256)
            checkSize(it.localName, it.sizeBytes)
        }
        expectedFiles.forEach { file ->
            if (file.localName.isBlank()) problems.add("expected file without a name")
            val standalone = standaloneDownloads.firstOrNull { it.localName == file.localName }
            checkHash(file.localName, file.sha256 ?: standalone?.sha256)
            checkSize(file.localName, file.sizeBytes ?: standalone?.sizeBytes)
        }
        return if (problems.isEmpty()) {
            ManifestValidationResult.Complete
        } else {
            ManifestValidationResult.Incomplete(problems)
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
        val HEX_SHA256_REGEX = Regex("[0-9a-fA-F]{64}")
        const val SAFETY_MARGIN_BYTES = 64L * 1024 * 1024
    }
}
