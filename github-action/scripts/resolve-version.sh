#!/usr/bin/env bash
# Picks the CLI: a build artifact from this run, else the `version` input, else this action's own
# tag. The version may be a bare release (2026.10.06), a tag (v2026.10.06), or a pinned workflow
# ref (block/trailblaze/.github/workflows/trail-runner.yml@refs/tags/v2026.10.06).
set -euo pipefail
if [ -n "${CLI_ARTIFACT:-}" ]; then
  echo "version=artifact" >> "$GITHUB_OUTPUT"
  echo "cli-dir=$RUNNER_TEMP/trailblaze-cli" >> "$GITHUB_OUTPUT"
  echo "Trailblaze from artifact $CLI_ARTIFACT"
  exit 0
fi
version="${INPUT_VERSION:-${ACTION_REF:-}}"
version="${version##*@}"
version="${version#refs/tags/}"
version="${version#v}"
if ! [[ "$version" =~ ^[0-9]{4}\.[0-9]{2}\.[0-9]{2}(\.[0-9]+)?$ ]]; then
  echo "::error title=Trailblaze::Pin Trailblaze to a release tag such as @v2026.10.06, or set \`version\`. Got '${INPUT_VERSION:-${ACTION_REF:-}}'."
  exit 1
fi
echo "version=$version" >> "$GITHUB_OUTPUT"
echo "cli-dir=$RUNNER_TOOL_CACHE/trailblaze/$version" >> "$GITHUB_OUTPUT"
echo "Trailblaze $version"
