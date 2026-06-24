#!/usr/bin/env bash
#
# Resolve a field from the Report Gallery showcase manifest
# (docs/showcase-trails.yml) for one platform — the single source of truth for
# which recorded trail powers each platform's published showcase report.
#
# Usage:
#   .github/showcase-trail.sh <platform> <field>
#     <platform>  ios | android | web
#     <field>     recording | slug
#
# Prints the resolved value on stdout. Exits non-zero (with a diagnostic on
# stderr, nothing on stdout) when the platform/field isn't found, so callers can
# guard the result:
#   if TRAIL="$(./.github/showcase-trail.sh ios recording)" && [ -n "$TRAIL" ]; then
#     trailblaze trail -d ios "$TRAIL"
#   else
#     echo "no ios showcase trail"; TEST_FAILED=true
#   fi
#
# Dependency-free (awk only) so it runs in any CI trail job without yq or PyYAML.
set -euo pipefail

PLATFORM="${1:?usage: showcase-trail.sh <platform> <field>}"
FIELD="${2:?usage: showcase-trail.sh <platform> <field>}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MANIFEST="${SCRIPT_DIR}/../docs/showcase-trails.yml"

if [ ! -f "$MANIFEST" ]; then
  echo "showcase-trail.sh: manifest not found at $MANIFEST" >&2
  exit 1
fi

# The manifest is a fixed two-level shape: top-level platform keys (`ios:` at
# column 0) each holding indented `slug:` / `recording:` fields. Track the
# current platform block, then emit the requested field's value verbatim.
VALUE="$(
  awk -v plat="$PLATFORM" -v field="$FIELD" '
    /^[a-z][a-z0-9_]*:[[:space:]]*$/ { cur=$1; sub(/:.*/, "", cur); next }
    {
      if (cur == plat && match($0, "^[[:space:]]+" field ":[[:space:]]*")) {
        val = substr($0, RLENGTH + 1)
        gsub(/[[:space:]]+$/, "", val)
        print val
        exit
      }
    }
  ' "$MANIFEST"
)"

if [ -z "$VALUE" ]; then
  echo "showcase-trail.sh: no '$FIELD' for platform '$PLATFORM' in $MANIFEST" >&2
  exit 1
fi

printf '%s\n' "$VALUE"
