#!/usr/bin/env bash
set -e

OLLAMA_MODEL="qwen3-vl:2b"

# Ollama resource optimization for CI
export OLLAMA_NUM_PARALLEL=1
export OLLAMA_MAX_LOADED_MODELS=1

echo "========================================="
echo "Starting Android Test Execution"
echo "Working directory: $(pwd)"
echo "========================================="

# Start Ollama LLM Server
echo "Starting Ollama server..."
nohup ollama serve > /tmp/ollama.log 2>&1 &
OLLAMA_PID=$!
echo "Ollama server started with PID: $OLLAMA_PID"
sleep 3
echo "Waiting for Ollama server to be ready..."
for attempt in 1 2 3 4 5 6 7 8 9 10; do curl -s http://localhost:11434/api/tags > /dev/null 2>&1 && break || (echo "Attempt $attempt/10..." && sleep 2); done
curl -s http://localhost:11434/api/tags > /dev/null 2>&1 || (echo "ERROR: Ollama failed to start" && cat /tmp/ollama.log && exit 1)
echo "✓ Ollama server is ready!"

echo "Downloading $OLLAMA_MODEL model..."
ollama pull $OLLAMA_MODEL
echo "✓ Model downloaded"

echo "Validating $OLLAMA_MODEL model connectivity..."
curl --verbose http://localhost:11434/api/generate -d '{"model": "'$OLLAMA_MODEL'", "prompt": "Test", "stream": false}' 2>&1 | head -30
echo "✓ Ollama LLM ready at http://localhost:11434"
echo "========================================="

# Start Trailblaze server in background
echo "Starting Trailblaze server..."
./gradlew :trailblaze-desktop:run --args="$(pwd) --headless" --no-daemon > /tmp/trailblaze.log 2>&1 &
TRAILBLAZE_PID=$!
echo "Trailblaze server started with PID: $TRAILBLAZE_PID"
echo "✓ Trailblaze server is running!"
echo "========================================="

# Start capturing logcat
echo "Starting logcat capture (filtering out noise)..."
adb logcat | grep -v "skipping invisible child" > logcat.log &
LOGCAT_PID=$!
echo "Logcat capture started with PID: $LOGCAT_PID"
echo "========================================="

# Run Android Tests
echo "Assembling Android Tests..."
./gradlew :examples:assembleDebugAndroidTest --no-daemon

echo "Running Android Tests..."
./gradlew --info :examples:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class="xyz.block.trailblaze.examples.clock.ClockTest" -Pandroid.testInstrumentationRunnerArguments.trailblaze.reverseProxy="true" --no-daemon || TEST_FAILED=true

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
mkdir -p "$(pwd)/trailblaze-logs"
adb pull /sdcard/Download/trailblaze-logs/. "$(pwd)/trailblaze-logs" && echo "Log pull succeeded" || echo "Failed to pull logs"

# Check what was pulled
echo "Checking pulled logs..."
[ -d "$(pwd)/trailblaze-logs" ] || { echo "WARNING: trailblaze-logs directory does not exist!"; exit 0; }
ls -laR "$(pwd)/trailblaze-logs"
echo "Total files pulled: $(find "$(pwd)/trailblaze-logs" -type f 2>/dev/null | wc -l)"

# Cleanup: Kill background servers
echo "========================================="
echo "Cleaning up background servers..."
if [ -n "$LOGCAT_PID" ]; then
  echo "Stopping logcat capture (PID: $LOGCAT_PID)..."
  kill $LOGCAT_PID 2>/dev/null || echo "Logcat capture already stopped"
fi
if [ -n "$OLLAMA_PID" ]; then
  echo "Stopping Ollama server (PID: $OLLAMA_PID)..."
  kill $OLLAMA_PID 2>/dev/null || echo "Ollama server already stopped"
fi
if [ -n "$TRAILBLAZE_PID" ]; then
  echo "Stopping Trailblaze server (PID: $TRAILBLAZE_PID)..."
  kill $TRAILBLAZE_PID 2>/dev/null || echo "Trailblaze server already stopped"
fi
echo "✓ Cleanup complete"
echo "========================================="
echo "Logcat saved to: $(pwd)/logcat.log"
echo "Emulator script completed"
