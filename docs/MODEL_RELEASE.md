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

## Verification status of the model files (release blocker)

Verification attempted on 2026-10-10 from the build sandbox:

| Source | Result |
|---|---|
| `https://huggingface.co/api/models/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17/revision/2365baeacb507f821a0c8120fcee3d484dba7a07` | Not reachable from the sandbox (TLS connection closed). Not verified. |
| GitHub release archive (`release-assets.githubusercontent.com`) | Not reachable from the sandbox. Archive internals not verified. |

Consequences:

- `model.int8.onnx` and `tokens.txt` keep `sha256 = null` in `ModelCatalog.kt`. The values published by
  third parties (for example, size `239233841` and the SHA-256 for `model.int8.onnx`) are **not** adopted
  without official verification.
- The archive SHA-256 and size and the VAD values come from the GitHub release metadata
  (`api.github.com/repos/k2-fsa/sherpa-onnx/releases/tags/asr-models`), which is reachable.
- The sherpa-onnx AAR (`v1.13.8`) size `50129134` and SHA-256 `633c24321e06b1fe79feafa03ea16cbc0f8a286641e2da3559bac91bdb13bd96`
  were confirmed against `api.github.com/repos/k2-fsa/sherpa-onnx/releases/tags/v1.13.8` on 2026-10-10.

To close this blocker, run on a machine that can reach Hugging Face and the GitHub release CDN:

```
tools/inspect-model-artifact.sh
```

then record the Hugging Face revision API response (oid and size of `model.int8.onnx`, size of
`tokens.txt`), and fill `ExpectedFile.sha256` in `ModelCatalog.kt`.

Space check (formula, to be filled with verified values):

```
expectedInstalledPayloadBytes = size(model.int8.onnx) + size(tokens.txt) + size(silero_vad.onnx)
requiredFreeSpaceBytes        = archiveBytes + vadBytes + expectedInstalledPayloadBytes + 64 MiB
```

With the verified VAD (643854) and archive (163002883) sizes, the 64 MiB margin (67108864) is fixed.
The model-file sizes are still needed before the total can be stated.

## Storage

- The model is downloaded only after the user confirms it on the first transcription.
- Deleting a model does not delete transcripts.
- Only one transcription runs at a time (`AsrGlobalLock`). Deletion is refused while one runs.

## Old test installs

Installs of `sensevoice-small-int8-2025-09-09` are a different model ID and are never reported as
Ready for 2024-07-17. They remain on disk until the user deletes them in model settings.
