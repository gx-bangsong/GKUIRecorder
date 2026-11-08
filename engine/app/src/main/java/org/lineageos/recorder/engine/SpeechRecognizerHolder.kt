/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.recorder.engine

import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import java.io.File

/**
 * Keeps one loaded sherpa-onnx recognizer and recreates it when the model or the
 * language changes. Access is serialized because the native recognizer is not thread-safe.
 */
object SpeechRecognizerHolder {
    private const val SAMPLE_RATE = 16_000
    private const val THREADS = 2

    private var recognizer: OfflineRecognizer? = null
    private var loadedKey: String? = null

    @Synchronized
    fun transcribe(modelDir: File, samples: FloatArray, language: String): String {
        val lang = language.ifBlank { "auto" }
        val key = "${modelDir.path}|$lang"
        if (recognizer == null || loadedKey != key) {
            recognizer?.release()
            recognizer = create(modelDir, lang)
            loadedKey = key
        }
        val stream = recognizer!!.createStream()
        try {
            stream.acceptWaveform(samples, SAMPLE_RATE)
            recognizer!!.decode(stream)
            return recognizer!!.getResult(stream).text.trim()
        } finally {
            stream.release()
        }
    }

    @Synchronized
    fun reset() {
        recognizer?.release()
        recognizer = null
        loadedKey = null
    }

    private fun create(modelDir: File, language: String): OfflineRecognizer {
        val model = ModelStore.findModelFile(modelDir) ?: error("Model file is missing")
        val config = OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
            modelConfig = OfflineModelConfig(
                senseVoice = OfflineSenseVoiceModelConfig(
                    model = model.path,
                    language = language,
                    useInverseTextNormalization = true,
                ),
                tokens = File(modelDir, "tokens.txt").path,
                numThreads = THREADS,
                debug = false,
                provider = "cpu",
                modelType = "sense_voice",
            ),
            decodingMethod = "greedy_search",
        )
        return OfflineRecognizer(assetManager = null, config = config)
    }
}
