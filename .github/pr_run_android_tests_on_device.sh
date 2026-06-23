#!/usr/bin/env bash
# On-device path: runs the trail through `connectedDebugAndroidTest` (classic JUnit
# instrumentation). The test logic executes inside the test process on the emulator
# and writes Trailblaze logs/screenshots to the device's Downloads directory.
# Note: intentionally not using set -e so that log collection always runs even if build/tests fail
TRAILBLAZE_LOGS_DIR="$(pwd)/trailblaze-logs"

# Create logs directory early so it always exists for downstream steps
mkdir -p "$TRAILBLAZE_LOGS_DIR"

echo "========================================="
echo "Starting Android Test Execution (on-device)"
echo "Working directory: $(pwd)"
echo "========================================="

# Start capturing logcat
echo "Starting logcat capture (filtering out noise)..."
adb logcat | grep -v "skipping invisible child" > logcat.log &
LOGCAT_PID=$!
echo "Logcat capture started with PID: $LOGCAT_PID"
echo "========================================="

# Run Android Tests
if [ "$TEST_FAILED" != "true" ]; then
  echo "Running Android Tests..."
  ./gradlew --info :examples:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class="xyz.block.trailblaze.examples.clock.ClockTest" || TEST_FAILED=true
else
  echo "Skipping test execution because setup failed"
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

# Cleanup: Kill background log collection
echo "========================================="
echo "Cleaning up background processes..."
if [ -n "$LOGCAT_PID" ]; then
  echo "Stopping logcat capture (PID: $LOGCAT_PID)..."
  kill $LOGCAT_PID 2>/dev/null || echo "Logcat capture already stopped"
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
