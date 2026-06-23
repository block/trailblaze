#!/usr/bin/env bash
# Generate the CI `trailblaze_report.html` index for the trail run.
#
# Uses the prebuilt Trailblaze CLI when a workflow installed one from the
# `build-uber-jar` artifact. Jobs that intentionally run without that artifact
# (notably Android on-device instrumentation) use the standalone report generator
# against the pulled logs directory instead of launching the full desktop CLI.
set -e

TRAILBLAZE_LOGS_DIR="$(pwd)/trailblaze-logs"
TRAILBLAZE_BIN="${TRAILBLAZE_BIN:-trailblaze}"

echo "========================================="

if [ ! -d "$TRAILBLAZE_LOGS_DIR" ] || [ -z "$(ls -A "$TRAILBLAZE_LOGS_DIR" 2>/dev/null)" ]; then
  echo "WARNING: No logs found in $TRAILBLAZE_LOGS_DIR - skipping report generation"
  echo "========================================="
  exit 0
fi

echo "Generating Trailblaze report..."
if command -v "$TRAILBLAZE_BIN" >/dev/null 2>&1; then
  "$TRAILBLAZE_BIN" report --output-dir "$TRAILBLAZE_LOGS_DIR"

  # `trailblaze report --output-dir` writes `report.html` under the canonical name; the
  # downstream artifact step (.github/pr_create_artifacts.sh) and the workflow upload
  # paths still expect the legacy `trailblaze_report.html` name. Rename in place to
  # avoid cascading the change into four workflow files. Best-effort: a missing input
  # means the CLI emitted nothing (already logged above) and we just skip silently.
  if [ -f "$TRAILBLAZE_LOGS_DIR/report.html" ]; then
    mv -f "$TRAILBLAZE_LOGS_DIR/report.html" "$TRAILBLAZE_LOGS_DIR/trailblaze_report.html"
  fi
elif [ -x "./gradlew" ]; then
  ./gradlew :trailblaze-report:generateReportTemplate -Ptrailblaze.wasm=true
  cp trailblaze-report/build/report-template/trailblaze_report.html trailblaze_report_template.html
  ./gradlew :trailblaze-report:run -Ptrailblaze.wasm=true --args="$TRAILBLAZE_LOGS_DIR"
else
  echo "WARNING: trailblaze command not found and ./gradlew is not executable - skipping report generation"
  echo "========================================="
  exit 0
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
