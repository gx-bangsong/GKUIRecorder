#!/bin/sh
# SPDX-FileCopyrightText: The LineageOS Project
# SPDX-License-Identifier: Apache-2.0
#
# Runs the instrumented tests on the emulator, then returns the evidence while the emulator is
# still up. Called from .github/workflows/emulator-test.yml as a single line, because the
# emulator runner executes each line of its script input on its own.
#
# The exit status is the gradle status. The evidence steps never change it.

# Each stage prints a notice, so a failure shows how far the script got.
notice() { echo "::notice title=$1::$2"; }
warn() { echo "::warning title=$1::$2"; }

# Lines become error annotations, which the GitHub API can read. Escape '%' so no line is altered.
annotate() {
    sed 's/%/%25/g' | while IFS= read -r line; do
        printf '::error title=%s::%s\n' "$1" "$line"
    done
}

notice stage "start in $(pwd)"
if [ ! -f gradlew ]; then
    echo "::error title=stage::gradlew not found"
    exit 1
fi

./gradlew :app:connectedDebugAndroidTest --stacktrace > gradle-emulator.log 2>&1
status=$?
notice stage "gradle exit $status, $(wc -l < gradle-emulator.log) lines"
tail -n 60 gradle-emulator.log | annotate gradle

adb=$(command -v adb || echo "$ANDROID_HOME/platform-tools/adb")
"$adb" logcat -d | grep -E 'GKUI_HL|AndroidRuntime' | tail -n 80 | annotate logcat

# Android 11+ blocks shell access to Android/data; the debuggable app's files are read with run-as.
mkdir -p highlight-evidence
for f in results.txt gap-2500.png; do
    "$adb" exec-out run-as org.lineageos.recorder.dev cat "files/highlight-evidence/$f" \
        > "highlight-evidence/$f" 2>/dev/null
    if [ ! -s "highlight-evidence/$f" ]; then
        warn evidence "could not read $f from the emulator"
        continue
    fi
    # Same way the UI workflow reference returns evidence: a Git blob, reachable through the API.
    base64 -w0 "highlight-evidence/$f" > "highlight-evidence/$f.b64"
    sha=$(gh api --method POST "repos/$GITHUB_REPOSITORY/git/blobs" \
        -f encoding=base64 -F "content=@highlight-evidence/$f.b64" --jq .sha 2>/dev/null)
    notice evidence-blob "$f ${sha:-upload-failed}"
done

exit "$status"
