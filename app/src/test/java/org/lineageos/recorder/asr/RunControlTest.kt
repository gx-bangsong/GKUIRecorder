/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.recorder.asr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.lineageos.recorder.asr.ThermalGovernor.Companion.STATUS_MODERATE
import org.lineageos.recorder.asr.ThermalGovernor.Companion.STATUS_NONE
import org.lineageos.recorder.asr.ThermalGovernor.Companion.STATUS_SEVERE
import org.lineageos.recorder.asr.engine.RecognitionOptions
import org.lineageos.recorder.asr.engine.RecognitionSession
import org.lineageos.recorder.asr.engine.SpeechEngine
import org.lineageos.recorder.asr.engine.Utterance
import org.lineageos.recorder.asr.model.EngineKind
import org.lineageos.recorder.asr.transcription.TranscriptRecord
import org.lineageos.recorder.asr.transcription.TranscriptStatus
import org.lineageos.recorder.asr.transcription.TranscriptStore
import org.lineageos.recorder.asr.transcription.TranscriptionPipeline
import org.lineageos.recorder.asr.transcription.TranscriptionRepository
import java.io.File

/** In-memory store with a write counter. Used in place of the file store on the JVM. */
class MemoryTranscriptStore : TranscriptStore {
    val records = HashMap<String, TranscriptRecord>()
    var writes = 0

    override fun read(stableId: String): TranscriptRecord? = records[stableId]

    override fun write(record: TranscriptRecord) {
        records[record.stableRecordingId] = record
        writes++
    }

    override fun quarantine(stableId: String, nowMillis: Long): String? = null

    override fun delete(stableId: String) {
        records.remove(stableId)
    }
}

/**
 * Drives the gate with a fake monotonic clock. Sleeping advances the clock, so timings are exact.
 * [statusByCall] is indexed by the number of thermal reads, so a script does not depend on time.
 */
class GateHarness(
    private val statusByCall: (Int) -> Int = { STATUS_NONE },
    private val recordingAt: (Long) -> Boolean = { false },
) {
    var now = 0L
    var thermalCalls = 0
    var cancelled = false
    val log = mutableListOf<String>()
    val entered = mutableListOf<RunPhase>()
    val phases = mutableListOf<RunPhase>()
    val metrics = RunMetrics()

    /** Runs after every sleep. Tests use it to cancel at a chosen moment. */
    var onSleep: (Long) -> Unit = {}

    val gate = TranscriptionGate(
        thermalStatus = { statusByCall(++thermalCalls) },
        recordingInProgress = { recordingAt(now) },
        monotonicMs = { now },
        sleepMs = { ms ->
            log += "sleep"
            now += ms
            onSleep(ms)
        },
        checkCancelled = { if (cancelled) throw IllegalStateException("cancelled") },
        onEnterWait = { phase ->
            entered += phase
            log += "enter"
        },
        onPhaseChanged = { phases += it },
        metrics = metrics,
    )
}

class TranscriptionGateTest {
    @Test
    fun normalHeatNeverWaitsOrRests() {
        val h = GateHarness()
        repeat(3) { h.gate.beforeInference() }
        assertEquals(0L, h.now)
        assertEquals(0L, h.metrics.waitMs)
        assertEquals(0L, h.metrics.restMs)
        assertTrue(h.phases.isEmpty())
    }

    @Test
    fun moderateHeatKeepsRunningWithRestAndNoWait() {
        val h = GateHarness(statusByCall = { STATUS_MODERATE })
        repeat(4) { h.gate.beforeInference() }
        // Lower load, not a stop: no waiting phase, a fixed rest after each inference
        assertEquals(listOf(RunPhase.REDUCED), h.phases)
        assertTrue(h.entered.isEmpty())
        assertEquals(4 * 250L, h.metrics.restMs)
        assertEquals(0L, h.metrics.waitMs)
    }

    @Test
    fun severeHeatSavesBeforeWaitingThenResumesOnlyAfterStableCooling() {
        // Severe for the first 3 reads, then calm
        val h = GateHarness(statusByCall = { call -> if (call <= 3) STATUS_SEVERE else STATUS_NONE })
        h.gate.beforeInference()
        assertEquals(listOf(RunPhase.WAITING_COOL), h.entered)
        // The save happens before the first sleep of the wait
        assertTrue(h.log.indexOf("enter") < h.log.indexOf("sleep"))
        // Severe for the first 3 reads, calm from read 4 (t = 6 s). Leaving the heat state needs 30 s
        // of calm on the monotonic clock: at t = 36 s the run is REDUCED, not normal.
        assertEquals(36_000L, h.now - 250L)
        assertEquals(listOf(RunPhase.WAITING_COOL, RunPhase.REDUCED), h.phases)
        // All waiting time is counted as waiting; the rest after the wait is counted separately
        assertEquals(36_000L, h.metrics.waitMs)
        assertEquals(250L, h.metrics.restMs)
    }

    @Test
    fun cancelWhileWaitingForCoolingStopsWithoutInference() {
        val h = GateHarness(statusByCall = { STATUS_SEVERE })
        h.onSleep = { if (h.log.count { it == "sleep" } == 3) h.cancelled = true }
        assertThrows(IllegalStateException::class.java) { h.gate.beforeInference() }
        assertEquals(listOf(RunPhase.WAITING_COOL), h.entered)
        // Cancel is seen within one wait slice, so it does not wait for the whole poll interval
        assertTrue(h.now <= 3 * 250L)
    }

    @Test
    fun cancelAfterWaitIsCheckedBeforeTheNextInference() {
        val h = GateHarness(statusByCall = { call -> if (call <= 3) STATUS_SEVERE else STATUS_NONE })
        // Cancel during the rest that follows the wait: the run must not reach inference
        h.onSleep = { if (h.phases.lastOrNull() == RunPhase.REDUCED) h.cancelled = true }
        assertThrows(IllegalStateException::class.java) { h.gate.beforeInference() }
    }

    @Test
    fun recordingInProgressWaitsAndKeepsSegments() {
        val h = GateHarness(recordingAt = { it < 5_000L })
        h.gate.beforeInference()
        assertEquals(listOf(RunPhase.WAITING_RECORDING, RunPhase.FULL), h.phases)
        assertEquals(listOf(RunPhase.WAITING_RECORDING), h.entered)
        assertEquals(5_000L, h.now)
    }

    @Test
    fun cancelDuringRecordingWaitStops() {
        val h = GateHarness(recordingAt = { true })
        h.onSleep = { h.cancelled = true }
        assertThrows(IllegalStateException::class.java) { h.gate.beforeInference() }
        assertEquals(listOf(RunPhase.WAITING_RECORDING), h.entered)
    }

    @Test
    fun recordingStartingAfterHeatWaitDoesNotSaveTwice() {
        // Heat wait first, then a recording starts during the wait: the phase changes, the save does not repeat
        val h = GateHarness(
            statusByCall = { call -> if (call <= 2) STATUS_SEVERE else STATUS_NONE },
            recordingAt = { it >= 3_000L && it < 6_000L },
        )
        h.gate.beforeInference()
        assertEquals(listOf(RunPhase.WAITING_COOL), h.entered)
        assertTrue(h.phases.contains(RunPhase.WAITING_RECORDING))
    }
}

/** Pipeline plus gate plus checkpointer, all production code, with a scripted engine. */
class GatedPipelineTest {
    private val uri = "content://media/external/audio/media/42"

    private class Session(private val log: MutableList<String>) : RecognitionSession {
        private var n = 0

        override fun acceptAudio(samples: FloatArray): List<Utterance> {
            log += "accept"
            n++
            return listOf(Utterance((n - 1) * 1000L, n * 1000L - 100L, "s$n"))
        }

        override fun finish(): List<Utterance> {
            log += "finish"
            return emptyList()
        }

        override fun close() {
            log += "close"
        }
    }

    private class Engine(private val log: MutableList<String>) : SpeechEngine {
        override val kind: EngineKind = EngineKind.SENSEVOICE_SHERPA

        override fun openSession(modelDir: File, options: RecognitionOptions): RecognitionSession {
            log += "open"
            return Session(log)
        }
    }

    private fun audio(chunks: Int) = TranscriptionPipeline.Audio(durationMs = 1_000_000L) { sink ->
        repeat(chunks) { i -> sink(FloatArray(16_000), (i + 1) * 1000L) }
    }

    private class Setup(val store: MemoryTranscriptStore, val repo: TranscriptionRepository, val base: TranscriptRecord, val state: RunState, val checkpointer: RunCheckpointer)

    private fun setup(harness: GateHarness, uri: String, store: MemoryTranscriptStore = MemoryTranscriptStore()): Setup {
        val repo = TranscriptionRepository(files = store, clock = { 1L })
        val base = repo.start(uri, "model-a", "v1", "auto")
        val state = RunState()
        val checkpointer = RunCheckpointer(
            repository = repo,
            uri = uri,
            base = base,
            state = state,
            monotonicMs = { harness.now },
            canWrite = { true },
            onPublish = {},
        )
        return Setup(store, repo, base, state, checkpointer)
    }

    private fun control(harness: GateHarness, s: Setup) = GatedPipelineControl(
        gate = harness.gate,
        metrics = harness.metrics,
        cancelCheck = { if (harness.cancelled) throw IllegalStateException("cancelled") },
        progress = { fraction, utterances -> s.checkpointer.onProgress(fraction, utterances) },
    )

    @Test
    fun moderateHeatCompletesEveryChunkAndFlush() {
        val h = GateHarness(statusByCall = { STATUS_MODERATE })
        val s = setup(h, uri)
        val log = mutableListOf<String>()
        val result = TranscriptionPipeline(Engine(log)).run(File("."), RecognitionOptions("auto", true, 1), audio(3), control(h, s))
        assertEquals(3, result.size)
        assertEquals(3, log.count { it == "accept" })
        assertEquals(1, log.count { it == "finish" })
        // Each inference is followed by a rest, including the final flush
        assertEquals(4 * 250L, h.metrics.restMs)
    }

    @Test
    fun severeHeatSavesCompletedSegmentsNotOnlyThrottledOnes() {
        // Pause before the 3rd chunk. Chunk 2's progress is inside the checkpoint throttle.
        val h = GateHarness(statusByCall = { call -> if (call == 3) STATUS_SEVERE else STATUS_NONE })
        h.onSleep = { if (h.log.count { it == "sleep" } == 2) h.cancelled = true }
        val s = setup(h, uri)
        val log = mutableListOf<String>()
        assertThrows(IllegalStateException::class.java) {
            TranscriptionPipeline(Engine(log)).run(File("."), RecognitionOptions("auto", true, 1), audio(4), control(h, s))
        }
        // Two chunks were recognized before the pause, and both are stored
        assertEquals(2, log.count { it == "accept" })
        assertFalse(log.contains("finish"))
        val stored = s.repo.get(uri)!!
        assertEquals(listOf("s1", "s2"), stored.segments.map { it.text })
        // The restart point is the end of the last completed segment, not the decoded position
        assertEquals(1_900L, stored.lastCompletedAudioPositionMs)
    }

    @Test
    fun cancelledPauseIsResumedOnRestartWithoutDuplicates() {
        val h = GateHarness(statusByCall = { call -> if (call == 3) STATUS_SEVERE else STATUS_NONE })
        h.onSleep = { if (h.log.count { it == "sleep" } == 2) h.cancelled = true }
        val first = setup(h, uri)
        assertThrows(IllegalStateException::class.java) {
            TranscriptionPipeline(Engine(mutableListOf())).run(File("."), RecognitionOptions("auto", true, 1), audio(4), control(h, first))
        }
        // A later run of the same model, on the same store, starts from the saved segments
        val second = setup(GateHarness(), uri, first.store)
        val resumed = second.base
        assertEquals(listOf("s1", "s2"), resumed.segments.map { it.text })
        assertEquals(1_900L, resumed.resumeFromMs)

        val fresh = GateHarness()
        val runSegments = TranscriptionPipeline(Engine(mutableListOf()))
            .run(File("."), RecognitionOptions("auto", true, 1), audio(4), control(fresh, setup(fresh, uri, MemoryTranscriptStore())))
            .map { org.lineageos.recorder.asr.transcription.TranscriptSegment(it.startMs, it.endMs, it.text) }
        second.repo.complete(uri, resumed, runSegments)
        val done = second.repo.get(uri)!!
        assertEquals(listOf("s1", "s2", "s3", "s4"), done.segments.map { it.text })
        assertEquals(TranscriptStatus.COMPLETED, done.status)
    }

    @Test
    fun finalFlushIsGatedLikeAChunk() {
        // Severe on the 4th read: 3 chunks, then the flush
        val h = GateHarness(statusByCall = { call -> if (call == 4) STATUS_SEVERE else STATUS_NONE })
        val s = setup(h, uri)
        val log = mutableListOf<String>()
        TranscriptionPipeline(Engine(log)).run(File("."), RecognitionOptions("auto", true, 1), audio(3), control(h, s))
        val lastAccept = log.lastIndexOf("accept")
        val firstSleep = log.indexOf("sleep")
        val finish = log.indexOf("finish")
        assertTrue(firstSleep > lastAccept)
        assertTrue(firstSleep < finish)
    }

    @Test
    fun cancelBeforeModelCreationDoesNotOpenTheEngine() {
        val h = GateHarness()
        h.cancelled = true
        val s = setup(h, uri)
        val log = mutableListOf<String>()
        assertThrows(IllegalStateException::class.java) {
            TranscriptionPipeline(Engine(log)).run(File("."), RecognitionOptions("auto", true, 1), audio(3), control(h, s))
        }
        assertFalse(log.contains("open"))
    }
}

class RunStateAndCheckpointTest {
    private val uri = "content://media/external/audio/media/7"

    @Test
    fun progressUpdateKeepsReducedPhase() {
        val state = RunState()
        assertTrue(state.setPhase(RunPhase.REDUCED))
        state.onProcessed(0.3f)
        // A progress update must not reset the phase to normal speed
        assertEquals(RunPhase.REDUCED, state.phase)
        assertEquals(30, state.percent())
        assertFalse(state.setPhase(RunPhase.REDUCED))
    }

    @Test
    fun percentIsClampedAndFractionsStayApart() {
        val state = RunState()
        state.onProcessed(1.7f)
        state.onPersisted(-0.2f)
        assertEquals(100, state.percent())
        assertEquals(0f, state.persistedFraction, 0f)
    }

    @Test
    fun checkpointsAreThrottledButForcedSaveIsNot() {
        val store = MemoryTranscriptStore()
        val repo = TranscriptionRepository(files = store, clock = { 1L })
        val base = repo.start(uri, "m", "v", "auto")
        var now = 0L
        val state = RunState()
        val checkpointer = RunCheckpointer(repo, uri, base, state, { now }, { true }, {})
        val u1 = Utterance(0, 900, "a")
        val u2 = Utterance(1000, 1900, "b")
        checkpointer.onProgress(0.001f, listOf(u1))
        val writesAfterFirst = store.writes
        // Small step and no time passed: throttled
        checkpointer.onProgress(0.002f, listOf(u1, u2))
        assertEquals(writesAfterFirst, store.writes)
        // Forced save before a wait writes the newest completed segments right away
        checkpointer.persistBeforeWait()
        assertEquals(writesAfterFirst + 1, store.writes)
        assertEquals(listOf("a", "b"), repo.get(uri)!!.segments.map { it.text })
    }

    @Test
    fun restartPointIsCompletedAudioNotDecodedPosition() {
        val store = MemoryTranscriptStore()
        val repo = TranscriptionRepository(files = store, clock = { 1L })
        val base = repo.start(uri, "m", "v", "auto")
        val state = RunState()
        val checkpointer = RunCheckpointer(repo, uri, base, state, { 0L }, { true }, {})
        // 90 % is decoded, but only the first segment is recognized
        checkpointer.onProgress(0.9f, listOf(Utterance(0, 2_000, "only")))
        val stored = repo.get(uri)!!
        assertEquals(0.9f, stored.progress, 1e-6f)
        assertEquals(2_000L, stored.lastCompletedAudioPositionMs)
    }

    @Test
    fun deletedRecordingGetsNoForcedSave() {
        val store = MemoryTranscriptStore()
        val repo = TranscriptionRepository(files = store, clock = { 1L })
        val base = repo.start(uri, "m", "v", "auto")
        val writesBefore = store.writes
        val checkpointer = RunCheckpointer(repo, uri, base, RunState(), { 0L }, { false }, {})
        checkpointer.persistBeforeWait()
        assertEquals(writesBefore, store.writes)
    }

    @Test
    fun metricsCountOnlyNumbers() {
        val metrics = RunMetrics()
        metrics.onChunk(120)
        metrics.onChunk(80)
        metrics.onRest(250)
        metrics.onWait(2_000)
        assertEquals(
            "chunks=2 computeMs=200 restMs=250 waitMs=2000",
            metrics.summary(),
        )
    }
}
