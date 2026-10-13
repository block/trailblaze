#!/usr/bin/env bash
# Puts ffmpeg on PATH for session video. Linux gets a pinned static build, because Ubuntu's apt
# ffmpeg (6.1.1) drops the wall-clock timestamps a live recording depends on, so the video drifts
# from the session. macOS gets Homebrew's.
set -euo pipefail
if [ "$(uname -s)" = Darwin ]; then
  command -v ffmpeg > /dev/null || brew install -q ffmpeg
  exit 0
fi
url="https://github.com/BtbN/FFmpeg-Builds/releases/download/autobuild-2026-08-31-13-27/ffmpeg-n8.1.2-50-g1a748fe2cd-linux64-gpl-8.1.tar.xz"
sha256="c733b4b2951e5957e15505f788b2c65a7a41b6da4b289e295852cc38079b4d2b"
dest="$RUNNER_TEMP/ffmpeg"
curl -fsSL -o "$RUNNER_TEMP/ffmpeg.tar.xz" "$url"
echo "$sha256  $RUNNER_TEMP/ffmpeg.tar.xz" | sha256sum -c - > /dev/null
mkdir -p "$dest"
tar xJf "$RUNNER_TEMP/ffmpeg.tar.xz" -C "$dest" --strip-components=1
echo "$dest/bin" >> "$GITHUB_PATH"
