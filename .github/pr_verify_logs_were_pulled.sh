#!/usr/bin/env bash
set -e

TRAILBLAZE_LOGS_DIR="$(pwd)/trailblaze-logs"

echo "========================================="
echo "Verifying logs in workspace"
echo "Working directory: $(pwd)"
echo "========================================="

if [ -d "$TRAILBLAZE_LOGS_DIR" ]; then
  echo "trailblaze logs directory exists in workspace"
  echo "Contents:"
  ls -laR "$TRAILBLAZE_LOGS_DIR" || echo "Could not list directory"
else
  echo "WARNING: trailblaze logs directory not found in workspace"
fi
