#!/usr/bin/env bash
#
# Guards scan_readme_cli_drift.sh. The gate is only worth running if it both
# fires on drift and stays quiet on the snippet shapes example READMEs really
# use — a scanner that silently parses nothing also "passes".
#
# The anchor case: `trailblaze run --workspace <dir>` sat in three
# example READMEs on main and died at argument parsing every time, because
# `--workspace` is declared only on `check`. That exact line must FAIL here.
#
# Fixtures are throwaway markdown files checked against the repo's real
# generated CLI reference, so the assertions track the actual CLI surface
# rather than a stub that can drift from it.
#
# Run locally with: bash scripts/scan_readme_cli_drift_test.sh

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SCAN="${SCRIPT_DIR}/scan_readme_cli_drift.sh"

_tmp="$(mktemp -d)"
trap 'rm -rf "${_tmp}"' EXIT

failures=0

# _expect <name> <expected-exit> <file> [substring-that-must-appear]
_expect() {
  local name="$1" want="$2" file="$3" needle="${4:-}"
  local out status
  out="$("${SCAN}" "${file}" 2>&1)"
  status=$?
  if [[ "${status}" != "${want}" ]]; then
    echo "❌ ${name}: expected exit ${want}, got ${status}"
    echo "${out}" | sed 's/^/    /'
    failures=$((failures + 1))
    return
  fi
  if [[ -n "${needle}" && "${out}" != *"${needle}"* ]]; then
    echo "❌ ${name}: output did not mention '${needle}'"
    echo "${out}" | sed 's/^/    /'
    failures=$((failures + 1))
    return
  fi
  echo "✅ ${name}"
}

echo "--- the run --workspace regression FAILS"
cat > "${_tmp}/workspace-on-run.md" <<'EOF'
```bash
trailblaze run trails/forms/text-input.trail.yaml --workspace examples/android-sample-app
```
EOF
_expect "run --workspace is rejected" 1 "${_tmp}/workspace-on-run.md" \
  '`trailblaze run` has no option `--workspace`'

echo "--- the same flag on the subcommand that declares it PASSES"
cat > "${_tmp}/workspace-on-check.md" <<'EOF'
```bash
trailblaze check --workspace examples/android-sample-app
```
EOF
_expect "check --workspace is accepted" 0 "${_tmp}/workspace-on-check.md"

echo "--- real README snippet shapes all PASS"
# Every form below appears in a shipped example README today. A false positive
# on any of them would make the gate unusable, so they are asserted together.
cat > "${_tmp}/shapes.md" <<'EOF'
```bash
cd examples/android-sample-app
# a whole-line comment --workspace
trailblaze run trails/forms/text-input.trail.yaml
trailblaze run trails/mcp-tools-demo/mcp-tools-demo.trail.yaml --device android/emulator-5554
trailblaze toolbox --device android --target sampleapp
trailblaze run trails/forms/text-input-args.trail.yaml \
  --arg name="Sam Edwards" --arg email="sam@example.com"
trailblaze check --workspace examples/wikipedia   # trailing comment --workspace
./trailblaze check sampleapp
./trailblaze app --stop
trailblaze app --stop && trailblaze app --headless & disown
TRAILBLAZE_PORT=42424 ./trailblaze tool x_openFixture --device web --target x -o "Open the page"
trailblaze run trails/x --device web --tags search,crud
bun install && ./gradlew build
```
EOF
_expect "documented snippet shapes are accepted" 0 "${_tmp}/shapes.md"

echo "--- nested subcommands resolve to their own option set"
cat > "${_tmp}/nested-ok.md" <<'EOF'
```bash
trailblaze session start --device ios
```
EOF
_expect "nested subcommand option is accepted" 0 "${_tmp}/nested-ok.md"

cat > "${_tmp}/nested-bad.md" <<'EOF'
```bash
trailblaze session start --workspace examples/android-sample-app
```
EOF
_expect "nested subcommand rejects a foreign option" 1 "${_tmp}/nested-bad.md" \
  '`trailblaze session start` has no option `--workspace`'

echo "--- aliases resolve to the command they alias"
# Both come from CLI.md's **Aliases:** lines; nothing in the gate names them.
cat > "${_tmp}/alias.md" <<'EOF'
```bash
trailblaze trail trails/forms/text-input.trail.yaml --workspace examples/android-sample-app
```
EOF
_expect 'the run --workspace line spelled with the trail alias is rejected' 1 "${_tmp}/alias.md" \
  '`trailblaze run` has no option `--workspace`'

cat > "${_tmp}/blaze.md" <<'EOF'
```bash
trailblaze blaze --workspace examples/x "Tap Submit"
```
EOF
_expect "an option check survives the blaze alias" 1 "${_tmp}/blaze.md" \
  '`trailblaze step` has no option `--workspace`'

echo "--- documented negatable booleans are accepted, invented ones are not"
# `--no-turbo` proves the table is the source: `--turbo`'s description never mentions it.
cat > "${_tmp}/negated.md" <<'EOF'
```bash
trailblaze run trails/x.trail.yaml --no-save-recording --no-capture-logcat --no-turbo
```
EOF
_expect "documented negatable forms are accepted" 0 "${_tmp}/negated.md"

cat > "${_tmp}/bad-negation.md" <<'EOF'
```bash
trailblaze ask "what is on screen" --no-device
```
EOF
_expect "an invented negation of a non-negatable option is rejected" 1 "${_tmp}/bad-negation.md" \
  '`trailblaze ask` has no option `--no-device`'

echo "--- quoted text is an argument, not options"
cat > "${_tmp}/quoted.md" <<'EOF'
```bash
trailblaze ask "Does it show --workspace?"
trailblaze ask 'what about --workspace; or | this' --device ios
trailblaze step "Tap #1" --device ios
```
EOF
_expect "flag-like words inside quotes are accepted" 0 "${_tmp}/quoted.md"

# A `#` or `;` inside quotes must not end the command early.
cat > "${_tmp}/quoted-bad.md" <<'EOF'
```bash
trailblaze step "Tap #1; then" --workspace examples/x
```
EOF
_expect "options after quoted text are still checked" 1 "${_tmp}/quoted-bad.md" \
  '`trailblaze step` has no option `--workspace`'

echo "--- continuations and chains are checked"
cat > "${_tmp}/continued.md" <<'EOF'
```bash
trailblaze run trails/x.trail.yaml \
  --device web \
  --workspace examples/x
```
EOF
_expect "an option on a continuation line is checked" 1 "${_tmp}/continued.md" \
  '`trailblaze run` has no option `--workspace`'

printf '```bash\n%s\n```\n' 'trailblaze app --stop && TRAILBLAZE_PORT=1 trailblaze run x --workspace y & disown' \
  > "${_tmp}/chained.md"
_expect "a command later in a chain, behind VAR=val, is checked" 1 "${_tmp}/chained.md" \
  '`trailblaze run` has no option `--workspace`'

echo "--- root-only options stay on the root"
# `--stop` is declared on the root command and, separately, on `app`. It is not
# inherited, so a subcommand that takes it is a line picocli rejects.
cat > "${_tmp}/root-only.md" <<'EOF'
```bash
trailblaze --stop
trailblaze app --stop
trailblaze run trails/x.trail.yaml --help
```
EOF
_expect "root and app accept --stop, and --help is per-command" 0 "${_tmp}/root-only.md"

cat > "${_tmp}/root-only-bad.md" <<'EOF'
```bash
trailblaze run trails/x.trail.yaml --stop
```
EOF
_expect "a root-only option on a subcommand is rejected" 1 "${_tmp}/root-only-bad.md" \
  '`trailblaze run` has no option `--stop`'

echo "--- a verb the reference does not define stays quiet"
printf '```bash\n%s\n```\n' 'trailblaze upgrade --to 1.2.3' > "${_tmp}/launcher-verb.md"
_expect "a launcher-only verb is not option-checked" 0 "${_tmp}/launcher-verb.md"

echo "--- inline code in prose is a command too"
cat > "${_tmp}/inline-bad.md" <<'EOF'
Validate it with `trailblaze check --workspace x`, then run `trailblaze run t.trail.yaml --workspace x`.
EOF
_expect "an inline command in prose is checked" 1 "${_tmp}/inline-bad.md" \
  '`trailblaze run` has no option `--workspace`'

cat > "${_tmp}/inline-ok.md" <<'EOF'
Run `trailblaze check --workspace <path-to-your-config>`, or `trailblaze run …` once it passes.
Mentioning the `--workspace` flag, or `trailblaze` alone, is not a command.
EOF
_expect "well-formed inline commands and prose mentions are accepted" 0 "${_tmp}/inline-ok.md"

echo "--- non-shell fences are skipped, and still close"
# A `text` fence is illustrative output, not something a reader pastes. It must
# still close, or every command after it goes unchecked.
printf '```text\n%s\n```\n' 'trailblaze run trails/x.trail.yaml --workspace examples/x' \
  > "${_tmp}/text-only.md"
_expect "a text fence is not scanned" 0 "${_tmp}/text-only.md"

cat > "${_tmp}/text-fence.md" <<'EOF'
```text
trailblaze run trails/x.trail.yaml --workspace examples/x
```
Then run `trailblaze run t.trail.yaml --workspace x`.
EOF
_expect "prose after a text fence is checked" 1 "${_tmp}/text-fence.md" \
  'text-fence.md:4: `trailblaze run` has no option `--workspace`'

echo "--- an unclosed fence does not blind the NEXT file"
# Fence state is per file. If it leaked, file two's opening ```bash would read as a
# close and its bad flag would go unscanned — the gate passing by silently doing nothing.
cat > "${_tmp}/unclosed.md" <<'EOF'
```bash
trailblaze check --workspace examples/wikipedia
EOF
cat > "${_tmp}/after-unclosed.md" <<'EOF'
```bash
trailblaze run trails/x.trail.yaml --workspace examples/wikipedia
```
EOF
out="$("${SCAN}" "${_tmp}/unclosed.md" "${_tmp}/after-unclosed.md" 2>&1)"
status=$?
if [[ "${status}" == 1 && "${out}" == *'after-unclosed.md'* ]]; then
  echo "✅ the file after an unclosed fence is still scanned"
else
  echo "❌ the file after an unclosed fence is still scanned: exit ${status}"
  echo "${out}" | sed 's/^/    /'
  failures=$((failures + 1))
fi

echo "--- a scan that cannot read its input fails closed"
_expect "a missing README fails the dead-subcommand scan" 1 "${_tmp}/does-not-exist.md" \
  'The dead-subcommand scan did not run'

echo "--- the default scan covers EVERY example README"
# The `**/README.md` pathspec matches only nested files. `examples/README.md` — one of
# the three that carried `run --workspace` — needs naming on its own, and a pathspec
# that silently matches less just makes the gate pass.
_root="$(git -C "${SCRIPT_DIR}" rev-parse --show-toplevel)"
if [[ -d "${_root}/opensource/examples" ]]; then _ex="opensource/examples"; else _ex="examples"; fi
expected="$(cd "${_root}" && git ls-files "${_ex}" | grep -E '(^|/)README\.md$' | sort)"
actual="$("${SCAN}" --list-files | sort)"
if [[ "${expected}" == "${actual}" ]]; then
  echo "✅ scanned set matches every tracked example README ($(echo "${actual}" | wc -l | tr -d ' ') files)"
else
  echo "❌ scanned set does not match every tracked example README"
  diff <(echo "${expected}") <(echo "${actual}") | sed 's/^/    /'
  failures=$((failures + 1))
fi

echo "--- a dead subcommand still FAILS"
cat > "${_tmp}/dead-verb.md" <<'EOF'
```bash
./trailblaze compile
```
EOF
_expect "denylisted subcommand is rejected" 1 "${_tmp}/dead-verb.md" "compile"

echo ""
if [[ "${failures}" -ne 0 ]]; then
  echo "❌ ${failures} scan_readme_cli_drift.sh assertion(s) failed."
  exit 1
fi
echo "✅ scan_readme_cli_drift.sh behaves as documented."
