#!/usr/bin/env bash
# Earn ONE iOS recording from a scratch (hinted) blaze copy, session-save it,
# and move it into the real trail dir. Same erase/boot + watchdog + port
# cleanup as earn-ios.sh. See README for when and how to hint — and remember
# to restore the canonical step text in the earned recording afterwards.
# Usage: earn-ios-scratch.sh <trail-name> <scratch-blaze-path> <title>
# Env: TB_IOS_UDID (required — see README), TB_REGEN_LOGS
set -uo pipefail
WT="$(cd "$(dirname "$0")/../../.." && pwd)"
UDID="${TB_IOS_UDID:?set TB_IOS_UDID — see trails/contacts/regen/README.md}"
LOGS="${TB_REGEN_LOGS:-${TMPDIR:-/tmp}/tb-regen-logs}"
mkdir -p "$LOGS"
T="$1"; BLAZE="$2"; TITLE="$3"
case "$BLAZE" in
  "$WT"/trails/*) echo "scratch copy must live OUTSIDE the trails tree (never committed)"; exit 1;;
esac
cd "$WT"
export TRAILBLAZE_CONFIG_DIR="$WT/trails/config"

echo "=== earn-ios-scratch $T ==="
xcrun simctl shutdown "$UDID" 2>/dev/null
xcrun simctl erase "$UDID" || { echo "ERASE FAILED"; exit 1; }
xcrun simctl boot "$UDID"
xcrun simctl bootstatus "$UDID" -b > /dev/null 2>&1
sleep 5

LOG="$LOGS/earn-ios-$T.log"
START=$(date +%s)
trailblaze run --no-daemon --max-llm-calls 100 --device "ios/$UDID" "$BLAZE" > "$LOG" 2>&1 &
PID=$!
for i in $(seq 1 600); do
  grep -q "^Results:" "$LOG" 2>/dev/null && break
  kill -0 "$PID" 2>/dev/null || break
  sleep 3
done
END=$(date +%s)
for i in $(seq 1 10); do kill -0 "$PID" 2>/dev/null || break; sleep 3; done
kill "$PID" 2>/dev/null
wait "$PID" 2>/dev/null
for i in $(seq 1 20); do
  lsof -nP -iTCP:52525 -sTCP:LISTEN >/dev/null 2>&1 || break
  sleep 3
done
rm -f "$HOME/.trailblaze/daemon-52525.pid"
if ! grep -q "Results: 1 passed" "$LOG"; then
  echo "BLAZE FAILED: $T after $((END-START))s (log: $LOG)"
  grep -m1 -E "Test execution failed|Error:" "$LOG"
  exit 1
fi
echo "blaze OK: $T in $((END-START))s"

SID=$(grep -oE "session [a-z0-9_]+" "$LOG" | head -1 | awk '{print $2}')
echo "session: ${SID:-<none>}"
if [ -n "$SID" ]; then SAVE_ARGS=(--id "$SID" --title "$TITLE"); else SAVE_ARGS=(--title "$TITLE"); fi
if ! trailblaze session save "${SAVE_ARGS[@]}" > "$LOGS/save-ios-$T.log" 2>&1; then
  echo "SAVE FAILED"; tail -5 "$LOGS/save-ios-$T.log"; exit 1
fi
SAVED_FILE=$(grep -oE "Trail saved: .*" "$LOGS/save-ios-$T.log" | sed 's/Trail saved: //')
SAVED_DIR=$(dirname "$SAVED_FILE")
echo "saved into: $SAVED_DIR"
for CAND in ios.trail.yaml ios-iphone.trail.yaml; do
  if [ -f "$SAVED_DIR/$CAND" ]; then
    mv "$SAVED_DIR/$CAND" "trails/contacts/$T/ios.trail.yaml"
    echo "RECORDING IN PLACE: trails/contacts/$T/ios.trail.yaml (from $CAND)"
    break
  fi
done
rm -f "$SAVED_DIR/ios-iphone.trail.yaml" "$SAVED_DIR/ios.trail.yaml"
rmdir "$SAVED_DIR" 2>/dev/null
# the scratch blaze may auto-save its recording next to the SCRATCH file — drop it
rm -f "$(dirname "$BLAZE")"/ios*.trail.yaml
echo "REMINDER: restore canonical step/verify text in trails/contacts/$T/ios.trail.yaml"
ls -la "trails/contacts/$T/"
