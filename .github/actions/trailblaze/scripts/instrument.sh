#!/usr/bin/env bash
# Runs an Android instrumentation test APK the way a device farm does: install it, run this shard's
# slice with `am instrument`, then pull the sessions its tests wrote on the device into the logs
# directory that export.sh reads. Exits non-zero when a test failed or the run didn't finish.
set -uo pipefail

fail() { echo "::error title=Trailblaze::$*"; exit 1; }
[ -f "$INPUT_TEST_APK" ] || fail "No test APK at $INPUT_TEST_APK."

# Single-quotes a value for the device's shell, which re-parses everything `adb shell` sends.
q() { printf "'%s'" "${1//\'/\'\\\'\'}"; }

aapt2=$(ls "${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"/build-tools/*/aapt2 2> /dev/null | sort -V | tail -n 1)
[ -n "$aapt2" ] || fail "aapt2 isn't in the Android SDK's build-tools, so the test APK's package can't be read."
package=$("$aapt2" dump packagename "$INPUT_TEST_APK") || fail "Couldn't read the package of $INPUT_TEST_APK."

adb install -r -t -g "$INPUT_TEST_APK" || fail "Couldn't install $INPUT_TEST_APK."
runner=$(adb shell pm list instrumentation | tr -d '\r' | sed -n "s|^instrumentation:$package/\([^ ]*\) .*|\1|p" | head -n 1)
[ -n "$runner" ] || fail "$INPUT_TEST_APK ($package) declares no instrumentation."

# This shard's slice, the test targets (one `-e` per kind, each a comma-separated list), then the
# request's own instrumentation arguments.
extras=""
if [ "${SHARD_COUNT:-1}" -gt 1 ]; then
  extras+=" -e numShards $(q "$SHARD_COUNT") -e shardIndex $(q "$SHARD_INDEX")"
fi
while read -r kind value; do
  [ -z "$kind" ] || extras+=" -e $(q "$kind") $(q "$value")"
done < <(printf '%s\n' "${INPUT_TEST_TARGETS:-}" \
  | awk 'NF == 2 { v[$1] = v[$1] (v[$1] ? "," : "") $2 } END { for (k in v) print k, v[k] }')
while IFS=$'\t' read -r key value; do
  [ -z "$key" ] || extras+=" -e $(q "$key") $(q "$value")"
done < <(jq -r 'to_entries[] | "\(.key)\t\(.value)"' <<< "${INPUT_TEST_ARGS:-"{}"}")

if [ "${ORCHESTRATOR:-false}" = true ]; then
  # Android Test Orchestrator runs each test in its own process and clears the app's data between
  # tests, so one device gives every test a clean start.
  dir="$RUNNER_TEMP/test-orchestrator"
  mkdir -p "$dir"
  while read -r url sha; do
    apk="$dir/${url##*/}"
    [ -f "$apk" ] || curl -fsSL "$url" -o "$apk" || fail "Couldn't download $url."
    echo "$sha  $apk" | sha256sum -c --quiet - || fail "$apk doesn't match its pinned checksum."
    adb install -r -t -g "$apk" || fail "Couldn't install $apk."
  done << 'EOF'
https://dl.google.com/android/maven2/androidx/test/orchestrator/1.6.1/orchestrator-1.6.1.apk 0a8aa1f18da0613d863be7be26e2a5c8de2f6892660b86577848a122ff7df6c5
https://dl.google.com/android/maven2/androidx/test/services/test-services/1.6.0/test-services-1.6.0.apk 3369693a0d79965888e674f0d05a3474d647ff46c4a62a5c8ea410a0126a20bf
EOF
  instrument="CLASSPATH=\$(pm path androidx.test.services) app_process / androidx.test.services.shellexecutor.ShellMain"
  instrument+=" am instrument -r -w -e clearPackageData true -e targetInstrumentation $(q "$package/$runner")"
  instrument+="$extras androidx.test.orchestrator/androidx.test.orchestrator.AndroidTestOrchestrator"
else
  instrument="am instrument -r -w$extras $(q "$package/$runner")"
fi

device_logs=/sdcard/Download/trailblaze-logs
adb shell rm -rf "$device_logs"
out="$RUNNER_TEMP/instrumentation.txt"
echo "adb shell $instrument"
adb shell "$instrument" | tr -d '\r' | tee "$out"

# The device writes every session's files side by side, named `<session>_<time>[_<seq>].<ext>` or
# `<session>-trace.json`. The report reads one directory per session, with each log named after
# its type (`<seq>_<time>_TrailblazeSessionStatusChangeLog.json`) and the trace as `trace.json`.
# Screenshots keep their names, which the logs refer to.
logs="${TRAILBLAZE_HOME:-$HOME/.trailblaze}/logs"
pulled="$RUNNER_TEMP/device-logs"
rm -rf "$pulled"
mkdir -p "$logs" "$pulled"
adb pull "$device_logs/." "$pulled" > /dev/null 2>&1 || true
ids=$(ls "$pulled" | sed -nE 's/(_[0-9]{13}(_[0-9]{10})?\.[a-z0-9]+|-trace\.json)$//p' | sort -u | awk '{ print length, $0 }' | sort -rn | cut -d' ' -f2-)
for f in "$pulled"/*; do
  [ -f "$f" ] || continue
  name="${f##*/}"
  while read -r id; do
    if [ -n "$id" ] && [[ "$name" == "$id"[_-]* ]]; then
      mkdir -p "$logs/$id"
      rest="${name#"$id"}"
      if [ "$rest" = -trace.json ]; then
        mv "$f" "$logs/$id/trace.json"
      elif [[ "$rest" =~ ^_([0-9]{13})_([0-9]{10})\.json$ ]]; then
        type=$(jq -r '.class // "" | split(".") | last' "$f" 2> /dev/null)
        mv "$f" "$logs/$id/${BASH_REMATCH[2]}_${BASH_REMATCH[1]}_${type:-TrailblazeLog}.json"
      else
        mv "$f" "$logs/$id/"
      fi
      break
    fi
  done <<< "$ids"
done
[ -n "$ids" ] || echo "::warning title=Trailblaze::The tests left no sessions in $device_logs."

# In raw output each test reports a status code: 1 starting, 0 passed, -1 error, -2 failed.
started=$(grep -c '^INSTRUMENTATION_STATUS_CODE: 1$' "$out")
failed=$(grep -cE '^INSTRUMENTATION_STATUS_CODE: -(1|2)$' "$out")
echo "$started tests ran on this shard, $failed failed."
grep -q '^INSTRUMENTATION_CODE: -1$' "$out" || fail "The instrumentation run didn't finish; see the output above."
[ "$failed" = 0 ] || exit 1
