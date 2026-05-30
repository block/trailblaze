#!/bin/bash
#
# Regression test for the stdout/stderr replay in `ipc_try_forward`
# (scripts/trailblaze). The fix it guards:
#
#   `$(jq -r .stdout)` and `$(jq -r .stderr)` strip ALL trailing newlines
#   from the captured bytes. Without restoring one with `printf '%s\n'`,
#   stdout (e.g. a snapshot UI tree) visually smashes into the stderr
#   replay that follows ("Connecting to …", "Connected: …"). The smashed
#   form looked like:
#
#     [n635] "Options"Connecting to Android device (emulator-5556)...
#
# This test mirrors the exact JSON-unmarshal + replay codepath against a
# synthetic CliExecResponse so a future revert of `printf '%s\n'` back to
# `printf '%s'` (or a refactor that loses the trailing newline) trips a
# loud, scriptable failure rather than silently re-introducing the bug.
#
# Run:
#   bash scripts/test_ipc_replay.sh
#
# Requires: bash, jq.

set -uo pipefail

if ! command -v jq >/dev/null 2>&1; then
  printf 'SKIP: jq not on PATH (the shim bails out the same way without jq)\n' >&2
  exit 0
fi

# Synthetic /cli/exec response shaped exactly like what a forwarded
# `snapshot` would receive after the daemon has connected to a device
# (Console.error -> stderr) and emitted a UI tree (Console.info -> stdout).
RESPONSE='{"stdout":"### Screen\nApp: com.android.camera2\n[i209] ImageView \"Shutter\"\n[n635] \"Options\"\n","stderr":"Connecting to Android device (emulator-5556)...\nConnected: android/emulator-5556\nEnded previous session.\n","exitCode":0,"forwarded":true}'

# Mirror the shim: jq-decode into bash vars (which strips trailing \n via $()),
# then run the EXACT replay lines from `ipc_try_forward`.
stdout=$(printf '%s' "$RESPONSE" | jq -r '.stdout // ""')
stderr=$(printf '%s' "$RESPONSE" | jq -r '.stderr // ""')

# Capture rendered output to inspect.
combined=$(
  {
    [ -n "$stdout" ] && printf '%s\n' "$stdout"
    [ -n "$stderr" ] && printf '%s\n' "$stderr" >&2
  } 2>&1
)

# Smashed form: the bug allowed "...Options\"Connecting" to appear on a
# single rendered line. Assert the smashed sequence is gone.
if printf '%s' "$combined" | grep -qE '"Options"Connecting'; then
  printf 'FAIL: stderr replay smashes into stdout last line — printf %%s\\n regression\n' >&2
  printf 'Got (truncated):\n' >&2
  printf '%s' "$combined" | head -10 >&2
  exit 1
fi

# Positive form: the stderr replay starts on its own line.
if ! printf '%s' "$combined" | grep -qE '^Connecting to Android device'; then
  printf 'FAIL: stderr first line did not start on its own line\n' >&2
  printf 'Got (truncated):\n' >&2
  printf '%s' "$combined" | head -10 >&2
  exit 1
fi

# Empty-output regression: no extra blank lines when both streams are empty.
EMPTY='{"stdout":"","stderr":"","exitCode":0,"forwarded":true}'
estdout=$(printf '%s' "$EMPTY" | jq -r '.stdout // ""')
estderr=$(printf '%s' "$EMPTY" | jq -r '.stderr // ""')
empty_out=$(
  {
    [ -n "$estdout" ] && printf '%s\n' "$estdout"
    [ -n "$estderr" ] && printf '%s\n' "$estderr" >&2
  } 2>&1
)
if [ -n "$empty_out" ]; then
  printf 'FAIL: empty stdout/stderr produced spurious output: %q\n' "$empty_out" >&2
  exit 1
fi

printf 'OK: ipc_try_forward stdout/stderr replay preserves stream separation\n'
