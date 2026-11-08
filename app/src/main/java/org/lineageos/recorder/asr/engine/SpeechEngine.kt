/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.recorder.asr.engine

import org.lineageos.recorder.asr.model.EngineKind
import java.io.File

/** Settings that influence recognition. Values are validated by the settings layer. */
data class RecognitionOptions(
    val language: String,
    val useItn: Boolean,
    val numThreads: Int,
) {
    init {
        require(language in SUPPORTED_LANGUAGES) { "Unsupported language" }
        require(numThreads in 1..MAX_THREADS) { "Thread count out of range" }
    }

    companion object {
        val SUPPORTED_LANGUAGES = setOf("auto", "zh", "en", "yue", "ja", "ko")
        const val MIN_THREADS = 1
        const val MAX_THREADS = 4
        const val DEFAULT_THREADS = 2
        const val DEFAULT_LANGUAGE = "auto"
    }
}

/** One recognized speech segment, with times relative to the start of the recording. */
data class Utterance(
    val startMs: Long,
    val endMs: Long,
    val text: String,
)

/**
 * A running recognition over one recording. Audio is fed in order as 16 kHz mono float samples.
 * [close] releases every native resource; callers must always close it, even after an error.
 */
interface RecognitionSession : AutoCloseable {
    /** Feeds samples and returns the utterances that became final. */
    fun acceptAudio(samples: FloatArray): List<Utterance>

    /** Ends the input and returns the remaining utterances. */
    fun finish(): List<Utterance>
}

/**
 * Engine abstraction. The sherpa-onnx types are confined to implementations of this interface, so
 * code that lists models never loads the native library.
 */
interface SpeechEngine {
    val kind: EngineKind

    /**
     * Opens a session using the verified files of one installed model. Loads native code and
     * allocates the recognizer and VAD. Call from a background thread only.
     */
    fun openSession(modelDir: File, options: RecognitionOptions): RecognitionSession

    /** Sample rate the engine expects. */
    val sampleRate: Int
        get() = 16_000
}

object SpeechEngines {
    fun forKind(kind: EngineKind): SpeechEngine = when (kind) {
        EngineKind.SENSEVOICE_SHERPA -> SenseVoiceEngine
    }
}
