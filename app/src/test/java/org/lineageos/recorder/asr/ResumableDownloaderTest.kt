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
import org.lineageos.recorder.asr.model.DownloadMeta
import org.lineageos.recorder.asr.model.FailureReason
import org.lineageos.recorder.asr.model.ModelFailure
import org.lineageos.recorder.asr.model.ResumableDownloader
import java.io.File

class ResumableDownloaderTest {
    private lateinit var dir: File
    private lateinit var remote: FakeRemote
    private lateinit var part: File
    private lateinit var meta: File
    private val body = pseudoRandomBytes(300_000, seed = 7)
    private val path = "/model.bin"
    private val url = fakeHost() + path

    @Before
    fun setUp() {
        dir = newTempDir()
        remote = FakeRemote()
        part = File(dir, "model.bin.part")
        meta = File(dir, "model.bin.meta.json")
        remote.resources[path] = FakeResource(body)
    }

    @After
    fun tearDown() {
        remote.close()
        dir.deleteRecursively()
    }

    private fun downloader(allowPlainHttp: Boolean = true) = ResumableDownloader(
        allowPlainHttp = allowPlainHttp,
        openConnection = { mapToLocal(remote, it) },
    )

    private fun run(
        expected: Long = body.size.toLong(),
        isCancelled: () -> Boolean = { false },
    ) = downloader().download(url, part, meta, expected, { _, _, _ -> }, isCancelled)

    private fun seedPartial(bytes: ByteArray, etag: String?) {
        part.writeBytes(bytes)
        meta.writeText(DownloadMeta(url, etag, null, body.size.toLong()).toJson())
    }

    @Test
    fun freshDownloadWritesWholeFileAndMetadata() {
        run()
        assertTrue(part.readBytes().contentEquals(body))
        assertEquals("\"v1\"", DownloadMeta.fromJson(meta.readText())!!.etag)
        assertEquals(null, remote.seenRanges.single())
    }

    @Test
    fun resumesWithRangeAndIfRangeAfterInterruption() {
        seedPartial(body.copyOfRange(0, 100_000), "\"v1\"")
        run()
        assertEquals("bytes=100000-", remote.seenRanges.single())
        assertEquals("\"v1\"", remote.seenIfRanges.single())
        assertTrue(part.readBytes().contentEquals(body))
    }

    @Test
    fun serverIgnoringRangeCausesFullRedownloadWithoutDuplicates() {
        remote.resources[path]!!.honorRange = false
        seedPartial(body.copyOfRange(0, 50_000), "\"v1\"")
        run()
        assertEquals(body.size.toLong(), part.length())
        assertTrue(part.readBytes().contentEquals(body))
    }

    @Test
    fun changedEtagDoesNotResumeAndRestartsFromZero() {
        // Server ignores If-Range, so the 206 it sends carries the new ETag
        remote.resources[path]!!.honorIfRange = false
        remote.resources[path]!!.etag = "\"v2\""
        seedPartial(body.copyOfRange(0, 50_000), "\"v1\"")
        run()
        assertTrue(part.readBytes().contentEquals(body))
        assertEquals("\"v2\"", DownloadMeta.fromJson(meta.readText())!!.etag)
    }

    @Test
    fun changedEtagWithIfRangeHonoredRestartsFromZero() {
        remote.resources[path]!!.etag = "\"v2\""
        seedPartial(body.copyOfRange(0, 50_000), "\"v1\"")
        run()
        assertTrue(part.readBytes().contentEquals(body))
    }

    @Test
    fun rangeNotSatisfiableRestartsOnce() {
        // Partial file longer than the remote file: the server answers 416
        remote.resources[path]!!.body = body.copyOfRange(0, 1000)
        seedPartial(body.copyOfRange(0, 5000), "\"v1\"")
        downloader().download(url, part, meta, 1000, { _, _, _ -> }, { false })
        assertTrue(part.readBytes().contentEquals(body.copyOfRange(0, 1000)))
    }

    @Test
    fun networkDropKeepsPartialAndNextAttemptResumes() {
        remote.resources[path]!!.truncateAt = 120_000
        val first = assertThrows(ModelFailure::class.java) { run() }
        assertEquals(FailureReason.NETWORK, first.reason)
        assertTrue(first.recoverable)
        assertTrue(part.length() in 1 until body.size)
        val kept = part.length()

        remote.resources[path]!!.truncateAt = null
        run()
        assertEquals("bytes=$kept-", remote.seenRanges.last())
        assertTrue(part.readBytes().contentEquals(body))
    }

    @Test
    fun wrongContentLengthIsRejectedAsSizeMismatch() {
        remote.resources[path]!!.declaredLength = body.size.toLong() + 10
        val failure = assertThrows(ModelFailure::class.java) { run() }
        assertEquals(FailureReason.SIZE_MISMATCH, failure.reason)
        assertFalse(failure.recoverable)
    }

    @Test
    fun short200BodyIsDetectedAsNetworkErrorAndKept() {
        // Declares the right length but closes early
        remote.resources[path]!!.truncateAt = 1000
        remote.resources[path]!!.declaredLength = body.size.toLong()
        val failure = assertThrows(ModelFailure::class.java) { run() }
        assertEquals(FailureReason.NETWORK, failure.reason)
        assertTrue(failure.recoverable)
    }

    @Test
    fun notFoundIsPermanentHttpFailure() {
        remote.resources[path]!!.status = 404
        val failure = assertThrows(ModelFailure::class.java) { run() }
        assertEquals(FailureReason.HTTP_STATUS, failure.reason)
        assertFalse(failure.recoverable)
    }

    @Test
    fun serverErrorIsRecoverable() {
        remote.resources[path]!!.status = 500
        val failure = assertThrows(ModelFailure::class.java) { run() }
        assertEquals(FailureReason.HTTP_STATUS, failure.reason)
        assertTrue(failure.recoverable)
    }

    @Test
    fun plainHttpIsRejectedInProduction() {
        val failure = assertThrows(ModelFailure::class.java) {
            downloader(allowPlainHttp = false).download(
                "http://fake.example.test/x", part, meta, 10, { _, _, _ -> }, { false },
            )
        }
        assertFalse(failure.recoverable)
    }

    @Test
    fun cancellationStopsAndKeepsPartial() {
        val failure = assertThrows(ModelFailure::class.java) {
            var calls = 0
            downloader().download(url, part, meta, body.size.toLong(), { _, _, _ -> }, { ++calls > 2 })
        }
        assertEquals(FailureReason.CANCELLED, failure.reason)
    }

    @Test
    fun contentRangeParsing() {
        assertEquals(Pair(100L, 200L), ResumableDownloader.parseContentRange("bytes 100-199/200"))
        assertEquals(null, ResumableDownloader.parseContentRange("garbage"))
    }
}
