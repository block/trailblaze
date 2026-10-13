#!/usr/bin/env bash
# Writes one zip (and optional storyboard) per session plus the all-sessions HTML report.
# Each export keeps going if another fails, so a broken report still leaves the zips.
set -uo pipefail
results="$RUNNER_TEMP/trailblaze-results"
logs="${TRAILBLAZE_HOME:-$HOME/.trailblaze}/logs"
mkdir -p "$results"
if [ ! -d "$logs" ]; then
  echo "::warning title=Trailblaze::No sessions were recorded."
  exit 0
fi
status=0
cd "$logs" || exit 1
for d in */; do
  id="${d%/}"
  if [ "$id" = reports ]; then continue; fi
  zip -q -r "$results/$id.zip" "$id" || status=1
  if [ "$STORYBOARDS" = true ]; then
    trailblaze report --id "$id" --storyboard="$results/$id.webp" --max-size=8MB \
      --output-dir "$RUNNER_TEMP/trailblaze-report-$id" > /dev/null || status=1
  fi
done
trailblaze report --output-dir "$results" > /dev/null || status=1
if [ -f "$results/report-interactive.html" ]; then
  mv "$results/report-interactive.html" "$results/trailblaze-report${SUFFIX:-}.html"
fi
ls -lh "$results"
exit $status
