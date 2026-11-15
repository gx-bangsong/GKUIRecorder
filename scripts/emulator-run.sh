#!/bin/sh
# SPDX-FileCopyrightText: The LineageOS Project
# SPDX-License-Identifier: Apache-2.0
#
# Runs the instrumented tests on the emulator, then returns the evidence while the emulator is
# still up. Called from .github/workflows/emulator-test.yml as a single line, because the
# emulator runner executes each line of its script input on its own.
#
# connectedDebugAndroidTest hung after its first test in earlier runs, so gradle only installs
# the two APKs here. The tests run through `adb shell am instrument`, one test class at a time,
# each with its own timeout. Every test result is printed as it finishes.
#
# The exit status is 0 only when every test class reports OK. The evidence steps never change it.

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

adb=$(command -v adb || echo "$ANDROID_HOME/platform-tools/adb")
pkg=org.lineageos.recorder.dev
runner=androidx.test.runner.AndroidJUnitRunner
classes="org.lineageos.recorder.asr.ModelSettingsSmokeTest org.lineageos.recorder.asr.PlaybackHighlightDeviceTest"

timeout 900 ./gradlew :app:installDebug :app:installDebugAndroidTest --stacktrace > gradle-install.log 2>&1
status=$?
notice stage "install gradle exit $status, $(wc -l < gradle-install.log) lines"
grep -E 'BUILD|What went wrong|FAILED|error:' gradle-install.log | head -n 20 | annotate gradle-install
if [ "$status" -ne 0 ]; then
    tail -n 40 gradle-install.log | annotate gradle-install-tail
    exit "$status"
fi

# One class per run. -w waits for the run to finish; the host timeout stops a hang.
# A class that does not finish in time counts as a failure, and the next class still runs.
: > instrument.log
result=0
for cls in $classes; do
    notice stage "running $cls"
    timeout 400 "$adb" shell am instrument -w -r -e class "$cls" "$pkg.test/$runner" > "instrument-$cls.txt" 2>&1
    rc=$?
    cat "instrument-$cls.txt" >> instrument.log
    "$adb" shell am force-stop "$pkg" > /dev/null 2>&1
    notice stage "class $cls exit $rc"
    # am instrument prints OK (n tests) on success. Otherwise the whole output is shown, which has
    # the failing test and its stack.
    head -n 150 "instrument-$cls.txt" | annotate "instrument-$cls"
    # A class passes only when it exits 0 and prints OK, with no failure marker.
    if [ "$rc" -ne 0 ] || ! grep -q '^OK (' "instrument-$cls.txt" \
        || grep -qE 'FAILURES|INSTRUMENTATION_FAILED|Process crashed' "instrument-$cls.txt"; then
        result=1
        notice stage "class $cls did not pass (rc $rc)"
    fi
done
notice stage "instrumentation result $result"
# The full output is returned as Git blobs, so the failing stack is read without log or artifact
# downloads. Annotations are not used for it: the API keeps only some lines.
for f in instrument.log gradle-install.log; do
    sha=$(gh api --method POST "repos/$GITHUB_REPOSITORY/git/blobs" \
        -f encoding=utf-8 -F "content=@$f" --jq .sha 2>/dev/null)
    notice blob-$f "${sha:-upload-failed}"
done
notice stage "instrument log $(wc -l < instrument.log) lines"

notice stage "reading logcat"
timeout 120 "$adb" logcat -d | grep -E 'GKUI_HL|AndroidRuntime' | tail -n 80 | annotate logcat
notice stage "logcat done"

# The test logs every row with the tag GKUI_HL, and logcat keeps them after the run.
mkdir -p highlight-evidence
# Rows come from the instrumentation status stream (INSTRUMENTATION_STATUS: GKUI_HL=...), which
# am instrument prints for each class. Logcat is a fallback.
cat instrument.log | sed -n 's/^INSTRUMENTATION_STATUS: GKUI_HL=//p' | grep -v '^step ' > highlight-evidence/results.txt
if [ ! -s highlight-evidence/results.txt ]; then
    timeout 120 "$adb" logcat -d -s GKUI_HL:I | sed -n 's/.*GKUI_HL: //p' > highlight-evidence/results.txt
fi
if [ ! -s highlight-evidence/results.txt ]; then
    warn evidence "no GKUI_HL rows in logcat: the highlight test did not reach its report"
else
    cat highlight-evidence/results.txt | annotate highlight
    # Returned as a Git blob, reachable through the API.
    sha=$(gh api --method POST "repos/$GITHUB_REPOSITORY/git/blobs" \
        -f encoding=utf-8 -F "content=@highlight-evidence/results.txt" --jq .sha 2>/dev/null)
    notice evidence-blob "results.txt ${sha:-upload-failed}"
fi

exit "$result"
