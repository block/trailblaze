#!/usr/bin/env bash
set -e

TRAILBLAZE_LOGS_DIR="$(pwd)/trailblaze-logs"
TRAILBLAZE_LOCAL_LOGS_DIR="$HOME/.trailblaze/logs"

echo "========================================="
echo "Starting Android Test Execution"
echo "Working directory: $(pwd)"
echo "========================================="

# Start Trailblaze server in background
echo "Building Trailblaze server..."
./gradlew :trailblaze-desktop:jar

echo "Starting Trailblaze server..."
./gradlew :trailblaze-desktop:run --args="$(pwd) --headless" > /tmp/trailblaze.log 2>&1 &
TRAILBLAZE_PID=$!
echo "Trailblaze server started with PID: $TRAILBLAZE_PID"
echo "Waiting for Trailblaze server to be ready on port 8443 (this may take up to 2 minutes)..."
sleep 10
for attempt in 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 19 20; do 
  nc -z localhost 8443 > /dev/null 2>&1 && break || (echo "Attempt $attempt/20..." && sleep 5)
done
nc -z localhost 8443 > /dev/null 2>&1 || (echo "ERROR: Trailblaze server failed to start on port 8443" && echo "=== Trailblaze logs ===" && cat /tmp/trailblaze.log && exit 1)
echo "✓ Trailblaze server is running on port 8443!"
echo "========================================="

# Start capturing logcat
echo "Starting logcat capture (filtering out noise)..."
adb logcat | grep -v "skipping invisible child" > logcat.log &
LOGCAT_PID=$!
echo "Logcat capture started with PID: $LOGCAT_PID"
echo "========================================="

# Run Android Tests
echo "Assembling Android Tests..."
./gradlew :examples:assembleDebugAndroidTest

echo "Running Android Tests..."
./gradlew --info :examples:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class="xyz.block.trailblaze.examples.clock.ClockTest" -Pandroid.testInstrumentationRunnerArguments.trailblaze.reverseProxy="true" || TEST_FAILED=true

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

# copy here

# Cleanup: Kill background servers
echo "========================================="
echo "Cleaning up background servers..."
if [ -n "$LOGCAT_PID" ]; then
  echo "Stopping logcat capture (PID: $LOGCAT_PID)..."
  kill $LOGCAT_PID 2>/dev/null || echo "Logcat capture already stopped"
fi
if [ -n "$TRAILBLAZE_PID" ]; then
  echo "Stopping Trailblaze server (PID: $TRAILBLAZE_PID)..."
  kill $TRAILBLAZE_PID 2>/dev/null || echo "Trailblaze server already stopped"
fi
echo "✓ Cleanup complete"
echo "========================================="
echo "Logcat saved to: $(pwd)/logcat.log"
echo "Emulator script completed"
