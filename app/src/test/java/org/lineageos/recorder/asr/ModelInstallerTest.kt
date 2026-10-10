/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.recorder.asr

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.lineageos.recorder.asr.model.Digests
import org.lineageos.recorder.asr.model.EngineKind
import org.lineageos.recorder.asr.model.ExpectedFile
import org.lineageos.recorder.asr.model.FailureReason
import org.lineageos.recorder.asr.model.InstallListener
import org.lineageos.recorder.asr.model.ModelDescriptor
import org.lineageos.recorder.asr.model.ModelFailure
import org.lineageos.recorder.asr.model.ModelInstaller
import org.lineageos.recorder.asr.model.ModelLicense
import org.lineageos.recorder.asr.model.ModelState
import org.lineageos.recorder.asr.model.ModelStorage
import org.lineageos.recorder.asr.model.ResumableDownloader
import org.lineageos.recorder.asr.model.StandaloneDownload
import java.io.File

class ModelInstallerTest {
    private lateinit var base: File
    private lateinit var storage: ModelStorage
    private lateinit var remote: FakeRemote
    private lateinit var installer: ModelInstaller

    private val modelBytes = pseudoRandomBytes(200_000, seed = 1)
    private val tokensBytes = "<blk>\n<s>\n".toByteArray()
    private val vadBytes = pseudoRandomBytes(50_000, seed = 2)
    private val archiveFile by lazy { File(base, "built-archive.tar.bz2") }

    @Before
    fun setUp() {
        base = newTempDir()
        storage = ModelStorage(base)
        remote = FakeRemote()
        installer = ModelInstaller(
            storage,
            ResumableDownloader(allowPlainHttp = false, openConnection = { mapToLocal(remote, it) }),
            clock = { 1_700_000_000_000L },
        )
        buildArchive(
            listOf(
                TarSpec("root/model.int8.onnx", modelBytes),
                TarSpec("root/tokens.txt", tokensBytes),
                TarSpec("root/test_wavs/0.wav", ByteArray(1000)),
                TarSpec("root/README.md", "readme".toByteArray()),
            ),
        )
        remote.resources["/silero_vad.onnx"] = FakeResource(vadBytes)
    }

    @After
    fun tearDown() {
        remote.close()
        base.deleteRecursively()
    }

    private fun buildArchive(entries: List<TarSpec>) {
        writeTarBz2(archiveFile, entries)
        remote.resources["/archive.tar.bz2"] = FakeResource(archiveFile.readBytes())
    }

    private fun descriptor(
        version: String = "2025-09-09",
        archiveSha: String = Digests.sha256Of(archiveFile),
        vadSha: String = Digests.sha256Of(vadBytes.inputStream()),
        vadBytesSize: Long = vadBytes.size.toLong(),
        maxExtracted: Long = 10L * 1024 * 1024,
    ) = ModelDescriptor(
        id = "sensevoice-small-int8-2025-09-09",
        displayName = "SenseVoiceSmall INT8",
        version = version,
        engine = EngineKind.SENSEVOICE_SHERPA,
        supportedLanguages = listOf("auto", "zh", "en", "yue", "ja", "ko"),
        archiveUrl = fakeHost() + "/archive.tar.bz2",
        archiveSha256 = archiveSha,
        archiveBytes = archiveFile.length(),
        archiveRoot = "root",
        standaloneDownloads = listOf(
            StandaloneDownload(fakeHost() + "/silero_vad.onnx", "silero_vad.onnx", vadSha, vadBytesSize),
        ),
        expectedFiles = listOf(
            ExpectedFile("model.int8.onnx", "model.int8.onnx", null),
            ExpectedFile("tokens.txt", "tokens.txt", null),
            ExpectedFile(null, "silero_vad.onnx", vadSha),
        ),
        maxExtractedBytes = maxExtracted,
        license = ModelLicense("FunASR Model Open Source License", "https://example.test/license", "test"),
        minRuntimeVersion = "1.13.8",
        minRamMb = null,
        useCase = "test",
    )

    private val listener = object : InstallListener {
        override fun onDownloading(downloadedBytes: Long, totalBytes: Long, bytesPerSecond: Long) {}
        override fun onVerifying() {}
        override fun onInstalling() {}
    }

    private fun install(d: ModelDescriptor) = installer.install(d, listener) { false }

    @Test
    fun successfulInstallWritesReadyAndOnlyWantedFiles() {
        val d = descriptor()
        assertEquals("2025-09-09", install(d))
        assertTrue(storage.isReady(d))
        val dir = storage.versionDir(d.id, d.version)
        assertTrue(File(dir, "READY").isFile)
        assertTrue(File(dir, "model.int8.onnx").readBytes().contentEquals(modelBytes))
        assertTrue(File(dir, "tokens.txt").isFile)
        assertTrue(File(dir, "silero_vad.onnx").readBytes().contentEquals(vadBytes))
        assertTrue(File(dir, "THIRD_PARTY_NOTICES.txt").isFile)
        assertFalse("test_wavs must not be extracted", File(dir, "0.wav").exists())
        val manifest = storage.readyManifest(d.id, d.version)!!
        assertEquals(3, manifest.files.size)
        assertEquals(vadBytes.size.toLong(), manifest.files.single { it.name == "silero_vad.onnx" }.sizeBytes)
        assertNotNull(Digests.sha256Of(File(dir, "model.int8.onnx")).takeIf { it.length == 64 })
        assertFalse(storage.stagingDir(d.id, d.version).exists())
        assertEquals(ModelState.Ready(d.version, manifest.installedBytes), storage.diskState(d))
    }

    @Test
    fun repeatedInstallIsNoOpWithoutNetwork() {
        val d = descriptor()
        install(d)
        val requests = remote.seenRanges.size
        install(d)
        assertEquals(requests, remote.seenRanges.size)
    }

    @Test
    fun archiveChecksumMismatchKeepsOldVersionAndNeverWritesReady() {
        val old = descriptor(version = "2025-01-01")
        install(old)

        val bad = descriptor(version = "2025-09-09", archiveSha = "0".repeat(64))
        val failure = assertThrows(ModelFailure::class.java) { install(bad) }
        assertEquals(FailureReason.CHECKSUM_MISMATCH, failure.reason)
        assertTrue(failure.recoverable)

        assertFalse(storage.isReady(bad))
        assertTrue(storage.isReady(old))
        assertEquals(
            ModelState.UpdateAvailable("2025-01-01", "2025-09-09"),
            storage.diskState(bad),
        )
        assertEquals("2025-01-01", storage.readyVersionDir(bad)?.name)
        // Bad archive is never kept for resume
        assertFalse(File(storage.stagingDir(bad.id, bad.version), "archive.tar.bz2.part").exists())
    }

    @Test
    fun successfulUpdateRemovesOldVersion() {
        install(descriptor(version = "2025-01-01"))
        install(descriptor(version = "2025-09-09"))
        assertEquals(listOf("2025-09-09"), storage.installedVersions("sensevoice-small-int8-2025-09-09"))
    }

    @Test
    fun standaloneChecksumMismatchFailsWithoutReady() {
        val d = descriptor(vadSha = "1".repeat(64))
        val failure = assertThrows(ModelFailure::class.java) { install(d) }
        assertEquals(FailureReason.CHECKSUM_MISMATCH, failure.reason)
        assertFalse(storage.isReady(d))
        assertFalse(File(storage.versionDir(d.id, d.version), "READY").exists())
        assertFalse(File(storage.stagingDir(d.id, d.version), "install").exists())
    }

    @Test
    fun zipSlipEntryRejectedAndNothingInstalled() {
        // Archive checksum is valid, the archive itself contains a traversal entry
        buildArchive(
            listOf(
                TarSpec("root/model.int8.onnx", modelBytes),
                TarSpec("root/tokens.txt", tokensBytes),
                TarSpec("root/../../escape.txt", ByteArray(10)),
            ),
        )
        val d = descriptor()
        val failure = assertThrows(ModelFailure::class.java) { install(d) }
        assertEquals(FailureReason.ARCHIVE_UNSAFE, failure.reason)
        assertFalse(failure.recoverable)
        assertFalse(storage.isReady(d))
        assertFalse(File(base, "escape.txt").exists())
        assertFalse(File(storage.stagingDir(d.id, d.version), "install").exists())
        assertFalse("unsafe archive must be deleted", File(storage.stagingDir(d.id, d.version), "archive.tar.bz2").exists())
    }

    @Test
    fun incompleteArchiveMissingExpectedFileFails() {
        buildArchive(listOf(TarSpec("root/model.int8.onnx", modelBytes)))
        val failure = assertThrows(ModelFailure::class.java) { install(descriptor()) }
        assertEquals(FailureReason.ARCHIVE_UNSAFE, failure.reason)
        assertFalse(storage.isReady(descriptor()))
    }

    @Test
    fun interruptedArchiveDownloadResumesOnNextAttempt() {
        val d = descriptor()
        remote.resources["/archive.tar.bz2"]!!.truncateAt = 1000
        val first = assertThrows(ModelFailure::class.java) { install(d) }
        assertEquals(FailureReason.NETWORK, first.reason)
        assertTrue(first.recoverable)
        val part = File(storage.stagingDir(d.id, d.version), "archive.tar.bz2.part")
        assertEquals(1000L, part.length())

        remote.resources["/archive.tar.bz2"]!!.truncateAt = null
        install(d)
        assertEquals("bytes=1000-", remote.seenRanges.filterNotNull().last())
        assertTrue(storage.isReady(d))
    }

    @Test
    fun wrongArchiveLengthIsRejected() {
        remote.resources["/archive.tar.bz2"]!!.declaredLength = archiveFile.length() + 5
        val failure = assertThrows(ModelFailure::class.java) { install(descriptor()) }
        assertEquals(FailureReason.SIZE_MISMATCH, failure.reason)
    }

    @Test
    fun http404AndHttp500DuringInstall() {
        remote.resources["/archive.tar.bz2"]!!.status = 404
        val notFound = assertThrows(ModelFailure::class.java) { install(descriptor()) }
        assertFalse(notFound.recoverable)
        remote.resources["/archive.tar.bz2"]!!.status = 500
        val serverError = assertThrows(ModelFailure::class.java) { install(descriptor()) }
        assertTrue(serverError.recoverable)
    }

    @Test
    fun incompleteVersionIsNeverReadyAndIsCleaned() {
        val d = descriptor()
        val dir = storage.versionDir(d.id, d.version)
        dir.mkdirs()
        File(dir, "metadata.json").writeText("{}")
        File(dir, "model.int8.onnx").writeBytes(modelBytes)
        assertFalse(storage.isReady(d))
        assertEquals(ModelState.NotInstalled, storage.diskState(d))
        storage.removeIncompleteVersions(d.id)
        assertFalse(dir.exists())
    }

    @Test
    fun deleteRemovesInstalledVersionsAndStaging() {
        val d = descriptor()
        install(d)
        File(storage.stagingDir(d.id, "2025-10-01"), "junk").apply { parentFile.mkdirs(); writeText("x") }
        storage.deleteModel(d.id)
        assertFalse(storage.isReady(d))
        assertEquals(ModelState.NotInstalled, storage.diskState(d))
        assertFalse(storage.stagingDir(d.id, d.version).exists())
    }

    @Test
    fun integrityCheckDetectsTamperedFile() {
        val d = descriptor()
        install(d)
        val dir = storage.versionDir(d.id, d.version)
        val manifest = storage.readyManifest(d.id, d.version)!!
        File(dir, "tokens.txt").writeText("tampered") // same size is not guaranteed; differing length is detected
        assertThrows(org.lineageos.recorder.asr.model.ModelIntegrityException::class.java) {
            storage.verifyIntegrity(dir, manifest)
        }
    }

    @Test
    fun descriptorRejectsNonHttpsAndBadHashes() {
        assertThrows(IllegalArgumentException::class.java) {
            descriptor().copy(archiveUrl = "http://example.test/a.tar.bz2")
        }
        assertThrows(IllegalArgumentException::class.java) {
            descriptor().copy(archiveSha256 = "XYZ")
        }
        assertNull(descriptor().expectedFiles.first().sha256)
    }
}
