/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.recorder.asr

import org.lineageos.recorder.asr.engine.Utterance
import org.lineageos.recorder.asr.transcription.TranscriptionPipeline

/**
 * Every timing value of the run loop, in one place. Changing a value here changes behaviour; the
 * defaults are the values measured in review, not tuned for any device.
 *
 * The rest between chunks is a throttle. It lowers the average load and lengthens the run. It is
 * not proof of lower power use or of a lower temperature. Measure both on a device before saying so.
 */
data class PacingConfig(
    /** Interval between thermal checks while waiting for the device to cool down. */
    val pauseCheckMs: Long = 2_000L,
    /** Interval between checks while a recording is in progress. */
    val recordingCheckMs: Long = 1_000L,
    /** Longest wait between two cancellation checks. Bounds how long a cancel takes to act. */
    val waitSliceMs: Long = 250L,
    /** Rest after each inference while the phase is REDUCED. */
    val reducedRestMs: Long = 250L,
    /** How long a heat state must stay calm before the run goes back to a lighter state. */
    val stableMs: Long = ThermalGovernor.STABLE_MS,
)

/** Durations and counts of one run. Never audio, text or file names. */
class RunMetrics {
    var chunks: Int = 0
        private set
    var computeMs: Long = 0L
        private set
    var restMs: Long = 0L
        private set
    var waitMs: Long = 0L
        private set

    fun onChunk(computeMs: Long) {
        chunks++
        this.computeMs += computeMs.coerceAtLeast(0L)
    }

    fun onRest(ms: Long) {
        restMs += ms.coerceAtLeast(0L)
    }

    fun onWait(ms: Long) {
        waitMs += ms.coerceAtLeast(0L)
    }

    fun summary(): String = "chunks=$chunks computeMs=$computeMs restMs=$restMs waitMs=$waitMs"
}

/**
 * The control point before each inference (each audio chunk and the final flush). It decides
 * whether the run may continue, and it does so with a monotonic clock only.
 *
 * - Recording in progress: waits, and keeps the segments already saved.
 * - Severe heat: waits at the chunk boundary, and saves finished segments before the first wait.
 * - Moderate heat: rests after each chunk, with no model reload.
 * - Cancellation is checked before each wait slice and again after the wait, so a cancelled run
 *   never starts another inference.
 *
 * Every side effect is injected, so the logic runs on the JVM with fake clocks and fake statuses.
 */
class TranscriptionGate(
    private val thermalStatus: () -> Int,
    private val recordingInProgress: () -> Boolean,
    private val monotonicMs: () -> Long,
    private val sleepMs: (Long) -> Unit,
    private val checkCancelled: () -> Unit,
    /** Called once when a waiting phase starts. Store the finished segments here. */
    private val onEnterWait: (RunPhase) -> Unit,
    /** Called on every phase change, including the change back to FULL or REDUCED. */
    private val onPhaseChanged: (RunPhase) -> Unit,
    private val metrics: RunMetrics,
    private val config: PacingConfig = PacingConfig(),
) {
    private val governor = ThermalGovernor(config.stableMs)
    private var phase = RunPhase.FULL

    /** Returns when inference may run. Throws when the run was cancelled. */
    fun beforeInference() {
        checkCancelled()
        while (true) {
            val recording = recordingInProgress()
            val action = governor.decide(thermalStatus(), monotonicMs())
            val next = when {
                recording -> RunPhase.WAITING_RECORDING
                action == ThermalAction.PAUSED -> RunPhase.WAITING_COOL
                action == ThermalAction.REDUCED -> RunPhase.REDUCED
                else -> RunPhase.FULL
            }
            if (next != phase) {
                val wasWaiting = phase.isWaiting
                phase = next
                if (next.isWaiting && !wasWaiting) {
                    onEnterWait(next)
                }
                onPhaseChanged(next)
            }
            if (!next.isWaiting) {
                break
            }
            waitFor(if (next == RunPhase.WAITING_RECORDING) config.recordingCheckMs else config.pauseCheckMs)
        }
        if (phase == RunPhase.REDUCED) {
            timedSleep(config.reducedRestMs) { metrics.onRest(it) }
        }
        // After any wait, and before the next inference
        checkCancelled()
    }

    private fun waitFor(totalMs: Long) {
        var left = totalMs
        while (left > 0L) {
            checkCancelled()
            val slice = minOf(left, config.waitSliceMs)
            timedSleep(slice) { metrics.onWait(it) }
            left -= slice
        }
    }

    private inline fun timedSleep(ms: Long, record: (Long) -> Unit) {
        val start = monotonicMs()
        sleepMs(ms)
        record(monotonicMs() - start)
    }
}

/**
 * The pipeline's control: cancellation, the gate before each inference, run metrics, and progress.
 * Production code, shared by the worker and the tests.
 */
class GatedPipelineControl(
    private val gate: TranscriptionGate,
    private val metrics: RunMetrics,
    private val cancelCheck: () -> Unit,
    private val progress: (Float, List<Utterance>) -> Unit,
) : TranscriptionPipeline.Control {
    override fun checkCancelled() = cancelCheck()

    override fun beforeChunk() = gate.beforeInference()

    override fun onChunkComputed(computeMs: Long) = metrics.onChunk(computeMs)

    override fun onProgress(fraction: Float, utterances: List<Utterance>) =
        progress(fraction, utterances)
}
