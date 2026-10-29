#!/usr/bin/env bash
# Builds one report from every shard's session zips, and records the verdict: every trail passed
# and every shard job succeeded, so a shard that died before writing its sessions still fails.
set -uo pipefail
results="$RUNNER_TEMP/trailblaze-results"
logs="${TRAILBLAZE_HOME:-$HOME/.trailblaze}/logs"
mkdir -p "$results" "$logs"
for z in "$results"/*.zip; do
  if [ -f "$z" ]; then unzip -q -o "$z" -d "$logs"; fi
done
status=0
trailblaze report --output-dir "$results" > /dev/null || status=1
if [ -f "$results/report-interactive.html" ]; then
  mv "$results/report-interactive.html" "$results/trailblaze-report.html"
fi
counts=$(jq -r '[.results | length, ([.[] | select(.outcome == "PASSED")] | length)] | @tsv' \
  "$results/summary.json" 2> /dev/null || echo "0	0")
read -r total passed <<< "$counts"
echo "$passed of $total trails passed across every shard."
if [ "${SHARDS_PASSED:-true}" != true ]; then echo "At least one shard job failed."; fi
if [ "$status" != 0 ] || [ "$total" = 0 ] || [ "$passed" != "$total" ] || [ "${SHARDS_PASSED:-true}" != true ]; then
  status=1
fi
echo "$status" > "$RUNNER_TEMP/trailblaze-exit"
