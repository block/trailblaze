#!/usr/bin/env bash
# Generate the docs Report Gallery assets for a trail run: a storyboard WebP, an
# animated-timeline WebP, and the full self-contained interactive HTML report. These are
# uploaded as a dedicated artifact (see the workflow) and fetched by the GitHub Pages
# build to embed on https://block.github.io/trailblaze/reports/.
#
# This is SEPARATE from pr_generate_trailblaze_report.sh (which builds the CI
# `trailblaze_report.html` index). Both scripts drive the `trailblaze report` CLI
# subcommand off the prebuilt uber JAR; this one passes `--storyboard` / `--webp`,
# which are wired only on that subcommand.
#
# Intentionally NOT `set -e`: a missing encoder or a flaky capture must never red the
# trail job. We emit clear diagnostics and exit 0 so the workflow's upload step still
# runs with whatever was produced.
set -uo pipefail

OUT_DIR="$(pwd)/report-assets"
# The `trailblaze report` subcommand reads the desktop app's resolved logsRepo, which on
# CI (as locally) is ~/.trailblaze/logs — the same dir the daemon wrote the session to.
LOGS_DIR="${TRAILBLAZE_LOCAL_LOGS_DIR:-$HOME/.trailblaze/logs}"

echo "========================================="
echo "Generating docs report-gallery assets"
echo "  logs dir:   $LOGS_DIR"
echo "  output dir: $OUT_DIR"
echo "========================================="

if [ ! -d "$LOGS_DIR" ]; then
  echo "WARNING: logs dir $LOGS_DIR does not exist — nothing to export. Skipping."
  exit 0
fi

# ffmpeg with libwebp_anim is required for the animated --webp. The storyboard uses
# bundled libwebp (no ffmpeg), so generate it in a separate CLI invocation below: if the
# animated timeline preflight fails, the storyboard and HTML still publish.
HAS_WEBP_ANIM=0
if command -v ffmpeg >/dev/null 2>&1 && ffmpeg -hide_banner -encoders 2>/dev/null | grep -q libwebp_anim; then
  HAS_WEBP_ANIM=1
  echo "✓ ffmpeg with libwebp_anim found"
else
  echo "WARNING: ffmpeg with libwebp_anim not found — the animated timeline.webp will be skipped."
fi

# Nothing here builds or publishes the legacy WASM report: every invocation below passes
# --no-wasm-report, so the gallery depends only on the interactive report and survives the
# WASM report's removal untouched. (It also drops the CPU-bound WASM render from this job.)
# Once that removal lands, the `-Ptrailblaze.wasm=true` the build-uber-jar job passes to
# bundle the WASM template is dead weight for this workflow too.

# Resolve the single session this trail produced. Session logs are per-session dirs under
# $LOGS_DIR; skip the sibling `reports/` output dir. Newest wins if there's more than one.
SESSION_ID="$(ls -1dt "$LOGS_DIR"/*/ 2>/dev/null | grep -v '/reports/$' | head -1 | xargs -I{} basename {})"
if [ -z "$SESSION_ID" ]; then
  echo "WARNING: no session found under $LOGS_DIR — skipping asset gen."
  exit 0
fi
echo "Using session: $SESSION_ID"

# --max-size caps storyboard / animated WebP so they stay light on the docs page and well
# under any inline limits; the HTML report itself is not size-capped (it's a download/link-out).
echo "Exporting storyboard + interactive report..."
trailblaze report --id "$SESSION_ID" --output-dir "$OUT_DIR" \
  --storyboard --no-wasm-report --max-size=8MB

if [ "$HAS_WEBP_ANIM" = 1 ]; then
  # --no-gif: we only embed the WebP (GitHub/the docs render it the same, smaller file).
  # The animated timeline records the interactive report (--export-from's default).
  echo "Exporting animated WebP timeline..."
  trailblaze report --id "$SESSION_ID" --output-dir "$OUT_DIR" \
    --webp --no-gif --no-wasm-report --max-size=8MB
fi

# The session archive the hosted report viewer reads. Same shape the daemon's
# /api/session/{id}/export.zip produces — entries prefixed with the session directory —
# so a plain `zip -r` from the logs root is the whole job. Published alongside the other
# assets so the gallery can deep-link report-viewer/?zip=<this>, which renders the run
# through the live viewer instead of shipping a frozen self-contained HTML.
echo "Archiving the session for the hosted report viewer..."
if command -v zip >/dev/null 2>&1; then
  rm -f "$OUT_DIR/session.zip"
  (cd "$LOGS_DIR" && zip -q -r "$OUT_DIR/session.zip" "$SESSION_ID") \
    && echo "✓ session.zip ($(du -h "$OUT_DIR/session.zip" | cut -f1))" \
    || echo "WARNING: session archive failed — the gallery deep-link will fall back to its error state."
else
  echo "WARNING: zip not on PATH — skipping the session archive."
fi

echo "========================================="
echo "Asset gen complete. Contents of $OUT_DIR:"
ls -lh "$OUT_DIR" 2>/dev/null || echo "  (output dir not created — export failed)"
echo "========================================="

# Surface (without failing) whether the files the docs page needs are present.
# report.html (the legacy WASM export) is deliberately NOT in this list: `trailblaze
# report` still writes it, but the gallery publishes report-interactive.html.
for f in storyboard.webp timeline.webp report-interactive.html session.zip; do
  if [ -f "$OUT_DIR/$f" ]; then
    echo "✓ $f"
  else
    echo "✗ $f MISSING — the docs page will fall back to the committed placeholder."
  fi
done
exit 0
