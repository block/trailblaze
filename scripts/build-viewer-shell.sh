#!/usr/bin/env bash
#
# Build the standalone Trailblaze report viewer: ONE self-contained index.html that turns any
# Trailblaze session archive into the full interactive report, in the browser, with no daemon and
# no backend.
#
# Usage:
#   ./scripts/build-viewer-shell.sh <out-dir>     # writes <out-dir>/index.html
#
# Serve that directory from any static host — there is nothing else to deploy. The generated file
# inlines the report's own stylesheet, the report viewer bundle, and the ZIP pipeline, so a hosted
# copy can't drift from the renderer: it IS the report's code with no run baked in.
#
# Two ways to open an archive, and they have different requirements:
#   - Drop a .zip on the page (or use its file picker) — reads the file locally, no network at all.
#   - ?zip=<archive-url> or the URL field — a cross-origin fetch, so it only works when the host
#     serving the archive sends `Access-Control-Allow-Origin`.
#
# Requires `bun` on PATH.

set -euo pipefail

OUT_DIR="${1:-}"
if [ -z "$OUT_DIR" ]; then
  echo "usage: $0 <out-dir>" >&2
  exit 2
fi

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
APP_DIR="$REPO_ROOT/trailblaze-report/src/main/resources/xyz/block/trailblaze/trailrunner/web/app"

[ -f "$APP_DIR/viewer-shell-cli.ts" ] || { echo "error: missing source $APP_DIR/viewer-shell-cli.ts" >&2; exit 1; }
command -v bun >/dev/null 2>&1 || { echo "error: bun not on PATH — see https://bun.sh" >&2; exit 1; }

mkdir -p "$OUT_DIR"
OUT_FILE="$(cd "$OUT_DIR" && pwd)/index.html"

# The bun macros in run-report-html.ts build the viewer bundle and the loader bundle and read the
# ZIP pipeline at transpile time, so this single call emits the finished document — no assembly step
# and no cache-busting to get wrong.
(cd "$APP_DIR" && bun run ./viewer-shell-cli.ts) > "$OUT_FILE"

# Guards on the generated document. Each catches a failure mode that is silent in a browser: empty
# output (a bun error swallowed by the redirect), a stray write before the doctype (which would put
# the page in quirks mode), a file that isn't the shell at all (no `data-tb-shell` marker, so the
# viewer bundle would auto-boot an empty report over the loader chrome), and truncation — the other
# three markers all sit in the first 200 bytes, so only the closing </html> proves the document
# survived to its end.
[ -s "$OUT_FILE" ] || { echo "error: generated shell is empty" >&2; exit 1; }
[ "$(head -c 15 "$OUT_FILE")" = "<!doctype html>" ] || { echo "error: generated file does not begin with the doctype — something wrote to stdout first" >&2; exit 1; }
grep -q 'data-tb-shell' "$OUT_FILE" || { echo "error: generated file is not a viewer shell (no data-tb-shell marker)" >&2; exit 1; }
tail -c 20 "$OUT_FILE" | grep -q '</html>' || { echo "error: generated shell is truncated (no closing </html>)" >&2; exit 1; }

echo "Built viewer shell: $OUT_FILE ($(wc -c < "$OUT_FILE" | tr -d ' ') bytes)" >&2
