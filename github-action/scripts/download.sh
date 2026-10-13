#!/usr/bin/env bash
# Downloads the release CLI straight from GitHub Releases (no Homebrew), unless the cache or a
# cli-artifact already put it in place.
set -euo pipefail
if [ ! -f "$CLI_DIR/trailblaze" ] || [ ! -f "$CLI_DIR/trailblaze.jar" ]; then
  if [ "$VERSION" = artifact ]; then
    echo "::error title=Trailblaze::The cli-artifact must hold trailblaze and trailblaze.jar at its root."
    exit 1
  fi
  mkdir -p "$CLI_DIR"
  gh release download "v$VERSION" --repo block/trailblaze -p trailblaze -p trailblaze.jar -D "$CLI_DIR" --clobber
fi
# Artifacts drop the executable bit.
chmod +x "$CLI_DIR/trailblaze"
echo "$CLI_DIR" >> "$GITHUB_PATH"
# Pins where sessions land. A CLI run inside a source checkout would otherwise log to <git root>/logs.
if [ -z "${TRAILBLAZE_HOME:-}" ]; then
  echo "TRAILBLAZE_HOME=$RUNNER_TEMP/trailblaze-home" >> "$GITHUB_ENV"
fi
