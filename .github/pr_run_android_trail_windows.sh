#!/usr/bin/env bash
# Android trail on a Windows host, against the emulator windows_boot_android_emulator.sh started.
#
# Drives Trailblaze through the installed Windows launcher (`trailblaze.cmd`), and lets
# `trailblaze run` start the daemon and install the on-device APK over adb, as it does for a
# Windows user. Then checks the step-by-step CLI against the same device. Every check runs even
# after one fails; the script fails if any did.
TB=trailblaze.cmd
ADB="$ANDROID_HOME/platform-tools/adb.exe"
FAILED=()
pass() { echo "PASS: $1"; }
fail() { echo "FAIL: $1"; FAILED+=("$1"); }

echo "========================================="
echo "1. Recorded trail"
echo "========================================="
if TRAIL="$(./.github/showcase-trail.sh android recording)" && [ -n "$TRAIL" ]; then
  echo "Android showcase trail (from docs/showcase-trails.yml): $TRAIL"
  "$TB" run "$TRAIL" --use-recorded-steps && pass "run $TRAIL" || fail "run $TRAIL"
else
  fail "resolve the android showcase trail from docs/showcase-trails.yml"
fi

echo "========================================="
echo "2. Step by step, pinned to this shell"
echo "========================================="
"$TB" device connect android && pass "device connect android" || fail "device connect android"
SNAPSHOT="$("$TB" snapshot 2>&1)"
echo "$SNAPSHOT" | head -20
# The trail leaves Desk Clock on screen.
grep -qi "alarm" <<<"$SNAPSHOT" \
  && pass "snapshot with no --device shows the app" || fail "snapshot with no --device shows the app"
"$TB" device disconnect && pass "device disconnect" || fail "device disconnect"

# Git Bash rewrites arguments that look like POSIX paths before a Windows program sees them; the
# device path must reach adb as written.
mkdir -p "$TRAILBLAZE_HOME/device-logs"
MSYS_NO_PATHCONV=1 "$ADB" pull /sdcard/Download/trailblaze-logs/. "$TRAILBLAZE_HOME/device-logs" \
  || echo "No on-device logs to pull"

echo "========================================="
if [ ${#FAILED[@]} -gt 0 ]; then
  echo "${#FAILED[@]} check(s) failed:"
  printf '  - %s\n' "${FAILED[@]}"
  exit 1
fi
echo "All checks passed"
