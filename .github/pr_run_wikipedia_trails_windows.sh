#!/usr/bin/env bash
# Wikipedia trail on a Windows host: the Windows counterpart of pr_run_wikipedia_trails.sh.
#
# Runs under Git Bash on the runner, but drives Trailblaze through the Windows launcher
# (`trailblaze.cmd`), which is what a Windows install puts on PATH. Unlike the Linux script,
# this does NOT start the daemon by hand: `trailblaze trail` starts it, which is the path a
# Windows user takes and the part of Windows support most worth covering.
#
# Logs are always collected into ./trailblaze-logs, even when the trail fails.
TRAILBLAZE_BIN="${TRAILBLAZE_BIN:-trailblaze.cmd}"
TRAILBLAZE_LOGS_DIR="$(pwd)/trailblaze-logs"
TRAILBLAZE_LOCAL_LOGS_DIR="$HOME/.trailblaze/logs"
mkdir -p "$TRAILBLAZE_LOGS_DIR"

echo "========================================="
echo "Starting Wikipedia Trail Execution (Windows)"
echo "Working directory: $(pwd)"
echo "========================================="

# esbuild for the trailmap's scripted tools; see pr_run_wikipedia_trails.sh.
echo "Installing TypeScript SDK devDependencies (esbuild)..."
(cd sdks/typescript && bun install --frozen-lockfile) \
  || { echo "ERROR: bun install failed in sdks/typescript"; TEST_FAILED=true; }

# Must be set before the first `trailblaze` call: the daemon that call starts inherits it.
# `pwd -W` gives the Windows form (D:/a/...), which the JVM needs.
export TRAILBLAZE_CONFIG_DIR="$(pwd -W)/examples/wikipedia/trails/config"
echo "TRAILBLAZE_CONFIG_DIR=$TRAILBLAZE_CONFIG_DIR"

if [ "$TEST_FAILED" != "true" ]; then
  echo "Pre-installing Playwright Chromium..."
  bunx playwright@1.59.0 install chromium \
    || echo "WARNING: Playwright pre-install failed — download will happen during trail execution"
fi

if [ "$TEST_FAILED" != "true" ]; then
  if WEB_SHOWCASE_TRAIL="$(./.github/showcase-trail.sh web recording)" && [ -n "$WEB_SHOWCASE_TRAIL" ]; then
    echo "Web showcase trail (from docs/showcase-trails.yml): $WEB_SHOWCASE_TRAIL"
    "$TRAILBLAZE_BIN" trail "$WEB_SHOWCASE_TRAIL" || TEST_FAILED=true
  else
    echo "ERROR: could not resolve the web showcase trail from docs/showcase-trails.yml"
    TEST_FAILED=true
  fi
fi

echo "========================================="
echo "Test execution completed (failed: ${TEST_FAILED:-false})"
echo "========================================="

"$TRAILBLAZE_BIN" status
"$TRAILBLAZE_BIN" stop

echo "Copying logs from $TRAILBLAZE_LOCAL_LOGS_DIR to $TRAILBLAZE_LOGS_DIR..."
cp -r "$TRAILBLAZE_LOCAL_LOGS_DIR"/* "$TRAILBLAZE_LOGS_DIR/" 2>/dev/null || echo "No logs found in $TRAILBLAZE_LOCAL_LOGS_DIR"

if [ "$TEST_FAILED" = "true" ]; then
  echo "Tests failed — exiting with code 1"
  exit 1
fi
