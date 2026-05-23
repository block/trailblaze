#!/usr/bin/env bash
# iOS Contacts trail: drives the iOS Contacts app via the Trailblaze CLI using
# the bundled Maestro/XCTest driver against a booted iOS Simulator.
# The simulator must be booted before this script runs (handled by the workflow).
# Note: intentionally not using set -e so that log collection always runs even if the test fails
TRAILBLAZE_LOGS_DIR="$(pwd)/trailblaze-logs"
TRAILBLAZE_LOCAL_LOGS_DIR="$HOME/.trailblaze/logs"

# Create logs directory early so it always exists for downstream steps
mkdir -p "$TRAILBLAZE_LOGS_DIR"

echo "========================================="
echo "Starting iOS Contacts Trail Execution"
echo "Working directory: $(pwd)"
echo "========================================="

# Install the TypeScript SDK's devDependencies (notably esbuild). The daemon's
# `LazyYamlScriptedToolRegistration.resolveEsbuildBinary()` walks up from CWD
# looking for `sdks/typescript/node_modules/.bin/esbuild`; without this step it
# finds no esbuild, silently drops every pack-defined scripted tool, and the
# trail run fails at dispatch with `Unsupported tool type for RPC execution`.
echo "Installing TypeScript SDK devDependencies (esbuild)..."
(cd sdks/typescript && bun install --frozen-lockfile) \
  || { echo "ERROR: bun install failed in sdks/typescript"; TEST_FAILED=true; }

# Export config dir before the first Gradle invocation so the Gradle daemon
# starts with it in its environment.  JavaExec subprocesses inherit the daemon's
# environment, not the caller's shell, so the export must precede the daemon's
# first start (triggered by :trailblaze-desktop:jar below).
export TRAILBLAZE_CONFIG_DIR="$(pwd)/examples/ios-contacts/trails/config"
echo "TRAILBLAZE_CONFIG_DIR=$TRAILBLAZE_CONFIG_DIR"

# Pre-compile the Trailblaze desktop module so the daemon starts within the
# 110s port-ready window below.
if [ "$TEST_FAILED" != "true" ]; then
  echo "Pre-building Trailblaze desktop classes..."
  ./gradlew :trailblaze-desktop:jar || { echo "ERROR: Failed to build Trailblaze desktop"; TEST_FAILED=true; }
fi

if [ "$TEST_FAILED" != "true" ]; then
  # Pre-launch the Contacts app so the contact list is showing before the trail
  # runs. The trail's recording assumes the app is open on the contacts list
  # root — doing this here avoids relying on the trail itself to open the app.
  echo "Pre-launching iOS Contacts app on the booted simulator..."
  xcrun simctl launch booted com.apple.MobileAddressBook && echo "✓ Contacts app launched" \
    || echo "WARNING: Could not pre-launch Contacts app — trail will likely fail"

  # Give the Contacts app time to render the full contact list before the
  # Trailblaze daemon connects and the trail begins.
  echo "Waiting for Contacts app to settle..."
  sleep 5

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

if [ "$TEST_FAILED" != "true" ]; then
  echo "Running iOS Contacts trail via Trailblaze CLI..."
  # The trail has recorded tool sequences so LLM inference is never invoked.
  # -d ios selects the booted simulator via the Maestro device service.
  # The bundled Maestro iOS driver auto-installs its XCTest runner on the
  # simulator at first connection — allow extra time in the workflow timeout.
  ./trailblaze trail -d ios \
    trails/ios-contacts/test-search-by-first-name/ios-iphone.trail.yaml \
    || TEST_FAILED=true

  ./trailblaze trail -d ios \
    trails/ios-contacts/test-search-no-results/ios-iphone.trail.yaml \
    || TEST_FAILED=true
else
  echo "Skipping test execution because setup failed"
fi

echo "========================================="
echo "Test execution completed (failed: ${TEST_FAILED:-false})"
echo "========================================="

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
if [ -n "$TRAILBLAZE_PID" ]; then
  echo "Stopping Trailblaze daemon (PID: $TRAILBLAZE_PID)..."
  kill $TRAILBLAZE_PID 2>/dev/null || echo "Trailblaze daemon already stopped"
fi
echo "✓ Cleanup complete"
echo "========================================="
echo "Script completed"

# Propagate test failure to the workflow (after log collection + cleanup have run).
if [ "$TEST_FAILED" = "true" ]; then
  echo "Tests failed — exiting with code 1"
  exit 1
fi
