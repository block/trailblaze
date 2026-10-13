#!/usr/bin/env bash
# Writes the job summary and keeps one PR comment with the same text, found again by its marker.
set -euo pipefail
results="$RUNNER_TEMP/trailblaze-results"
viewer=https://block.github.io/trailblaze/report-viewer/
marker="<!-- trailblaze-github-action -->"
out="$RUNNER_TEMP/trailblaze-summary.md"
# Opens one session: in the viewer when its zip is published, else the HTML report artifact.
session_link() {
  if [ -n "$RAW" ]; then
    echo "$viewer?zip=$(jq -rn --arg u "$RAW/$1.zip" '$u|@uri')"
  else
    echo "$REPORT_URL"
  fi
}
rows() {
  jq -r '.results[] | [.outcome, (.title | gsub("\\|"; "\\|")), (.device_classifier // .platform // ""),
    ((.duration_ms // 0) / 1000 | floor), .session_id,
    ((.failure_reason // "") | gsub("\\s+"; " ") | .[0:300])] | @tsv' \
    "$results/summary.json" 2> /dev/null || true
}
passed=$(rows | awk -F'\t' '$1=="PASSED"' | wc -l | tr -d ' ')
total=$(rows | wc -l | tr -d ' ')
{
  echo "$marker"
  if [ "$total" -gt 0 ] && [ "$passed" = "$total" ]; then
    echo "## Trailblaze: ✅ $passed of $total trails passed"
  else
    echo "## Trailblaze: ❌ $passed of $total trails passed"
  fi
  echo
  if [ -n "$REPORT_URL" ]; then
    echo "[Open the full report]($REPORT_URL)"
    echo
  fi
  if [ "$total" -gt 0 ]; then
    echo "| | Trail | Device | Time |"
    echo "|---|---|---|---|"
  fi
  rows | while IFS=$'\t' read -r outcome title device secs id reason; do
    case "$outcome" in PASSED) icon=✅ ;; FAILED) icon=❌ ;; *) icon=⚠️ ;; esac
    echo "| $icon | [$title]($(session_link "$id")) | $device | ${secs}s |"
  done
  rows | while IFS=$'\t' read -r outcome title device secs id reason; do
    if [ -n "$reason" ]; then printf -- '\n- **%s**: %s\n' "$title" "$reason"; fi
  done
  if [ -z "$RAW" ]; then
    if [ -n "$SESSIONS_URL" ]; then
      echo
      echo "Session zips: [download]($SESSIONS_URL), then drop one on $viewer."
    fi
  elif [ "$STORYBOARDS" = true ]; then
    rows | while IFS=$'\t' read -r outcome title device secs id reason; do
      if [ ! -f "$results/$id.webp" ]; then continue; fi
      printf -- '\n### %s\n\n[![%s storyboard](%s)](%s)\n' \
        "$title" "$title" "$RAW/$id.webp" "$(session_link "$id")"
    done
  fi
} > "$out"
echo "passed=$passed" >> "$GITHUB_OUTPUT"
echo "total=$total" >> "$GITHUB_OUTPUT"
if [ "${WRITE_SUMMARY:-true}" != true ]; then exit 0; fi
cat "$out" >> "$GITHUB_STEP_SUMMARY"

if [ "$COMMENT" != true ] || [ -z "$PR" ]; then exit 0; fi
# Fork PRs get a read-only token, so a failed comment warns instead of failing the job.
comments="repos/$GITHUB_REPOSITORY/issues/$PR/comments"
id=$(gh api "$comments" --paginate --jq ".[] | select(.body | startswith(\"$marker\")) | .id" | tail -n 1) \
  || { echo "::warning title=Trailblaze::Could not read PR comments."; exit 0; }
if [ -n "$id" ]; then
  gh api -X PATCH "repos/$GITHUB_REPOSITORY/issues/comments/$id" -F body=@"$out" > /dev/null \
    || echo "::warning title=Trailblaze::Could not update the PR comment (needs pull-requests: write)."
else
  gh api -X POST "$comments" -F body=@"$out" > /dev/null \
    || echo "::warning title=Trailblaze::Could not comment on the PR (needs pull-requests: write)."
fi
