#!/usr/bin/env bash
#
# Scan example READMEs for invocations of CLI subcommands that have been
# renamed or removed. Prevents the failure mode where a `./trailblaze
# <verb>` rename (e.g. PR #3236 collapsed `compile` + `typecheck` into
# `check`) lands without sweeping the README quick-starts authors copy
# into their terminals.
#
# The denylist below is the maintenance surface. When a CLI subcommand is
# renamed or removed, add the old name here in the same PR that ships the
# rename. CI will then fail until every example README's quick-start is
# updated.
#
# Scope: `examples/**/README.md` only. This is intentionally narrower
# than the sensitive-terms scanner — the goal is "command an external
# first-time contributor will paste into their shell" and that audience
# reads READMEs under `examples/`, not deeper docs.
#
# Why grep, not actual invocation: the simplest version catches the
# observed drift (#3262) with zero infra. Upgrading to actually-run-the-
# commands would require a built CLI and is overkill until grep stops
# catching real drift.

set -euo pipefail

# Subcommands that no longer exist in the current CLI surface. Keep this
# in sync with `./trailblaze --help`. Add one entry per rename/removal.
dead_commands=(
  # PR #3236 / #3250 — `compile` + `typecheck` merged into `check`.
  "compile"
  "typecheck"
  # CLI UX redesign — solo mode dropped in favor of `--tools` flag.
  "solo"
)

root_dir="$(git -C "$(dirname "${BASH_SOURCE[0]}")" rev-parse --show-toplevel)"
# Support both the OSS repo layout (examples/ at root) and the internal
# repo layout (opensource/examples/) from the same script.
if [[ -d "${root_dir}/opensource/examples" ]]; then
  examples_prefix="opensource/examples"
else
  examples_prefix="examples"
fi
examples_dir="${root_dir}/${examples_prefix}"

if [[ ! -d "${examples_dir}" ]]; then
  echo "Expected directory not found: ${examples_dir}" >&2
  exit 1
fi

echo "Scanning ${examples_dir}/**/README.md for dead CLI subcommands..."

# Build an extended regex like:
#   trailblaze[[:space:]]+(compile|typecheck|solo)([^[:alnum:]_]|$)
# The trailing `([^[:alnum:]_]|$)` is the portable POSIX-ERE stand-in for `\b`:
# any non-identifier char (space, backtick, period, comma, etc.) or end-of-line
# closes the match. Without this, README forms like `./trailblaze compile`
# (closing backtick) or `./trailblaze compile.` slip past the scanner while
# still being commands an external contributor will copy.
joined="$(IFS='|'; echo "${dead_commands[*]}")"
pattern="trailblaze[[:space:]]+(${joined})([^[:alnum:]_]|$)"

# Enumerate tracked READMEs under examples/ so the scan matches what
# ships in the repo (not editor backup files / untracked scratch).
hits="$(cd "${root_dir}" && git ls-files -z "${examples_prefix}/**/README.md" \
  | xargs -0 grep -E -H -n --binary-files=without-match -- "${pattern}" \
  || true)"

if [[ -z "${hits}" ]]; then
  echo "✅ No dead CLI subcommands found in example READMEs."
  exit 0
fi

echo ""
echo "❌ Dead CLI subcommand(s) referenced in example README(s):"
echo "------------------------------------------------------------"
echo "${hits}"
echo "------------------------------------------------------------"
echo ""
echo "Each match invokes a subcommand that no longer exists in the"
echo "current Trailblaze CLI. Run \`./trailblaze --help\` to see the live"
echo "subcommand list, then update the README to the replacement (or"
echo "remove the snippet)."
echo ""
echo "Denylist lives at the top of this script — if a subcommand is"
echo "legitimately back, drop it from \`dead_commands\` in the same PR."
exit 1
