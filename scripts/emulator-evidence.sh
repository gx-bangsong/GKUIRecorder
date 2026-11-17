#!/bin/sh
# SPDX-FileCopyrightText: The LineageOS Project
# SPDX-License-Identifier: Apache-2.0
#
# Returns the emulator evidence as Git blobs, so it can be read through the API. Runs in its own
# workflow step after the emulator step, which leaves the files in the workspace. Each step has its
# own annotation limit, so the blob ids printed here are not hidden by the test output.
#
# Files: the instrumentation log, the Gradle install log, the highlight rows, and the layout
# screenshots. A missing file is reported and skipped. This step never changes the test result.

notice() { echo "::notice title=$1::$2"; }
warn() { echo "::warning title=$1::$2"; }

# Text files are sent as UTF-8; PNG screenshots as base64.
upload_text() {
    sha=$(gh api --method POST "repos/$GITHUB_REPOSITORY/git/blobs" \
        -f encoding=utf-8 -F "content=@$1" --jq .sha 2>/dev/null)
    notice "blob-$1" "${sha:-upload-failed}"
}
upload_png() {
    base64 -w0 "$1" > "$1.b64"
    sha=$(gh api --method POST "repos/$GITHUB_REPOSITORY/git/blobs" \
        -f encoding=base64 -F "content=@$1.b64" --jq .sha 2>/dev/null)
    notice "blob-$1" "${sha:-upload-failed}"
}

for f in instrument.log gradle-install.log highlight-evidence/results.txt; do
    if [ -s "$f" ]; then upload_text "$f"; else warn evidence "$f missing"; fi
done
for f in layout-transcript-off.png layout-transcript-on.png layout-transcript-highlight.png; do
    if [ -s "$f" ]; then upload_png "$f"; else warn evidence "$f missing"; fi
done
exit 0
