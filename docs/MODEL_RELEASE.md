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

## Verification of the model files

All values below come from official sherpa-onnx `asr-models` release assets.

| File | Size (bytes) | SHA-256 | Source |
|---|---|---|---|
| Archive `sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17.tar.bz2` | 163002883 | `7d1efa2138a65b0b488df37f8b89e3d91a60676e416f515b952358d83dfd347e` | GitHub release asset metadata (API `size` and `digest`), checked against the API. |
| `model.int8.onnx` (from the archive) | 239233841 | `c71f0ce00bec95b07744e116345e33d8cbbe08cef896382cf907bf4b51a2cd51` | Measured by the maintainer from the official archive. Not re-measured in CI. |
| `tokens.txt` (from the archive) | 315894 | `f449eb28dc567533d7fa59be34e2abca8784f771850c78a47fb731a31429a1dc` | Measured by the maintainer from the official archive (25055 lines). Not re-measured in CI. |
| VAD `silero_vad.onnx` | 643854 | `9e2449e1087496d8d4caba907f23e0bd3f78d91fa552479bb9c23ac09cbb1fd6` | GitHub release asset metadata (API `size` and `digest`). |

The sherpa-onnx AAR (`v1.13.8`) size `50129134` and SHA-256 `633c24321e06b1fe79feafa03ea16cbc0f8a286641e2da3559bac91bdb13bd96`
are confirmed against `api.github.com/repos/k2-fsa/sherpa-onnx/releases/tags/v1.13.8`.

Note on reproduction: the GitHub API publishes a digest only for the archive and the VAD file. The two
in-archive values were measured by the maintainer from the official archive and are not re-checked by CI.
To re-check, download the archive from the official release, confirm its SHA-256, extract it and run
`sha256sum` and `stat -c %s` on `model.int8.onnx` and `tokens.txt`.

Space check, computed from the values above:

```
expectedInstalledPayloadBytes = size(model.int8.onnx) + size(tokens.txt) + size(silero_vad.onnx)
                              = 239233841 + 315894 + 643854 = 240193589
requiredFreeSpaceBytes        = archiveBytes + vadBytes + expectedInstalledPayloadBytes + 64 MiB
                              = 163002883 + 643854 + 240193589 + 67108864 = 470949190
```

## Storage

- The model is downloaded only after the user confirms it on the first transcription.
- Deleting a model does not delete transcripts.
- Only one transcription runs at a time (`AsrGlobalLock`). Deletion is refused while one runs.

## Old test installs

Installs of `sensevoice-small-int8-2025-09-09` are a different model ID and are never reported as
Ready for 2024-07-17. They remain on disk until the user deletes them in model settings.
