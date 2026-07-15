#!/usr/bin/env bash
# Start the Slidev dev server for the droidcon deck (samdroidcon.md).
# Usage: ./start-slides.sh [port]   (default port 3030)
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")"

if [ ! -d node_modules ]; then
  echo "node_modules/ missing — running bun install..."
  bun install
fi

PORT="${1:-3030}"

exec ./node_modules/.bin/slidev samdroidcon.md --port "$PORT" --open
