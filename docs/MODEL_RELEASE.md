<!--
SPDX-FileCopyrightText: The LineageOS Project
SPDX-License-Identifier: Apache-2.0
-->

# Offline transcription model: release checklist

## Default model

| Field | Value |
|---|---|
| ID | `sensevoice-small-int8-2024-07-17` |
| Archive | `sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17.tar.bz2` |
| Archive size (GitHub asset metadata) | 163002883 bytes |
| Archive SHA-256 (GitHub asset digest) | `7d1efa2138a65b0b488df37f8b89e3d91a60676e416f515b952358d83dfd347e` |
| VAD | `silero_vad.onnx`, 643854 bytes, SHA-256 `9e2449e1087496d8d4caba907f23e0bd3f78d91fa552479bb9c23ac09cbb1fd6` |
| Language / ITN | `auto` / on (digits and punctuation) |

Why 2024-07-17 and not 2025-09-09: the upstream sherpa-onnx documentation states the 2025-09-09
release does not output punctuation, and it is tuned with Cantonese data. The 2024-07-17 INT8
release outputs digits and punctuation with ITN enabled. The 2025-09-09 release can be added later
as an optional "Cantonese" package.

## Not yet verified (release blockers)

- `model.int8.onnx` and `tokens.txt` inside the archive: byte sizes and SHA-256 are **not yet
  recorded**. They must be taken from the real archive, not copied from another version or a
  repackaged mirror. Run `tools/inspect-model-artifact.sh` on a machine that can reach
  `release-assets.githubusercontent.com`, then fill `ExpectedFile.sha256` and the installed size in
  `ModelCatalog.kt`.
- Until those values are recorded, the installer relies on the archive SHA-256 only.

## Storage

- The model is downloaded only after the user confirms it on the first transcription.
- Deleting a model does not delete transcripts.
- Only one transcription runs at a time (`AsrGlobalLock`). Deletion is refused while one runs.

## Old test installs

Installs of `sensevoice-small-int8-2025-09-09` are a different model ID and are never reported as
Ready for 2024-07-17. They remain on disk until the user deletes them in model settings.
