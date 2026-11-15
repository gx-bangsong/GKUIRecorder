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

# Bounded, so the evidence below is still collected when a test hangs. 124 means the timeout hit.
timeout 1200 ./gradlew :app:connectedDebugAndroidTest --stacktrace > gradle-emulator.log 2>&1
status=$?
notice stage "gradle exit $status, $(wc -l < gradle-emulator.log) lines"
# Did the tests run, and what did each one report? Read from the result XML that gradle wrote.
grep -E 'Tests on|connectedDebugAndroidTest|BUILD|tests completed' gradle-emulator.log | head -n 20 | annotate gradle-summary
for xml in $(find app/build/outputs/androidTest-results -name '*.xml' 2>/dev/null); do
    notice test-results "$xml: $(grep -o '<testcase name="[^"]*"' "$xml" | sed 's/<testcase name=//' | tr '\n' ' ') failures=$(grep -c '<failure' "$xml")"
done
# The failure line can be far from the end of the log, so the key lines are taken from the whole log.
grep -E 'FAILED|What went wrong|AssertionError|Exception|expected|highlight per position|seek=' -A4 gradle-emulator.log \
    | head -n 120 | annotate gradle
tail -n 20 gradle-emulator.log | annotate gradle-tail

adb=$(command -v adb || echo "$ANDROID_HOME/platform-tools/adb")
"$adb" logcat -d | grep -E 'GKUI_HL|AndroidRuntime' | tail -n 80 | annotate logcat

# The app is uninstalled when connectedDebugAndroidTest finishes, so its files cannot be read here.
# The test logs every row with the tag GKUI_HL, and logcat keeps them after the run.
mkdir -p highlight-evidence
"$adb" logcat -d -s GKUI_HL:I | sed -n 's/.*GKUI_HL: //p' > highlight-evidence/results.txt
if [ ! -s highlight-evidence/results.txt ]; then
    warn evidence "no GKUI_HL rows in logcat: the highlight test did not reach its report"
else
    cat highlight-evidence/results.txt | annotate highlight
    # Same way the UI workflow reference returns evidence: a Git blob, reachable through the API.
    sha=$(gh api --method POST "repos/$GITHUB_REPOSITORY/git/blobs" \
        -f encoding=utf-8 -F "content=@highlight-evidence/results.txt" --jq .sha 2>/dev/null)
    notice evidence-blob "results.txt ${sha:-upload-failed}"
fi

exit "$status"
