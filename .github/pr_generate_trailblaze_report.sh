#!/usr/bin/env bash
# Generate the CI `trailblaze_report.html` index for the trail run.
#
# Runs entirely off the prebuilt Trailblaze CLI installed on $PATH by the
# upstream `build-uber-jar` job's `install-trailblaze-from-artifact.sh` step.
# The WASM report template is bundled into the uber JAR's classpath (the
# build-uber-jar job invokes Gradle with -Ptrailblaze.wasm=true), so there's
# no separate `:trailblaze-ui:wasmJsBrowserProductionWebpack` /
# `:trailblaze-report:run` Gradle dance — just one `trailblaze report` call.
set -e

TRAILBLAZE_LOGS_DIR="$(pwd)/trailblaze-logs"

echo "========================================="

if [ ! -d "$TRAILBLAZE_LOGS_DIR" ] || [ -z "$(ls -A "$TRAILBLAZE_LOGS_DIR" 2>/dev/null)" ]; then
  echo "WARNING: No logs found in $TRAILBLAZE_LOGS_DIR - skipping report generation"
  echo "========================================="
  exit 0
fi

echo "Generating Trailblaze report..."
trailblaze report --output-dir "$TRAILBLAZE_LOGS_DIR"

# `trailblaze report --output-dir` writes `report.html` under the canonical name; the
# downstream artifact step (.github/pr_create_artifacts.sh) and the workflow upload
# paths still expect the legacy `trailblaze_report.html` name. Rename in place to
# avoid cascading the change into four workflow files. Best-effort: a missing input
# means the CLI emitted nothing (already logged above) and we just skip silently.
if [ -f "$TRAILBLAZE_LOGS_DIR/report.html" ]; then
  mv -f "$TRAILBLAZE_LOGS_DIR/report.html" "$TRAILBLAZE_LOGS_DIR/trailblaze_report.html"
fi

echo "Checking for generated report..."
if [ -f "$TRAILBLAZE_LOGS_DIR/trailblaze_report.html" ]; then
  echo "✓ Report found at: $TRAILBLAZE_LOGS_DIR/trailblaze_report.html"
  ls -lh "$TRAILBLAZE_LOGS_DIR/trailblaze_report.html"
else
  echo "✗ Report NOT found at expected location"
  echo "Searching for report files..."
  find "$(pwd)" -name "trailblaze_report.html" -o -name "report.html" -o -name "*report*.html" 2>/dev/null || echo "No report files found"
fi
echo "========================================="
