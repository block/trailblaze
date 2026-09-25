#!/bin/bash
#
# Regression test for `ipc_replay_response` in `scripts/trailblaze` — the function that turns a
# `/cli/exec` JSON body back into terminal output. Two bugs live here, and both are silent:
#
#   1. ORDER. The daemon runs the command in its own process, so the caller has to reassemble
#      what it wrote. Replaying all of stdout and then all of stderr reorders a command's output
#      against itself: a device line printed first lands under an error printed last, and an
#      error ends up separated from the tip that explains it. The daemon now sends `transcript`,
#      one ordered list of chunks tagged with the stream each went to.
#
#   2. TRAILING NEWLINES. `$(jq -r .stdout)` strips them, which smashed the first stderr line
#      onto the last stdout line:
#
#        [n635] "Options"Connecting to Android device (emulator-5556)...
#
#      Assertions about bug 2 capture through `_replay_marked`, not a bare `$(…)` — see there.
#
# The function is extracted from the launcher and run here, not copied — a copy would keep
# passing after the launcher regressed.
#
# Run (from the directory holding this `scripts/` dir):
#   bash scripts/test_ipc_replay.sh
#
# Requires: bash, jq.

set -uo pipefail

if ! command -v jq > /dev/null 2>&1; then
  printf 'SKIP: jq not on PATH (the shim bails out the same way without jq)\n' >&2
  exit 0
fi

_passes=0
_failures=0

_ok() {
  echo "  ok  $1"
  _passes=$((_passes + 1))
}

_bad() {
  echo "  FAIL  $1"
  _failures=$((_failures + 1))
}

# Compares whole values, and shows both when they differ — an ordering bug is invisible in a
# "did not contain" message.
_eq() {
  local label="$1" expected="$2" actual="$3"
  if [ "$expected" = "$actual" ]; then
    _ok "$label"
  else
    _bad "$label"
    printf '        expected: %q\n' "$expected"
    printf '        actual:   %q\n' "$actual"
  fi
}

# Command substitution strips trailing newlines, and `_eq` compares two of them — so a bare
# `$(ipc_replay_response …)` cannot tell `…refs.\n` from `…refs.`, and bug 2 above could not fail
# any assertion. The marker is printed inside the same substitution, after the replay, so the
# newlines between the last line and the marker have to survive to match. A replay that drops the
# final newline puts the marker on the output's last line instead.
_END_MARKER='<end>'
_replay_marked() {
  ipc_replay_response "$1" 2>&1
  printf '%s' "$_END_MARKER"
}

# The expected side of a `_replay_marked` comparison: the text (with `\n` escapes expanded by
# `%b`), then the same marker. The marker is last, so the `$(…)` around this call has no trailing
# newline of its own to strip — which is what lets the expected side state one at all.
_marked() {
  printf '%b%s' "$1" "$_END_MARKER"
}

_absent() {
  local label="$1" needle="$2" haystack="$3"
  if [[ "$haystack" == *"$needle"* ]]; then
    _bad "$label"
    printf '        found %q in: %q\n' "$needle" "$haystack"
  else
    _ok "$label"
  fi
}

SHIM="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/trailblaze"
[ -f "$SHIM" ] || {
  printf 'FAIL: launcher not found at %s\n' "$SHIM" >&2
  exit 1
}

# Take the real definition rather than a copy of it. Sourcing the launcher would run it.
_replay_source=$(sed -n '/^ipc_replay_response() {$/,/^}$/p' "$SHIM")
if [ -z "$_replay_source" ]; then
  printf 'FAIL: ipc_replay_response not found in %s — did the launcher rename it?\n' "$SHIM" >&2
  exit 1
fi
# The terminator is declared outside the function, so pull it across too. Without it the function
# would compare against an empty string and the completeness check would pass on anything.
_terminator_source=$(sed -n '/^IPC_REPLAY_TERMINATOR=/p' "$SHIM")
if [ -z "$_terminator_source" ]; then
  printf 'FAIL: IPC_REPLAY_TERMINATOR not found in %s — did the launcher rename it?\n' "$SHIM" >&2
  exit 1
fi
eval "$_terminator_source"
eval "$_replay_source"

echo "--- both streams replay in the order the command wrote them"
# What a failed `trailblaze tool` produces: the device line is written while connecting, long
# before the tool runs, and the tip belongs to the error immediately above it.
ORDERED='{
  "stdout":"FLATTENED-STDOUT-MUST-NOT-BE-USED\n",
  "stderr":"FLATTENED-STDERR-MUST-NOT-BE-USED\n",
  "exitCode":2,
  "forwarded":true,
  "transcript":[
    {"stream":"stderr","text":"Auto-using only connected device: android/emulator-5560\n"},
    {"stream":"stdout","text":"→ Error — tap: Element ref not found on current screen.\n"},
    {"stream":"stderr","text":"Tip: Run trailblaze snapshot for the current screen refs.\n"}
  ]
}'
combined=$(_replay_marked "$ORDERED")
_eq "the tip follows its own error, with the device line above both, last newline intact" \
  "$(_marked 'Auto-using only connected device: android/emulator-5560\n→ Error — tap: Element ref not found on current screen.\nTip: Run trailblaze snapshot for the current screen refs.\n')" \
  "$combined"
# The flattened fields are what an older daemon sends and what the old replay read. Proving they
# are ignored is what proves the transcript is the source — identical text in both would not.
_absent "the flattened stdout field is not replayed alongside the transcript" \
  "FLATTENED-STDOUT-MUST-NOT-BE-USED" "$combined"
_absent "the flattened stderr field is not replayed alongside the transcript" \
  "FLATTENED-STDERR-MUST-NOT-BE-USED" "$combined"

echo "--- each chunk still lands on the stream it was written to"
only_stdout=$(ipc_replay_response "$ORDERED" 2> /dev/null)
_eq "stdout carries the tool result and nothing else" \
  "→ Error — tap: Element ref not found on current screen." "$only_stdout"
only_stderr=$(ipc_replay_response "$ORDERED" 2>&1 1> /dev/null)
_eq "stderr carries the status lines and nothing else" \
  "$(printf 'Auto-using only connected device: android/emulator-5560\nTip: Run trailblaze snapshot for the current screen refs.')" \
  "$only_stderr"

echo "--- the command's exit code survives the round trip"
ipc_replay_response "$ORDERED" > /dev/null 2>&1
_eq "exit code is reported to the caller" "2" "${IPC_REPLAY_EXIT_CODE:-unset}"
ipc_replay_response '{"stdout":"","stderr":"","exitCode":300,"forwarded":true}' > /dev/null 2>&1
_eq "an out-of-range exit code is wrapped into a byte" "44" "${IPC_REPLAY_EXIT_CODE:-unset}"

echo "--- a body the shim cannot use falls through instead of replaying half of it"
for body in \
  '{"stdout":"x","stderr":"","exitCode":0,"forwarded":false}' \
  'not json at all' \
  ''; do
  out=$(ipc_replay_response "$body" 2>&1)
  rc=$?
  if [ "$rc" -eq 0 ]; then
    _bad "unusable body was treated as a forwarded response: $body"
  elif [ -n "$out" ]; then
    _bad "unusable body printed something before falling through: $body"
  else
    _ok "falls through on: ${body:-<empty body>}"
  fi
done

echo "--- a decode that dies partway is not mistaken for a command that printed nothing"
# jq streams, so a filter that fails mid-way has already emitted everything before the failure.
# These bodies are forwarded and have a valid exitCode, so jq gets as far as emitting it and then
# errors on the transcript — leaving the exact field count of a legitimate silent command. Treating
# that as handled would swallow the output the body does carry and skip the fall-through.
for bad_transcript in \
  '{"stdout":"REAL-STDOUT-MUST-NOT-VANISH","stderr":"","exitCode":0,"forwarded":true,"transcript":{"a":1}}' \
  '{"stdout":"REAL-STDOUT-MUST-NOT-VANISH","stderr":"","exitCode":0,"forwarded":true,"transcript":[1,2]}' \
  '{"stdout":"REAL-STDOUT-MUST-NOT-VANISH","stderr":"","exitCode":0,"forwarded":true,"transcript":"nope"}'; do
  out=$(ipc_replay_response "$bad_transcript" 2>&1)
  rc=$?
  if [ "$rc" -eq 0 ]; then
    _bad "a partially decoded body was reported as replayed: $bad_transcript"
    printf '        replayed: %q\n' "$out"
  elif [ -n "$out" ]; then
    _bad "a partially decoded body printed something before falling through: $bad_transcript"
  else
    _ok "falls through on a transcript jq cannot finish decoding: $(printf '%s' "$bad_transcript" | jq -c '.transcript')"
  fi
done

echo "--- a daemon that predates the transcript still replays correctly"
# Exactly what a forwarded `snapshot` used to return: a UI tree on stdout, connection status on
# stderr, no transcript field.
LEGACY='{"stdout":"### Screen\nApp: com.android.camera2\n[i209] ImageView \"Shutter\"\n[n635] \"Options\"\n","stderr":"Connecting to Android device (emulator-5556)...\nConnected: android/emulator-5556\n","exitCode":0,"forwarded":true}'
legacy_combined=$(_replay_marked "$LEGACY")
_absent "the stderr replay does not smash into the last stdout line" \
  '"Options"Connecting' "$legacy_combined"
_eq "stdout replays first, then stderr, newlines intact" \
  "$(_marked '### Screen\nApp: com.android.camera2\n[i209] ImageView "Shutter"\n[n635] "Options"\nConnecting to Android device (emulator-5556)...\nConnected: android/emulator-5556\n')" \
  "$legacy_combined"

echo "--- a command that printed nothing prints nothing"
# Marked, so "printed nothing" means no bytes at all — a bare `$(…)` would strip an invented
# newline and report the same empty string either way.
empty_out=$(_replay_marked '{"stdout":"","stderr":"","exitCode":0,"forwarded":true}')
_eq "no spurious blank line when both streams are empty" "$(_marked '')" "$empty_out"
empty_out=$(_replay_marked '{"stdout":"","stderr":"","exitCode":0,"forwarded":true,"transcript":[]}')
_eq "an empty transcript falls back without inventing output" "$(_marked '')" "$empty_out"

echo "--- the launcher does not enable job control"
# Job control makes bash `setpgid` every child it forks. On a long-running macOS host pids are
# recycled until one child is born holding the session id — that child is a session leader,
# `setpgid` is refused, and bash prints "child setpgid (N to N): Operation not permitted" onto the
# stderr of whatever command the user forwarded. Observed on a long-running CI host. Whether it
# fires is a matter of pid luck, so no behavioral assertion can guard it; the source is the only
# thing that can be pinned. Nothing here needs a process group anyway — the wait-notice watchdog
# hands its `sleep` private fds instead.
#
# This lives beside the launcher so the guard travels with the file it guards.
# A single regex on the whole line only ever inspects the FIRST option word after `set` — it
# missed `set -e -m` (the flags as separate words) the first time this shipped. `set` takes any
# number of short-opt words, and `m` enables job control in any of them, bundled or not, so every
# word after `set` has to be checked, not just the one immediately following it.
_job_control_hits=""
while IFS= read -r _line || [ -n "$_line" ]; do
  [[ "$_line" =~ ^[[:space:]]*# ]] && continue
  # Drop a trailing shell comment before tokenizing — otherwise prose like `true # never set -m`
  # scans as a real `set -m` invocation, even though bash never runs it. Cuts at the first
  # whitespace-then-`#`, so a `#` inside a quoted string with no space before it survives; that is
  # the same no-quote-awareness tradeoff the rest of this checker already makes.
  _code_line="${_line%%[[:space:]]\#*}"
  # Split into simple commands on the shell separators, so a `set` inside `if …; then set -m; fi`
  # or `(set -m)` is checked on its own — otherwise `if` or `(` would be mistaken for `set`'s own
  # first word.
  # macOS ships bash 3.2, where `"${arr[@]}"` on a zero-element array is itself an unbound
  # reference under `set -u` (fixed in later bash, but this launcher targets 3.2). The
  # `${arr[@]+"${arr[@]}"}` form substitutes nothing instead of erroring when `arr` is empty.
  _segs=()
  IFS=';&|(){}' read -ra _segs <<<"$_code_line"
  for _seg in "${_segs[@]+"${_segs[@]}"}"; do
    _words=()
    read -ra _words <<<"$_seg"
    for ((_i = 0; _i < ${#_words[@]}; _i++)); do
      [ "${_words[$_i]}" = "set" ] || continue
      for ((_j = _i + 1; _j < ${#_words[@]}; _j++)); do
        _w="${_words[$_j]}"
        case "$_w" in
          --) break ;;
          -*) : ;;
          *) continue ;;
        esac
        case "$_w" in
          -o)
            [ "${_words[$((_j + 1))]:-}" = "monitor" ] && _job_control_hits+="$_line"$'\n'
            break
            ;;
          -*m*)
            _job_control_hits+="$_line"$'\n'
            break
            ;;
        esac
      done
    done
  done
done < "$SHIM"
if [ -n "$_job_control_hits" ]; then
  _bad "the launcher enables job control nowhere"
  printf '        %s\n' "$_job_control_hits"
else
  _ok "the launcher enables job control nowhere"
fi

echo "--- the launcher turns OFF job control it was handed"
# Not enabling it is not enough. Bash reads SHELLOPTS from the environment at startup, so a caller
# that exported it containing `monitor` hands the launcher a shell already under job control, and
# every child it forks gets a `setpgid` it may not be allowed to make. (`bash -m <script>` does not
# do this — bash drops monitor for a non-interactive shell — so SHELLOPTS is the route that
# reaches us.)
#
# This one IS behavioral: the launcher's own line is extracted and run under an inherited monitor,
# so it fails if the line is deleted OR if it stops working. A copy of `set +m` here would keep
# passing after the launcher lost it.
_disable_source=$(sed -n '/^set +m$/p' "$SHIM")
if [ -z "$_disable_source" ]; then
  _bad "the launcher disables inherited job control"
  printf '        no top-level `set +m` found in %s\n' "$SHIM"
else
  # `$-` is the authority on whether this shell has job control; `SHELLOPTS` is what children
  # inherit. Both must come back clean, or a child still forks under monitor.
  _after=$(env SHELLOPTS=monitor bash -c "
    case \"\$-\" in *m*) echo 'inherited-on';; esac
    $_disable_source
    case \"\$-\" in *m*) echo 'still-on';; esac
    case \"\$SHELLOPTS\" in *monitor*) echo 'children-still-on';; esac
  ")
  case "$_after" in
    inherited-on)
      _ok "the launcher disables inherited job control"
      ;;
    "")
      # The precondition never held, so the assertion proved nothing — this bash did not inherit
      # monitor from SHELLOPTS at all. Fail rather than report a green that tested nothing.
      _bad "the launcher disables inherited job control"
      printf '        this bash did not inherit monitor from SHELLOPTS, so nothing was exercised\n'
      ;;
    *)
      _bad "the launcher disables inherited job control"
      printf '        after the launcher line, monitor is still set: %s\n' "$_after"
      ;;
  esac
fi

echo
if [ "$_failures" -eq 0 ]; then
  echo "PASS: $_passes assertions"
  exit 0
fi
echo "FAIL: $_failures of $((_passes + _failures)) assertions"
exit 1
