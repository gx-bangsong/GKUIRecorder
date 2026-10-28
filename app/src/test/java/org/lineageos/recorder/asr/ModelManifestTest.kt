/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.recorder.asr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.lineageos.recorder.asr.model.EngineKind
import org.lineageos.recorder.asr.model.ExpectedFile
import org.lineageos.recorder.asr.model.FailureReason
import org.lineageos.recorder.asr.model.InstallListener
import org.lineageos.recorder.asr.model.ManifestValidationResult
import org.lineageos.recorder.asr.model.ModelCatalog
import org.lineageos.recorder.asr.model.ModelDescriptor
import org.lineageos.recorder.asr.model.ModelFailure
import org.lineageos.recorder.asr.model.ModelInstaller
import org.lineageos.recorder.asr.model.ModelLicense
import org.lineageos.recorder.asr.model.ModelStorage
import org.lineageos.recorder.asr.model.ResumableDownloader
import org.lineageos.recorder.asr.model.StandaloneDownload

class ModelManifestTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val good = "a".repeat(63) + "1"
    private val goodVad = "b".repeat(63) + "2"

    /** A manifest with every key resource verified. Only the hash and size values are test data. */
    private fun complete(
        modelSha: String? = good,
        modelBytes: Long? = 200_000L,
        tokensSha: String? = good,
        tokensBytes: Long? = 300L,
        archiveSha: String = good,
        archiveBytes: Long = 1_000_000L,
    ) = ModelDescriptor(
        id = "sensevoice-small-int8-2024-07-17",
        displayName = "SenseVoiceSmall INT8",
        version = "2024-07-17",
        engine = EngineKind.SENSEVOICE_SHERPA,
        supportedLanguages = listOf("auto", "zh"),
        archiveUrl = "https://example.test/archive.tar.bz2",
        archiveSha256 = archiveSha,
        archiveBytes = archiveBytes,
        archiveRoot = "root",
        standaloneDownloads = listOf(
            StandaloneDownload("https://example.test/silero_vad.onnx", "silero_vad.onnx", goodVad, 643_854L),
        ),
        expectedFiles = listOf(
            ExpectedFile("model.int8.onnx", "model.int8.onnx", modelSha, modelBytes),
            ExpectedFile("tokens.txt", "tokens.txt", tokensSha, tokensBytes),
            ExpectedFile(null, "silero_vad.onnx", goodVad),
        ),
        maxExtractedBytes = 512L * 1024 * 1024,
        license = ModelLicense("FunASR Model Open Source License 1.1", "https://example.test/license", "test"),
        minRuntimeVersion = "sherpa-onnx 1.13.8",
        minRamMb = null,
        useCase = "test",
    )

    @Test
    fun completeManifestPasses() {
        assertEquals(ManifestValidationResult.Complete, complete().validateManifest())
    }

    @Test
    fun missingModelHashFails() {
        val result = complete(modelSha = null).validateManifest()
        assertTrue(result is ManifestValidationResult.Incomplete)
        assertTrue((result as ManifestValidationResult.Incomplete).problems.any { it.startsWith("model.int8.onnx") })
    }

    @Test
    fun missingTokensHashFails() {
        val result = complete(tokensSha = null).validateManifest()
        assertTrue(result is ManifestValidationResult.Incomplete)
        assertTrue((result as ManifestValidationResult.Incomplete).problems.any { it.startsWith("tokens.txt") })
    }

    @Test
    fun allZeroHashesFailValidation() {
        // Well-formed hex, so the constructor accepts it. Validation must still reject it.
        assertTrue(complete(tokensSha = "0".repeat(64)).validateManifest() is ManifestValidationResult.Incomplete)
        assertTrue(complete(archiveSha = "0".repeat(64)).validateManifest() is ManifestValidationResult.Incomplete)
    }

    @Test
    fun emptyOrMalformedHashesCannotBuildADescriptor() {
        // The constructor is the second line of defence: such an entry cannot even be created.
        assertThrows(IllegalArgumentException::class.java) { complete(modelSha = "") }
        assertThrows(IllegalArgumentException::class.java) { complete(modelSha = "xyz") }
        assertThrows(IllegalArgumentException::class.java) { complete(modelSha = "a".repeat(63)) }
    }

    @Test
    fun zeroOrNegativeBytesFail() {
        assertTrue(complete(modelBytes = 0L).validateManifest() is ManifestValidationResult.Incomplete)
        assertTrue(complete(tokensBytes = -1L).validateManifest() is ManifestValidationResult.Incomplete)
        assertTrue(complete(modelBytes = null).validateManifest() is ManifestValidationResult.Incomplete)
        assertTrue(complete(archiveBytes = 0L).validateManifest() is ManifestValidationResult.Incomplete)
    }

    @Test
    fun installerRefusesIncompleteManifestBeforeCreatingAnything() {
        val root = tmp.root
        val storage = ModelStorage(root)
        val installer = ModelInstaller(storage, ResumableDownloader())
        val listener = object : InstallListener {
            override fun onDownloading(downloadedBytes: Long, totalBytes: Long, bytesPerSecond: Long) = Unit
            override fun onVerifying() = Unit
            override fun onInstalling() = Unit
        }
        val incomplete = complete(modelSha = null)

        val error = assertThrows(ModelFailure::class.java) {
            installer.install(incomplete, listener) { false }
        }
        assertEquals(FailureReason.MANIFEST_INCOMPLETE, error.reason)
        assertFalse("not a network failure, not retryable", error.recoverable)
        // No staging directory and no READY marker
        assertFalse(storage.stagingDir(incomplete.id, incomplete.version).exists())
        assertFalse(storage.isReady(incomplete))
        assertFalse(root.resolve("models").exists() && root.resolve("models").list().orEmpty().isNotEmpty())
    }

    /** The shipped catalog must pass. This fails until the official file hashes are recorded. */
    @Test
    fun shippedDefaultModelHasVerifiedKeyHashes() {
        assertEquals(ManifestValidationResult.Complete, ModelCatalog.default.validateManifest())
    }
}
