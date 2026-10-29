#!/usr/bin/env bash
# Pushes this run's session zips and storyboards to a branch, where raw.githubusercontent.com serves
# them with CORS open, which the report viewer needs. Each push replaces the branch with one commit
# holding the newest KEEP_RUNS runs, so the branch never grows.
set -euo pipefail
results="$RUNNER_TEMP/trailblaze-results"
repo="$GITHUB_REPOSITORY"
dir="runs/${GITHUB_RUN_ID}-${GITHUB_RUN_ATTEMPT}"
url="https://x-access-token:${GH_TOKEN}@github.com/${repo}.git"
if ! ls "$results"/*.zip > /dev/null 2>&1; then
  echo "No sessions to publish."
  exit 0
fi
for attempt in 1 2 3; do
  pub="$RUNNER_TEMP/trailblaze-publish-$attempt"
  rm -rf "$pub"
  base=$(git ls-remote --heads "$url" "$BRANCH" | cut -f1)
  if [ -n "$base" ]; then
    git clone -q --depth 1 --branch "$BRANCH" "$url" "$pub"
  else
    git init -q "$pub"
    git -C "$pub" remote add origin "$url"
  fi
  mkdir -p "$pub/$dir"
  cp "$results"/*.zip "$pub/$dir/"
  cp "$results"/*.webp "$pub/$dir/" 2> /dev/null || true
  # Run ids only grow, so the lowest numbers are the oldest runs.
  ls -1 "$pub/runs" | sort -t- -k1,1n -k2,2n | head -n -"$KEEP_RUNS" | while read -r old; do
    rm -rf "${pub:?}/runs/$old"
  done
  # A throwaway clone on the runner, so the commit is the Actions bot's, not a person's.
  git -C "$pub" config user.name "github-actions[bot]"
  git -C "$pub" config user.email "41898299+github-actions[bot]@users.noreply.github.com"
  git -C "$pub" checkout -q --orphan trailblaze-publish
  git -C "$pub" add -A
  git -C "$pub" commit -q -m "Trailblaze sessions, newest run ${GITHUB_RUN_ID}"
  if git -C "$pub" push -q --force-with-lease="refs/heads/$BRANCH:$base" origin "HEAD:refs/heads/$BRANCH"; then
    echo "raw=https://raw.githubusercontent.com/$repo/$BRANCH/$dir" >> "$GITHUB_OUTPUT"
    exit 0
  fi
  echo "Another run published at the same time; retrying."
  sleep $((attempt * 3))
done
echo "::warning title=Trailblaze::Could not publish sessions to $BRANCH, so the summary links to the artifacts instead."
