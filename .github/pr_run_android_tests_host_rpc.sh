#!/usr/bin/env bash
# Host-RPC path: drives the trail from the host via the Trailblaze CLI
# (`./trailblaze trail …`). The CLI runs the agent loop in-process on the host
# and dispatches device tools over RPC to its bundled on-device APK, which it
# auto-installs over ADB. No `connectedDebugAndroidTest` and no separate
# headless server are required.
# Note: intentionally not using set -e so that log collection always runs even if the test fails
TRAILBLAZE_LOGS_DIR="$(pwd)/trailblaze-logs"
TRAILBLAZE_LOCAL_LOGS_DIR="$HOME/.trailblaze/logs"

# Create logs directory early so it always exists for downstream steps
mkdir -p "$TRAILBLAZE_LOGS_DIR"

echo "========================================="
echo "Starting Android Test Execution (host-rpc)"
echo "Working directory: $(pwd)"
echo "========================================="

# Pre-compile the Trailblaze desktop module so the daemon starts within the
# 110s port-ready window below. Without this, `./trailblaze app …` does a cold
# Kotlin compile (4+ min on CI) inside the backgrounded process, the wait loop
# times out, and the subsequent `trail` invocation triggers the launcher's
# auto-start fallback — producing two concurrent `gradlew run` invocations
# that have corrupted the Kotlin incremental cache on past runs.
echo "Pre-building Trailblaze desktop classes..."
./gradlew :trailblaze-desktop:jar || { echo "ERROR: Failed to build Trailblaze desktop"; TEST_FAILED=true; }

if [ "$TEST_FAILED" != "true" ]; then
  # Start the Trailblaze daemon in the background. `app --foreground --headless`
  # blocks the process, so we background it with `&` and poll /ping until ready.
  # The `trail` invocation below detects the running daemon and reuses it.
  echo "Starting Trailblaze daemon (app --foreground --headless)..."
  ./trailblaze app --foreground --headless > /tmp/trailblaze.log 2>&1 &
  TRAILBLAZE_PID=$!
  echo "Trailblaze daemon started with PID: $TRAILBLAZE_PID"
  echo "Waiting for Trailblaze daemon to be ready on port 52525 (this may take up to 2 minutes)..."
  sleep 10
  for attempt in 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 19 20; do
    curl -s --connect-timeout 1 http://localhost:52525/ping > /dev/null 2>&1 && break || (echo "Attempt $attempt/20..." && sleep 5)
  done
  if ! curl -s --connect-timeout 1 http://localhost:52525/ping > /dev/null 2>&1; then
    echo "ERROR: Trailblaze daemon failed to start"
    echo "=== Trailblaze logs ==="
    cat /tmp/trailblaze.log
    TEST_FAILED=true
  else
    echo "✓ Trailblaze daemon is running on port 52525!"
  fi
fi
echo "========================================="

# Start capturing logcat
echo "Starting logcat capture (filtering out noise)..."
adb logcat | grep -v "skipping invisible child" > logcat.log &
LOGCAT_PID=$!
echo "Logcat capture started with PID: $LOGCAT_PID"
echo "========================================="

if [ "$TEST_FAILED" != "true" ]; then
  echo "Running Trail via Trailblaze CLI..."
  # The trail has recorded tool sequences so LLM inference is never invoked.
  # The fake OPENAI_API_KEY from the workflow env satisfies the provider wiring.
  ./trailblaze trail trails/clock/set-alarm-730am/android.trail.yaml || TEST_FAILED=true
else
  echo "Skipping test execution because daemon failed to start"
fi

echo "========================================="
echo "Test execution completed (failed: ${TEST_FAILED:-false})"
echo "========================================="

# Check device status
echo "Checking ADB devices..."
adb devices -l || echo "Could not list ADB devices"

# Check if logs exist on device
echo "Checking for logs on device..."
adb shell "ls -la /sdcard/Download/trailblaze-logs/ 2>&1" || echo "Could not list device log directory"

echo "Pulling logs from device..."
mkdir -p "$TRAILBLAZE_LOGS_DIR"
adb pull /sdcard/Download/trailblaze-logs/. "$TRAILBLAZE_LOGS_DIR" && echo "Log pull succeeded" || echo "Failed to pull logs"

# Check what was pulled
echo "Checking pulled logs..."
[ -d "$TRAILBLAZE_LOGS_DIR" ] || { echo "WARNING: trailblaze logs directory does not exist!"; exit 0; }
ls -laR "$TRAILBLAZE_LOGS_DIR"
echo "Total files pulled: $(find "$TRAILBLAZE_LOGS_DIR" -type f 2>/dev/null | wc -l)"

echo "Checking contents of $TRAILBLAZE_LOCAL_LOGS_DIR..."
if [ -d "$TRAILBLAZE_LOCAL_LOGS_DIR" ]; then
  ls -laR "$TRAILBLAZE_LOCAL_LOGS_DIR"
  echo "Total files in local logs: $(find "$TRAILBLAZE_LOCAL_LOGS_DIR" -type f 2>/dev/null | wc -l)"
else
  echo "Directory $TRAILBLAZE_LOCAL_LOGS_DIR does not exist"
fi

echo "Copying logs from $TRAILBLAZE_LOCAL_LOGS_DIR to $TRAILBLAZE_LOGS_DIR..."
mkdir -p "$TRAILBLAZE_LOGS_DIR"
cp -r "$TRAILBLAZE_LOCAL_LOGS_DIR"/* "$TRAILBLAZE_LOGS_DIR/" 2>/dev/null || echo "No logs found in $TRAILBLAZE_LOCAL_LOGS_DIR"

# Copy daemon log for debugging
if [ -f /tmp/trailblaze.log ]; then
  cp /tmp/trailblaze.log "$TRAILBLAZE_LOGS_DIR/trailblaze-daemon.log"
  echo "Copied daemon log to $TRAILBLAZE_LOGS_DIR/trailblaze-daemon.log"
fi

# Cleanup: Kill background processes
echo "========================================="
echo "Cleaning up background processes..."
if [ -n "$LOGCAT_PID" ]; then
  echo "Stopping logcat capture (PID: $LOGCAT_PID)..."
  kill $LOGCAT_PID 2>/dev/null || echo "Logcat capture already stopped"
fi
if [ -n "$TRAILBLAZE_PID" ]; then
  echo "Stopping Trailblaze daemon (PID: $TRAILBLAZE_PID)..."
  kill $TRAILBLAZE_PID 2>/dev/null || echo "Trailblaze daemon already stopped"
fi
echo "✓ Cleanup complete"
echo "========================================="
echo "Logcat saved to: $(pwd)/logcat.log"
echo "Emulator script completed"

# Propagate test failure to the workflow (after log collection + cleanup have run).
if [ "$TEST_FAILED" = "true" ]; then
  echo "Tests failed — exiting with code 1"
  exit 1
fi
