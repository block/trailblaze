#!/usr/bin/env bash
# Start the Slidev dev server for the droidcon deck (samdroidcon.md).
# Usage: ./start-slides.sh [port]   (default port 3030)
#
# Set TRAILBLAZE_NPM_REGISTRY in your own shell profile to install from a
# private mirror (e.g. an internal Artifactory proxy); left unset, bun uses
# the public npm registry. Never hardcode a private registry URL here — this
# file is checked in and this repo is public.
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")"

if [ ! -d node_modules ]; then
  echo "node_modules/ missing — running bun install..."
  if [ -n "${TRAILBLAZE_NPM_REGISTRY:-}" ]; then
    npm_config_registry="$TRAILBLAZE_NPM_REGISTRY" bun install
  else
    bun install
  fi
fi

PORT="${1:-3030}"

exec ./node_modules/.bin/slidev samdroidcon.md --port "$PORT" --open
