#!/usr/bin/env bash
# Windows host: what a Windows user does beyond `trailblaze run`, each checked by its result.
#
#   1. Drive a browser step by step from Git Bash: `device connect` once, then `tool` and
#      `snapshot` with no --device, which only works if the pin found this shell.
#   2. Trail Runner: `trailblaze app` serves it, the UI renders in Chromium, and it lists, opens
#      and runs a trail, then exports the session.
#
# The PowerShell side of the pin check is a step of its own in the workflow. Runs after
# pr_run_wikipedia_trails_windows.sh. Every check runs even after one fails; the script fails if
# any did.
TB="${TRAILBLAZE_BIN:-trailblaze.cmd}"
PORT=52525
BASE="http://localhost:$PORT/trailrunner"
PIN_FILE="$HOME/.trailblaze/shell-device-pins-$PORT.json"
DEVICE=web/playwright-native
FAILED=()
pass() { echo "PASS: $1"; }
fail() { echo "FAIL: $1"; FAILED+=("$1"); }

command -v jq >/dev/null || { echo "jq is required"; exit 1; }
export TRAILBLAZE_CONFIG_DIR="$(pwd -W)/examples/wikipedia/trails/config"

echo "========================================="
echo "1. Step by step from Git Bash, pinned to this shell"
echo "========================================="
# Under Git Bash the pin belongs to a bash of the chain this script runs in, never to the
# short-lived fork Git Bash starts each command from (see WindowsCallerShell). Collect that chain's
# Windows PIDs through /proc: `$$` and ppid are MSYS PIDs, `winpid` is what the JVM sees.
CHAIN_WINPIDS=()
pid=$$
while [ -e "/proc/$pid/winpid" ]; do
  CHAIN_WINPIDS+=("$(cat /proc/$pid/winpid)")
  pid="$(cat /proc/$pid/ppid 2>/dev/null)" || break
  [ "$pid" -gt 1 ] || break
done
echo "This script's bash chain (Windows PIDs, innermost first): ${CHAIN_WINPIDS[*]}"
CONNECT="$("$TB" device connect web 2>&1)" || fail "device connect web"
echo "$CONNECT"
# The launcher has the JVM print UTF-8; in the console's legacy code page the check mark is a `?`.
grep -q "✓ Pinned" <<<"$CONNECT" && pass "output is UTF-8" || fail "output is UTF-8"
PINNED="$(jq -r '.shells | keys[]' "$PIN_FILE" 2>/dev/null)"
if [ "$(jq '.shells | length' "$PIN_FILE" 2>/dev/null)" = 1 ] && [[ " ${CHAIN_WINPIDS[*]} " == *" $PINNED "* ]]; then
  pass "device connect pinned this script's shell (Windows PID $PINNED)"
else
  fail "device connect pinned this script's shell (pinned: ${PINNED:-none})"
  cat "$PIN_FILE" 2>/dev/null
fi
"$TB" tool web_navigate url=https://en.wikipedia.org/wiki/Ada_Lovelace \
  && pass "tool with no --device" || fail "tool with no --device"
SNAPSHOT="$("$TB" snapshot 2>&1)"
echo "$SNAPSHOT" | head -12
grep -q "Page title: Ada Lovelace" <<<"$SNAPSHOT" \
  && pass "snapshot with no --device shows the page" || fail "snapshot with no --device shows the page"
"$TB" device disconnect && pass "device disconnect" || fail "device disconnect"
if jq -e '.shells | length == 0' "$PIN_FILE" >/dev/null 2>&1; then
  pass "device disconnect removed the pin"
else
  fail "device disconnect removed the pin"
fi

echo "========================================="
echo "2. Trail Runner"
echo "========================================="
# `trailblaze app` opens Trail Runner in the default browser on Windows. Here there is no one to
# look at it, so it is enough that the command succeeds and the page it names is served.
"$TB" app && pass "trailblaze app" || fail "trailblaze app"
STATUS="$(curl -s -o /dev/null -w '%{http_code}' "$BASE/")"
[ "$STATUS" = 200 ] && pass "Trail Runner page served" || fail "Trail Runner page served (HTTP $STATUS)"

# Point Trail Runner at the repo's Wikipedia trails, as a user adding a folder in the UI would.
ROOT="$(pwd -W)/trails/wikipedia"
curl -sf -X POST -H 'content-type: application/json' -d "$(jq -n --arg p "$ROOT" '{path: $p}')" \
  "$BASE/api/trails/roots" >/dev/null && pass "add trails folder" || fail "add trails folder"
TRAILS="$(curl -sf "$BASE/api/trails")"
TRAIL_ID="$(jq -r '.trails[] | select(.path == "test-article-shakespeare/trail.yaml") | .id' <<<"$TRAILS")"
if [ -n "$TRAIL_ID" ]; then
  pass "trail listed as $TRAIL_ID"
else
  fail "trail listed with a forward-slash path"
  jq -c '.trails[:3]' <<<"$TRAILS"
fi

# The UI itself, rendered in the same Chromium the trails use.
"$TB" tool web_navigate url="$BASE/" --device "$DEVICE" || fail "open Trail Runner in Chromium"
sleep 5
UI="$("$TB" snapshot --device "$DEVICE" 2>&1)"
echo "$UI" | head -30
grep -q "Page title: Trailblaze App" <<<"$UI" && grep -qE "Workspace [0-9]+ trails" <<<"$UI" \
  && pass "Trail Runner UI renders" || fail "Trail Runner UI renders"
"$TB" device disconnect --device "$DEVICE" || fail "release the browser for the Trail Runner run"

YAML="$(curl -sf "$BASE/api/trail/$TRAIL_ID" | jq -r '.yaml')"
grep -q "William Shakespeare" <<<"$YAML" && pass "open trail" || fail "open trail"
RUN_REQUEST="$(jq -n --arg y "$YAML" --arg id "$TRAIL_ID" '{
  trailblazeDeviceId: {instanceId: "playwright-native", trailblazeDevicePlatform: "WEB"},
  yaml: $y, trailId: $id, useRecordedSteps: true, selfHeal: false
}')"
RUN="$(curl -s -X POST -H 'content-type: application/json' -d "$RUN_REQUEST" "$BASE/api/run")"
echo "$RUN"
SESSION_ID="$(jq -r '.sessionId // empty' <<<"$RUN")"
if [ -n "$SESSION_ID" ]; then
  # The session is listed once it has written its first log, a moment after the run starts.
  RUN_STATUS=
  for _ in $(seq 1 60); do
    sleep 3
    RUN_STATUS="$(curl -s "$BASE/api/sessions" | jq -r --arg s "$SESSION_ID" '.sessions[] | select(.id == $s) | .status')"
    [ -n "$RUN_STATUS" ] && [ "$RUN_STATUS" != running ] && break
  done
  [ "$RUN_STATUS" = passed ] && pass "run trail from Trail Runner" || fail "run trail from Trail Runner (status: $RUN_STATUS)"
  curl -sf -o trail-runner-session.zip "$BASE/api/session/$SESSION_ID/export.zip" \
    && jar tf trail-runner-session.zip | grep -q TrailblazeToolLog \
    && pass "export session" || fail "export session"
else
  fail "run trail from Trail Runner (no session started)"
fi

"$TB" stop

echo "========================================="
if [ ${#FAILED[@]} -gt 0 ]; then
  echo "${#FAILED[@]} check(s) failed:"
  printf '  - %s\n' "${FAILED[@]}"
  exit 1
fi
echo "All checks passed"
