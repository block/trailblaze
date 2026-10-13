#!/usr/bin/env bash
# Boots a headless Android emulator on a Windows runner, as the Linux jobs' emulator action does
# there (that action has no Windows support). The runner's Windows Hypervisor Platform accelerates
# it. Returns once the device has finished booting, with animations off.
set -euo pipefail

API_LEVEL="${API_LEVEL:-34}"
IMAGE_TAG="${IMAGE_TAG:-default}"
IMAGE="system-images;android-$API_LEVEL;$IMAGE_TAG;x86_64"
SDK="${ANDROID_HOME:?ANDROID_HOME is not set}"
SDKMANAGER="$SDK/cmdline-tools/latest/bin/sdkmanager.bat"
AVDMANAGER="$SDK/cmdline-tools/latest/bin/avdmanager.bat"
ADB="$SDK/platform-tools/adb.exe"

"$SDK/emulator/emulator.exe" -accel-check

(yes || true) | "$SDKMANAGER" --licenses > /dev/null || true
"$SDKMANAGER" --install "$IMAGE" > sdkmanager.log 2>&1 || { tail -20 sdkmanager.log; exit 1; }
echo no | "$AVDMANAGER" create avd -n trailblaze -k "$IMAGE" -d pixel_6 --force

# Software rendering is slow; give it the runner's four cores, or System UI stops responding.
"$SDK/emulator/emulator.exe" -avd trailblaze -no-window -no-audio -no-boot-anim -no-snapshot \
  -gpu swiftshader_indirect -accel on -cores 4 -memory 4096 > emulator.log 2>&1 &

"$ADB" start-server
BOOTED=
for i in $(seq 1 180); do
  # `adb shell` fails until the emulator shows up; that is the wait, not an error.
  BOOTED="$("$ADB" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' || true)"
  [ "$BOOTED" = 1 ] && break
  if [ $((i % 12)) = 0 ]; then
    echo "Still booting after $((i * 5))s: $("$ADB" get-state 2>&1 | tr -d '\r'); emulator log: $(tail -1 emulator.log)"
  fi
  sleep 5
done
"$ADB" devices -l
if [ "$BOOTED" != 1 ]; then
  echo "Emulator did not finish booting"
  tail -50 emulator.log
  exit 1
fi
echo "Booted after about $((i * 5))s"

for setting in window_animation_scale transition_animation_scale animator_duration_scale; do
  "$ADB" shell settings put global "$setting" 0
done
# A just-booted emulator is still busy; a slow moment then raises "isn't responding" dialogs that
# would cover the app under test.
"$ADB" shell settings put global hide_error_dialogs 1
"$ADB" shell input keyevent 82
sleep 30
"$ADB" shell dumpsys window | grep -m1 mCurrentFocus || true
