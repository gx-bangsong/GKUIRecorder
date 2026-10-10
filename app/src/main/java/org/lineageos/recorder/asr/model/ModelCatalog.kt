/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.recorder.asr.model

/**
 * The models this build can install. Sizes and digests come from the GitHub release asset metadata
 * of the upstream sherpa-onnx "asr-models" release (asset `size` and `digest` fields), not from
 * a local copy of the files, so the app never ships a model in the APK.
 *
 * To add a model: append a [ModelDescriptor] here. Nothing else changes for a new model of an
 * already supported [EngineKind].
 */
object ModelCatalog {
    private const val SHERPA_RELEASE = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models"

    private const val SENSEVOICE_ARCHIVE_ROOT = "sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2025-09-09"

    val SENSEVOICE_SMALL_INT8 = ModelDescriptor(
        id = "sensevoice-small-int8-2025-09-09",
        displayName = "SenseVoiceSmall INT8",
        version = "2025-09-09",
        engine = EngineKind.SENSEVOICE_SHERPA,
        supportedLanguages = listOf("auto", "zh", "en", "yue", "ja", "ko"),
        archiveUrl = "$SHERPA_RELEASE/$SENSEVOICE_ARCHIVE_ROOT.tar.bz2",
        archiveSha256 = "7305f7905bfcf77fa0b39388a313f3da35c68d971661a65475b56fb2162c8e63",
        archiveBytes = 165_783_878L,
        archiveRoot = SENSEVOICE_ARCHIVE_ROOT,
        standaloneDownloads = listOf(
            StandaloneDownload(
                url = "$SHERPA_RELEASE/silero_vad.onnx",
                localName = "silero_vad.onnx",
                sha256 = "9e2449e1087496d8d4caba907f23e0bd3f78d91fa552479bb9c23ac09cbb1fd6",
                sizeBytes = 643_854L,
            ),
        ),
        expectedFiles = listOf(
            ExpectedFile(archivePath = "model.int8.onnx", localName = "model.int8.onnx", sha256 = null),
            ExpectedFile(archivePath = "tokens.txt", localName = "tokens.txt", sha256 = null),
            ExpectedFile(
                archivePath = null,
                localName = "silero_vad.onnx",
                sha256 = "9e2449e1087496d8d4caba907f23e0bd3f78d91fa552479bb9c23ac09cbb1fd6",
            ),
        ),
        maxExtractedBytes = 512L * 1024 * 1024,
        license = ModelLicense(
            name = "FunASR Model Open Source License Agreement v1.1 (SenseVoiceSmall weights)",
            url = "https://huggingface.co/FunAudioLLM/SenseVoiceSmall",
            attribution = "SenseVoiceSmall by FunAudioLLM / Alibaba (FunASR). Keep this attribution and the model name.",
        ),
        minRuntimeVersion = "sherpa-onnx 1.13.8",
        minRamMb = null,
        useCase = "Offline transcription of finished recordings in Chinese, English, Cantonese, Japanese and Korean.",
    )

    val all: List<ModelDescriptor> = listOf(SENSEVOICE_SMALL_INT8)

    /** The model used by the transcription feature. First version only ships one. */
    val default: ModelDescriptor get() = SENSEVOICE_SMALL_INT8

    fun find(id: String): ModelDescriptor? = all.firstOrNull { it.id == id }
}
