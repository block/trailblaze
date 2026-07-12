#!/usr/bin/env bash
# Android contacts parity suite: replays the 4 recorded contacts trails
# sequentially on the emulator via `trailblaze run --no-daemon` (in-process
# agent loop, device tools dispatched over RPC to the auto-installed
# on-device APK). No daemon start/poll dance — each run is self-contained,
# which also mirrors exactly how the recordings were verified locally.
#
# `trailblaze` resolves on $PATH because the workflow runs
# `install-trailblaze-from-artifact.sh` against the upstream `build-uber-jar`
# job's prebuilt JAR before invoking this script.
# Note: intentionally not using set -e so that log collection always runs even
# if a trail fails; all 4 trails run regardless so one red trail still leaves
# green/red status for the other three in the log.
TRAILBLAZE_LOGS_DIR="$(pwd)/trailblaze-logs"
TRAILBLAZE_LOCAL_LOGS_DIR="$HOME/.trailblaze/logs"

mkdir -p "$TRAILBLAZE_LOGS_DIR"

echo "========================================="
echo "Starting Android Contacts Trails Execution"
echo "Working directory: $(pwd)"
echo "========================================="

# Install the TypeScript SDK's devDependencies (notably esbuild). The runner's
# `LazyYamlScriptedToolRegistration.resolveEsbuildBinary()` walks up from CWD
# looking for `sdks/typescript/node_modules/.bin/esbuild`; without this step it
# finds no esbuild, silently drops every pack-defined scripted tool (including
# `contacts_android_launchApp`, which every recording opens with), and the
# trail run fails at dispatch with `Unsupported tool type for RPC execution`.
echo "Installing TypeScript SDK devDependencies (esbuild)..."
(cd sdks/typescript && bun install --frozen-lockfile) \
  || { echo "ERROR: bun install failed in sdks/typescript"; TEST_FAILED=true; }

# The contacts trailmap (custom launch tool + app_ids manifest) lives in the
# repo's trails config, not the default ~/.trailblaze one.
export TRAILBLAZE_CONFIG_DIR="$(pwd)/trails/config"
echo "TRAILBLAZE_CONFIG_DIR=$TRAILBLAZE_CONFIG_DIR"

# Start capturing logcat
echo "Starting logcat capture (filtering out noise)..."
adb logcat | grep -v "skipping invisible child" > logcat.log &
LOGCAT_PID=$!
echo "Logcat capture started with PID: $LOGCAT_PID"
echo "========================================="

# Pre-grant the notification permission BEFORE the first trail: the recordings
# were deliberately earned without the one-time POST_NOTIFICATIONS dialog tap,
# so an ungranted permission would put a dialog on screen no recording expects.
# Re-granted after every `pm clear` below — clearing resets runtime grants.
grant_notifications() {
  adb shell pm grant com.android.contacts android.permission.POST_NOTIFICATIONS
}

if [ "$TEST_FAILED" != "true" ]; then
  for TRAIL in create-contact find-contact add-phone-to-contact delete-contact; do
    echo "=== Trail: $TRAIL ==="
    # Each trail is self-contained (creates the contact it needs), so reset
    # ContactsProvider between trails — exactly the state the recordings were
    # earned and replay-verified against.
    adb shell pm clear com.android.contacts
    adb shell pm clear com.android.providers.contacts
    grant_notifications
    # --no-save-recording: a successful replay would otherwise auto-save an
    # `android-phone.trail.yaml` next to the source — a closest-wins duplicate.
    if trailblaze run --no-daemon --no-save-recording "trails/contacts/$TRAIL/android.trail.yaml"; then
      echo "=== PASS: $TRAIL ==="
    else
      echo "=== FAIL: $TRAIL ==="
      TEST_FAILED=true
    fi
  done
else
  echo "Skipping trail execution because setup failed"
fi

echo "========================================="
echo "Trail execution completed (failed: ${TEST_FAILED:-false})"
echo "========================================="

# Check device status
echo "Checking ADB devices..."
adb devices -l || echo "Could not list ADB devices"

echo "Pulling logs from device..."
adb pull /sdcard/Download/trailblaze-logs/. "$TRAILBLAZE_LOGS_DIR" && echo "Log pull succeeded" || echo "Failed to pull logs"

echo "Copying logs from $TRAILBLAZE_LOCAL_LOGS_DIR to $TRAILBLAZE_LOGS_DIR..."
cp -r "$TRAILBLAZE_LOCAL_LOGS_DIR"/* "$TRAILBLAZE_LOGS_DIR/" 2>/dev/null || echo "No logs found in $TRAILBLAZE_LOCAL_LOGS_DIR"

# Cleanup: Kill background processes
echo "========================================="
echo "Cleaning up background processes..."
if [ -n "$LOGCAT_PID" ]; then
  echo "Stopping logcat capture (PID: $LOGCAT_PID)..."
  kill $LOGCAT_PID 2>/dev/null || echo "Logcat capture already stopped"
fi
echo "✓ Cleanup complete"
echo "Logcat saved to: $(pwd)/logcat.log"

# Propagate test failure to the workflow (after log collection + cleanup have run).
if [ "$TEST_FAILED" = "true" ]; then
  echo "Trails failed — exiting with code 1"
  exit 1
fi
