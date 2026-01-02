#!/usr/bin/env bash
set -e

TRAILBLAZE_LOGS_DIR="$(pwd)/trailblaze-logs"

echo "========================================="
echo "Building Compose Web/WASM UI..."
./gradlew :trailblaze-ui:wasmJsBrowserProductionWebpack
UI_EXIT_CODE=$?
echo "UI build exit code: $UI_EXIT_CODE"

echo "Generating Trailblaze report..."
./gradlew :trailblaze-report:run --args="$TRAILBLAZE_LOGS_DIR"
REPORT_EXIT_CODE=$?
echo "Report generation exit code: $REPORT_EXIT_CODE"

echo "Checking for generated report..."
if [ -f "$TRAILBLAZE_LOGS_DIR/trailblaze_report.html" ]; then
  echo "✓ Report found at: $TRAILBLAZE_LOGS_DIR/trailblaze_report.html"
  ls -lh "$TRAILBLAZE_LOGS_DIR/trailblaze_report.html"
else
  echo "✗ Report NOT found at expected location"
  echo "Searching for report files..."
  find "$(pwd)" -name "trailblaze_report.html" -o -name "*report*.html" 2>/dev/null || echo "No report files found"
fi
echo "========================================="
