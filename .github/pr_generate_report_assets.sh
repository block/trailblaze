#!/usr/bin/env bash
# Generate the docs Report Gallery assets for a trail run: a storyboard WebP, an
# animated-timeline WebP, and the full self-contained interactive HTML report. These are
# uploaded as a dedicated artifact (see the workflow) and fetched by the GitHub Pages
# build to embed on https://block.github.io/trailblaze/reports/.
#
# This is SEPARATE from pr_generate_trailblaze_report.sh (which builds the CI
# `trailblaze_report.html` index via `:trailblaze-report:run`). Here we drive the
# `trailblaze report` CLI subcommand, which is the only entry point wired to the
# `--storyboard` / `--webp` exporters.
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
# bundled libwebp (no ffmpeg) and will still be produced if ffmpeg is absent.
if command -v ffmpeg >/dev/null 2>&1 && ffmpeg -hide_banner -encoders 2>/dev/null | grep -q libwebp_anim; then
  echo "✓ ffmpeg with libwebp_anim found"
else
  echo "WARNING: ffmpeg with libwebp_anim not found — the animated timeline.webp will be skipped."
fi

# The report HTML (and therefore the storyboard/webp, which screenshot it via headless
# Playwright) renders from the WASM report template. In CI `./trailblaze` runs via Gradle
# WITHOUT -Ptrailblaze.wasm=true, so we must materialize the template to the build-output
# path that ReportTemplateResolver checks (trailblaze-report/build/report-template/).
echo "Building WASM report template..."
./gradlew :trailblaze-report:generateReportTemplate -Ptrailblaze.wasm=true || {
  echo "ERROR: failed to build WASM report template — reports would render blank. Aborting asset gen."
  exit 0
}

# Resolve the single session this trail produced. Session logs are per-session dirs under
# $LOGS_DIR; skip the sibling `reports/` output dir. Newest wins if there's more than one.
SESSION_ID="$(ls -1dt "$LOGS_DIR"/*/ 2>/dev/null | grep -v '/reports/$' | head -1 | xargs -I{} basename {})"
if [ -z "$SESSION_ID" ]; then
  echo "WARNING: no session found under $LOGS_DIR — skipping asset gen."
  exit 0
fi
echo "Using session: $SESSION_ID"

# --no-gif: we only embed the WebP (GitHub/the docs render it the same, smaller file).
# --max-size caps the animated WebP so it stays light on the docs page and well under
# any inline limits; the HTML report itself is not size-capped (it's a download/link-out).
echo "Exporting storyboard + animated WebP + interactive report..."
./trailblaze report --id "$SESSION_ID" --output-dir "$OUT_DIR" \
  --storyboard --webp --no-gif --max-size=8MB

echo "========================================="
echo "Asset gen complete. Contents of $OUT_DIR:"
ls -lh "$OUT_DIR" 2>/dev/null || echo "  (output dir not created — export failed)"
echo "========================================="

# Surface (without failing) whether the three files the docs page needs are present.
for f in storyboard.webp timeline.webp report.html; do
  if [ -f "$OUT_DIR/$f" ]; then
    echo "✓ $f"
  else
    echo "✗ $f MISSING — the docs page will fall back to the committed placeholder."
  fi
done
exit 0
