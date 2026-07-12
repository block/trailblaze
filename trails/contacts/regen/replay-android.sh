#!/usr/bin/env bash
# Replay-verify contacts parity recordings on Android (zero-LLM), resetting
# app state before each trail and timing each replay.
# Usage: replay-android.sh <trail-name> [...]   (e.g. create-contact)
# Env: TB_ANDROID_SERIAL (default: first `adb devices` entry),
#      TB_REGEN_LOGS (default: ${TMPDIR:-/tmp}/tb-regen-logs)
#
# `trailblaze run` can linger after printing Results (trace-post retry), so
# success is judged from the log and the process gets a hard watchdog.
set -uo pipefail
WT="$(cd "$(dirname "$0")/../../.." && pwd)"
SERIAL="${TB_ANDROID_SERIAL:-$(adb devices | awk 'NR>1 && $2=="device"{print $1; exit}')}"
[ -n "$SERIAL" ] || { echo "no android device — set TB_ANDROID_SERIAL or boot an emulator"; exit 1; }
LOGS="${TB_REGEN_LOGS:-${TMPDIR:-/tmp}/tb-regen-logs}"
mkdir -p "$LOGS"
cd "$WT"
export TRAILBLAZE_CONFIG_DIR="$WT/trails/config"

for T in "$@"; do
  echo "=== replay $T ==="
  adb -s "$SERIAL" shell pm clear com.android.contacts >/dev/null
  adb -s "$SERIAL" shell pm clear com.android.providers.contacts >/dev/null
  adb -s "$SERIAL" shell pm grant com.android.contacts android.permission.POST_NOTIFICATIONS
  LOG="$LOGS/replay-android-$T.log"
  START=$(date +%s)
  trailblaze run --no-daemon --no-save-recording --device "android/$SERIAL" "trails/contacts/$T/android.trail.yaml" > "$LOG" 2>&1 &
  PID=$!
  # Watchdog: up to 600s for a Results line, then 30s grace for a clean exit.
  for i in $(seq 1 200); do
    grep -q "^Results:" "$LOG" 2>/dev/null && break
    kill -0 "$PID" 2>/dev/null || break
    sleep 3
  done
  END=$(date +%s)
  for i in $(seq 1 10); do kill -0 "$PID" 2>/dev/null || break; sleep 3; done
  kill "$PID" 2>/dev/null
  wait "$PID" 2>/dev/null
  if grep -q "Results: 1 passed" "$LOG"; then
    echo "REPLAY OK: $T in $((END-START))s"
  else
    echo "REPLAY FAILED: $T after $((END-START))s (log: $LOG)"
    grep -m1 -A4 "Test execution failed" "$LOG"
  fi
done
echo "=== ANDROID REPLAY SEQUENCE DONE ==="
