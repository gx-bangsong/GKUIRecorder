/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.recorder.asr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.lineageos.recorder.asr.audio.Downmix
import org.lineageos.recorder.asr.audio.StreamingResampler
import org.lineageos.recorder.asr.audio.WavParser
import org.lineageos.recorder.asr.model.EngineKind
import org.lineageos.recorder.asr.engine.RecognitionOptions
import org.lineageos.recorder.asr.engine.RecognitionSession
import org.lineageos.recorder.asr.engine.SpeechEngine
import org.lineageos.recorder.asr.engine.Utterance
import org.lineageos.recorder.asr.transcription.KeyValueStore
import org.lineageos.recorder.asr.transcription.SegmentMerger
import org.lineageos.recorder.asr.transcription.TranscriptSegment
import org.lineageos.recorder.asr.transcription.TranscriptStatus
import org.lineageos.recorder.asr.transcription.TranscriptionPipeline
import org.lineageos.recorder.asr.transcription.TranscriptionRepository
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException

class AudioAndTranscriptionTest {
    private fun wav(sampleRate: Int, channels: Int, frames: Int): ByteArray {
        val dataSize = frames * channels * 2
        val out = java.io.ByteArrayOutputStream()
        fun int(v: Int) { out.write(v and 0xFF); out.write((v shr 8) and 0xFF); out.write((v shr 16) and 0xFF); out.write((v shr 24) and 0xFF) }
        fun short(v: Int) { out.write(v and 0xFF); out.write((v shr 8) and 0xFF) }
        out.write("RIFF".toByteArray()); int(36 + dataSize); out.write("WAVE".toByteArray())
        out.write("fmt ".toByteArray()); int(16); short(1); short(channels); int(sampleRate)
        int(sampleRate * channels * 2); short(channels * 2); short(16)
        out.write("data".toByteArray()); int(dataSize)
        repeat(frames * channels) { short(it % 1000) }
        return out.toByteArray()
    }

    @Test
    fun wavHeaderIsParsedAndDurationComputed() {
        val info = WavParser.parse(ByteArrayInputStream(wav(16000, 1, 32000)))!!
        assertEquals(16000, info.sampleRate)
        assertEquals(1, info.channels)
        assertTrue(info.isPcm16)
        assertEquals(2000L, info.durationMs)
    }

    @Test
    fun nonWavInputIsNotParsed() {
        assertNull(WavParser.parse(ByteArrayInputStream("OggS0000".toByteArray())))
    }

    @Test
    fun stereoDownmixAveragesChannels() {
        val interleaved = shortArrayOf(1000, 3000, -2000, -4000)
        val out = FloatArray(2)
        Downmix.pcm16ToMono(interleaved, 2, 2, out)
        assertEquals(2000f / 32768f, out[0], 1e-6f)
        assertEquals(-3000f / 32768f, out[1], 1e-6f)
    }

    @Test
    fun resamplerProducesExpectedLengthAcrossChunks() {
        val resampler = StreamingResampler(48000, 16000)
        var total = 0
        val chunk = FloatArray(4800) { Math.sin(it * 0.05).toFloat() }
        repeat(10) { resampler.process(chunk, chunk.size) { _, n -> total += n } }
        resampler.flush { _, n -> total += n }
        assertTrue("total=$total", Math.abs(total - 16000) <= 2)
    }

    @Test
    fun resamplerPassesThroughSameRate() {
        val resampler = StreamingResampler(16000, 16000)
        var total = 0
        resampler.process(FloatArray(100), 100) { _, n -> total += n }
        assertEquals(100, total)
    }

    @Test
    fun resamplerPreservesLowFrequencyTone() {
        val resampler = StreamingResampler(48000, 16000)
        val input = FloatArray(48000) { Math.sin(2 * Math.PI * 440 * it / 48000.0).toFloat() }
        val output = ArrayList<Float>()
        resampler.process(input, input.size) { buf, n -> for (i in 0 until n) output += buf[i] }
        resampler.flush { buf, n -> for (i in 0 until n) output += buf[i] }
        val peak = output.drop(200).maxOf { Math.abs(it) }
        assertTrue("peak=$peak", peak > 0.8f)
    }

    // ---- Repository ------------------------------------------------------------------------

    private class MemoryStore : KeyValueStore {
        val map = HashMap<String, String>()
        override fun get(key: String) = map[key]
        override fun put(key: String, value: String) { map[key] = value }
        override fun remove(key: String) { map.remove(key) }
    }

    @Test
    fun legacyPlainTextIsMigratedAsCompletedAndNotLost() {
        val store = MemoryStore()
        store.put("transcript:content://rec/1", "旧版本的转写文本")
        val repo = TranscriptionRepository(store)
        val record = repo.get("content://rec/1")!!
        assertEquals(TranscriptStatus.COMPLETED, record.status)
        assertEquals("旧版本的转写文本", record.text)
        assertEquals("旧版本的转写文本", repo.completedText("content://rec/1"))
    }

    @Test
    fun corruptStructuredRecordBecomesFailedNotCompleted() {
        val store = MemoryStore()
        store.put("transcript:u", "{broken")
        val record = TranscriptionRepository(store).get("u")!!
        assertEquals(TranscriptStatus.FAILED, record.status)
        assertNull(TranscriptionRepository(store).completedText("u"))
    }

    @Test
    fun startCheckpointCompleteLifecycleAndDelete() {
        val store = MemoryStore()
        val repo = TranscriptionRepository(store, clock = { 5L })
        val base = repo.start("u", "m", "v", "auto")
        assertEquals(TranscriptStatus.RUNNING, base.status)
        repo.checkpoint("u", base, 0.5f, listOf(TranscriptSegment(0, 1, "一")))
        assertEquals(0.5f, repo.get("u")!!.progress, 1e-6f)
        repo.complete("u", base, listOf(TranscriptSegment(0, 1, "一"), TranscriptSegment(1, 2, "二")))
        assertEquals("一二", repo.completedText("u"))
        repo.remove("u")
        assertNull(repo.get("u"))
    }

    // ---- Pipeline --------------------------------------------------------------------------

    private class FakeSession(private val log: MutableList<String>) : RecognitionSession {
        var closed = false
        private var chunk = 0
        override fun acceptAudio(samples: FloatArray): List<Utterance> {
            log += "accept:${samples.size}"
            chunk++
            return listOf(Utterance(chunk * 1000L, chunk * 1000L + 900, "segment$chunk"))
        }
        override fun finish(): List<Utterance> { log += "finish"; return emptyList() }
        override fun close() { closed = true; log += "close" }
    }

    private class FakeEngine(val log: MutableList<String>) : SpeechEngine {
        override val kind = EngineKind.SENSEVOICE_SHERPA
        var last: FakeSession? = null
        override fun openSession(modelDir: File, options: RecognitionOptions): RecognitionSession {
            log += "open"
            return FakeSession(log).also { last = it }
        }
    }

    private class CancelAfter(private val chunks: Int) : TranscriptionPipeline.Control {
        var seen = 0
        var progressCalls = 0
        override fun checkCancelled() {
            if (seen >= chunks) throw IOException("cancelled")
        }
        override fun beforeChunk() { seen++ }
        override fun onProgress(fraction: Float, utterances: List<Utterance>) { progressCalls++ }
    }

    private object NoopControl : TranscriptionPipeline.Control {
        override fun checkCancelled() {}
        override fun beforeChunk() {}
        override fun onProgress(fraction: Float, utterances: List<Utterance>) {}
    }

    private val options = RecognitionOptions("auto", true, 2)
    private val audio = TranscriptionPipeline.Audio(durationMs = 3000) { sink ->
        sink(FloatArray(16000), 1000)
        sink(FloatArray(16000), 2000)
        sink(FloatArray(16000), 3000)
    }

    @Test
    fun pipelineRunsAndAlwaysClosesSession() {
        val log = mutableListOf<String>()
        val engine = FakeEngine(log)
        val result = TranscriptionPipeline(engine).run(File("."), options, audio, NoopControl)
        assertEquals(3, result.size)
        assertTrue(engine.last!!.closed)
        assertEquals("close", log.last())
    }

    @Test
    fun cancellationStopsBeforeMoreAudioAndStillClosesSession() {
        val log = mutableListOf<String>()
        val engine = FakeEngine(log)
        assertThrows(IOException::class.java) {
            TranscriptionPipeline(engine).run(File("."), options, audio, CancelAfter(chunks = 1))
        }
        assertTrue(engine.last!!.closed)
        assertEquals(1, log.count { it.startsWith("accept") })
        assertEquals("close", log.last())
    }

    @Test
    fun engineErrorStillClosesSession() {
        val log = mutableListOf<String>()
        val failing = object : SpeechEngine {
            override val kind = EngineKind.SENSEVOICE_SHERPA
            override fun openSession(modelDir: File, options: RecognitionOptions): RecognitionSession =
                object : RecognitionSession {
                    var closed = false
                    override fun acceptAudio(samples: FloatArray): List<Utterance> = throw IllegalStateException("native")
                    override fun finish(): List<Utterance> = emptyList()
                    override fun close() { log += "closed" }
                }
        }
        assertThrows(IllegalStateException::class.java) {
            TranscriptionPipeline(failing).run(File("."), options, audio, NoopControl)
        }
        assertEquals(listOf("closed"), log)
    }

    // ---- Merge (overlap dedupe) ------------------------------------------------------------

    @Test
    fun overlappingDuplicatesAreRemoved() {
        val merged = SegmentMerger.merge(
            listOf(
                Utterance(10_000, 14_000, "今天天气很好。"),
                Utterance(10_200, 14_100, "今天天气很好"), // same sentence from the next window
                Utterance(15_000, 18_000, "我们去公园吧"),
            ),
        )
        assertEquals(listOf("今天天气很好。", "我们去公园吧"), merged.map { it.text })
    }

    @Test
    fun sameTextFarApartIsKept() {
        val merged = SegmentMerger.merge(
            listOf(Utterance(0, 1000, "好"), Utterance(60_000, 61_000, "好")),
        )
        assertEquals(2, merged.size)
    }

    @Test
    fun mergeSortsByTimeAndDropsEmptyText() {
        val merged = SegmentMerger.merge(
            listOf(Utterance(5000, 6000, "b"), Utterance(0, 1000, "a"), Utterance(2000, 3000, " ")),
        )
        assertEquals(listOf("a", "b"), merged.map { it.text })
    }
}
