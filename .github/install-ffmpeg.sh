#!/usr/bin/env bash
# Puts a pinned static ffmpeg on PATH for the rest of a Linux GitHub Actions job.
#
# Session video (TRAILBLAZE_CAPTURE_VIDEO) encodes through ffmpeg, and so do the
# trailblaze-capture tests and the docs gallery's animated WebP. The runner image
# has no ffmpeg, and Ubuntu 24.04's apt build (6.1.1) drops the wall-clock
# timestamps a live recording depends on, so the recording drifts from the
# session. This is a BtbN month-end build: BtbN keeps those for over a year and
# daily ones for about two weeks.
set -euo pipefail

FFMPEG_URL="https://github.com/BtbN/FFmpeg-Builds/releases/download/autobuild-2026-08-31-13-27/ffmpeg-n8.1.2-50-g1a748fe2cd-linux64-gpl-8.1.tar.xz"
FFMPEG_SHA256="c733b4b2951e5957e15505f788b2c65a7a41b6da4b289e295852cc38079b4d2b"

dest="${RUNNER_TEMP:?}/ffmpeg"
curl -fsSL -o "$RUNNER_TEMP/ffmpeg.tar.xz" "$FFMPEG_URL"
echo "$FFMPEG_SHA256  $RUNNER_TEMP/ffmpeg.tar.xz" | sha256sum -c -
mkdir -p "$dest"
tar xJf "$RUNNER_TEMP/ffmpeg.tar.xz" -C "$dest" --strip-components=1
echo "$dest/bin" >> "${GITHUB_PATH:?}"

"$dest/bin/ffmpeg" -version | head -1
# The encoders session video (VP9, H.264) and the docs WebP (libwebp_anim) use.
encoders="$("$dest/bin/ffmpeg" -hide_banner -encoders)"
for enc in libvpx-vp9 libx264 libwebp_anim; do
  grep -qw "$enc" <<<"$encoders" || { echo "ffmpeg has no $enc encoder" >&2; exit 1; }
done
