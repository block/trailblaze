#!/usr/bin/env bash
# Direct-MCP dispatch path: an external MCP client (Claude Code, Codex, Goose)
# calls a *first-class* TrailblazeTool over `POST /mcp` — `tapOnPoint`,
# `assertVisible`, … — instead of going through the `step` tool's inner agent.
#
# Why this needs its own job. The other two Android jobs miss this code path
# entirely:
#   * `pr_run_android_tests_on_device.sh` is pure instrumentation
#     (`connectedDebugAndroidTest`); no host bridge is involved.
#   * `pr_run_android_tests_host_rpc.sh` drives `trailblaze trail …`, which goes
#     through DesktopYamlRunner → HostOnDeviceRpcTrailblazeAgent, not the MCP
#     bridge.
# Only the first-class MCP surface reaches
# `TrailblazeMcpBridgeImpl.executeToolViaRpc` with the interface's
# `blocking = false` default, which is exactly where a fire-and-forget dispatch
# used to report phantom success and leave a terminal UiAutomation wedge unarmed.
#
# The two assertions below are the regression:
#   1. a tool that FAILS on device must surface as `isError: true`
#   2. a tool that SUCCEEDS must return the tool's own output, never the
#      outcome-free `Executed <ToolName>` placeholder a fire-and-forget response
#      produces
#
# `trailblaze` resolves on $PATH because the workflow runs
# `install-trailblaze-from-artifact.sh` against the upstream `build-uber-jar`
# job's prebuilt JAR before invoking this script.
# Note: intentionally not using `set -e` so log collection always runs even if an
# assertion fails.

TRAILBLAZE_LOGS_DIR="$(pwd)/trailblaze-logs"
TRAILBLAZE_LOCAL_LOGS_DIR="$HOME/.trailblaze/logs"
# The daemon honours TRAILBLAZE_PORT; CI leaves it unset and gets the default.
# Overridable so a contributor can run this script against an isolated daemon
# while another worktree holds 52525.
PORT="${TRAILBLAZE_PORT:-52525}"
MCP_URL="http://localhost:$PORT/mcp"
PING_URL="http://localhost:$PORT/ping"
MCP_OUT_DIR="$(pwd)/mcp-dispatch"

mkdir -p "$TRAILBLAZE_LOGS_DIR" "$MCP_OUT_DIR"

echo "========================================="
echo "Starting Android MCP dispatch check"
echo "Working directory: $(pwd)"
echo "========================================="

# ---------------------------------------------------------------------------
# MCP streamable-HTTP helpers
#
# The daemon runs the transport with `enableJsonResponse = true`, so a POST gets
# a plain JSON-RPC body back — no SSE framing to unwrap.
# ---------------------------------------------------------------------------
MCP_SESSION_ID=""

mcp_post() {
  local body="$1"
  local -a session_header=()
  [ -n "$MCP_SESSION_ID" ] && session_header=(-H "Mcp-Session-Id: $MCP_SESSION_ID")
  curl -s --max-time 300 -D "$MCP_OUT_DIR/headers.txt" -X POST "$MCP_URL" \
    -H 'Content-Type: application/json' \
    -H 'Accept: application/json, text/event-stream' \
    "${session_header[@]}" \
    -d "$body"
}

# Calls a first-class MCP tool and writes the raw JSON-RPC response to
# $MCP_OUT_DIR/<label>.json for artifact upload.
mcp_call_tool() {
  local label="$1" name="$2" args="$3"
  mcp_post "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":{\"name\":\"$name\",\"arguments\":$args}}" \
    > "$MCP_OUT_DIR/$label.json"
  echo "--- $label ($name) ---"
  cat "$MCP_OUT_DIR/$label.json"
  echo
}

# ---------------------------------------------------------------------------
# Daemon startup
#
# `bun install` first: the workspace trailmaps under trails/config/trailmaps/
# ship meta-only scripted-tool descriptors that need the analyzer to enrich.
# Without `sdks/typescript/node_modules`, trailmap compilation fails hard and
# `app --foreground --headless` exits before it ever binds the port.
# ---------------------------------------------------------------------------
echo "Installing TypeScript SDK devDependencies (analyzer + esbuild)..."
(cd sdks/typescript && bun install --frozen-lockfile) \
  || { echo "ERROR: bun install failed in sdks/typescript"; SETUP_FAILED=true; }

if [ "$SETUP_FAILED" != "true" ]; then
  echo "Starting Trailblaze daemon (app --foreground --headless)..."
  trailblaze app --foreground --headless > /tmp/trailblaze.log 2>&1 &
  TRAILBLAZE_PID=$!
  echo "Trailblaze daemon started with PID: $TRAILBLAZE_PID"
  echo "Waiting for Trailblaze daemon to be ready on port $PORT (this may take up to 2 minutes)..."
  sleep 10
  for attempt in $(seq 1 20); do
    if curl -s --connect-timeout 1 "$PING_URL" > /dev/null 2>&1; then
      break
    fi
    echo "Attempt $attempt/20..."
    sleep 5
  done
  if ! curl -s --connect-timeout 1 "$PING_URL" > /dev/null 2>&1; then
    echo "ERROR: Trailblaze daemon failed to start"
    echo "=== Trailblaze logs ==="
    cat /tmp/trailblaze.log
    SETUP_FAILED=true
  else
    echo "✓ Trailblaze daemon is running on port $PORT!"
  fi
fi
echo "========================================="

echo "Starting logcat capture (filtering out noise)..."
adb logcat | grep -v "skipping invisible child" > logcat.log &
LOGCAT_PID=$!
echo "========================================="

# ---------------------------------------------------------------------------
# Bind the emulator. Without a bound device the driver is unknown and
# `tools/list` carries no TrailblazeTools at all.
#
# `-t default` makes the target explicit rather than inherited. Note it does NOT
# by itself pin the advertised tool surface — that is resolved per MCP session by
# `TrailblazeMcpServer.resolveTargetScopedToolClasses`, and a developer machine
# with a persisted target selection advertises a wider set than a fresh CI
# checkout does. The assertions below therefore depend only on tools present in
# both; see the tool choice at the discovery step.
# ---------------------------------------------------------------------------
if [ "$SETUP_FAILED" != "true" ]; then
  ANDROID_DEVICE_ID="$(adb devices | awk '/\tdevice$/ {print $1; exit}')"
  if [ -z "$ANDROID_DEVICE_ID" ]; then
    echo "ERROR: no booted Android device visible to adb"
    adb devices -l
    SETUP_FAILED=true
  else
    echo "Connecting android/$ANDROID_DEVICE_ID with target 'default'..."
    trailblaze device connect "android/$ANDROID_DEVICE_ID" -t default || {
      echo "ERROR: could not connect android/$ANDROID_DEVICE_ID"
      SETUP_FAILED=true
    }
  fi
fi

# Force the on-device runner up (install + `am instrument`) and land on a real
# screen, so the tap below has a live hierarchy to resolve refs against.
if [ "$SETUP_FAILED" != "true" ]; then
  echo "Starting the on-device runner via a snapshot..."
  trailblaze snapshot -d "android/$ANDROID_DEVICE_ID" || {
    echo "ERROR: on-device runner did not come up for android/$ANDROID_DEVICE_ID"
    SETUP_FAILED=true
  }
fi

# ---------------------------------------------------------------------------
# MCP handshake + tool discovery
# ---------------------------------------------------------------------------
if [ "$SETUP_FAILED" != "true" ]; then
  echo "Opening an MCP session against $MCP_URL..."
  mcp_post '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-03-26","capabilities":{},"clientInfo":{"name":"pr-checks-mcp-dispatch","version":"1"}}}' \
    > "$MCP_OUT_DIR/initialize.json"
  MCP_SESSION_ID="$(grep -i '^mcp-session-id:' "$MCP_OUT_DIR/headers.txt" | awk '{print $2}' | tr -d '\r')"
  if [ -z "$MCP_SESSION_ID" ]; then
    echo "ERROR: MCP initialize did not return an Mcp-Session-Id"
    cat "$MCP_OUT_DIR/initialize.json"
    SETUP_FAILED=true
  else
    echo "✓ MCP session: $MCP_SESSION_ID"
    mcp_post '{"jsonrpc":"2.0","method":"notifications/initialized"}' > /dev/null

    # Bind the device to THIS MCP session. The CLI's terminal pin above does not
    # reliably carry into a freshly-created MCP session — observed locally
    # producing a session whose tools/list held only the session-management tools
    # and no TrailblazeTools at all. `connectToDevice` is the daemon's own
    # per-session bind, and it fires the tools/list_changed that registers the
    # driver-scoped surface, so calling it makes discovery deterministic.
    mcp_call_tool connect-device connectToDevice \
      "{\"trailblazeDeviceId\":{\"instanceId\":\"$ANDROID_DEVICE_ID\",\"trailblazeDevicePlatform\":\"ANDROID\"}}"

    mcp_post '{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}' > "$MCP_OUT_DIR/tools-list.json"
    # Both tools come from the baseline catalog every Android driver resolves
    # with no target-declared toolsets, so they are advertised on a bare CI
    # checkout and on a developer machine alike. Do NOT reach for a tool from the
    # `verification` or `memory` toolsets (assertVisible, rememberText, …): those
    # appear only when the resolved target declares them, which is why an earlier
    # version of this check passed locally and failed in CI.
    for required in tapOnPoint tap; do
      if ! jq -e --arg n "$required" '.result.tools | map(.name) | index($n)' "$MCP_OUT_DIR/tools-list.json" > /dev/null; then
        echo "ERROR: first-class tool '$required' is not in the advertised surface," \
          "so the direct-MCP dispatch path is not under test. Advertised tools:"
        jq -r '.result.tools[]?.name' "$MCP_OUT_DIR/tools-list.json"
        SETUP_FAILED=true
      fi
    done
  fi
fi

# ---------------------------------------------------------------------------
# Assertion 1 — a tool that fails on device must surface as an error.
#
# `tap` against a ref that is not on any screen fails inside the on-device
# runner. A dispatch that does not await completion gets back a response with no
# `success` and no `errorMessage`, so the failure is invisible to the caller and
# the call reports OK.
# ---------------------------------------------------------------------------
if [ "$SETUP_FAILED" != "true" ]; then
  mcp_call_tool ondevice-failure tap \
    '{"ref":"zzz999","reasoning":"pr-checks: ref that is deliberately not on screen"}'
  if [ "$(jq -r '.result.isError' "$MCP_OUT_DIR/ondevice-failure.json")" != "true" ]; then
    echo "ERROR: a failing on-device tool did not surface as isError=true."
    echo "       The dispatch returned before the tool ran (phantom success)."
    TEST_FAILED=true
  else
    echo "✓ On-device failure surfaced to the MCP caller"
  fi
fi

# ---------------------------------------------------------------------------
# Assertion 2 — a tool that succeeds must return its own output.
#
# `Executed <ToolName>` is the outcome-free placeholder the bridge emits when the
# on-device response carries no `success` field. Seeing it means the dispatch was
# fire-and-forget even though this one happens to have worked.
# ---------------------------------------------------------------------------
if [ "$SETUP_FAILED" != "true" ]; then
  mcp_call_tool ondevice-success tapOnPoint \
    '{"x":100,"y":100,"reasoning":"pr-checks: dispatch that must report a real outcome"}'
  SUCCESS_TEXT="$(jq -r '.result.content[0].text // ""' "$MCP_OUT_DIR/ondevice-success.json")"
  if [ "$(jq -r '.result.isError' "$MCP_OUT_DIR/ondevice-success.json")" != "false" ]; then
    echo "ERROR: tapOnPoint reported an error: $SUCCESS_TEXT"
    TEST_FAILED=true
  elif printf '%s' "$SUCCESS_TEXT" | grep -qE '^\[OK\] Executed [A-Za-z]+TrailblazeTool$'; then
    echo "ERROR: dispatch returned the outcome-free placeholder: $SUCCESS_TEXT"
    echo "       The bridge never read a terminal on-device response."
    TEST_FAILED=true
  else
    echo "✓ Successful dispatch returned the tool's own result: $SUCCESS_TEXT"
  fi
fi

echo "========================================="
echo "MCP dispatch check completed (setup failed: ${SETUP_FAILED:-false}, assertions failed: ${TEST_FAILED:-false})"
echo "========================================="

# ---------------------------------------------------------------------------
# Log collection + cleanup (mirrors pr_run_android_tests_host_rpc.sh)
# ---------------------------------------------------------------------------
adb devices -l || echo "Could not list ADB devices"

echo "Pulling logs from device..."
adb pull /sdcard/Download/trailblaze-logs/. "$TRAILBLAZE_LOGS_DIR" && echo "Log pull succeeded" || echo "Failed to pull logs"

if [ -d "$TRAILBLAZE_LOCAL_LOGS_DIR" ]; then
  cp -r "$TRAILBLAZE_LOCAL_LOGS_DIR"/* "$TRAILBLAZE_LOGS_DIR/" 2>/dev/null || echo "No logs found in $TRAILBLAZE_LOCAL_LOGS_DIR"
fi

if [ -f /tmp/trailblaze.log ]; then
  cp /tmp/trailblaze.log "$TRAILBLAZE_LOGS_DIR/trailblaze-daemon.log"
fi
cp -r "$MCP_OUT_DIR" "$TRAILBLAZE_LOGS_DIR/" 2>/dev/null || true

echo "Cleaning up background processes..."
[ -n "$LOGCAT_PID" ] && kill "$LOGCAT_PID" 2>/dev/null
[ -n "$TRAILBLAZE_PID" ] && kill "$TRAILBLAZE_PID" 2>/dev/null
echo "✓ Cleanup complete"
echo "========================================="

if [ "$SETUP_FAILED" = "true" ] || [ "$TEST_FAILED" = "true" ]; then
  echo "MCP dispatch check failed — exiting with code 1"
  exit 1
fi
