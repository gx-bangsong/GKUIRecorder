# Recorder speech engine

Offline speech-to-text APK for the Recorder app. It is not part of the Recorder APK.
The Recorder app downloads it on demand, asks you to install it, and talks to it over
AIDL (`app/src/main/aidl/.../ISpeechEngine.aidl`, which must match the copy in the
Recorder project).

## Build

1. In `gradle.properties`, set `sherpaOnnxAarUrl` and `sherpaOnnxAarSha256` to a
   sherpa-onnx Android AAR from https://github.com/k2-fsa/sherpa-onnx/releases.
   The build downloads the AAR and checks the hash. The AAR is not stored in Git.
2. Build with Android Studio, or with a Gradle wrapper that you generate locally.
   This directory has no wrapper jar.

## Model package

Host a zip file that contains, at its root:

- `tokens.txt`
- `model.int8.onnx` (or `model.onnx`)

The SenseVoice int8 model from the sherpa-onnx model list fits this layout. Repack its
archive as a zip. Put the URL (and optional SHA-256) in the Recorder settings under
"Offline speech engine".

## Callers

Only `org.lineageos.recorder` and `org.lineageos.recorder.dev` may call the service.
