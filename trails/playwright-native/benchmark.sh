#!/usr/bin/env bash
#
# Benchmark: AI vs Recording replay for Playwright-native trails.
# Runs each trail twice (AI mode, then recording mode), prints a comparison table,
# and appends results to docs/benchmarks/playwright-native-benchmarks.csv.
#
# Usage: bash trails/playwright-native/benchmark.sh
#
# Prerequisites: ./trailblaze must be running (or will auto-start via `run`).

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"
LOG_DIR="$ROOT_DIR/logs"
BENCHMARK_CSV="$ROOT_DIR/docs/benchmarks/playwright-native-benchmarks.csv"

TRAILS=(
  "test-counter"
  "test-form-interaction"
  "test-navigation"
  "test-all-tools"
  "test-scroll-containers"
  "test-duplicate-list"
  "test-search-duplicates"
)

# --- Helper functions ---

# Finds the most recently created session log directory.
# Since trails run sequentially, the newest directory is always the one we just ran.
find_latest_session() {
  ls -dt "$LOG_DIR"/*/ 2>/dev/null | head -1
}

extract_duration_ms() {
  local session_dir="$1"
  local end_log
  end_log=$(ls "$session_dir"/*TrailblazeSessionStatusChangeLog.json 2>/dev/null | tail -1)
  if [[ -n "$end_log" ]]; then
    python3 -c "
import json
d = json.load(open('$end_log'))
status = d.get('sessionStatus', {})
if 'durationMs' in status:
    print(status['durationMs'])
else:
    print(-1)
" 2>/dev/null || echo "-1"
  else
    echo "-1"
  fi
}

ms_to_seconds() {
  local ms="$1"
  if [[ "$ms" == "-1" ]]; then
    echo "N/A"
  else
    python3 -c "print(f'{$ms / 1000:.1f}')"
  fi
}

speedup() {
  local ai_ms="$1"
  local rec_ms="$2"
  if [[ "$ai_ms" == "-1" || "$rec_ms" == "-1" || "$rec_ms" == "0" ]]; then
    echo "N/A"
  else
    python3 -c "print(f'{$ai_ms / $rec_ms:.1f}x')"
  fi
}

# --- Ensure CSV exists with header ---
init_csv() {
  if [[ ! -f "$BENCHMARK_CSV" ]]; then
    mkdir -p "$(dirname "$BENCHMARK_CSV")"
    echo "date,trail,ai_seconds,recording_seconds,speedup" > "$BENCHMARK_CSV"
  fi
}

# --- Main ---

echo ""
echo "=== Playwright-Native Benchmark: AI vs Recording ==="
echo ""

TODAY=$(date +%Y-%m-%d)
init_csv

# Parallel arrays to store results (compatible with bash 3.x on macOS)
AI_DURATIONS=()
REC_DURATIONS=()

for trail in "${TRAILS[@]}"; do
  trail_file="$SCRIPT_DIR/${trail}/trail.yaml"
  recording_file="$SCRIPT_DIR/${trail}/web.trail.yaml"

  # --- AI run ---
  echo "Running AI:        $trail ..."
  "$ROOT_DIR/trailblaze" run "$trail_file" 2>&1 | tail -1
  ai_session=$(find_latest_session)
  AI_DURATIONS+=("$(extract_duration_ms "$ai_session")")

  # --- Recording run ---
  if [[ -f "$recording_file" ]]; then
    echo "Running Recording: $trail ..."
    "$ROOT_DIR/trailblaze" run "$recording_file" --use-recorded-steps 2>&1 | tail -1
    rec_session=$(find_latest_session)
    REC_DURATIONS+=("$(extract_duration_ms "$rec_session")")
  else
    echo "  (no recording file for $trail, skipping)"
    REC_DURATIONS+=("-1")
  fi

  echo ""
done

# --- Print results table ---
echo ""
printf "%-25s | %10s | %14s | %7s\n" "Trail" "AI (sec)" "Recording (sec)" "Speedup"
printf "%-25s-|-%10s-|-%14s-|-%7s\n" "-------------------------" "----------" "--------------" "-------"

i=0
for trail in "${TRAILS[@]}"; do
  ai_ms="${AI_DURATIONS[$i]}"
  rec_ms="${REC_DURATIONS[$i]}"
  ai_sec=$(ms_to_seconds "$ai_ms")
  rec_sec=$(ms_to_seconds "$rec_ms")
  spd=$(speedup "$ai_ms" "$rec_ms")

  printf "%-25s | %10s | %14s | %7s\n" "$trail" "${ai_sec}s" "${rec_sec}s" "$spd"

  # Append to CSV
  echo "$TODAY,$trail,$ai_sec,$rec_sec,$spd" >> "$BENCHMARK_CSV"
  i=$((i + 1))
done

# --- Update the markdown report with latest results ---
BENCHMARK_MD="$ROOT_DIR/docs/benchmarks/playwright-native-benchmarks.md"

cat > "$BENCHMARK_MD" <<MDEOF
# Playwright-Native Benchmarks

Comparison of **AI mode** (LLM interprets natural language steps) vs **Recording mode** (pre-recorded tool calls replayed deterministically) for the Playwright-native test trails.

## Latest Results ($TODAY)

| Trail | AI (sec) | Recording (sec) | Speedup |
|---|---:|---:|---:|
MDEOF

i=0
for trail in "${TRAILS[@]}"; do
  ai_ms="${AI_DURATIONS[$i]}"
  rec_ms="${REC_DURATIONS[$i]}"
  ai_sec=$(ms_to_seconds "$ai_ms")
  rec_sec=$(ms_to_seconds "$rec_ms")
  spd=$(speedup "$ai_ms" "$rec_ms")
  echo "| $trail | $ai_sec | $rec_sec | $spd |" >> "$BENCHMARK_MD"
  i=$((i + 1))
done

cat >> "$BENCHMARK_MD" <<'MDEOF'

## Trail Descriptions

- **test-counter** - Navigate to counter page, increment three times, verify value is 3, decrement once, verify value is 2, reset, verify value is 0
- **test-form-interaction** - Fill out a contact form (name, email, category dropdown, message textarea), submit, verify success message and submitted data
- **test-navigation** - Navigate between Home, Form, Counter, and About pages via links, verify each page heading/content is correct
- **test-all-tools** - Exercises every Playwright-native tool: navigate, snapshot, verify (text/element/value/list), click, type, hover, scroll (page + container), select option, press key, wait, browser back/forward
- **test-scroll-containers** - Scroll within independent sidebar and content panel containers, verify initially-hidden items (Category 15, Item 20) become visible after scrolling
- **test-duplicate-list** - Click specific View buttons in a list where multiple items share the same "Premium Cable" or "Standard Adapter" text across Electronics, Office Supplies, and Accessories sections, verifying each click selects the correct item by its unique ID
- **test-search-duplicates** - Search for products with duplicate names (Wireless Mouse, Keyboard, Monitor Stand), then click each individual result distinguished by subtitle/variant, verifying the correct item detail is shown

## How to Run

```bash
bash trails/playwright-native/benchmark.sh
```

Results are appended to [`playwright-native-benchmarks.csv`](./playwright-native-benchmarks.csv) for tracking over time.

## Notes

- AI mode timings include LLM inference latency and are expected to vary between runs.
- Recording mode replays pre-recorded tool calls without LLM inference, so timings reflect pure Playwright execution speed.
- The speedup ratio shows how much faster recording mode is compared to AI mode. Higher speedup on simpler trails (e.g., test-counter) reflects the fixed overhead of LLM calls dominating short tests.
MDEOF

echo ""
echo "Results saved to:"
echo "  CSV: docs/benchmarks/playwright-native-benchmarks.csv"
echo "  MD:  docs/benchmarks/playwright-native-benchmarks.md"
echo ""
