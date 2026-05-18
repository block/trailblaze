#!/usr/bin/env bash
# Build the iOS sample app for the iOS Simulator and install it onto the
# currently booted simulator. Mirrors what `launchMode: REINSTALL` does for
# the Android sample app — eval trails that target this app expect a clean
# install before they run.
#
# Usage (from the repo root):
#   ./examples/ios-sample-app/build-and-install.sh
#
# Requires:
#   - Xcode 16+ (the project uses `PBXFileSystemSynchronizedRootGroup`, which is
#     Xcode 16+ only)
#   - iOS Simulator SDK installed (`xcodebuild -showsdks` should list "iOS Simulator")
#   - Exactly one booted iOS Simulator (`xcrun simctl install booted` is ambiguous
#     when multiple sims are booted)
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT="${SCRIPT_DIR}/IosSampleApp.xcodeproj"
BUILD_LOG="${SCRIPT_DIR}/build/build.log"
BUNDLE_ID="xyz.block.trailblaze.examples.iossampleapp"

if [[ ! -d "${PROJECT}" ]]; then
  printf '%s\n' "error: Xcode project not found at ${PROJECT}" >&2
  exit 1
fi

# The pbxproj uses file-system-synchronized folder groups (objectVersion 77),
# introduced in Xcode 16. Older Xcode versions silently regenerate the project
# in a different format, breaking the scheme and committed pbxproj contents —
# fail fast instead.
XCODE_VERSION_LINE="$(xcodebuild -version | head -n 1)"
if ! printf '%s' "${XCODE_VERSION_LINE}" | grep -Eq 'Xcode (1[6-9]|[2-9][0-9])'; then
  printf '%s\n' "error: ${XCODE_VERSION_LINE} is too old — this project requires Xcode 16+." >&2
  printf '%s\n' "       Hint: Install Xcode 16 from developer.apple.com/download and run 'xcode-select -s'." >&2
  exit 1
fi

BOOTED_COUNT="$(xcrun simctl list devices booted | grep -c "Booted" || true)"
if [[ "${BOOTED_COUNT}" -eq 0 ]]; then
  printf '%s\n' "error: no booted iOS Simulator." >&2
  printf '%s\n' "       Run 'xcrun simctl list devices' to see available simulators, then" >&2
  printf '%s\n' "       'xcrun simctl boot <UDID>' or 'open -a Simulator' to boot one." >&2
  exit 1
fi
if [[ "${BOOTED_COUNT}" -gt 1 ]]; then
  printf '%s\n' "error: ${BOOTED_COUNT} simulators are booted — 'simctl install booted' is ambiguous." >&2
  printf '%s\n' "       Shut down extras with 'xcrun simctl shutdown <UDID>' until exactly one remains." >&2
  exit 1
fi

printf '%s\n' "==> Building IosSampleApp for iphonesimulator"
mkdir -p "$(dirname "${BUILD_LOG}")"
# Capture xcodebuild output to a log so build failures aren't opaque. Use -target
# instead of -scheme + -destination so xcodebuild doesn't try to resolve a
# destination by SDK version — it builds a fat (arm64+x86_64) .app that any
# iphonesimulator runtime >= deployment target can install.
if ! xcodebuild \
  -project "${PROJECT}" \
  -target IosSampleApp \
  -sdk iphonesimulator \
  -configuration Debug \
  CODE_SIGNING_ALLOWED=NO \
  build \
  >"${BUILD_LOG}" 2>&1; then
  printf '%s\n' "error: xcodebuild failed — last 80 lines of ${BUILD_LOG}:" >&2
  tail -n 80 "${BUILD_LOG}" >&2
  exit 1
fi

APP_PATH="${SCRIPT_DIR}/build/Debug-iphonesimulator/IosSampleApp.app"
if [[ ! -d "${APP_PATH}" ]]; then
  printf '%s\n' "error: build succeeded but ${APP_PATH} not found" >&2
  printf '%s\n' "       See ${BUILD_LOG} for the full xcodebuild output." >&2
  exit 1
fi

printf '%s\n' "==> Installing ${APP_PATH} on booted simulator"
xcrun simctl install booted "${APP_PATH}"

printf '%s\n' "==> ${BUNDLE_ID} installed."
