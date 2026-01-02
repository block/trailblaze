#!/usr/bin/env bash
set -e

TRAILBLAZE_LOGS_DIR="$(pwd)/trailblaze-logs"

echo "========================================="
echo "Creating Artifacts"
echo "========================================="

# Check for HTML report
echo "Checking for HTML report..."
if [ -f "$TRAILBLAZE_LOGS_DIR/trailblaze_report.html" ]; then
  REPORT_SIZE=$(ls -lh "$TRAILBLAZE_LOGS_DIR/trailblaze_report.html" | awk '{print $5}')
  echo "✓ Found trailblaze_report.html (size: $REPORT_SIZE)"

  # Copy report to workspace root for separate artifact upload
  echo "Copying report to workspace root..."
  cp "$TRAILBLAZE_LOGS_DIR/trailblaze_report.html" "$(pwd)/trailblaze_report.html"
  if [ -f "$(pwd)/trailblaze_report.html" ]; then
    echo "✓ Report copied successfully"
  else
    echo "✗ Failed to copy report"
  fi
else
  echo "✗ trailblaze_report.html not found"
fi

# Zip up logs
if [ -d "$TRAILBLAZE_LOGS_DIR" ] && [ "$(ls -A $TRAILBLAZE_LOGS_DIR 2>/dev/null)" ]; then
  echo "Zipping logs..."
  cd "$TRAILBLAZE_LOGS_DIR" && zip -r "../trailblaze-logs.zip" . && cd ..
  ZIP_EXIT_CODE=$?
  echo "Zip exit code: $ZIP_EXIT_CODE"

  if [ -f "$(pwd)/trailblaze-logs.zip" ]; then
    ZIP_SIZE=$(ls -lh "$(pwd)/trailblaze-logs.zip" | awk '{print $5}')
    echo "Created trailblaze-logs.zip (size: $ZIP_SIZE)"
  else
    echo "WARNING: trailblaze-logs.zip was not created!"
  fi
else
  echo "WARNING: No logs found to zip (directory empty or doesn't exist)"
fi

echo "========================================="
