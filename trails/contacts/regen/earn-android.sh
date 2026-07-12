#!/usr/bin/env bash
# Earn Android recordings for the contacts parity suite, sequentially, on an
# API-34 emulator. Between trails the Contacts app + provider are cleared
# (same reset CI does). For each trail: blaze via agent, session-save, move
# the recording next to its blaze.yaml, drop classifier-specific duplicates.
# Usage: earn-android.sh <trail-name> [...]
# Env: TB_ANDROID_SERIAL (default: first `adb devices` entry), TB_REGEN_LOGS,
#      TB_AGENT (optional, e.g. MULTI_AGENT_V3; incompatible with --max-llm-calls)
#
# After earning, ALWAYS do the post-earn checklist in README.md — in
# particular check the launch step records `launchApp` with
# `launchMode: RESUME` (the custom launch tool returns at `am start`, and a
# zero-LLM replay would otherwise tap into a cold start).
set -uo pipefail
export PATH="$HOME/Library/Android/sdk/platform-tools:$PATH"
WT="$(cd "$(dirname "$0")/../../.." && pwd)"
SERIAL="${TB_ANDROID_SERIAL:-$(adb devices | awk 'NR>1 && $2=="device"{print $1; exit}')}"
[ -n "$SERIAL" ] || { echo "no android device — set TB_ANDROID_SERIAL or boot an emulator"; exit 1; }
LOGS="${TB_REGEN_LOGS:-${TMPDIR:-/tmp}/tb-regen-logs}"
mkdir -p "$LOGS"
cd "$WT"
export TRAILBLAZE_CONFIG_DIR="$WT/trails/config"

title_for() {
  case "$1" in
    create-contact) echo "Contacts: create a contact";;
    find-contact) echo "Contacts: find a contact by name";;
    add-phone-to-contact) echo "Contacts: add a phone number to an existing contact";;
    delete-contact) echo "Contacts: delete a contact";;
    *) echo "Contacts: $1";;
  esac
}

for T in "$@"; do
  TITLE=$(title_for "$T")
  echo "=== earn-android $T ==="
  adb -s "$SERIAL" shell pm clear com.android.contacts >/dev/null
  adb -s "$SERIAL" shell pm clear com.android.providers.contacts >/dev/null
  adb -s "$SERIAL" shell pm grant com.android.contacts android.permission.POST_NOTIFICATIONS

  LOG="$LOGS/earn-android-$T.log"
  START=$(date +%s)
  if [ -n "${TB_AGENT:-}" ]; then
    EXTRA_ARGS=(--agent "$TB_AGENT")
  else
    EXTRA_ARGS=(--max-llm-calls 100)
  fi
  trailblaze run --no-daemon "${EXTRA_ARGS[@]}" --device "android/$SERIAL" "trails/contacts/$T/blaze.yaml" > "$LOG" 2>&1 &
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
  if ! trailblaze session save "${SAVE_ARGS[@]}" > "$LOGS/save-android-$T.log" 2>&1; then
    echo "SAVE FAILED: $T"; tail -5 "$LOGS/save-android-$T.log"; continue
  fi
  SAVED_FILE=$(grep -oE "Trail saved: .*" "$LOGS/save-android-$T.log" | sed 's/Trail saved: //')
  SAVED_DIR=$(dirname "$SAVED_FILE")
  echo "saved into: $SAVED_DIR"
  MOVED=false
  for CAND in android.trail.yaml android-phone.trail.yaml; do
    if [ -f "$SAVED_DIR/$CAND" ] && [ "$MOVED" = "false" ]; then
      mv "$SAVED_DIR/$CAND" "trails/contacts/$T/android.trail.yaml"
      MOVED=true
      echo "RECORDING IN PLACE: trails/contacts/$T/android.trail.yaml (from $CAND)"
    fi
  done
  rm -f "$SAVED_DIR/android-phone.trail.yaml" "$SAVED_DIR/android.trail.yaml"
  rmdir "$SAVED_DIR" 2>/dev/null
  # closest-wins hazard: `run` may also have auto-saved a classifier-named
  # duplicate next to the blaze.yaml — keep only android.trail.yaml.
  if [ -f "trails/contacts/$T/android-phone.trail.yaml" ]; then
    if [ "$MOVED" = "false" ]; then
      mv "trails/contacts/$T/android-phone.trail.yaml" "trails/contacts/$T/android.trail.yaml"
      MOVED=true
      echo "RECORDING IN PLACE (from auto-save): trails/contacts/$T/android.trail.yaml"
    else
      rm -f "trails/contacts/$T/android-phone.trail.yaml"
    fi
  fi
  [ "$MOVED" = "false" ] && { echo "WARN: no recording found for $T"; ls "$SAVED_DIR" 2>/dev/null; }
done
echo "=== ANDROID EARN SEQUENCE DONE — now do the post-earn checklist in README.md ==="
ls -la "$WT"/trails/contacts/*/android*.trail.yaml 2>/dev/null
