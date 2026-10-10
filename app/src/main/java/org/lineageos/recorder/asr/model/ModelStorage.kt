/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.recorder.asr.model

import java.io.File

/** One file recorded in metadata.json at install time. */
data class InstalledFile(
    val name: String,
    val sha256: String,
    val sizeBytes: Long,
)

/** Contents of metadata.json: what was installed, from which verified archive, and how big it is. */
data class InstalledManifest(
    val modelId: String,
    val version: String,
    val archiveSha256: String,
    val installedBytes: Long,
    val installedAtMillis: Long,
    val files: List<InstalledFile>,
) {
    fun toJson(): String = MiniJson.write(
        mapOf(
            "modelId" to modelId,
            "version" to version,
            "archiveSha256" to archiveSha256,
            "installedBytes" to installedBytes,
            "installedAtMillis" to installedAtMillis,
            "files" to files.map {
                mapOf("name" to it.name, "sha256" to it.sha256, "sizeBytes" to it.sizeBytes)
            },
        ),
    )

    companion object {
        @Suppress("UNCHECKED_CAST")
        fun fromJson(text: String): InstalledManifest {
            val root = MiniJson.parseObject(text)
            val files = (root["files"] as List<Map<String, Any?>>).map {
                InstalledFile(
                    name = it["name"] as String,
                    sha256 = it["sha256"] as String,
                    sizeBytes = (it["sizeBytes"] as Number).toLong(),
                )
            }
            return InstalledManifest(
                modelId = root["modelId"] as String,
                version = root["version"] as String,
                archiveSha256 = root["archiveSha256"] as String,
                installedBytes = (root["installedBytes"] as Number).toLong(),
                installedAtMillis = (root["installedAtMillis"] as Number).toLong(),
                files = files,
            )
        }
    }
}

/**
 * Layout under the app's no-backup files directory:
 *
 *   models/{id}/{version}/            verified, usable installs (READY marker inside)
 *   model-staging/{id}/{version}/     downloads and extraction in progress
 *
 * Both live under the same file system so the final step is an atomic rename.
 */
class ModelStorage(baseDir: File) {
    val modelsRoot = File(baseDir, "models")
    val stagingRoot = File(baseDir, "model-staging")
    private val volumeRoot = baseDir

    fun versionDir(modelId: String, version: String): File {
        requireSafeSegment(modelId, ModelDescriptor.ID_REGEX)
        requireSafeSegment(version, ModelDescriptor.ID_REGEX)
        return File(File(modelsRoot, modelId), version)
    }

    fun stagingDir(modelId: String, version: String): File {
        requireSafeSegment(modelId, ModelDescriptor.ID_REGEX)
        requireSafeSegment(version, ModelDescriptor.ID_REGEX)
        return File(File(stagingRoot, modelId), version)
    }

    fun freeSpaceBytes(): Long = volumeRoot.usableSpace

    /**
     * Ready means: READY marker present, metadata.json readable, and every expected file present
     * with the recorded size. Content hashes are checked by [verifyIntegrity] before use.
     */
    fun isReady(descriptor: ModelDescriptor): Boolean {
        val dir = versionDir(descriptor.id, descriptor.version)
        return readReadyDir(dir)?.let { manifest ->
            manifest.files.all { File(dir, it.name).let { f -> f.isFile && f.length() == it.sizeBytes } }
        } ?: false
    }

    /** Installed manifest for a specific version directory, or null when not ready. */
    fun readyManifest(modelId: String, version: String): InstalledManifest? =
        readReadyDir(versionDir(modelId, version))

    /** The version directory to run, preferring the catalog version. Null if nothing usable. */
    fun readyVersionDir(descriptor: ModelDescriptor): File? {
        if (isReady(descriptor)) {
            return versionDir(descriptor.id, descriptor.version)
        }
        return installedVersions(descriptor.id).firstOrNull { version ->
            readyManifest(descriptor.id, version) != null
        }?.let { versionDir(descriptor.id, it) }
    }

    /** Versions with a verified install, newest first (lexicographic order matches the date versions). */
    fun installedVersions(modelId: String): List<String> {
        requireSafeSegment(modelId, ModelDescriptor.ID_REGEX)
        val dirs = File(modelsRoot, modelId).listFiles() ?: return emptyList()
        return dirs.filter { it.isDirectory }.map { it.name }.sortedDescending()
    }

    /**
     * Computes the public state from disk. Never returns Queued/Downloading: those belong to
     * running work and are owned by the repository.
     */
    fun diskState(descriptor: ModelDescriptor): ModelState {
        if (isReady(descriptor)) {
            val manifest = readyManifest(descriptor.id, descriptor.version)
            return ModelState.Ready(descriptor.version, manifest?.installedBytes)
        }
        val older = installedVersions(descriptor.id).firstOrNull { readyManifest(descriptor.id, it) != null }
        return if (older != null) {
            ModelState.UpdateAvailable(installedVersion = older, availableVersion = descriptor.version)
        } else {
            ModelState.NotInstalled
        }
    }

    /**
     * Removes version directories that are not ready (interrupted installs). Ready versions are kept.
     * Never touches staging, which holds resumable downloads.
     */
    fun removeIncompleteVersions(modelId: String) {
        File(modelsRoot, modelId).listFiles()?.forEach { dir ->
            if (dir.isDirectory && readReadyDir(dir) == null) {
                dir.deleteRecursively()
            }
        }
    }

    /** Removes older ready versions once [keepVersion] is ready. Keeps the old one until then. */
    fun removeOtherVersions(modelId: String, keepVersion: String) {
        File(modelsRoot, modelId).listFiles()?.forEach { dir ->
            if (dir.isDirectory && dir.name != keepVersion) {
                dir.deleteRecursively()
            }
        }
    }

    /** Deletes every installed version and every staging file of the model. */
    fun deleteModel(modelId: String) {
        requireSafeSegment(modelId, ModelDescriptor.ID_REGEX)
        File(modelsRoot, modelId).deleteRecursively()
        File(stagingRoot, modelId).deleteRecursively()
    }

    /** Writes the READY marker. Call only after every check has passed. */
    fun markReady(dir: File, version: String) {
        val tmp = File(dir, "READY.tmp")
        tmp.writeText("READY $version\n")
        if (!tmp.renameTo(File(dir, READY_FILE))) {
            tmp.delete()
            throw IllegalStateException("Cannot write READY marker")
        }
    }

    /** Re-hashes every file listed in the manifest. Throws on any mismatch. */
    fun verifyIntegrity(dir: File, manifest: InstalledManifest) {
        for (file in manifest.files) {
            val target = File(dir, file.name)
            if (!target.isFile || target.length() != file.sizeBytes) {
                throw ModelIntegrityException("File missing or size changed: ${file.name}")
            }
            if (Digests.sha256Of(target) != file.sha256) {
                throw ModelIntegrityException("Checksum mismatch: ${file.name}")
            }
        }
    }

    private fun readReadyDir(dir: File): InstalledManifest? {
        val ready = File(dir, READY_FILE)
        val metadata = File(dir, METADATA_FILE)
        if (!ready.isFile || !metadata.isFile) {
            return null
        }
        return runCatching { InstalledManifest.fromJson(metadata.readText()) }.getOrNull()
    }

    private fun requireSafeSegment(value: String, pattern: Regex) {
        require(pattern.matches(value)) { "Unsafe path segment" }
    }

    companion object {
        const val READY_FILE = "READY"
        const val METADATA_FILE = "metadata.json"
    }
}

class ModelIntegrityException(message: String) : Exception(message)
