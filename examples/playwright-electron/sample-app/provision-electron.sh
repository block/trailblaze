#!/usr/bin/env bash
# Ensure Electron's prebuilt platform binary is present under node_modules/electron/dist.
#
# npm >=11.16 no longer runs Electron's postinstall (the step that downloads this binary), so we fetch
# it explicitly. If a download base URL has been written to /tmp/electron-download-base (some CI agents
# set this because their egress can't reach github.com release assets), pull and extract the archive
# from there; otherwise fall back to Electron's own installer, which downloads from github.com.
set -euo pipefail
cd "$(dirname "$0")"

base_file=/tmp/electron-download-base
rm -rf node_modules/electron/dist node_modules/electron/path.txt

if [ -f "$base_file" ]; then
  base="$(cat "$base_file")"
  ver="$(node -p "require('electron/package.json').version")"
  plat="$(node -p "process.platform + '-' + process.arch")"
  curl -fsSL -o /tmp/electron-archive.zip "${base}v${ver}/electron-v${ver}-${plat}.zip"
  size="$(wc -c < /tmp/electron-archive.zip)"
  if [ "$size" -lt 50000000 ]; then
    printf 'electron archive too small (%s bytes) — the download base did not serve a binary\n' "$size" >&2
    head -c 200 /tmp/electron-archive.zip >&2
    exit 1
  fi
  mkdir -p node_modules/electron/dist
  unzip -q -o /tmp/electron-archive.zip -d node_modules/electron/dist
  case "$(node -p process.platform)" in
    darwin) printf 'Electron.app/Contents/MacOS/Electron' > node_modules/electron/path.txt ;;
    win32)  printf 'electron.exe' > node_modules/electron/path.txt ;;
    *)      printf 'electron' > node_modules/electron/path.txt ;;
  esac
else
  node node_modules/electron/install.js
fi

test -f node_modules/electron/path.txt
