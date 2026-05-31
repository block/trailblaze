#!/usr/bin/env bash
#
# Build the open-source Trailblaze CLI uber JAR from this repo and install it
# onto $PATH so `trailblaze` resolves to a real binary — the same boot path an
# end user hits via the published install.sh / GitHub release.
#
# Sibling of:
#   - install.sh (repo root) — installs from a published GitHub release
#   - scripts/dev-jar-cache.sh — local-dev JAR cache helper
#
# Usage (run from this repo's root):
#   ./scripts/install-trailblaze-source.sh              # build + install
#   ./scripts/install-trailblaze-source.sh --uninstall  # remove install
#
# Always uninstalls any prior source-install (~/.trailblaze/install + its
# symlinks) first — a stale binary from an earlier build would silently shadow
# the new one. Does NOT touch a published-release install at
# ~/.trailblaze/bin/trailblaze (laid down by install.sh from the GitHub
# release); that path is detected separately and surfaces a remediation hint
# during the post-install verification step.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

INSTALL_DIR="${HOME}/.trailblaze/install"
# `:trailblaze-desktop` is the open-source uber JAR module. Override only if
# you know what you're doing.
TRAILBLAZE_MODULE="${TRAILBLAZE_MODULE:-:trailblaze-desktop}"

# Resolve the bin dir the same way the published installer does: prefer the
# active Homebrew prefix (Apple Silicon: /opt/homebrew/bin, Intel:
# /usr/local/bin), fall back to /usr/local/bin.
if command -v brew >/dev/null 2>&1; then
  BIN_DIR="$(brew --prefix)/bin"
else
  BIN_DIR="/usr/local/bin"
fi

# ── Inline uninstall ────────────────────────────────────────────────────────
# Sweeps every standard bin dir (not just BIN_DIR) so a binary installed under
# a different brew prefix on the same machine — e.g. an Intel install left
# over after migration to Apple Silicon — still gets removed. Self-contained
# so external users cloning only this repo don't need monorepo siblings.
uninstall() {
  local bin_dirs=(/opt/homebrew/bin /usr/local/bin "$HOME/.local/bin")
  if command -v brew >/dev/null 2>&1; then
    bin_dirs+=("$(brew --prefix)/bin")
  fi
  local removed_any=0
  for dir in "${bin_dirs[@]}"; do
    for name in trailblaze tb; do
      local f="${dir}/${name}"
      if [ -L "$f" ]; then
        local target
        target="$(readlink "$f" 2>/dev/null || true)"
        case "$target" in
          "${INSTALL_DIR}"*)
            echo "Removing symlink ${f} -> ${target}"
            rm -f "$f"
            removed_any=1
            ;;
          *)
            [ -n "$target" ] && echo "Leaving ${f} alone (-> ${target}; not managed by this install)"
            ;;
        esac
      fi
    done
  done
  if [ -d "$INSTALL_DIR" ]; then
    echo "Removing install dir ${INSTALL_DIR}"
    rm -rf "$INSTALL_DIR"
    removed_any=1
  fi
  if [ "$removed_any" -eq 0 ]; then
    echo "Nothing to uninstall — no managed trailblaze install found."
  fi
}

if [ "${1:-}" = "--uninstall" ]; then
  uninstall
  exit 0
fi

echo "--- Removing any existing trailblaze install"
uninstall

# Sanity-check: confirm `trailblaze` is no longer resolvable from $PATH before
# we build. If it still is, something we don't manage is in the way. The most
# likely culprit is a published-release install at ~/.trailblaze/bin/trailblaze
# (laid down by install.sh from the GitHub release) which we deliberately do
# not touch — call it out by name so the user knows the exact file.
if command -v trailblaze >/dev/null 2>&1; then
  STILL_RESOLVES="$(command -v trailblaze)"
  echo "WARNING: trailblaze still resolves on PATH at ${STILL_RESOLVES}"
  if [ "$STILL_RESOLVES" = "${HOME}/.trailblaze/bin/trailblaze" ]; then
    echo "  This is the published-release install (from install.sh / GitHub release)."
    echo "  This script's source-install will lay a symlink at ${BIN_DIR}/trailblaze;"
    echo "  whichever directory is earlier on \$PATH wins. If you want this source"
    echo "  build to take precedence, remove the published install first:"
    echo "    rm -rf ${HOME}/.trailblaze/bin"
    echo ""
  fi
fi

# Warn if Homebrew already manages trailblaze — our `ln -sf` below would
# silently shadow the brew-managed binary, leaving `brew uninstall` in a
# confusing state.
if command -v brew >/dev/null 2>&1 && brew list --formula trailblaze &>/dev/null; then
  echo "Warning: trailblaze is already installed via Homebrew."
  echo "  This source install will shadow the Homebrew version."
  echo "  To remove the Homebrew version first: brew uninstall trailblaze"
  echo ""
fi

# ── Build the release artifacts ─────────────────────────────────────────────
# `:releaseArtifacts` runs `packageUberJarForCurrentOS` and then copies the
# launcher script (scripts/trailblaze) alongside the JAR into
# build/release/. That gives us a self-contained {trailblaze.jar, trailblaze}
# pair without having to extract the launcher from inside the JAR (the OSS
# desktop module ships the launcher as a sibling, not an embedded resource).
#
# `-Ptrailblaze.variant=source` overrides the default in gradle.properties so
# the JAR's bundled version.properties announces itself as `(source)` in the
# GUI tray menu and `trailblaze --version`. This keeps a developer's locally-
# built install visibly distinct from an officially-published Homebrew/release
# binary which carry the inherited `Internal` variant.
echo "--- Building trailblaze release artifacts (${TRAILBLAZE_MODULE}:releaseArtifacts)"
cd "$REPO_ROOT"

# Bootstrap local.properties if missing. The Android Gradle Plugin reads this file for the
# SDK location and fails with a generic "SDK location not found" error pointing at this
# exact path. On a fresh worktree the file doesn't exist; rather than punt that error to
# the user, populate it from ANDROID_HOME / ANDROID_SDK_ROOT / the standard macOS install
# path (in priority order). This script only fails loudly if NONE of those resolve a real
# SDK dir — at that point the user genuinely doesn't have the SDK installed.
if [ ! -f "${REPO_ROOT}/local.properties" ]; then
  resolved_sdk=""
  for candidate in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}" "${HOME}/Library/Android/sdk" "${HOME}/Android/Sdk"; do
    if [ -n "$candidate" ] && [ -d "$candidate" ]; then
      resolved_sdk="$candidate"
      break
    fi
  done
  if [ -n "$resolved_sdk" ]; then
    echo "Bootstrapping ${REPO_ROOT}/local.properties (sdk.dir=$resolved_sdk)"
    printf 'sdk.dir=%s\n' "$resolved_sdk" > "${REPO_ROOT}/local.properties"
  else
    echo "Warning: no Android SDK found at ANDROID_HOME, ANDROID_SDK_ROOT, ~/Library/Android/sdk, or ~/Android/Sdk." >&2
    echo "  The build will likely fail with 'SDK location not found'." >&2
    echo "  Install the Android SDK (e.g. via Android Studio) or set ANDROID_HOME, then re-run." >&2
  fi
fi

# `TRAILBLAZE_GRADLE_EXTRA_ARGS` lets the caller layer in extra Gradle flags without
# editing this script. The CI prebuild step in `.github/workflows/pr-checks.yml`
# (and siblings) uses it to pass `-Ptrailblaze.wasm=true`, so the downstream test
# jobs consume an uber JAR with the WASM report template already embedded —
# matching the internal repo's build-uber-jar pattern. Unset by default, so local
# dev builds keep the gradle.properties default of `trailblaze.wasm=false`.
# Word-splitting is intentional so callers can pass multiple flags.
# shellcheck disable=SC2086
./gradlew "${TRAILBLAZE_MODULE}:releaseArtifacts" --stacktrace \
  -Ptrailblaze.variant=source \
  ${TRAILBLAZE_GRADLE_EXTRA_ARGS:-}

# Locate the built JAR + launcher. `releaseArtifacts` lays them down with
# stable names (no timestamp) — no need to hunt with `ls -t`.
module_dir="${TRAILBLAZE_MODULE#:}"
module_dir="${module_dir//://}"
RELEASE_DIR="${REPO_ROOT}/${module_dir}/build/release"
JAR_PATH="${RELEASE_DIR}/trailblaze.jar"
LAUNCHER_PATH="${RELEASE_DIR}/trailblaze"
if [ ! -f "$JAR_PATH" ]; then
  echo "Error: expected JAR not found at ${JAR_PATH}" >&2
  exit 1
fi
if [ ! -s "$LAUNCHER_PATH" ]; then
  echo "Error: expected launcher not found (or empty) at ${LAUNCHER_PATH}" >&2
  exit 1
fi
echo "Built JAR:      ${JAR_PATH}"
echo "Built launcher: ${LAUNCHER_PATH}"

# ── Install into ~/.trailblaze/install/ ─────────────────────────────────────
mkdir -p "$INSTALL_DIR"
cp "$JAR_PATH" "${INSTALL_DIR}/trailblaze.jar"
cp "$LAUNCHER_PATH" "${INSTALL_DIR}/trailblaze-launcher"
chmod 755 "${INSTALL_DIR}/trailblaze-launcher"

# Wrapper script — points the launcher at the bundled JAR. Same shape as the
# Homebrew exoskeleton and the GitHub-release install layout.
cat > "${INSTALL_DIR}/trailblaze" <<'WRAPPER'
#!/usr/bin/env bash
export TRAILBLAZE_JAR="${HOME}/.trailblaze/install/trailblaze.jar"
exec "${HOME}/.trailblaze/install/trailblaze-launcher" "$@"
WRAPPER
chmod 755 "${INSTALL_DIR}/trailblaze"

# ── Symlink onto $PATH ──────────────────────────────────────────────────────
if [ ! -d "$BIN_DIR" ]; then
  echo "Error: ${BIN_DIR} does not exist; cannot symlink trailblaze onto PATH." >&2
  exit 1
fi
# Probe writability before `ln -sf`. If BIN_DIR is owned by a different user
# (corp Mac with managed admin, or a CI agent with restricted permissions),
# the symlink would fail AFTER the JAR is laid down, leaving a half-installed
# state. Fail fast with a clear message instead.
WRITE_PROBE="${BIN_DIR}/.trailblaze-install-write-test.$$"
if ! (touch "$WRITE_PROBE" 2>/dev/null && rm -f "$WRITE_PROBE"); then
  echo "Error: ${BIN_DIR} is not writable; cannot symlink trailblaze onto PATH." >&2
  echo "  Check ownership/permissions, or rerun with appropriate privileges." >&2
  exit 1
fi
ln -sf "${INSTALL_DIR}/trailblaze" "${BIN_DIR}/trailblaze"
ln -sf "${INSTALL_DIR}/trailblaze" "${BIN_DIR}/tb"

# ── Verify the install resolves to OUR binary ───────────────────────────────
RESOLVED_TRAILBLAZE="$(command -v trailblaze 2>/dev/null || true)"
if [ -z "$RESOLVED_TRAILBLAZE" ]; then
  echo "Error: trailblaze is not resolvable on PATH after install." >&2
  echo "  Expected to resolve to ${BIN_DIR}/trailblaze." >&2
  echo "  Ensure ${BIN_DIR} is on PATH for this shell." >&2
  exit 1
fi
if [ ! -L "$RESOLVED_TRAILBLAZE" ]; then
  # Most common cause: the user previously ran the published install.sh, which
  # lays a regular file at ~/.trailblaze/bin/trailblaze and (by default) puts
  # ~/.trailblaze/bin on $PATH ahead of ${BIN_DIR}. Detect that specifically
  # and emit a remediation hint that names the offending file. Otherwise fall
  # back to the generic "another install or shell function shadows ours" hint.
  PUBLISHED_INSTALL="${HOME}/.trailblaze/bin/trailblaze"
  if [ "$RESOLVED_TRAILBLAZE" = "$PUBLISHED_INSTALL" ]; then
    echo "Error: \`trailblaze\` on PATH resolves to the published-release install at" >&2
    echo "       ${PUBLISHED_INSTALL} (a regular file)," >&2
    echo "  while this script just laid down the source build at" >&2
    echo "       ${BIN_DIR}/trailblaze (symlink → ${INSTALL_DIR}/trailblaze)." >&2
    echo "" >&2
    echo "  The published install came from running ${REPO_ROOT}/install.sh (or curl-piped equivalent)" >&2
    echo "  and earlier on \$PATH it shadows the source install." >&2
    echo "" >&2
    echo "  To prefer the source build, remove the published install:" >&2
    echo "    rm -rf ${HOME}/.trailblaze/bin" >&2
    echo "  and remove the matching PATH entry from your shell rc (~/.zshrc or ~/.bashrc)." >&2
    exit 1
  fi
  echo "Error: ${RESOLVED_TRAILBLAZE} is not a symlink." >&2
  echo "  Another trailblaze install (or a shell function shadowing the name) is taking precedence." >&2
  echo "  Remove the conflicting entry and rerun." >&2
  exit 1
fi
RESOLVED_TARGET="$(readlink "$RESOLVED_TRAILBLAZE" 2>/dev/null || true)"
if [ "$RESOLVED_TARGET" != "${INSTALL_DIR}/trailblaze" ]; then
  echo "Error: \`trailblaze\` on PATH does not point to the install we just laid down." >&2
  echo "  Resolved:        ${RESOLVED_TRAILBLAZE}" >&2
  echo "  Symlink target:  ${RESOLVED_TARGET:-<empty>}" >&2
  echo "  Expected target: ${INSTALL_DIR}/trailblaze" >&2
  echo "  Another trailblaze install is shadowing ours — remove it or fix PATH." >&2
  exit 1
fi

echo ""
echo "Installed trailblaze (built from open-source):"
echo "  JAR:      ${INSTALL_DIR}/trailblaze.jar ($(du -h "${INSTALL_DIR}/trailblaze.jar" | cut -f1))"
echo "  Symlinks: ${BIN_DIR}/trailblaze, ${BIN_DIR}/tb"
echo "  Resolves: ${RESOLVED_TRAILBLAZE}"
