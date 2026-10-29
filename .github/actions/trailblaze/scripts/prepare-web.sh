#!/usr/bin/env bash
# Installs the system libraries Chromium needs. The CLI downloads the browser itself on first use.
set -euo pipefail
if [ "$(uname -s)" != Linux ]; then
  echo "::error title=Trailblaze::Web runs need a Linux runner such as ubuntu-latest."
  exit 1
fi
bunx playwright install-deps chromium > /dev/null
