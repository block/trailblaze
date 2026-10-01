#!/usr/bin/env bash
#
# Scan example READMEs for `trailblaze` invocations that cannot run as
# written. Two independent checks:
#
#   1. Dead SUBCOMMANDS — a hand-maintained denylist of verbs that were
#      renamed or removed (e.g. PR #3236 collapsed `compile` + `typecheck`
#      into `check`).
#   2. Unknown OPTIONS — every `--flag` on a `trailblaze` line in a README
#      shell block or inline code span is checked against the options the CLI
#      actually declares for that subcommand.
#      This one needs no maintenance: `docs/CLI.md` is GENERATED from the
#      picocli annotations, so it tracks the CLI automatically.
#
# Check 2 exists because check 1 could not see this drift: three
# example READMEs documented `trailblaze run --workspace <dir>`, and
# `--workspace` is declared only on `check`. The verb was live, so a
# subcommand denylist had nothing to match. Every one of those snippets
# died at argument parsing.
#
# Scope: `examples/**/README.md` only. This is intentionally narrower
# than the sensitive-terms scanner — the goal is "command an external
# first-time contributor will paste into their shell" and that audience
# reads READMEs under `examples/`, not deeper docs.
#
# Why parse CLI.md, not invoke the CLI: the generated doc is the real
# annotation surface, already committed, and checked for staleness by its
# own mandatory gate. Reading it keeps this scan infra-free — no built
# CLI, no JVM, no device.
#
# Usage:
#   scan_readme_cli_drift.sh              # scan the tracked example READMEs
#   scan_readme_cli_drift.sh FILE...      # scan exactly these files (used by the self-test)
#   scan_readme_cli_drift.sh --list-files # print the files the scan resolves, and stop

set -euo pipefail

list_only=0
if [[ "${1:-}" == "--list-files" ]]; then
  list_only=1
  shift
fi

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

# `docs/` is a sibling of `examples/` in both layouts, so deriving it from
# the directory resolved above keeps one branch instead of two.
cli_md="$(dirname "${examples_dir}")/docs/CLI.md"
if [[ ! -f "${cli_md}" ]]; then
  echo "Expected generated CLI reference not found: ${cli_md}" >&2
  exit 1
fi

cd "${root_dir}"

# Files to scan: the caller's list, else every tracked README under examples/.
readmes=()
if [[ $# -gt 0 ]]; then
  readmes=("$@")
else
  # Two pathspecs: `**/README.md` matches only the NESTED ones, so the index
  # page at `examples/README.md` needs naming on its own. It is not an edge
  # case — it is one of the three files that carried `run --workspace`.
  while IFS= read -r -d '' f; do
    readmes+=("${f}")
  done < <(git ls-files -z "${examples_prefix}/README.md" "${examples_prefix}/**/README.md")
fi

if [[ ${#readmes[@]} -eq 0 ]]; then
  echo "No README files to scan under ${examples_dir}" >&2
  exit 1
fi

# Print the resolved file set and stop. The default set comes from a pathspec, and a
# pathspec that quietly matches less than intended is the one failure this gate cannot
# report on its own — it just passes. The self-test asserts against this.
if [[ "${list_only}" == "1" ]]; then
  printf '%s\n' "${readmes[@]}"
  exit 0
fi

failed=0

# ---------------------------------------------------------------------------
# Check 1 — dead subcommands
# ---------------------------------------------------------------------------

if [[ $# -gt 0 ]]; then
  scan_label="${#readmes[@]} file(s) named on the command line"
else
  scan_label="${examples_dir}/**/README.md"
fi

echo "Scanning ${scan_label} for dead CLI subcommands..."

# Build an extended regex like:
#   trailblaze[[:space:]]+(compile|typecheck|solo)([^[:alnum:]_]|$)
# The trailing `([^[:alnum:]_]|$)` is the portable POSIX-ERE stand-in for `\b`:
# any non-identifier char (space, backtick, period, comma, etc.) or end-of-line
# closes the match. Without this, README forms like `./trailblaze compile`
# (closing backtick) or `./trailblaze compile.` slip past the scanner while
# still being commands an external contributor will copy.
joined="$(IFS='|'; echo "${dead_commands[*]}")"
pattern="trailblaze[[:space:]]+(${joined})([^[:alnum:]_]|$)"

# grep exits 1 for "no match", which is the clean result. Anything above that is
# grep failing — an unreadable file, a bad pattern — and must not read as clean.
set +e
hits="$(grep -E -H -n --binary-files=without-match -- "${pattern}" "${readmes[@]}")"
grep_status=$?
set -e
if [[ "${grep_status}" -gt 1 ]]; then
  echo "❌ The dead-subcommand scan did not run: grep exited ${grep_status}." >&2
  exit 1
fi

if [[ -z "${hits}" ]]; then
  echo "✅ No dead CLI subcommands found in example READMEs."
else
  failed=1
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
fi

# ---------------------------------------------------------------------------
# Check 2 — unknown options
# ---------------------------------------------------------------------------

echo "Checking example README \`trailblaze\` snippets against ${cli_md#"${root_dir}"/}..."

# awk because this needs a per-subcommand option map and macOS ships bash 3.2
# (no associative arrays). Pass 1 builds the map from CLI.md; pass 2 reads each
# README's shell blocks and inline `trailblaze ...` spans.
#
# Deliberately a line reader, not a shell parser: it handles the forms example
# READMEs use — `VAR=val` prefixes, `&&` / `;` / `|` / `&` chains, trailing
# comments, `\` continuations, and quoted text — and nothing more. Only `--long`
# options are checked; that is the `run --workspace` shape and it needs no cluster rules.
read -r -d '' awk_prog <<'AWK' || true
# Fence state is per file: a README that ends inside an unclosed fence must not
# make the next file's opening ```bash read as a close.
FNR == 1 { in_fence = 0; in_shell = 0; pending = "" }

# ---- Pass 1: the generated CLI reference ----
FILENAME == CLI_MD {
  if ($0 ~ /^## Global Options/) { cmd = ""; in_options = 1; next }
  if ($0 ~ /^### `trailblaze /) {
    heading = $0
    sub(/^### `trailblaze[ \t]*/, "", heading)
    sub(/`.*$/, "", heading)
    cmd = heading
    known[cmd] = 1
    in_options = 0
    next
  }
  # Any other heading or a horizontal rule closes the current options table.
  if ($0 ~ /^#/ || $0 ~ /^---[ \t]*$/) { in_options = 0; next }
  if ($0 ~ /^\*\*Options:\*\*/) { in_options = 1; next }
  # An alias is another name for the command whose section this is. Unresolved,
  # `trailblaze trail x --workspace y` — the `run --workspace` line under the old verb —
  # would match no known command and be skipped.
  if ($0 ~ /^\*\*Aliases:\*\*/) {
    n = split($0, parts, "`")
    for (i = 2; i <= n; i += 2) {
      name = parts[i]
      sub(/^trailblaze[ \t]*/, "", name)
      alias[name] = cmd
    }
    next
  }
  if ($0 ~ /^\*\*[A-Za-z]+:\*\*/) { in_options = 0; next }
  if (!in_options || $0 !~ /^\|/) next

  # Only the FIRST cell names the option. Descriptions routinely mention other
  # flags, and harvesting those would make the map accept anything ever mentioned.
  split($0, cells, "|")
  n = split(cells[2], parts, "`")
  for (i = 2; i <= n; i += 2) {
    if (parts[i] ~ /^-/) flags[cmd SUBSEP parts[i]] = 1
  }
  next
}

# ---- Pass 2: the READMEs ----
/^[ \t]*```/ {
  in_fence = !in_fence
  lang = $0
  sub(/^[ \t]*```[ \t]*/, "", lang)
  sub(/[ \t].*$/, "", lang)
  in_shell = in_fence && (lang == "bash" || lang == "sh" || lang == "shell" || lang == "console")
  pending = ""
  next
}

# Prose carries copyable commands too, as inline code: `trailblaze check --workspace x`.
!in_fence {
  n = split($0, parts, "`")
  for (i = 2; i < n; i += 2) {
    if (parts[i] ~ /^(\.\/)?trailblaze /) check_command(unquote(parts[i]), FNR)
  }
  next
}

!in_shell { next }

{
  line = unquote($0)
  sub(/(^|[ \t])#.*$/, "", line)       # comment
  if (pending == "") start_ln = FNR
  line = pending line
  if (line ~ /\\[ \t]*$/) {            # continued on the next line
    sub(/\\[ \t]*$/, " ", line)
    pending = line
    next
  }
  pending = ""
  check_command(line, start_ln)
}

# Quoted text is one argument, never an option: `ask "Does it show --workspace?"`.
function unquote(s) {
  gsub(/"[^"]*"|'[^']*'/, "_", s)
  return s
}

function check_command(s, ln,   nseg, segs, i, ntok, toks, j, k, cmd, cand, flag, label) {
  gsub(/&&|\|\||[;|&]/, "\n", s)
  nseg = split(s, segs, "\n")
  for (i = 1; i <= nseg; i++) {
    ntok = split(segs[i], toks, /[ \t]+/)
    j = 1
    while (j <= ntok && (toks[j] == "" || toks[j] ~ /^[A-Za-z_][A-Za-z0-9_]*=/)) j++
    if (j > ntok || toks[j] !~ /(^|\/)trailblaze$/) continue
    j++

    # Longest known subcommand path wins, so `session start` beats `session`
    # and a trailing positional (`check sampleapp`) is not mistaken for one.
    cmd = ""
    while (j <= ntok && toks[j] !~ /^-/) {
      cand = (cmd == "" ? toks[j] : cmd " " toks[j])
      if (cand in alias) cand = alias[cand]
      if (!(cand in known)) break
      cmd = cand
      j++
    }
    # A verb CLI.md does not define — the launcher's `upgrade` / `downgrade`, or a
    # dead one the denylist above reports — has no option table to check against.
    if (cmd == "" && (j > ntok || toks[j] !~ /^-/)) continue
    label = (cmd == "" ? "trailblaze" : "trailblaze " cmd)

    for (k = j; k <= ntok; k++) {
      flag = toks[k]
      if (flag !~ /^--[A-Za-z]/) continue
      sub(/=.*$/, "", flag)
      # No fallback to the global options: `--stop` is declared on the root and on
      # `app` only, and picocli rejects it anywhere else.
      if ((cmd SUBSEP flag) in flags) continue
      bad[++nbad] = FILENAME ":" ln ": `" label "` has no option `" flag "`"
    }
  }
}

END {
  for (i = 1; i <= nbad; i++) print bad[i]
  exit (nbad > 0 ? 1 : 0)
}
AWK

# Exit 1 is how the awk program reports findings; anything else is awk itself
# failing (bad program, unreadable input). Swallowing that would leave
# `option_hits` empty and report a clean scan that never happened.
set +e
option_hits="$(awk -v CLI_MD="${cli_md}" "${awk_prog}" "${cli_md}" "${readmes[@]}")"
awk_status=$?
set -e
if [[ "${awk_status}" -gt 1 ]]; then
  echo "❌ The option scan did not run: awk exited ${awk_status}." >&2
  exit 1
fi

if [[ -z "${option_hits}" ]]; then
  echo "✅ Every \`trailblaze\` option in example READMEs is declared by the CLI."
else
  failed=1
  echo ""
  echo "❌ Unknown CLI option(s) in example README(s):"
  echo "------------------------------------------------------------"
  echo "${option_hits}"
  echo "------------------------------------------------------------"
  echo ""
  echo "Each snippet dies at argument parsing with \"Unknown option\"."
  echo "Run \`trailblaze <subcommand> --help\` (or read the options table"
  echo "for that subcommand in the generated CLI reference) and rewrite"
  echo "the snippet to a flag the subcommand really declares."
  echo ""
  echo "If the flag IS new, regenerate the reference in the same PR:"
  echo "  ./gradlew :docs:generator:run"
fi

exit "${failed}"
