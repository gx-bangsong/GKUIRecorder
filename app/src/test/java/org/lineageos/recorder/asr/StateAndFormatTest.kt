/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.recorder.asr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.lineageos.recorder.asr.engine.RecognitionOptions
import org.lineageos.recorder.asr.engine.Utterance
import org.lineageos.recorder.asr.model.MiniJson
import org.lineageos.recorder.asr.model.ModelState
import org.lineageos.recorder.asr.model.ModelStateTransitions
import org.lineageos.recorder.asr.model.FailureReason
import org.lineageos.recorder.asr.model.InstalledFile
import org.lineageos.recorder.asr.model.InstalledManifest
import org.lineageos.recorder.asr.transcription.TranscriptRecord
import org.lineageos.recorder.asr.transcription.TranscriptSegment
import org.lineageos.recorder.asr.transcription.TranscriptStatus
import org.lineageos.recorder.asr.transcription.TranscriptText
import org.lineageos.recorder.asr.model.ModelCatalog
import org.lineageos.recorder.asr.model.DownloadMeta

class StateAndFormatTest {
    private val ready = ModelState.Ready("2025-09-09", 1000L)
    private val downloading = ModelState.Downloading(1L, 10L, 0L)

    @Test
    fun legalStateTransitionsAreAccepted() {
        val allowed = listOf(
            ModelState.NotInstalled to ModelState.Queued,
            ModelState.Queued to downloading,
            downloading to ModelState.Verifying,
            ModelState.Verifying to ModelState.Installing,
            ModelState.Installing to ready,
            ready to ModelState.UpdateAvailable("a", "b"),
            ModelState.Failed(FailureReason.NETWORK, true) to ModelState.Queued,
        )
        for ((from, to) in allowed) {
            assertTrue("$from -> $to", ModelStateTransitions.isAllowed(from, to))
        }
    }

    @Test
    fun illegalJumpsAreRejected() {
        assertFalse(ModelStateTransitions.isAllowed(ready, downloading))
        assertFalse(ModelStateTransitions.isAllowed(ModelState.NotInstalled, ModelState.Verifying))
        assertFalse(ModelStateTransitions.isAllowed(ModelState.Installing, downloading))
    }

    @Test
    fun busyStatesBlockDelete() {
        assertTrue(ModelStateTransitions.isBusy(ModelState.Queued))
        assertTrue(ModelStateTransitions.isBusy(downloading))
        assertTrue(ModelStateTransitions.isBusy(ModelState.Verifying))
        assertTrue(ModelStateTransitions.isBusy(ModelState.Installing))
        assertFalse(ModelStateTransitions.isBusy(ready))
        assertFalse(ModelStateTransitions.isBusy(ModelState.Failed(FailureReason.UNKNOWN, false)))
    }

    @Test
    fun catalogEntriesAreValid() {
        val model = ModelCatalog.all.single()
        assertEquals("sensevoice-small-int8-2025-09-09", model.id)
        assertTrue(model.archiveUrl.startsWith("https://"))
        assertNotNull(ModelCatalog.find(model.id))
        assertNull(ModelCatalog.find("missing"))
        assertTrue(model.standaloneDownloads.any { it.localName == "silero_vad.onnx" })
    }

    @Test
    fun recognitionOptionsValidateRanges() {
        assertEquals(2, RecognitionOptions("auto", true, RecognitionOptions.DEFAULT_THREADS).numThreads)
        assertThrows(IllegalArgumentException::class.java) { RecognitionOptions("fr", true, 2) }
        assertThrows(IllegalArgumentException::class.java) { RecognitionOptions("zh", true, 0) }
        assertThrows(IllegalArgumentException::class.java) { RecognitionOptions("zh", true, 5) }
    }

    @Test
    fun miniJsonRoundTripsEscapesAndNumbers() {
        val source = mapOf(
            "text" to "line\n\"quote\" \\ 中文 \u0001",
            "n" to 12345L,
            "f" to 0.5,
            "nil" to null,
            "list" to listOf(1L, "x", true),
        )
        val parsed = MiniJson.parseObject(MiniJson.write(source))
        assertEquals(source["text"], parsed["text"])
        assertEquals(12345L, (parsed["n"] as Number).toLong())
        assertEquals(0.5, (parsed["f"] as Number).toDouble(), 1e-9)
        assertNull(parsed["nil"])
        assertEquals(listOf(1L, "x", true), parsed["list"])
    }

    @Test
    fun miniJsonRejectsMalformedInput() {
        assertThrows(Exception::class.java) { MiniJson.parse("{\"a\":") }
        assertThrows(Exception::class.java) { MiniJson.parse("{\"a\" 1}") }
    }

    @Test
    fun installedManifestSurvivesJson() {
        val manifest = InstalledManifest(
            modelId = "m", version = "v1", archiveSha256 = "a".repeat(64),
            installedBytes = 42, installedAtMillis = 7,
            files = listOf(InstalledFile("tokens.txt", "b".repeat(64), 42)),
        )
        assertEquals(manifest, InstalledManifest.fromJson(manifest.toJson()))
    }

    @Test
    fun downloadMetaSurvivesJsonAndRejectsGarbage() {
        val meta = DownloadMeta("https://x/y", "\"e\"", null, 9)
        assertEquals(meta, DownloadMeta.fromJson(meta.toJson()))
        assertNull(DownloadMeta.fromJson("not json"))
    }

    @Test
    fun transcriptRecordRoundTrip() {
        val record = TranscriptRecord(
            status = TranscriptStatus.COMPLETED, progress = 1f, text = "你好 world",
            segments = listOf(TranscriptSegment(0, 1500, "你好"), TranscriptSegment(1500, 3000, "world")),
            language = "auto", modelId = "m", modelVersion = "v", errorMessage = null,
            createdAtMillis = 1, updatedAtMillis = 2,
        )
        assertEquals(record, TranscriptRecord.fromJson(record.toJson()))
    }

    @Test
    fun transcriptTextJoinsWithoutSplittingChinese() {
        val text = TranscriptText.join(
            listOf(Utterance(0, 1, "你好"), Utterance(1, 2, " world"), Utterance(2, 3, "again"), Utterance(3, 4, "  ")),
        )
        assertEquals("你好 world again", text)
        assertEquals("中文测试", TranscriptText.join(listOf(Utterance(0, 1, "中文"), Utterance(1, 2, "测试"))))
    }
}
