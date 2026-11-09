#!/usr/bin/env bash
#
# Builds the legacy and standalone release APKs, checks them, and writes release files to dist/.
#
# Called only by the release workflow (see docs/RELEASE.md), with CI=true. It needs these
# environment variables from the workflow: KEYSTORE_BASE64, KEYSTORE_PASS, KEY_ALIAS, KEY_PASS,
# and GH_TOKEN for the previous-release check. Nothing is committed or written to tracked files.
#
# Output in dist/:
#   Recorder-legacy-<versionName>.apk
#   Recorder-legacy-<versionName>.apk.sha256
#   Recorder-standalone-<versionName>.apk
#   Recorder-standalone-<versionName>.apk.sha256
#   build-info-legacy.json
#   build-info-standalone.json
# and, outside dist/, build/release-notes.md for the release body.
# The tag v<versionName> is written to GITHUB_OUTPUT as "tag".

set -euo pipefail

readonly APP_NAME="Recorder"
# Install identities. Must match app/build.gradle.kts distributionIds.
readonly LEGACY_APPLICATION_ID="org.lineageos.recorder"
readonly STANDALONE_APPLICATION_ID="io.github.gxbangsong.gkuirecorder"

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
readonly REPO_ROOT
readonly SIGNER_FILE="${REPO_ROOT}/release-signer.sha256"
readonly LEGACY_APK_IN="${REPO_ROOT}/app/build/outputs/apk/release/app-release.apk"
readonly STANDALONE_APK_IN="${REPO_ROOT}/app/build/outputs/apk/release/app-standalone-release.apk"
readonly DIST_DIR="${REPO_ROOT}/dist"
readonly NOTES_FILE="${REPO_ROOT}/build/release-notes.md"

die() {
    echo "::error::$*" >&2
    exit 1
}

# Progress goes to stderr: some functions print values on stdout and are captured with $(...).
log() {
    echo "[ci-build] $*" >&2
}

require_env() {
    local name="$1"
    if [[ -z "${!name:-}" ]]; then
        die "environment variable ${name} is not set"
    fi
}

require_command() {
    command -v "$1" >/dev/null 2>&1 || die "Required command not found: $1"
}

for command in base64 find git gh jq sha256sum; do
    require_command "$command"
done

# Locates a build-tools binary. If the workflow pins PINNED_BUILD_TOOLS_VERSION, use that
# exact build-tools directory so newer preinstalled SDKs cannot change gate behavior; otherwise
# prefer PATH, then the newest build-tools under the SDK.
find_build_tool() {
    local name="$1"
    local sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
    if [[ -n "${PINNED_BUILD_TOOLS_VERSION:-}" ]]; then
        if [[ -z "${sdk}" ]]; then
            die "ANDROID_HOME is not set; cannot find pinned ${name}"
        fi
        local pinned="${sdk}/build-tools/${PINNED_BUILD_TOOLS_VERSION}/${name}"
        if [[ ! -x "${pinned}" ]]; then
            die "pinned ${name} not found at ${pinned}; install build-tools;${PINNED_BUILD_TOOLS_VERSION}"
        fi
        echo "${pinned}"
        return
    fi
    if command -v "${name}" >/dev/null 2>&1; then
        command -v "${name}"
        return
    fi
    if [[ -z "${sdk}" ]]; then
        die "ANDROID_HOME is not set; cannot find ${name}"
    fi
    local found
    found="$(find "${sdk}/build-tools" -mindepth 2 -maxdepth 2 -name "${name}" 2>/dev/null | sort -V | tail -n 1)"
    if [[ -z "${found}" ]]; then
        die "${name} not found under ${sdk}/build-tools; install a build-tools package"
    fi
    echo "${found}"
}

# Decodes the keystore into a private temporary file. The file is removed on exit.
# Global, so the EXIT trap can still see it after the function returns.
KEYSTORE_TMP=""

decode_keystore() {
    require_env KEYSTORE_BASE64
    umask 077
    KEYSTORE_TMP="$(mktemp "${RUNNER_TEMP:-/tmp}/release-keystore.XXXXXX")"
    trap 'rm -f "${KEYSTORE_TMP}"' EXIT
    if ! printf '%s' "${KEYSTORE_BASE64}" | base64 --decode >"${KEYSTORE_TMP}"; then
        die "KEYSTORE_BASE64 is not valid base64"
    fi
    if [[ ! -s "${KEYSTORE_TMP}" ]]; then
        die "decoded keystore is empty"
    fi
    export KEYSTORE_FILE="${KEYSTORE_TMP}"
}

# Reads a field from aapt2 badging output, first match only.
badging_field() {
    local badging="$1"
    local regex="$2"
    sed -n "${regex}" <<<"${badging}" | head -n 1
}

# Lower-case hex SHA-256 with spaces, colons and line breaks removed.
normalise_fingerprint() {
    tr -d ' :\r\n' | tr 'A-F' 'a-f'
}

# Prints the expected signer fingerprint, or dies. Comments/blank lines are ignored.
expected_fingerprint() {
    if [[ ! -s "${SIGNER_FILE}" ]]; then
        echo "release-signer.sha256 is missing. The owner must create it with one SHA-256 fingerprint per line." >&2
        die "release-signer.sha256 not found in the repository"
    fi
    local expected
    expected="$(grep -Ev '^[[:space:]]*(#|$)' "${SIGNER_FILE}" | head -n 1 | normalise_fingerprint || true)"
    if [[ ! "${expected}" =~ ^[0-9a-f]{64}$ ]]; then
        die "release-signer.sha256 must contain one SHA-256 fingerprint"
    fi
    printf '%s' "${expected}"
}

# Verifies and prints the APK's single signer SHA-256, or dies. $1 is used only in error text.
signer_fingerprint() {
    local apk="$1"
    local apksigner="$2"
    local expected="$3"
    local signer_out signer_count signer_digests fingerprint
    if ! signer_out="$("${apksigner}" verify --print-certs "${apk}")"; then
        die "apksigner verify failed for ${apk}: the APK is not correctly signed"
    fi
    # build-tools 37 renamed the signer lines (for example, "V2 Signer: certificate SHA-256 digest:").
    # Accept any prefix before "certificate SHA-256 digest:", normalize each digest, and count
    # distinct certificates. One key under multiple signature schemes is still one signer.
    signer_digests="$(printf '%s\n' "${signer_out}" | sed -n 's/.*certificate SHA-256 digest:[[:space:]]*//p' | tr -d ' :\r' | tr 'A-F' 'a-f' | grep -E '^[0-9a-f]{64}$' | sort -u || true)"
    signer_count="$(printf '%s\n' "${signer_digests}" | sed '/^$/d' | wc -l | tr -d '[:space:]')"
    if [[ "${signer_count}" == "0" ]]; then
        printf '%s\n' "${signer_out}" >&2
        die "could not read any certificate SHA-256 digest from apksigner output for ${apk}"
    fi
    if [[ "${signer_count}" != "1" ]]; then
        printf '%s\n' "${signer_out}" >&2
        die "expected exactly one signer for ${apk}, found ${signer_count} distinct certificate(s)"
    fi
    fingerprint="$(sed -n '1p' <<<"${signer_digests}")"
    if [[ ! "${fingerprint}" =~ ^[0-9a-f]{64}$ ]]; then
        die "could not read the signer SHA-256 from apksigner output for ${apk}"
    fi
    if [[ "${fingerprint}" != "${expected}" ]]; then
        die "signer SHA-256 ${fingerprint} for ${apk} does not match release-signer.sha256 (${expected})"
    fi
    printf '%s' "${fingerprint}"
}

# Prints the versionCode of the most recent release that has a build-info file.
# Prints nothing when there is no earlier release, or when it has no compatible build-info file.
previous_version_code() {
    local tag
    if ! tag="$(gh release list --exclude-drafts --limit 1 --json tagName --jq '.[0].tagName // empty')"; then
        die "cannot list releases with gh (check GH_TOKEN)"
    fi
    if [[ -z "${tag}" ]]; then
        return
    fi
    local assets asset=""
    if ! assets="$(gh release view "${tag}" --json assets --jq '.assets[].name')"; then
        die "cannot read assets of release ${tag}"
    fi
    if grep -qx 'build-info.json' <<<"${assets}"; then
        asset="build-info.json"
    elif grep -qx 'build-info-standalone.json' <<<"${assets}"; then
        asset="build-info-standalone.json"
    elif grep -qx 'build-info-legacy.json' <<<"${assets}"; then
        asset="build-info-legacy.json"
    else
        log "previous release ${tag} has no build-info file; versionCode check skipped"
        return
    fi
    local tmp_dir
    tmp_dir="$(mktemp -d "${RUNNER_TEMP:-/tmp}/prev-release.XXXXXX")"
    if ! gh release download "${tag}" --pattern "${asset}" --dir "${tmp_dir}" --clobber >/dev/null; then
        die "cannot download ${asset} from release ${tag}"
    fi
    jq -r '.versionCode' "${tmp_dir}/${asset}"
    rm -rf "${tmp_dir}"
}

main() {
    if [[ "${CI:-}" != "true" ]]; then
        die "ci-build.sh is for CI only (CI=true); local release builds use Gradle directly"
    fi

    # versionCode is the commit count: a shallow clone undercounts and must never release.
    if [[ "$(git -C "${REPO_ROOT}" rev-parse --is-shallow-repository)" == "true" ]]; then
        die "shallow clone detected: versionCode requires the full history (checkout with fetch-depth: 0)"
    fi

    require_env KEYSTORE_PASS
    require_env KEY_ALIAS
    require_env KEY_PASS
    local aapt2 apksigner
    aapt2="$(find_build_tool aapt2)"
    apksigner="$(find_build_tool apksigner)"
    local expected_signer
    expected_signer="$(expected_fingerprint)"

    decode_keystore

    cd "${REPO_ROOT}"

    log "building legacy release APK"
    ./gradlew --no-daemon assembleRelease

    log "building standalone release APK"
    ./gradlew --no-daemon -Pdistribution=standalone assembleRelease

    # The build must not have changed any tracked file. Build output is git-ignored.
    if [[ -n "$(git status --porcelain)" ]]; then
        git status --short
        die "the build changed tracked files; release refused"
    fi

    local -a dists=(legacy standalone)
    declare -A APK=( [legacy]="${LEGACY_APK_IN}" [standalone]="${STANDALONE_APK_IN}" )
    declare -A EXPECTED_ID=( [legacy]="${LEGACY_APPLICATION_ID}" [standalone]="${STANDALONE_APPLICATION_ID}" )
    declare -A APP_ID=()
    declare -A VERSION_CODE=()
    declare -A VERSION_NAME=()
    declare -A FINGERPRINT=()

    # --- Gates 1 and 2: package identity, debuggable flag, signer fingerprint ----------------
    local dist
    for dist in "${dists[@]}"; do
        local apk="${APK[$dist]}"
        if [[ ! -f "${apk}" ]]; then
            die "${dist} release APK not found at ${apk}"
        fi

        local badging app_id version_code version_name
        badging="$("${aapt2}" dump badging "${apk}")"
        app_id="$(badging_field "${badging}" "s/^package: name='\\([^']*\\)'.*/\\1/p")"
        version_code="$(badging_field "${badging}" "s/^package: .* versionCode='\\([^']*\\)'.*/\\1/p")"
        version_name="$(badging_field "${badging}" "s/^package: .* versionName='\\([^']*\\)'.*/\\1/p")"

        if [[ "${app_id}" =~ \\.(dev|debug)$ ]]; then
            die "${dist} applicationId ${app_id} carries a dev/debug suffix"
        fi
        if [[ "${app_id}" != "${EXPECTED_ID[$dist]}" ]]; then
            die "${dist} applicationId is ${app_id}, expected ${EXPECTED_ID[$dist]}"
        fi
        if [[ ! "${version_code}" =~ ^[0-9]+$ ]]; then
            die "${dist} versionCode is not a number: '${version_code}'"
        fi
        if [[ -z "${version_name}" ]]; then
            die "${dist} versionName is empty"
        fi

        local manifest
        manifest="$("${aapt2}" dump xmltree --file AndroidManifest.xml "${apk}")"
        if grep -Eq 'android:debuggable\\(0x[0-9a-f]+\\)=\\(type 0x[0-9a-f]+\\)0xffffffff' <<<"${manifest}"; then
            die "${dist} release APK is debuggable"
        fi

        local fingerprint
        fingerprint="$(signer_fingerprint "${apk}" "${apksigner}" "${expected_signer}")"
        APP_ID[$dist]="${app_id}"
        VERSION_CODE[$dist]="${version_code}"
        VERSION_NAME[$dist]="${version_name}"
        FINGERPRINT[$dist]="${fingerprint}"
        log "${dist}: applicationId=${app_id} versionCode=${version_code} versionName=${version_name}"
        log "${dist}: signer SHA-256 ${fingerprint} matches release-signer.sha256"
    done

    if [[ "${VERSION_CODE[legacy]}" != "${VERSION_CODE[standalone]}" ]]; then
        die "legacy and standalone versionCode differ (${VERSION_CODE[legacy]} != ${VERSION_CODE[standalone]})"
    fi
    if [[ "${VERSION_NAME[legacy]}" != "${VERSION_NAME[standalone]}" ]]; then
        die "legacy and standalone versionName differ (${VERSION_NAME[legacy]} != ${VERSION_NAME[standalone]})"
    fi
    if [[ "${FINGERPRINT[legacy]}" != "${FINGERPRINT[standalone]}" ]]; then
        die "legacy and standalone signers differ"
    fi

    local version_code="${VERSION_CODE[legacy]}"
    local version_name="${VERSION_NAME[legacy]}"
    local signer_sha256="${FINGERPRINT[legacy]}"

    # --- Gate 3: versionCode must increase over the previous release -----------------------
    local prev_code
    prev_code="$(previous_version_code)"
    if [[ -n "${prev_code}" ]]; then
        if [[ ! "${prev_code}" =~ ^[0-9]+$ ]]; then
            die "previous build-info has no numeric versionCode: '${prev_code}'"
        fi
        if (( version_code <= prev_code )); then
            die "versionCode ${version_code} is not greater than the previous release (${prev_code})"
        fi
        log "versionCode ${version_code} > previous ${prev_code}"
    fi

    local tag="v${version_name}"
    if gh release view "${tag}" >/dev/null 2>&1; then
        die "release ${tag} already exists"
    fi

    # --- Outputs ---------------------------------------------------------------------------
    local commit="${GITHUB_SHA:-$(git rev-parse HEAD)}"
    local repo="${GITHUB_REPOSITORY:-gx-bangsong/GKUIRecorder}"
    local source_url="https://github.com/${repo}/tree/${commit}"

    rm -rf "${DIST_DIR}"
    mkdir -p "${DIST_DIR}"

    declare -A APK_SHA=()
    for dist in "${dists[@]}"; do
        local base="Recorder-${dist}-${version_name}"
        cp "${APK[$dist]}" "${DIST_DIR}/${base}.apk"
        (cd "${DIST_DIR}" && sha256sum "${base}.apk" >"${base}.apk.sha256")
        APK_SHA[$dist]="$(cut -d' ' -f1 "${DIST_DIR}/${base}.apk.sha256")"
        jq -n \
            --arg applicationId "${APP_ID[$dist]}" \
            --argjson versionCode "${version_code}" \
            --arg versionName "${version_name}" \
            --arg signer_sha256 "${signer_sha256}" \
            --arg apk_sha256 "${APK_SHA[$dist]}" \
            --arg apk "${base}.apk" \
            --arg commit "${commit}" \
            --arg source_url "${source_url}" \
            '{applicationId: $applicationId, versionCode: $versionCode, versionName: $versionName,
              signer_sha256: $signer_sha256, apk_sha256: $apk_sha256, apk: $apk,
              commit: $commit, source_url: $source_url}' \
            >"${DIST_DIR}/build-info-${dist}.json"
    done

    mkdir -p "$(dirname "${NOTES_FILE}")"
    cat >"${NOTES_FILE}" <<NOTES
Release build of ${APP_NAME}, signed with the release key. Two install identities are published:

- legacy: \`${APP_ID[legacy]}\` — \`Recorder-legacy-${version_name}.apk\`
- standalone: \`${APP_ID[standalone]}\` — \`Recorder-standalone-${version_name}.apk\`

Common fields:

- versionCode: ${version_code}
- versionName: ${version_name}
- signer SHA-256: \`${signer_sha256}\`
- legacy APK SHA-256: \`${APK_SHA[legacy]}\`
- standalone APK SHA-256: \`${APK_SHA[standalone]}\`
- Source code for this build: ${source_url}

The two packages are separate apps with separate private data. Installing one does not upgrade
or replace the other. These release builds are signed with the release key, not the debug key.
NOTES

    if [[ -n "${GITHUB_OUTPUT:-}" ]]; then
        printf 'tag=%s\n' "${tag}" >>"${GITHUB_OUTPUT}"
    fi
    log "wrote dist/Recorder-legacy-${version_name}.apk and dist/Recorder-standalone-${version_name}.apk, tag ${tag}"
}

main "$@"
