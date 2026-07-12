#!/usr/bin/env bash
# Replay-verify contacts parity recordings on iOS (zero-LLM), erasing the
# simulator before each trail (the same fresh state the recordings were
# earned on) and timing each replay.
# Usage: replay-ios.sh <trail-name> [...]   (e.g. create-contact)
# Env: TB_IOS_UDID (required — see README for creating a probe simulator),
#      TB_REGEN_LOGS (default: ${TMPDIR:-/tmp}/tb-regen-logs)
set -uo pipefail
WT="$(cd "$(dirname "$0")/../../.." && pwd)"
UDID="${TB_IOS_UDID:?set TB_IOS_UDID — see trails/contacts/regen/README.md}"
LOGS="${TB_REGEN_LOGS:-${TMPDIR:-/tmp}/tb-regen-logs}"
mkdir -p "$LOGS"
cd "$WT"
export TRAILBLAZE_CONFIG_DIR="$WT/trails/config"

for T in "$@"; do
  echo "=== replay-ios $T ==="
  xcrun simctl shutdown "$UDID" 2>/dev/null
  xcrun simctl erase "$UDID" || { echo "ERASE FAILED: $T"; continue; }
  xcrun simctl boot "$UDID"
  xcrun simctl bootstatus "$UDID" -b > /dev/null 2>&1
  sleep 5

  LOG="$LOGS/replay-ios-$T.log"
  START=$(date +%s)
  trailblaze run --no-daemon --no-save-recording --device "ios/$UDID" "trails/contacts/$T/ios.trail.yaml" > "$LOG" 2>&1 &
  PID=$!
  # Watchdog: up to 900s for a Results line, then 30s grace for a clean exit.
  for i in $(seq 1 300); do
    grep -q "^Results:" "$LOG" 2>/dev/null && break
    kill -0 "$PID" 2>/dev/null || break
    sleep 3
  done
  END=$(date +%s)
  for i in $(seq 1 10); do kill -0 "$PID" 2>/dev/null || break; sleep 3; done
  kill "$PID" 2>/dev/null
  wait "$PID" 2>/dev/null
  # A killed run leaves port 52525 (its report server) closing and a stale
  # daemon pidfile behind; the next run refuses to start over either.
  for i in $(seq 1 20); do
    lsof -nP -iTCP:52525 -sTCP:LISTEN >/dev/null 2>&1 || break
    sleep 3
  done
  rm -f "$HOME/.trailblaze/daemon-52525.pid"
  if grep -q "Results: 1 passed" "$LOG"; then
    echo "REPLAY OK: $T in $((END-START))s"
  else
    echo "REPLAY FAILED: $T after $((END-START))s (log: $LOG)"
    grep -m1 -A4 "Test execution failed" "$LOG"
  fi
done
echo "=== IOS REPLAY SEQUENCE DONE ==="
