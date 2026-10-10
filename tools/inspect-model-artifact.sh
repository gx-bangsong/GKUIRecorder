#!/usr/bin/env bash
# SPDX-FileCopyrightText: The LineageOS Project
# SPDX-License-Identifier: Apache-2.0
#
# Reproducible inspection of the SenseVoice INT8 archive and the Silero VAD model.
# Prints the exact byte sizes and SHA-256 values needed by ModelCatalog.kt.
# Downloads go to a temporary directory; nothing is committed.
#
# Usage: tools/inspect-model-artifact.sh [workdir]
set -euo pipefail

RELEASE="https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models"
ARCHIVE="sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17.tar.bz2"
VAD="silero_vad.onnx"
WORK="${1:-$(mktemp -d)}"
mkdir -p "$WORK"
cd "$WORK"

echo "== remote metadata (Content-Length, no body) =="
for f in "$ARCHIVE" "$VAD"; do
    echo "$f:"
    curl -fsSLI "$RELEASE/$f" | tr -d '\r' | grep -i '^content-length' | tail -1
done

echo "== download =="
[ -f "$ARCHIVE" ] || curl -fL --retry 3 -o "$ARCHIVE" "$RELEASE/$ARCHIVE"
[ -f "$VAD" ] || curl -fL --retry 3 -o "$VAD" "$RELEASE/$VAD"

echo "== archive =="
stat -c '%n bytes=%s' "$ARCHIVE"
sha256sum "$ARCHIVE"

echo "== archive listing =="
tar -tjf "$ARCHIVE"

echo "== extract =="
rm -rf extracted && mkdir extracted
tar -xjf "$ARCHIVE" -C extracted

ROOT="extracted/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17"
for f in model.int8.onnx tokens.txt; do
    echo "== $f =="
    stat -c '%n bytes=%s' "$ROOT/$f"
    sha256sum "$ROOT/$f"
done

echo "== $VAD =="
stat -c '%n bytes=%s' "$VAD"
sha256sum "$VAD"

echo "== installed size (archive root, bytes) =="
du -sb "$ROOT" | cut -f1
