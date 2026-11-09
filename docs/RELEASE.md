# Release workflow

This repository builds and publishes two release APKs automatically. The logic lives in
`scripts/ci-build.sh`; `.github/workflows/release.yml` only checks out, sets up the toolchain,
and calls the script. Nothing here changes app functionality.

## What happens automatically

A push to `lineage-23.2` (or a manual **Actions -> release -> Run workflow**) builds both
release identities and publishes one GitHub Release with:

- `Recorder-legacy-<versionName>.apk` — `org.lineageos.recorder`
- `Recorder-legacy-<versionName>.apk.sha256`
- `Recorder-standalone-<versionName>.apk` — `io.github.gxbangsong.gkuirecorder`
- `Recorder-standalone-<versionName>.apk.sha256`
- `build-info-legacy.json`
- `build-info-standalone.json`

The `build-info-*.json` files keep the same field names as other GKUI apps: `applicationId`,
`versionCode`, `versionName`, `signer_sha256`, `apk_sha256`, `apk`, `commit`, `source_url`.
The release tag is `v<versionName>` and points at the exact commit that was built.
Documentation-only pushes (`docs/**`, `**.md`) do not publish.

## Version scheme

- `versionCode` = `git rev-list --count HEAD` — the commit count. It only grows, so every
  release is newer than the last. This needs the full history: the workflow checks out with
  `fetch-depth: 0`, and `scripts/ci-build.sh` refuses to build a release from a shallow clone.
- `versionName` = `yyyyMMdd.<versionCode>` (UTC). The `VERSION_NAME` environment variable
  overrides it when a custom name is needed.
- No version value is ever written back into the repository; everything is computed at build
  time. `ci-build.sh` fails the release if the build changed any tracked file.

## Signing

Release signing is read **only** from environment variables, never from
`~/.gradle/gradle.properties` or any file in the repository:

| Variable          | Meaning                                             |
|-------------------|-----------------------------------------------------|
| `KEYSTORE_BASE64` | base64-encoded keystore contents (used by CI)      |
| `KEYSTORE_PASS`   | keystore password                                   |
| `KEY_ALIAS`       | key alias                                           |
| `KEY_PASS`        | key password                                        |

Local Gradle builds use `KEYSTORE_FILE=/path/to/release.jks` as a keystore path. The GitHub
Actions release workflow does **not** read a keystore path from secrets; it decodes
`KEYSTORE_BASE64` into a private temporary file and points Gradle at it. If `KEYSTORE_BASE64`
is empty, the workflow falls back to the `KEYSTORE_FILE` secret, still treating it as base64
keystore contents.

Both distributions are signed with the same key. The release script refuses to run without the
complete signing environment, so the workflow never reaches signing with incomplete secrets.
Other Gradle builds without the signing environment fall back to the **debug key** so local
development and non-publishing CI checks keep working.

## Gates (all must pass before anything is published)

1. **Package identity** — `aapt2 dump badging`: legacy must be exactly `org.lineageos.recorder`;
   standalone must be exactly `io.github.gxbangsong.gkuirecorder`; no `.dev`/`.debug` suffix;
   the APK must not be debuggable; versionCode must be numeric; versionName non-empty. The two
   distributions must agree on versionCode and versionName.
2. **Signer fingerprint** — `apksigner verify --print-certs`: exactly one distinct signer for
   each APK, and its SHA-256 certificate fingerprint must match `release-signer.sha256` in the
   repository root. A different key fails the release.
3. **Monotonic versionCode** — the new versionCode must be greater than the `versionCode` in the
   most recent GitHub Release's build-info file (`build-info.json`, or the recorded
   `build-info-standalone.json` / `build-info-legacy.json` for this project). Skipped when there
   is no previous release or it has no compatible build-info file.
4. **Tag uniqueness** — `v<versionName>` must not already exist.
5. **Clean tree** — the build must not have modified any tracked file.

## Owner checklist (manual, one-time)

1. **Use the GKUI release keystore** for both distributions, or create a new one. If a new key
   is used, update `release-signer.sha256` in the repository root to the new fingerprint.
2. **Add four repository secrets** (Settings -> Secrets and variables -> Actions):
   `KEYSTORE_BASE64`, `KEYSTORE_PASS`, `KEY_ALIAS`, `KEY_PASS`. If a base64 keystore was stored
   as `KEYSTORE_FILE`, the workflow falls back to it for compatibility.
3. **Check `release-signer.sha256`** — it contains the GKUI release-key fingerprint used by the
   other GKUI apps. If the Recorder release uses a different key, the first release run fails at
   the signer gate and prints the detected fingerprint; replace the file, commit, and run again.
4. **Run the workflow** — merge a change into `lineage-23.2` (non-docs), or Actions -> release ->
   Run workflow. On success the stable release appears with the six assets listed above.

## Local release builds

```sh
KEYSTORE_FILE=/path/release.jks KEYSTORE_PASS=... KEY_ALIAS=gkui-release KEY_PASS=... \
  ./gradlew assembleRelease
KEYSTORE_FILE=/path/release.jks KEYSTORE_PASS=... KEY_ALIAS=gkui-release KEY_PASS=... \
  ./gradlew -Pdistribution=standalone assembleRelease
```

Without those variables the release builds are signed with the debug key and warn. Nothing is
published locally; releases only come from the workflow.
