#!/usr/bin/env bash
# Boots the requested iPhone simulator, or the first available iPhone if that model is missing.
set -euo pipefail
if [ "$(uname -s)" != Darwin ]; then
  echo "::error title=Trailblaze::iOS runs need a macOS runner such as macos-26."
  exit 1
fi
uuid='[A-F0-9]{8}-[A-F0-9]{4}-[A-F0-9]{4}-[A-F0-9]{4}-[A-F0-9]{12}'
devices=$(xcrun simctl list devices available)
udid=$(grep -F "    $SIMULATOR (" <<< "$devices" | head -1 | grep -oE "$uuid" || true)
if [ -z "$udid" ]; then
  echo "::warning title=Trailblaze::No $SIMULATOR simulator on this runner; using the first iPhone."
  udid=$(grep "iPhone" <<< "$devices" | head -1 | grep -oE "$uuid" || true)
fi
if [ -z "$udid" ]; then
  echo "::error title=Trailblaze::This runner has no iPhone simulator."
  exit 1
fi
echo "Booting $(grep "$udid" <<< "$devices" | sed 's/^ *//')"
xcrun simctl boot "$udid"
xcrun simctl bootstatus "$udid" -b > /dev/null
