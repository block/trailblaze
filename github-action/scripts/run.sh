#!/usr/bin/env bash
# Runs the trails on the prepared device. Always exits 0 and leaves the verdict in a file, so the
# export, publish and summary steps still run after a failed trail.
set -uo pipefail
status=0
case "$DEVICE" in
  android) device=android/emulator-5554 ;;
  *) device="$DEVICE" ;;
esac
if [ -n "${INPUT_CONFIG_DIR:-}" ]; then
  export TRAILBLAZE_CONFIG_DIR="$PWD/$INPUT_CONFIG_DIR"
fi
if [ -n "${INPUT_SETUP:-}" ]; then
  bash -c "$INPUT_SETUP" || status=$?
fi
if [ "$status" = 0 ] && [ -n "$INPUT_APP" ]; then
  case "$DEVICE" in
    android) adb install -r "$INPUT_APP" || status=$? ;;
    ios) xcrun simctl install booted "$INPUT_APP" || status=$? ;;
  esac
fi
if [ "$status" = 0 ]; then
  flags=()
  if [ "$CAPTURE_VIDEO" = true ]; then flags+=(--capture-video); fi
  # Both are lists, split on whitespace on purpose.
  read -r -d '' -a trails <<< "$INPUT_TRAILS" || true
  read -r -d '' -a args <<< "$INPUT_ARGS" || true
  trailblaze run "${trails[@]}" -d "$device" "${args[@]}" "${flags[@]}" || status=$?
fi
echo "$status" > "$RUNNER_TEMP/trailblaze-exit"
exit 0
