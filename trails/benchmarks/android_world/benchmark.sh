#!/usr/bin/env bash
#
# Benchmark: Android World trails.
# Discovers all android.trail.yaml files, runs them sequentially,
# then builds a combined markdown report at
# docs/benchmarks/android-world-benchmarks.md.
#
# Usage:
#   bash opensource/trails/benchmarks/android_world/benchmark.sh
#   bash opensource/trails/benchmarks/android_world/benchmark.sh --filter clock
#   bash opensource/trails/benchmarks/android_world/benchmark.sh --filter "clock/timer_entry"
#
# Options:
#   --filter <pattern>   Only run trails whose path contains <pattern>
#   --device <id>        Device ID to run on (passed to trailblaze run --device)
#   --dry-run            List trails that would be run without executing them
#
# Prerequisites: An Android emulator must be running and ./trailblaze must be available.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/../../../.." && pwd)"
BENCHMARK_MD_DIR="$ROOT_DIR/opensource/docs/benchmarks"
TRAILS_DIR="$SCRIPT_DIR"

# Parse arguments
FILTER=""
DEVICE_ARGS=""
DRY_RUN=false
while [[ $# -gt 0 ]]; do
  case "$1" in
    --filter)
      FILTER="$2"
      shift 2
      ;;
    --device)
      DEVICE_ARGS="--device $2"
      shift 2
      ;;
    --dry-run)
      DRY_RUN=true
      shift
      ;;
    *)
      echo "Unknown option: $1"
      echo "Usage: $0 [--filter <pattern>] [--device <id>] [--dry-run]"
      exit 1
      ;;
  esac
done

# Discover trail files
TRAIL_FILES=()
while IFS= read -r f; do
  if [[ -n "$FILTER" ]] && [[ "$f" != *"$FILTER"* ]]; then
    continue
  fi
  TRAIL_FILES+=("$f")
done < <(find "$TRAILS_DIR" -name "android.trail.yaml" | sort)

if [[ ${#TRAIL_FILES[@]} -eq 0 ]]; then
  echo "No trail files found matching filter: ${FILTER:-<none>}"
  exit 1
fi

echo ""
echo "=== Android World Benchmark ==="
echo "Trails to run: ${#TRAIL_FILES[@]}"
echo ""

if [[ "$DRY_RUN" == true ]]; then
  for trail_file in "${TRAIL_FILES[@]}"; do
    rel_path="${trail_file#$TRAILS_DIR/}"
    echo "  $rel_path"
  done
  echo ""
  echo "(dry run — no trails executed)"
  exit 0
fi

# --- Run trails and collect results ---

PASSED=0
FAILED=0
TOTAL=0

# Parallel arrays (bash 3.x compatible)
TRAIL_NAMES=()
TRAIL_OUTCOMES=()

START_TIME=$(date +%s)

for trail_file in "${TRAIL_FILES[@]}"; do
  rel_path="${trail_file#$TRAILS_DIR/}"
  trail_name="${rel_path%/android.trail.yaml}"
  TOTAL=$((TOTAL + 1))

  echo "[$TOTAL/${#TRAIL_FILES[@]}] Running: $trail_name ..."

  TRAIL_NAMES+=("$trail_name")

  # Run the trail; capture exit code without aborting the script
  set +e
  "$ROOT_DIR/trailblaze" run "$trail_file" $DEVICE_ARGS
  exit_code=$?
  set -e

  if [[ $exit_code -eq 0 ]]; then
    PASSED=$((PASSED + 1))
    TRAIL_OUTCOMES+=("PASSED")
  else
    FAILED=$((FAILED + 1))
    TRAIL_OUTCOMES+=("FAILED")
  fi

  echo ""
done

END_TIME=$(date +%s)
ELAPSED=$((END_TIME - START_TIME))

# --- Print console summary ---

echo "=== Results ==="
echo ""
for i in "${!TRAIL_NAMES[@]}"; do
  printf "  %-6s %s\n" "${TRAIL_OUTCOMES[$i]}" "${TRAIL_NAMES[$i]}"
done

PASS_RATE=0
if [[ $TOTAL -gt 0 ]]; then
  PASS_RATE=$(( PASSED * 100 / TOTAL ))
fi

echo ""
echo "Total: $TOTAL  |  Passed: $PASSED  |  Failed: $FAILED  |  Pass Rate: ${PASS_RATE}%"
echo "Elapsed: ${ELAPSED}s"
echo ""

# --- Build markdown report ---

TODAY=$(date +%Y-%m-%d)
COMBINED_MD="$BENCHMARK_MD_DIR/android-world-benchmarks.md"
mkdir -p "$BENCHMARK_MD_DIR"

{
  echo "# Android World Benchmarks"
  echo ""
  echo "**Date:** $TODAY"
  echo ""
  echo "**Passed:** $PASSED / $TOTAL (${PASS_RATE}%)"
  echo ""
  echo "| Trail | Result |"
  echo "|---|---|"
  for i in "${!TRAIL_NAMES[@]}"; do
    echo "| ${TRAIL_NAMES[$i]} | ${TRAIL_OUTCOMES[$i]} |"
  done
} > "$COMBINED_MD"

echo "Markdown report: opensource/docs/benchmarks/android-world-benchmarks.md"
echo ""
