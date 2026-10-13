#!/usr/bin/env bash
# Fails the job with the run's exit code. A missing file means the run never finished.
set -euo pipefail
f="$RUNNER_TEMP/trailblaze-exit"
if [ ! -f "$f" ]; then
  echo "::error title=Trailblaze::The trails did not run to completion."
  exit 1
fi
exit "$(cat "$f")"
