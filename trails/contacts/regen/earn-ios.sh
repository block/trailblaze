#!/usr/bin/env bash
# Earn iOS recordings for the contacts parity suite, sequentially, on a fresh
# accountless simulator. Between trails the sim is erased (restores pristine
# state incl. stock sample contacts — the state a fresh CI sim ships). For
# each trail: blaze via agent, session-save, move the recording next to its
# blaze.yaml, drop classifier-specific duplicates.
# Usage: earn-ios.sh <trail-name> [...]
# Env: TB_IOS_UDID (required — see README), TB_REGEN_LOGS,
#      TB_AGENT (optional, e.g. MULTI_AGENT_V3; incompatible with --max-llm-calls)
#
# The 4 flows NEVER touch the Add-Photo avatar sheet (iOS 26.x crash bug).
# NOTE: the phone-entry trails (create-contact, add-phone-to-contact) fail
# unhinted — use earn-ios-scratch.sh with a hinted copy instead (see README).
set -uo pipefail
WT="$(cd "$(dirname "$0")/../../.." && pwd)"
UDID="${TB_IOS_UDID:?set TB_IOS_UDID — see trails/contacts/regen/README.md}"
LOGS="${TB_REGEN_LOGS:-${TMPDIR:-/tmp}/tb-regen-logs}"
mkdir -p "$LOGS"
cd "$WT"
export TRAILBLAZE_CONFIG_DIR="$WT/trails/config"

title_for() {
  case "$1" in
    create-contact) echo "Contacts iOS: create a contact";;
    find-contact) echo "Contacts iOS: find a contact by name";;
    add-phone-to-contact) echo "Contacts iOS: add a phone number to an existing contact";;
    delete-contact) echo "Contacts iOS: delete a contact";;
    *) echo "Contacts iOS: $1";;
  esac
}

for T in "$@"; do
  TITLE=$(title_for "$T")
  echo "=== earn-ios $T ==="
  xcrun simctl shutdown "$UDID" 2>/dev/null
  xcrun simctl erase "$UDID" || { echo "ERASE FAILED: $T"; continue; }
  xcrun simctl boot "$UDID"
  xcrun simctl bootstatus "$UDID" -b > /dev/null 2>&1
  sleep 5

  LOG="$LOGS/earn-ios-$T.log"
  START=$(date +%s)
  if [ -n "${TB_AGENT:-}" ]; then
    EXTRA_ARGS=(--agent "$TB_AGENT")
  else
    EXTRA_ARGS=(--max-llm-calls 100)
  fi
  trailblaze run --no-daemon "${EXTRA_ARGS[@]}" --device "ios/$UDID" "trails/contacts/$T/blaze.yaml" > "$LOG" 2>&1 &
  PID=$!
  # Watchdog: up to 1800s for a Results line, then 30s grace for a clean exit.
  for i in $(seq 1 600); do
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
  if ! grep -q "Results: 1 passed" "$LOG"; then
    echo "BLAZE FAILED: $T after $((END-START))s (log: $LOG)"
    grep -m1 -E "Test execution failed|Error:" "$LOG"
    continue
  fi
  echo "blaze OK: $T in $((END-START))s"

  SID=$(grep -oE "session [a-z0-9_]+" "$LOG" | head -1 | awk '{print $2}')
  echo "session: ${SID:-<none — using current>}"
  if [ -n "$SID" ]; then
    SAVE_ARGS=(--id "$SID" --title "$TITLE")
  else
    SAVE_ARGS=(--title "$TITLE")
  fi
  if ! trailblaze session save "${SAVE_ARGS[@]}" > "$LOGS/save-ios-$T.log" 2>&1; then
    echo "SAVE FAILED: $T"; tail -5 "$LOGS/save-ios-$T.log"; continue
  fi
  SAVED_FILE=$(grep -oE "Trail saved: .*" "$LOGS/save-ios-$T.log" | sed 's/Trail saved: //')
  SAVED_DIR=$(dirname "$SAVED_FILE")
  echo "saved into: $SAVED_DIR"
  MOVED=false
  for CAND in ios.trail.yaml ios-iphone.trail.yaml; do
    if [ -f "$SAVED_DIR/$CAND" ] && [ "$MOVED" = "false" ]; then
      mv "$SAVED_DIR/$CAND" "trails/contacts/$T/ios.trail.yaml"
      MOVED=true
      echo "RECORDING IN PLACE: trails/contacts/$T/ios.trail.yaml (from $CAND)"
    fi
  done
  rm -f "$SAVED_DIR/ios-iphone.trail.yaml" "$SAVED_DIR/ios.trail.yaml"
  rmdir "$SAVED_DIR" 2>/dev/null
  # closest-wins hazard: `run` may also have auto-saved a classifier-named
  # duplicate next to the blaze.yaml — keep only ios.trail.yaml.
  if [ -f "trails/contacts/$T/ios-iphone.trail.yaml" ]; then
    if [ "$MOVED" = "false" ]; then
      mv "trails/contacts/$T/ios-iphone.trail.yaml" "trails/contacts/$T/ios.trail.yaml"
      MOVED=true
      echo "RECORDING IN PLACE (from auto-save): trails/contacts/$T/ios.trail.yaml"
    else
      rm -f "trails/contacts/$T/ios-iphone.trail.yaml"
    fi
  fi
  [ "$MOVED" = "false" ] && { echo "WARN: no recording found for $T"; ls "$SAVED_DIR" 2>/dev/null; }
done
echo "=== IOS EARN SEQUENCE DONE — now do the post-earn checklist in README.md ==="
ls -la "$WT"/trails/contacts/*/ios*.trail.yaml 2>/dev/null
