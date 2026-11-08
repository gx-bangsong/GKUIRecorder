# Offline speech-to-text architecture

This document describes the state of the code on the beta4 branch. Items marked **open** are not
done and are not claimed as verified.

## Model and engine

- Default model: `sensevoice-small-int8-2024-07-17` (SenseVoiceSmall INT8, ITN on, language auto).
  It is chosen over the 2025-09-09 release because the upstream sherpa-onnx documentation says the
  2025-09-09 release does not output punctuation. See `MODEL_RELEASE.md`.
- Models are never in the APK. They are downloaded on demand from the official sherpa-onnx release
  assets (ASR archive plus `silero_vad.onnx` as a separate standalone download).
- Runtime: sherpa-onnx 1.13.8 AAR, fetched by an execution-phase Gradle task with a SHA-256 check.
  Configuration does no network or file I/O.
- **Open:** the official SHA-256 and byte sizes of the INT8 `model.int8.onnx` and `tokens.txt`
  have not been recorded. `ModelCatalog.default` therefore fails `validateManifest()` and cannot be
  downloaded in this build (see below).

## Fail-closed manifest

`ModelDescriptor.validateManifest()` requires every key resource (ASR archive, `model.int8.onnx`,
`tokens.txt`, `silero_vad.onnx`) to have a non-blank file name, a size above zero, and a SHA-256
matching `[0-9a-fA-F]{64}` that is not all zeros. It is checked at:

1. `ModelRepository.enqueueDownload` (no work is enqueued),
2. `ModelDownloadWorker.doWork` (fails with `MANIFEST_INCOMPLETE`, not retryable),
3. `ModelInstaller.install` (before staging, so nothing is written and READY is never committed).

The UI disables the download button and shows "模型清单尚未完成验证，此构建暂不可下载". A
manifest refusal is not counted as a network error.

## Install chain

Each download is a separate task: ASR archive, then VAD. Each goes to a staging directory, is
size- and hash-checked, and extracted with path and link-type checks. READY is written only after
every file has passed. A failed or cancelled download leaves the previous READY install in place.

## Transcription

- One recognizer app-wide. `AsrGlobalLock` is a cross-process file lock. Recording has priority:
  transcription does not start while a recording is in progress.
- WorkManager queues one transcription per recording (tag built from the URI) and keeps the queue
  across restarts.
- Cancel and failure keep the finished segments. Checkpoints happen at least every 30 seconds and
  when a run is cancelled or fails.
- Resume: a run of the same model and version re-decodes from the start. Segments that end before
  the last completed position are kept and duplicates are dropped. There is no exact seek claim.

## Transcript storage

- One JSON file per recording: `noBackupFilesDir/transcripts/{id}.json`, where `id` is the lowercase
  hex SHA-256 of the recording URI string. The URI and title are never used in file names.
- Writes use a temporary file, fsync, and atomic replace (`android.util.AtomicFile`), under a
  per-ID lock. Reads run on the IO dispatcher. JSON size is capped and unknown fields are ignored.
- Corrupt files are renamed to `{id}.corrupt-{epochMillis}.json`; at most three are kept. The UI
  shows "转写数据损坏，可重新生成".
- Migration: a missing new file is read from the legacy `transcript:{uri}` SharedPreferences
  value (beta4 JSON or beta3 plain text). The new file is written first; the legacy key is deleted
  only after the write succeeds. New writes never put transcript text in SharedPreferences.
- Deleting a recording cancels its transcription, deletes the recording, then removes its
  transcript files only if the recording is gone. Deleting a model never deletes transcripts.
- Logs contain status and exception type only, never transcript text or file URIs.

## Foreground service and target SDK

**Open:** the foreground service types and `targetSdk` review are not re-checked in this change.

## Tests

Unit tests live in `app/src/test/`. The manifest tests, transcript storage tests (identity, atomic
write, corruption, migration, checkpoint and resume, deletion linkage) are the new coverage.
