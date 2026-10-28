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
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.lineageos.recorder.asr.model.ModelStorage
import org.lineageos.recorder.asr.transcription.AtomicFileWriter
import org.lineageos.recorder.asr.transcription.KeyValueStore
import org.lineageos.recorder.asr.transcription.LegacyTranscriptReader
import org.lineageos.recorder.asr.transcription.NioAtomicFileWriter
import org.lineageos.recorder.asr.transcription.TranscriptCorruptException
import org.lineageos.recorder.asr.transcription.TranscriptFileStore
import org.lineageos.recorder.asr.transcription.TranscriptJson
import org.lineageos.recorder.asr.transcription.TranscriptLoad
import org.lineageos.recorder.asr.transcription.TranscriptMerge
import org.lineageos.recorder.asr.transcription.TranscriptRecord
import org.lineageos.recorder.asr.transcription.TranscriptSegment
import org.lineageos.recorder.asr.transcription.TranscriptSource
import org.lineageos.recorder.asr.transcription.TranscriptStatus
import org.lineageos.recorder.asr.transcription.TranscriptText
import org.lineageos.recorder.asr.transcription.TranscriptionRepository
import java.io.File
import java.io.IOException

class TranscriptStorageTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val uri = "content://media/external/audio/media/1"
    private val stableId by lazy { TranscriptRecord.stableRecordingId(uri) }

    private fun transcriptsDir() = File(tmp.root, "noBackup/transcripts")

    private class MemoryKeyValue : KeyValueStore {
        val map = HashMap<String, String>()
        override fun get(key: String) = map[key]
        override fun put(key: String, value: String) {
            map[key] = value
        }
        override fun remove(key: String) {
            map.remove(key)
        }
    }

    /** Writer that fails before anything is replaced, as a full disk would. */
    private class FailingWriter : AtomicFileWriter {
        override fun writeBytes(file: File, bytes: ByteArray) {
            throw IOException("disk full")
        }
    }

    private fun record(
        status: TranscriptStatus = TranscriptStatus.COMPLETED,
        text: String = "你好 world",
        segments: List<TranscriptSegment> = listOf(TranscriptSegment(0, 1500, "你好"), TranscriptSegment(1500, 3000, "world")),
    ) = TranscriptRecord(
        stableRecordingId = stableId,
        status = status,
        progress = 1f,
        text = text,
        segments = segments,
        language = "auto",
        modelId = "sensevoice-small-int8-2024-07-17",
        modelVersion = "2024-07-17",
        runtimeVersion = TranscriptionRepository.RUNTIME_VERSION,
        lastCompletedAudioPositionMs = 3000,
        resumeFromMs = 0,
        errorCode = null,
        errorMessage = null,
        createdAtMillis = 10,
        updatedAtMillis = 20,
    )

    // ---- Identity -----------------------------------------------------------------------------

    @Test
    fun stableRecordingIdIsFixedSha256OfTheUri() {
        assertEquals(
            "30d1357b175cea04e54d688111406853ed87e898fe6241426fc4e33ebc53864c",
            TranscriptRecord.stableRecordingId("content://rec/1"),
        )
    }

    @Test
    fun stableRecordingIdIs64LowercaseHex() {
        assertTrue(Regex("^[0-9a-f]{64}$").matches(stableId))
    }

    @Test
    fun fileNameNeverContainsTheUriOrTitle() {
        val store = TranscriptFileStore(transcriptsDir(), NioAtomicFileWriter())
        store.write(record())
        val names = transcriptsDir().list().orEmpty().toList()
        assertEquals(listOf("$stableId.json"), names)
        assertFalse(names.any { it.contains("content") || it.contains("media") })
    }

    // ---- JSON ---------------------------------------------------------------------------------

    @Test
    fun jsonRoundTripKeepsEveryField() {
        val original = record()
        assertEquals(original, TranscriptJson.decode(TranscriptJson.encode(original)))
    }

    @Test
    fun unknownFieldsAreIgnored() {
        val json = String(TranscriptJson.encode(record()), Charsets.UTF_8)
            .replaceFirst("{", "{\"futureField\":{\"a\":[1,2]},")
        val decoded = TranscriptJson.decode(json.toByteArray(Charsets.UTF_8))
        assertEquals(record(), decoded)
    }

    @Test
    fun progressIsNormalizedToZeroOne() {
        assertEquals(1f, record().copy(progress = 7f).normalized().progress, 0f)
        assertEquals(0f, record().copy(progress = -3f).normalized().progress, 0f)
        assertEquals(0f, record().copy(progress = Float.NaN).normalized().progress, 0f)
    }

    @Test
    fun segmentTimesAreClampedSoStartIsNotNegativeAndEndNotBeforeStart() {
        val fixed = TranscriptSegment(-5, -10, "x").normalized()
        assertEquals(0L, fixed.startMs)
        assertEquals(0L, fixed.endMs)
        val reversed = TranscriptSegment(500, 100, "y").normalized()
        assertEquals(500L, reversed.startMs)
        assertEquals(500L, reversed.endMs)
    }

    @Test
    fun oversizedJsonIsRejectedBeforeParsing() {
        val huge = ByteArray(TranscriptJson.MAX_BYTES + 1) { ' '.code.toByte() }
        assertThrows(Exception::class.java) { TranscriptJson.decode(huge) }
        val store = TranscriptFileStore(transcriptsDir(), NioAtomicFileWriter())
        transcriptsDir().mkdirs()
        File(transcriptsDir(), "$stableId.json").writeBytes(huge)
        assertThrows(TranscriptCorruptException::class.java) { store.read(stableId) }
    }

    // ---- Atomic writes and corruption ---------------------------------------------------------

    @Test
    fun atomicWriteSucceedsAndLeavesNoTemporaryFile() {
        val store = TranscriptFileStore(transcriptsDir(), NioAtomicFileWriter())
        store.write(record(text = "第一版"))
        store.write(record(text = "第二版"))
        assertEquals("第二版", store.read(stableId)!!.text)
        assertEquals(listOf("$stableId.json"), transcriptsDir().list().orEmpty().toList())
    }

    @Test
    fun failedWriteKeepsThePreviousRecordReadable() {
        val good = TranscriptFileStore(transcriptsDir(), NioAtomicFileWriter())
        good.write(record(text = "旧结果"))
        val broken = TranscriptFileStore(transcriptsDir(), FailingWriter())
        assertThrows(IOException::class.java) { broken.write(record(text = "新结果")) }
        assertEquals("旧结果", good.read(stableId)!!.text)
    }

    @Test
    fun truncatedJsonIsIsolatedAsRecoverableCorruption() {
        transcriptsDir().mkdirs()
        val full = TranscriptJson.encode(record())
        File(transcriptsDir(), "$stableId.json").writeBytes(full.copyOf(full.size / 2))
        val repo = TranscriptionRepository(TranscriptFileStore(transcriptsDir(), NioAtomicFileWriter()))
        val load = repo.load(uri)
        assertTrue(load is TranscriptLoad.RecoverableCorruption)
        val name = (load as TranscriptLoad.RecoverableCorruption).quarantinedName
        assertNotNull(name)
        assertTrue(Regex("^${stableId}\\.corrupt-\\d+\\.json$").matches(name!!))
        assertFalse(File(transcriptsDir(), "$stableId.json").exists())
        assertTrue(File(transcriptsDir(), name).isFile)
    }

    @Test
    fun corruptionIsReportedAsFailedWithCorruptCodeNotAsCompleted() {
        transcriptsDir().mkdirs()
        File(transcriptsDir(), "$stableId.json").writeText("{\"status\":\"COMPLETED\"")
        val repo = TranscriptionRepository(TranscriptFileStore(transcriptsDir(), NioAtomicFileWriter()))
        val record = repo.get(uri)!!
        assertEquals(TranscriptStatus.FAILED, record.status)
        assertEquals(TranscriptRecord.ERROR_CORRUPT, record.errorCode)
        assertNull(repo.completedText(uri))
    }

    @Test
    fun onlyTheThreeMostRecentCorruptFilesAreKept() {
        val store = TranscriptFileStore(transcriptsDir(), NioAtomicFileWriter())
        transcriptsDir().mkdirs()
        for (t in listOf(100L, 200L, 300L, 400L, 500L)) {
            File(transcriptsDir(), "$stableId.json").writeText("{broken")
            store.quarantine(stableId, t)
        }
        val kept = transcriptsDir().list().orEmpty().filter { it.contains(".corrupt-") }.sorted()
        assertEquals(
            listOf("$stableId.corrupt-300.json", "$stableId.corrupt-400.json", "$stableId.corrupt-500.json"),
            kept,
        )
    }

    // ---- Migration from SharedPreferences -----------------------------------------------------

    @Test
    fun beta3PlainTextMigratesAsCompletedWithTextUnchanged() {
        val kv = MemoryKeyValue()
        val legacyText = "旧版本的转写文本，标点保留。Hello, world!"
        kv.put("transcript:$uri", legacyText)
        val repo = TranscriptionRepository(
            TranscriptFileStore(transcriptsDir(), NioAtomicFileWriter()),
            LegacyTranscriptReader(kv),
        )
        val load = repo.load(uri) as TranscriptLoad.Found
        assertEquals(TranscriptSource.LEGACY_TEXT, load.source)
        assertEquals(TranscriptStatus.COMPLETED, load.record.status)
        assertEquals(legacyText, load.record.text)
        assertEquals(legacyText, TranscriptFileStore(transcriptsDir(), NioAtomicFileWriter()).read(stableId)!!.text)
    }

    @Test
    fun plainTextThatLooksLikeBrokenJsonIsTreatedAsText() {
        val kv = MemoryKeyValue()
        kv.put("transcript:$uri", "{不是 JSON")
        val load = TranscriptionRepository(
            TranscriptFileStore(transcriptsDir(), NioAtomicFileWriter()),
            LegacyTranscriptReader(kv),
        ).load(uri) as TranscriptLoad.Found
        assertEquals(TranscriptSource.LEGACY_TEXT, load.source)
        assertEquals("{不是 JSON", load.record.text)
    }

    @Test
    fun intermediateBeta4JsonMigratesKeepingStatusProgressSegmentsAndModel() {
        val kv = MemoryKeyValue()
        kv.put(
            "transcript:$uri",
            """{"status":"COMPLETED","progress":1.0,"text":"你好 world",""" +
                """"segments":[{"startMs":0,"endMs":1500,"text":"你好"},{"startMs":1500,"endMs":3000,"text":"world"}],""" +
                """"language":"zh","modelId":"sensevoice-small-int8-2025-09-09","modelVersion":"2025-09-09",""" +
                """"errorMessage":null,"createdAtMillis":5,"updatedAtMillis":6}""",
        )
        val load = TranscriptionRepository(
            TranscriptFileStore(transcriptsDir(), NioAtomicFileWriter()),
            LegacyTranscriptReader(kv),
        ).load(uri) as TranscriptLoad.Found
        assertEquals(TranscriptSource.LEGACY_STRUCTURED, load.source)
        val r = load.record
        assertEquals(TranscriptStatus.COMPLETED, r.status)
        assertEquals(1f, r.progress, 0f)
        assertEquals(2, r.segments.size)
        assertEquals("zh", r.language)
        assertEquals("sensevoice-small-int8-2025-09-09", r.modelId)
        assertEquals("2025-09-09", r.modelVersion)
        assertEquals(3000L, r.lastCompletedAudioPositionMs)
    }

    @Test
    fun oldKeyIsRemovedOnlyAfterTheNewFileIsWritten() {
        val kv = MemoryKeyValue()
        kv.put("transcript:$uri", "旧文本")
        val repo = TranscriptionRepository(
            TranscriptFileStore(transcriptsDir(), FailingWriter()),
            LegacyTranscriptReader(kv),
        )
        val load = repo.load(uri) as TranscriptLoad.Found
        assertEquals("旧文本", load.record.text)
        assertTrue("old key must survive a failed migration", kv.map.containsKey("transcript:$uri"))
        // The next read tries again
        val retry = TranscriptionRepository(
            TranscriptFileStore(transcriptsDir(), NioAtomicFileWriter()),
            LegacyTranscriptReader(kv),
        ).load(uri) as TranscriptLoad.Found
        assertEquals("旧文本", retry.record.text)
        assertFalse(kv.map.containsKey("transcript:$uri"))
    }

    @Test
    fun newFileWinsOverOldKeyWhenBothExist() {
        val kv = MemoryKeyValue()
        kv.put("transcript:$uri", "旧文本")
        TranscriptFileStore(transcriptsDir(), NioAtomicFileWriter()).write(record(text = "新文本"))
        val load = TranscriptionRepository(
            TranscriptFileStore(transcriptsDir(), NioAtomicFileWriter()),
            LegacyTranscriptReader(kv),
        ).load(uri) as TranscriptLoad.Found
        assertEquals(TranscriptSource.FILE, load.source)
        assertEquals("新文本", load.record.text)
    }

    @Test
    fun corruptFileFallsBackToOldKeyWhenPresent() {
        val kv = MemoryKeyValue()
        kv.put("transcript:$uri", "恢复的旧文本")
        transcriptsDir().mkdirs()
        File(transcriptsDir(), "$stableId.json").writeText("{broken")
        val load = TranscriptionRepository(
            TranscriptFileStore(transcriptsDir(), NioAtomicFileWriter()),
            LegacyTranscriptReader(kv),
        ).load(uri) as TranscriptLoad.Found
        assertEquals("恢复的旧文本", load.record.text)
    }

    // ---- Checkpoints, resume and dedupe -------------------------------------------------------

    @Test
    fun newWritesDoNotTouchSharedPreferences() {
        val kv = MemoryKeyValue()
        val repo = TranscriptionRepository(
            TranscriptFileStore(transcriptsDir(), NioAtomicFileWriter()),
            LegacyTranscriptReader(kv),
        )
        val base = repo.start(uri, "m", "v", "auto")
        repo.checkpoint(uri, base, 0.5f, listOf(TranscriptSegment(0, 1000, "一")))
        repo.complete(uri, base, listOf(TranscriptSegment(0, 1000, "一")))
        assertTrue(kv.map.isEmpty())
        assertEquals("一", repo.completedText(uri))
    }

    @Test
    fun checkpointedSegmentsSurviveRestartAndAreNotDuplicated() {
        val first = TranscriptionRepository(TranscriptFileStore(transcriptsDir(), NioAtomicFileWriter()), clock = { 1L })
        val base = first.start(uri, "m", "v", "auto")
        first.checkpoint(
            uri,
            base,
            0.4f,
            listOf(TranscriptSegment(0, 1000, "甲"), TranscriptSegment(1000, 2000, "乙")),
        )

        // The process dies here. A new repository reads the file.
        val second = TranscriptionRepository(TranscriptFileStore(transcriptsDir(), NioAtomicFileWriter()), clock = { 2L })
        val resumed = second.start(uri, "m", "v", "auto")
        assertEquals(2000L, resumed.resumeFromMs)
        assertEquals(listOf("甲", "乙"), resumed.segments.map { it.text })

        // The re-decode starts from zero and produces the same first segments plus new ones.
        second.complete(
            uri,
            resumed,
            listOf(
                TranscriptSegment(0, 1000, "甲'"),
                TranscriptSegment(1000, 2000, "乙'"),
                TranscriptSegment(2000, 3000, "丙"),
            ),
        )
        val final = second.get(uri)!!
        assertEquals(listOf("甲", "乙", "丙"), final.segments.map { it.text })
        assertEquals("甲乙丙", final.text)
    }

    @Test
    fun resumeIsNotUsedAcrossModelVersions() {
        val repo = TranscriptionRepository(TranscriptFileStore(transcriptsDir(), NioAtomicFileWriter()))
        val base = repo.start(uri, "m", "v1", "auto")
        repo.checkpoint(uri, base, 0.5f, listOf(TranscriptSegment(0, 1000, "旧模型")))
        val other = repo.start(uri, "m", "v2", "auto")
        assertTrue(other.segments.isEmpty())
        assertEquals(0L, other.resumeFromMs)
    }

    @Test
    fun cancelAndFailKeepTheSegmentsOfTheCurrentRun() {
        val repo = TranscriptionRepository(TranscriptFileStore(transcriptsDir(), NioAtomicFileWriter()))
        val base = repo.start(uri, "m", "v", "auto")
        repo.cancel(uri, base, listOf(TranscriptSegment(0, 1000, "已完成")), 0.3f)
        val cancelled = repo.get(uri)!!
        assertEquals(TranscriptStatus.CANCELLED, cancelled.status)
        assertEquals("已完成", cancelled.text)
        assertEquals(0.3f, cancelled.progress, 1e-6f)

        val again = repo.start(uri, "m", "v", "auto")
        repo.fail(uri, again, "boom", listOf(TranscriptSegment(0, 1000, "已完成"), TranscriptSegment(1000, 2000, "还有")), 0.6f)
        val failed = repo.get(uri)!!
        assertEquals(TranscriptStatus.FAILED, failed.status)
        assertEquals(listOf("已完成", "还有"), failed.segments.map { it.text })
    }

    @Test
    fun mergeDropsRerunSegmentsInsideThePrefixAndKeepsStraddlingOnes() {
        val earlier = listOf(TranscriptSegment(0, 1000, "A"), TranscriptSegment(1000, 2000, "B"))
        val current = listOf(
            TranscriptSegment(0, 1000, "A'"),
            TranscriptSegment(1000, 2000, "B'"),
            TranscriptSegment(1900, 2500, "C"),
            TranscriptSegment(2500, 3000, "D"),
        )
        val merged = TranscriptMerge.merge(earlier, 2000L, current)
        assertEquals(listOf("A", "B", "C", "D"), merged.map { it.text })
    }

    @Test
    fun joinStillSeparatesLatinWordsOnly() {
        val text = TranscriptText.join(
            listOf(TranscriptSegment(0, 1, "你好"), TranscriptSegment(1, 2, "world"), TranscriptSegment(2, 3, "再见")),
        )
        assertEquals("你好 world 再见", text)
    }

    // ---- Deletion linkage ---------------------------------------------------------------------

    @Test
    fun removingARecordingsTranscriptDeletesItsFilesAndOldKey() {
        val kv = MemoryKeyValue()
        kv.put("transcript:$uri", "旧")
        val repo = TranscriptionRepository(
            TranscriptFileStore(transcriptsDir(), NioAtomicFileWriter()),
            LegacyTranscriptReader(kv),
        )
        repo.load(uri)
        assertTrue(File(transcriptsDir(), "$stableId.json").isFile)
        repo.remove(uri)
        assertFalse(File(transcriptsDir(), "$stableId.json").exists())
        assertNull(repo.get(uri))
        assertFalse(kv.map.containsKey("transcript:$uri"))
    }

    @Test
    fun deletingAModelDoesNotDeleteTranscripts() {
        val root = tmp.root.resolve("noBackup").apply { mkdirs() }
        val repo = TranscriptionRepository(TranscriptFileStore(File(root, "transcripts"), NioAtomicFileWriter()))
        val base = repo.start(uri, "sensevoice-small-int8-2024-07-17", "2024-07-17", "auto")
        repo.complete(uri, base, listOf(TranscriptSegment(0, 1000, "保留")))
        ModelStorage(root).deleteModel("sensevoice-small-int8-2024-07-17")
        assertEquals("保留", repo.completedText(uri))
    }
}
