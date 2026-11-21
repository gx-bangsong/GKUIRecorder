#!/bin/sh
# SPDX-FileCopyrightText: The LineageOS Project
# SPDX-License-Identifier: Apache-2.0
#
# Returns the full lint report as Git blobs, so it can be read through the API. The check-run
# annotations only show the first failures, so the complete report goes through blobs instead.
# Runs in its own workflow step after the lint step. This step never changes the test result.

notice() { echo "::notice title=$1::$2"; }
warn() { echo "::warning title=$1::$2"; }

# Copy the files under plain names so the blob titles are easy to read.
mkdir -p lint-evidence
[ -s app/build/reports/lint-results-debug.xml ] && cp app/build/reports/lint-results-debug.xml lint-evidence/lint-results-debug.xml
[ -s lint.log ] && cp lint.log lint-evidence/lint.log

upload_text() {
    sha=$(gh api --method POST "repos/$GITHUB_REPOSITORY/git/blobs" \
        -f encoding=utf-8 -F "content=@$1" --jq .sha 2>/dev/null)
    notice "blob-$(basename "$1")" "${sha:-upload-failed}"
}

for f in lint-evidence/lint-results-debug.xml lint-evidence/lint.log; do
    if [ -s "$f" ]; then upload_text "$f"; else warn lint-evidence "$f missing"; fi
done
exit 0
