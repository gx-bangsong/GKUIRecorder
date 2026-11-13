/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.recorder.asr

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.lineageos.recorder.asr.model.Digests
import org.lineageos.recorder.asr.model.EngineKind
import org.lineageos.recorder.asr.model.ExpectedFile
import org.lineageos.recorder.asr.model.FailureReason
import org.lineageos.recorder.asr.model.ImportListener
import org.lineageos.recorder.asr.model.ImportTarget
import org.lineageos.recorder.asr.model.ModelDescriptor
import org.lineageos.recorder.asr.model.ModelFailure
import org.lineageos.recorder.asr.model.ModelImporter
import org.lineageos.recorder.asr.model.ModelInstaller
import org.lineageos.recorder.asr.model.ModelLicense
import org.lineageos.recorder.asr.model.ModelStorage
import org.lineageos.recorder.asr.model.ResumableDownloader
import org.lineageos.recorder.asr.model.StandaloneDownload
import java.io.File
import java.io.IOException
import java.io.InputStream

/**
 * Local import through the real installer, archive extractor and digests. No network: the
 * downloader is wired to fail if it is ever called.
 */
class ModelImporterTest {
    private lateinit var base: File
    private lateinit var storage: ModelStorage
    private lateinit var installer: ModelInstaller
    private lateinit var importer: ModelImporter

    private val modelBytes = pseudoRandomBytes(200_000, seed = 11)
    private val tokensBytes = "<blk>\n<s>\n".toByteArray()
    private val vadBytes = pseudoRandomBytes(50_000, seed = 12)
    private val listener = object : ImportListener {
        override fun onCopying(copiedBytes: Long, totalBytes: Long) = Unit
        override fun onVerifying() = Unit
        override fun onInstalling() = Unit
    }

    private lateinit var archive: File
    private lateinit var vad: File

    @Before
    fun setUp() {
        base = newTempDir()
        storage = ModelStorage(base)
        installer = ModelInstaller(
            storage,
            ResumableDownloader(allowPlainHttp = false, openConnection = { throw IOException("network disabled in test") }),
            clock = { 1_700_000_000_000L },
        )
        importer = ModelImporter(storage, installer)
        archive = File(base, "official.tar.bz2")
        writeTarBz2(
            archive,
            listOf(
                TarSpec("root/model.int8.onnx", modelBytes),
                TarSpec("root/tokens.txt", tokensBytes),
                TarSpec("root/test_wavs/0.wav", ByteArray(100)),
            ),
        )
        vad = File(base, "silero_vad.onnx").apply { writeBytes(vadBytes) }
    }

    @After
    fun tearDown() {
        base.deleteRecursively()
    }

    private fun descriptor(
        version: String = "2024-07-17",
        archiveSha: String = Digests.sha256Of(archive),
        archiveBytes: Long = archive.length(),
        vadSha: String = Digests.sha256Of(vad),
        vadBytes: Long = vad.length(),
    ) = ModelDescriptor(
        id = "sensevoice-test",
        displayName = "SenseVoiceSmall INT8 (test)",
        version = version,
        engine = EngineKind.SENSEVOICE_SHERPA,
        supportedLanguages = listOf("auto", "zh"),
        archiveUrl = "https://example.invalid/official.tar.bz2",
        archiveSha256 = archiveSha,
        archiveBytes = archiveBytes,
        archiveRoot = "root",
        standaloneDownloads = listOf(
            StandaloneDownload("https://example.invalid/silero_vad.onnx", "silero_vad.onnx", vadSha, vadBytes),
        ),
        expectedFiles = listOf(
            ExpectedFile("model.int8.onnx", "model.int8.onnx", Digests.sha256Of(modelBytes.inputStream()), modelBytes.size.toLong()),
            ExpectedFile("tokens.txt", "tokens.txt", Digests.sha256Of(tokensBytes.inputStream()), tokensBytes.size.toLong()),
            ExpectedFile(null, "silero_vad.onnx", vadSha),
        ),
        maxExtractedBytes = 10L * 1024 * 1024,
        license = ModelLicense("test", "https://example.invalid/license", "test"),
        minRuntimeVersion = "1.13.8",
        minRamMb = null,
        useCase = "test",
    )

    /** Copies [file] to a fresh temp file, so the test can delete the "external source" later. */
    private fun sourceCopy(file: File, name: String): File =
        File(base, "picked-$name").apply { writeBytes(file.readBytes()) }

    private fun stageArchive(d: ModelDescriptor, source: InputStream = archive.inputStream(), cancelled: () -> Boolean = { false }) =
        importer.stage(d, ImportTarget.Archive, source, listener, cancelled)

    private fun stageVad(d: ModelDescriptor) =
        importer.stage(d, ImportTarget.Companion("silero_vad.onnx"), vad.inputStream(), listener) { false }

    private fun install(d: ModelDescriptor, cancelled: () -> Boolean = { false }) =
        importer.install(d, listener, cancelled)

    private fun stagingFiles(d: ModelDescriptor): List<String> =
        storage.stagingDir(d.id, d.version).listFiles()?.map { it.name }.orEmpty()

    @Test
    fun completeTrustedImportInstallsAndSourceDeletionKeepsTheModel() {
        val d = descriptor()
        val archiveSource = sourceCopy(archive, "archive")
        val vadSource = sourceCopy(vad, "vad")
        stageArchive(d, archiveSource.inputStream())
        importer.stage(d, ImportTarget.Companion("silero_vad.onnx"), vadSource.inputStream(), listener) { false }
        assertTrue(importer.missing(d).isEmpty())

        assertEquals("2024-07-17", install(d))
        assertTrue(storage.isReady(d))
        assertTrue(archiveSource.delete())
        assertTrue(vadSource.delete())
        // Installed files live in app storage: deleting the source changes nothing
        assertTrue(storage.isReady(d))
        assertTrue(File(storage.versionDir(d.id, d.version), "model.int8.onnx").isFile)
        assertFalse(storage.stagingDir(d.id, d.version).exists())
    }

    @Test
    fun missingCompanionIsNeverReadyAndCanBeCompleted() {
        val d = descriptor()
        stageArchive(d)
        assertEquals(listOf("silero_vad.onnx"), importer.missing(d))

        val failure = assertThrows(ModelFailure::class.java) { install(d) }
        assertEquals(FailureReason.FILE_MISSING, failure.reason)
        assertTrue(failure.message!!.contains("silero_vad.onnx"))
        assertFalse(storage.isReady(d))
        assertFalse(File(storage.versionDir(d.id, d.version), "READY").exists())

        stageVad(d)
        assertEquals(d.version, install(d))
        assertTrue(storage.isReady(d))
    }

    @Test
    fun badArchiveHashIsRejectedAndNoPartialFileRemains() {
        val d = descriptor(archiveSha = "ab".repeat(32))
        val failure = assertThrows(ModelFailure::class.java) { stageArchive(d) }
        assertEquals(FailureReason.CHECKSUM_MISMATCH, failure.reason)
        assertFalse(failure.recoverable)
        assertTrue(stagingFiles(d).none { it.contains("import") || it.contains("archive") })
        assertFalse(storage.isReady(d))
    }

    @Test
    fun wrongSizeIsRejected() {
        val d = descriptor(archiveBytes = archive.length() + 1)
        val failure = assertThrows(ModelFailure::class.java) { stageArchive(d) }
        assertEquals(FailureReason.SIZE_MISMATCH, failure.reason)
        assertTrue(stagingFiles(d).isEmpty())
    }

    @Test
    fun corruptArchiveWithTrustedHashDoesNotBecomeReady() {
        val garbage = pseudoRandomBytes(4_096, seed = 99)
        val corrupt = File(base, "corrupt.tar.bz2").apply { writeBytes(garbage) }
        val d = descriptor(archiveSha = Digests.sha256Of(corrupt), archiveBytes = corrupt.length())
        stageArchive(d, corrupt.inputStream())
        stageVad(d)
        val failure = assertThrows(ModelFailure::class.java) { install(d) }
        assertEquals(FailureReason.CHECKSUM_MISMATCH, failure.reason)
        assertFalse(storage.isReady(d))
    }

    @Test
    fun pathTraversalAndAbsolutePathsAreRejected() {
        val names = listOf("root/../escape.txt", "/etc/escape.txt")
        names.forEachIndexed { index, name ->
            val evil = File(base, "evil$index.tar.bz2")
            writeTarBz2(
                evil,
                listOf(TarSpec("root/model.int8.onnx", modelBytes), TarSpec(name, ByteArray(4)), TarSpec("root/tokens.txt", tokensBytes)),
            )
            val d = descriptor(
                version = "evil$index",
                archiveSha = Digests.sha256Of(evil),
                archiveBytes = evil.length(),
            )
            stageArchive(d, evil.inputStream())
            stageVad(d)
            val failure = assertThrows(ModelFailure::class.java) { install(d) }
            assertEquals(name, FailureReason.ARCHIVE_UNSAFE, failure.reason)
            assertFalse(storage.isReady(d))
            assertFalse(File(base, "escape.txt").exists())
        }
    }

    @Test
    fun cancelDuringCopyKeepsTheOldModelAndRemovesThePartialFile() {
        val old = descriptor(version = "2024-07-17")
        stageArchive(old)
        stageVad(old)
        install(old)

        val newer = descriptor(version = "2025-09-09")
        val failure = assertThrows(ModelFailure::class.java) {
            // Cancel after the first buffer
            var calls = 0
            stageArchive(newer, archive.inputStream()) { calls++ > 0 }
        }
        assertEquals(FailureReason.CANCELLED, failure.reason)
        assertTrue(storage.isReady(old))
        assertFalse(stagingFiles(newer).any { it.endsWith(".import.part") })
    }

    @Test
    fun readFailureKeepsTheOldModel() {
        val old = descriptor(version = "2024-07-17")
        stageArchive(old)
        stageVad(old)
        install(old)

        val newer = descriptor(version = "2025-09-09")
        val broken = object : InputStream() {
            private var sent = 0
            override fun read(): Int = throw IOException("read interrupted")
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (sent > 1_000) throw IOException("read interrupted")
                sent += 1_000
                java.util.Arrays.fill(b, off, off + 1_000, 0)
                return 1_000
            }
        }
        val failure = assertThrows(ModelFailure::class.java) { stageArchive(newer, broken) }
        assertEquals(FailureReason.SOURCE_UNAVAILABLE, failure.reason)
        assertTrue(storage.isReady(old))
        assertFalse(stagingFiles(newer).any { it.endsWith(".import.part") })
    }

    @Test
    fun insufficientSpaceFailsBeforeCopyAndKeepsTheOldModel() {
        val old = descriptor(version = "2024-07-17")
        stageArchive(old)
        stageVad(old)
        install(old)

        val tight = ModelImporter(storage, installer, freeSpace = { 0L })
        val newer = descriptor(version = "2025-09-09")
        val failure = assertThrows(ModelFailure::class.java) {
            tight.stage(newer, ImportTarget.Archive, archive.inputStream(), listener) { false }
        }
        assertEquals(FailureReason.NO_SPACE, failure.reason)
        assertTrue(storage.isReady(old))
    }

    @Test
    fun duplicateOfAVerifiedVersionIsDetectedWithoutCopying() {
        val d = descriptor()
        stageArchive(d)
        stageVad(d)
        install(d)
        assertTrue(importer.alreadyInstalled(d))
        // Only the installed version exists: no second copy was made
        val versions = storage.modelsRoot.resolve(d.id).listFiles()?.map { it.name }.orEmpty()
        assertEquals(listOf(d.version), versions)
    }

    @Test
    fun importNeverWritesReadyWhenTheCompanionIsNotFromTheCatalog() {
        val d = descriptor()
        val failure = assertThrows(ModelFailure::class.java) {
            importer.stage(d, ImportTarget.Companion("unknown.onnx"), vad.inputStream(), listener) { false }
        }
        assertEquals(FailureReason.INTERNAL, failure.reason)
        assertFalse(storage.isReady(d))
    }
}
