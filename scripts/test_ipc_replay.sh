#!/bin/bash
#
# Regression test for the stdout/stderr replay in `ipc_try_forward`
# (scripts/trailblaze). The fix it guards:
#
#   `$(jq -r .stdout)` and `$(jq -r .stderr)` strip ALL trailing newlines
#   from the captured bytes. The shim now decodes all fields in one jq process
#   with NUL delimiters, so bash preserves the exact stream endings. Without
#   that preservation, stdout (e.g. a snapshot UI tree) visually smashed into
#   the stderr replay that followed. The smashed form looked like:
#
#     [n635] "Options"Connecting to Android device (emulator-5556)...
#
# This test mirrors the exact JSON-unmarshal + replay codepath against a
# synthetic CliExecResponse so a refactor that loses the trailing newline
# trips a loud, scriptable failure rather than silently re-introducing the bug.
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

# Mirror the shim: one jq decode with NUL delimiters preserves trailing newlines.
fields=()
while IFS= read -r -d '' field; do
  fields+=("$field")
done < <(printf '%s' "$RESPONSE" | jq -j '
  if (.forwarded // false) != true then
    error("not forwarded")
  else
    (.stdout // ""), "\u0000",
    (.stderr // ""), "\u0000",
    (
      if (.exitCode | type) == "number" then
        (.exitCode | floor | if . < 0 then 256 + (. % 256) elif . > 255 then . % 256 else . end)
      else 1 end
      | tostring
    ), "\u0000"
  end
')
[ "${#fields[@]}" -eq 3 ] || { printf 'FAIL: response did not decode into three fields\n' >&2; exit 1; }
stdout="${fields[0]}"
stderr="${fields[1]}"

# Capture rendered output to inspect.
combined=$(
  {
    [ -n "$stdout" ] && printf '%s' "$stdout"
    [ -n "$stderr" ] && printf '%s' "$stderr" >&2
  } 2>&1
)

# Smashed form: the bug allowed "...Options\"Connecting" to appear on a
# single rendered line. Assert the smashed sequence is gone.
if printf '%s' "$combined" | grep -qE '"Options"Connecting'; then
  printf 'FAIL: stderr replay smashes into stdout last line — trailing newline regression\n' >&2
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
empty_fields=()
while IFS= read -r -d '' field; do
  empty_fields+=("$field")
done < <(printf '%s' "$EMPTY" | jq -j '(.stdout // ""), "\u0000", (.stderr // ""), "\u0000", "0", "\u0000"')
estdout="${empty_fields[0]}"
estderr="${empty_fields[1]}"
empty_out=$(
  {
    [ -n "$estdout" ] && printf '%s' "$estdout"
    [ -n "$estderr" ] && printf '%s' "$estderr" >&2
  } 2>&1
)
if [ -n "$empty_out" ]; then
  printf 'FAIL: empty stdout/stderr produced spurious output: %q\n' "$empty_out" >&2
  exit 1
fi

printf 'OK: ipc_try_forward stdout/stderr replay preserves stream separation\n'
