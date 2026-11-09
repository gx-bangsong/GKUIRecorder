# Distributions and install identities

This project ships two release identities from the same source tree. Both are first-party builds of
this code. Neither is a stealth or detection-resistant build: a package name is visible to the
system, to other apps and to anyone who inspects the APK.

| Distribution | Release applicationId | Debug applicationId | Notes |
|---|---|---|---|
| `legacy` (default) | `org.lineageos.recorder` | `org.lineageos.recorder.dev` | Existing identity and update path. |
| `standalone` | `io.github.gxbangsong.gkuirecorder` | `io.github.gxbangsong.gkuirecorder.dev` | Second install. Its package name has no `lineageos`. |

The source namespace (`org.lineageos.recorder`), the class names, and the upstream copyright and
license notices are unchanged in both. Only the install identity changes.

## Building

The distribution is a Gradle property. Anything other than `legacy` or `standalone` fails the
configuration.

```sh
./gradlew assembleDebug                                  # legacy debug, app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease                                # legacy release, app/build/outputs/apk/release/
./gradlew -Pdistribution=standalone assembleDebug        # app/build/outputs/apk/debug/app-standalone-debug.apk
./gradlew -Pdistribution=standalone assembleRelease      # app/build/outputs/apk/release/app-standalone-release.apk
./gradlew app:generateBp                                 # Android.bp, legacy identity only
```

The legacy file names are unchanged, because CI and `verify-apk.yml` read them. A non-legacy build
gets its own file name, so building the second distribution does not replace the first APK.

Switching the property re-runs the manifest merge and packaging, so an APK is not reused across
identities. Check the identity of any APK you intend to ship with `apkanalyzer` (see below).

## Signing

Release signing is read only from environment variables (`KEYSTORE_FILE`, `KEYSTORE_PASS`,
`KEY_ALIAS`, `KEY_PASS`), never from repository files or `~/.gradle/gradle.properties`. The
GitHub Actions release workflow decodes `KEYSTORE_BASE64` into a private temporary file instead
of using a keystore path; it falls back to the `KEYSTORE_FILE` secret only for compatibility,
still treating it as base64 keystore contents. The release
workflow's `scripts/ci-build.sh` refuses to run without a complete signing environment; other
Gradle builds fall back to the debug key. See `docs/RELEASE.md` for the automated release flow. The two distributions use the same key:

- Same key: the two packages are still separate apps (different applicationIds), so they can be
  installed side by side. The key choice affects only how the publisher is identified.
- Each distribution is updated only with its own signature. The legacy update path does not change.

## What does not carry over to the standalone install

A different applicationId is a different app. Android keeps each app's private data separate, so
installing `standalone` does not give it anything from `legacy`, and the reverse is also true:

- Downloaded speech models (in the app's private storage) are not shared. The model must be
  downloaded again, and the download is subject to the same checks.
- Settings (SharedPreferences) are not shared.
- Transcripts (per-recording files in the app's private storage) are not shared.
- Recordings are files in shared storage, but the recording list only shows recordings whose owner
  package is the installed app (`MediaStore.Audio.Media.OWNER_PACKAGE_NAME`). A standalone install
  therefore does **not** list recordings that the `legacy` install created. Those files are still on
  the device. No automated import is provided in this change. The existing per-recording share and
  export actions work on the files shown by the app that owns them.

Users do not need to uninstall the other distribution first. The two can run at the same time.

## Runtime identity references

| Place | Status |
|---|---|
| `AndroidManifest.xml` | No providers, no custom permissions, no hard-coded package names. The `package` attribute is kept as the source namespace. Verify the merged value with `apkanalyzer`. |
| `res/xml/shortcuts.xml` | `targetPackage` is `@string/shortcut_target_package`. Gradle sets it per identity (`resValue`). The main value is the legacy package for AOSP builds. `targetClass` stays the real class name. |
| Explicit Intents and PendingIntents | Built from class references (`Intent(context, X::class.java)`), so they resolve to the installed identity. Notification taps open the correct recording tab. |
| `RecordingsFlow` | Uses `context.packageName`. This is why the list is scoped to the installed identity. |
| `engine/` (speech engine service, separate APK) | Caller allowlist includes both Recorder identities and their `.dev` forms. The engine's own applicationId is still `org.lineageos.recorder.engine`. It is a separate app and is not renamed by this change. |
| `Android.bp` (AOSP) | Generated from the legacy identity. AOSP builds do not produce the standalone identity. |

## Verification

Run these on the built APKs. Do not rely on `build.gradle.kts` strings.

```sh
APKANALYZER="$ANDROID_HOME/cmdline-tools/latest/bin/apkanalyzer"
$APKANALYZER manifest application-id app/build/outputs/apk/debug/app-debug.apk                 # org.lineageos.recorder.dev
$APKANALYZER manifest application-id app/build/outputs/apk/debug/app-standalone-debug.apk      # io.github.gxbangsong.gkuirecorder.dev
$APKANALYZER manifest application-id app/build/outputs/apk/release/app-standalone-release.apk  # io.github.gxbangsong.gkuirecorder
$APKANALYZER manifest print app/build/outputs/apk/release/app-standalone-release.apk
```

Those checks are not yet run on a device. See the status section below.

## Status of this change

- CI (`.github/workflows/build/action.yml`) builds the legacy release and both standalone variants,
  runs `apkanalyzer manifest application-id` on each APK, checks the shortcut `targetPackage` string
  with `aapt2 dump resources`, and uploads the standalone APKs as `standalone-apks`.
- Not yet confirmed: the CI run for the workflow change. The sandbox has no Android SDK, Gradle
  distribution or `apkanalyzer`, so the standalone build and these checks were not run here.
- Not verified on a device: installing both identities side by side, launching, recording,
  playback, transcript pages, notification taps, shortcuts, and the legacy-to-legacy update path.
- AOSP / Soong: no change. The standalone identity is a Gradle-only build.
