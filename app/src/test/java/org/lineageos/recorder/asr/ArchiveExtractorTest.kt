/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.recorder.asr

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.lineageos.recorder.asr.model.ArchiveExtractor
import org.lineageos.recorder.asr.model.FailureReason
import org.lineageos.recorder.asr.model.ModelFailure
import java.io.File

class ArchiveExtractorTest {
    private lateinit var dir: File
    private lateinit var archive: File
    private lateinit var out: File

    @Before
    fun setUp() {
        dir = newTempDir()
        archive = File(dir, "a.tar.bz2")
        out = File(dir, "out")
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun extract(maxTotal: Long = 1L shl 20, wanted: Map<String, String> = mapOf("a.bin" to "a.bin")) =
        ArchiveExtractor.extract(archive, "root", wanted, out, maxTotal) { false }

    @Test
    fun extractsOnlyWantedFiles() {
        writeTarBz2(archive, listOf(TarSpec("root/a.bin", ByteArray(10) { 1 }), TarSpec("root/other.bin", ByteArray(5))))
        val written = extract()
        assertEquals(mapOf("a.bin" to 10L), written)
        assertFalse(File(out, "other.bin").exists())
    }

    @Test
    fun rejectsParentTraversal() {
        writeTarBz2(archive, listOf(TarSpec("root/../evil.bin", ByteArray(4))))
        assertEquals(FailureReason.ARCHIVE_UNSAFE, assertThrows(ModelFailure::class.java) { extract() }.reason)
        assertFalse(File(dir, "evil.bin").exists())
    }

    @Test
    fun rejectsAbsolutePath() {
        writeTarBz2(archive, listOf(TarSpec("/etc/passwd", ByteArray(4))))
        assertEquals(FailureReason.ARCHIVE_UNSAFE, assertThrows(ModelFailure::class.java) { extract() }.reason)
    }

    @Test
    fun rejectsEntriesOutsideRoot() {
        writeTarBz2(archive, listOf(TarSpec("other/a.bin", ByteArray(4))))
        assertEquals(FailureReason.ARCHIVE_UNSAFE, assertThrows(ModelFailure::class.java) { extract() }.reason)
    }

    @Test
    fun rejectsSymbolicLinks() {
        writeTarBz2(archive, listOf(TarSpec("root/a.bin", linkTarget = "/etc/passwd")))
        assertEquals(FailureReason.ARCHIVE_UNSAFE, assertThrows(ModelFailure::class.java) { extract() }.reason)
    }

    @Test
    fun rejectsBackslashAndNulNames() {
        assertThrows(ModelFailure::class.java) { ArchiveExtractor.relativeName("root\\..\\x", "root") }
        assertThrows(ModelFailure::class.java) { ArchiveExtractor.relativeName("root/a\u0000b", "root") }
        assertThrows(ModelFailure::class.java) { ArchiveExtractor.relativeName("C:/x", "root") }
    }

    @Test
    fun relativeNameStripsRoot() {
        assertEquals("sub/x.bin", ArchiveExtractor.relativeName("root/sub/x.bin", "root"))
        assertEquals("", ArchiveExtractor.relativeName("root/", "root"))
    }

    @Test
    fun expansionLimitStopsExtraction() {
        // Skipped (unwanted) entries count toward the limit too
        writeTarBz2(archive, listOf(TarSpec("root/a.bin", ByteArray(100)), TarSpec("root/big.bin", ByteArray(5000))))
        assertEquals(FailureReason.ARCHIVE_UNSAFE, assertThrows(ModelFailure::class.java) { extract(maxTotal = 1000) }.reason)
    }

    @Test
    fun missingExpectedFileFails() {
        writeTarBz2(archive, listOf(TarSpec("root/other.bin", ByteArray(4))))
        assertEquals(FailureReason.ARCHIVE_UNSAFE, assertThrows(ModelFailure::class.java) { extract() }.reason)
    }

    @Test
    fun truncatedArchiveIsIoError() {
        writeTarBz2(archive, listOf(TarSpec("root/a.bin", pseudoRandomBytes(200_000, 3))))
        archive.writeBytes(archive.readBytes().copyOf(1000))
        assertThrows(java.io.IOException::class.java) { extract() }
    }
}
