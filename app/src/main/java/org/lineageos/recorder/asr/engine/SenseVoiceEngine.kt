/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.recorder.asr.engine

import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import org.lineageos.recorder.asr.model.EngineKind
import java.io.File

/**
 * SenseVoiceSmall (INT8) through sherpa-onnx, offline and non-streaming.
 *
 * Silero VAD splits the audio at silence and caps each segment at [MAX_SEGMENT_SECONDS], so the
 * recognizer only sees speech and never a long block. Native objects are created in [openSession]
 * and released by [SenseVoiceSession.close]; nothing relies on finalizers.
 *
 * Expected files in the model directory: model.int8.onnx, tokens.txt, silero_vad.onnx.
 */
object SenseVoiceEngine : SpeechEngine {
    override val kind: EngineKind = EngineKind.SENSEVOICE_SHERPA

    const val MODEL_FILE = "model.int8.onnx"
    const val TOKENS_FILE = "tokens.txt"
    const val VAD_FILE = "silero_vad.onnx"

    private const val MAX_SEGMENT_SECONDS = 25f
    private const val VAD_THRESHOLD = 0.5f
    private const val MIN_SILENCE_SECONDS = 0.5f
    private const val MIN_SPEECH_SECONDS = 0.25f
    private const val VAD_WINDOW = 512

    override fun openSession(modelDir: File, options: RecognitionOptions): RecognitionSession {
        val model = File(modelDir, MODEL_FILE)
        val tokens = File(modelDir, TOKENS_FILE)
        val vadModel = File(modelDir, VAD_FILE)
        require(model.isFile && tokens.isFile && vadModel.isFile) { "Model files are missing" }

        val vad = Vad(
            assetManager = null,
            config = VadModelConfig(
                sileroVadModelConfig = SileroVadModelConfig(
                    model = vadModel.absolutePath,
                    threshold = VAD_THRESHOLD,
                    minSilenceDuration = MIN_SILENCE_SECONDS,
                    minSpeechDuration = MIN_SPEECH_SECONDS,
                    windowSize = VAD_WINDOW,
                    maxSpeechDuration = MAX_SEGMENT_SECONDS,
                ),
                sampleRate = sampleRate,
                numThreads = 1,
                provider = "cpu",
                debug = false,
            ),
        )
        val recognizer = try {
            OfflineRecognizer(
                assetManager = null,
                config = OfflineRecognizerConfig(
                    featConfig = FeatureConfig(sampleRate = sampleRate, featureDim = 80),
                    modelConfig = OfflineModelConfig(
                        senseVoice = OfflineSenseVoiceModelConfig(
                            model = model.absolutePath,
                            language = options.language,
                            useInverseTextNormalization = options.useItn,
                        ),
                        tokens = tokens.absolutePath,
                        numThreads = options.numThreads,
                        debug = false,
                        provider = "cpu",
                    ),
                    decodingMethod = "greedy_search",
                ),
            )
        } catch (e: Throwable) {
            vad.release()
            throw e
        }
        return SenseVoiceSession(vad, recognizer)
    }
}

private class SenseVoiceSession(
    private val vad: Vad,
    private val recognizer: OfflineRecognizer,
) : RecognitionSession {
    private var closed = false

    override fun acceptAudio(samples: FloatArray): List<Utterance> {
        check(!closed) { "Session is closed" }
        vad.acceptWaveform(samples)
        return drain()
    }

    override fun finish(): List<Utterance> {
        check(!closed) { "Session is closed" }
        vad.flush()
        return drain()
    }

    private fun drain(): List<Utterance> {
        val utterances = ArrayList<Utterance>()
        while (!vad.empty()) {
            val segment = vad.front()
            vad.pop()
            val startMs = segment.start * 1000L / SenseVoiceEngine.sampleRate
            val endMs = (segment.start + segment.samples.size) * 1000L / SenseVoiceEngine.sampleRate
            val text = decode(segment.samples)
            if (text.isNotBlank()) {
                utterances += Utterance(startMs, endMs, text)
            }
        }
        return utterances
    }

    private fun decode(samples: FloatArray): String {
        val stream = recognizer.createStream()
        try {
            stream.acceptWaveform(samples, SenseVoiceEngine.sampleRate)
            recognizer.decode(stream)
            return recognizer.getResult(stream).text.trim()
        } finally {
            stream.release()
        }
    }

    override fun close() {
        if (closed) {
            return
        }
        closed = true
        // Release both, even if the first throws
        try {
            recognizer.release()
        } finally {
            vad.release()
        }
    }
}
