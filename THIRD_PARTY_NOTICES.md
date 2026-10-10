<!--
SPDX-FileCopyrightText: The LineageOS Project
SPDX-License-Identifier: Apache-2.0
-->

# Third-party notices

| Component | Used for | License | Source |
|---|---|---|---|
| sherpa-onnx 1.13.8 (Kotlin API `classes.jar`, `libsherpa-onnx-jni.so`) | Offline speech recognition runtime and VAD | Apache-2.0 | https://github.com/k2-fsa/sherpa-onnx (LICENSE) |
| ONNX Runtime (`libonnxruntime.so`, shipped inside the sherpa-onnx AAR) | Neural network inference | MIT | https://github.com/microsoft/onnxruntime (LICENSE) |
| SenseVoiceSmall INT8 weights, release `sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17` | Speech recognition model (downloaded on demand, never in the APK) | FunASR Model Open Source License Agreement v1.1 | https://huggingface.co/FunAudioLLM/SenseVoiceSmall ; https://github.com/modelscope/FunASR/blob/main/MODEL_LICENSE |
| Silero VAD `silero_vad.onnx` (release `asr-models`) | Voice activity detection (downloaded on demand) | MIT | https://github.com/snakers4/silero-vad (LICENSE, Copyright (c) 2020-present Silero Team) |
| Apache Commons Compress 1.28.0 | Reading the `.tar.bz2` model archive | Apache-2.0 | https://commons.apache.org/proper/commons-compress/ |

## Model attribution

SenseVoiceSmall is the work of FunAudioLLM / Alibaba (FunASR). Its weights are **not** Apache-2.0.
They are distributed under the FunASR Model Open Source License Agreement v1.1 (Copyright (C)
[2023-2028] [Alibaba Group]). Keep this attribution, the model name and the license link whenever
the model is shown or redistributed. The app downloads the model only when the user asks for it.

The 2025-09-09 SenseVoice release is not used by this build.
