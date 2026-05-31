#!/usr/bin/env bash
#
# Install the trailblaze CLI from a `trailblaze.jar` + `trailblaze` (launcher)
# artifact pair previously uploaded by the upstream `build-uber-jar` job in
# `.github/workflows/pr-checks.yml` (and sibling workflows). Mirrors
# `install-trailblaze-source.sh`'s install logic (lay down JAR + launcher,
# write wrapper, symlink onto $PATH) but skips the Gradle build — used by
# downstream test jobs that have `needs: build-uber-jar` declared.
#
# Sibling of:
#   - scripts/install-trailblaze-source.sh — builds from source via Gradle
#
# Why: rebuilding the uber JAR in every test job wasted ~3-5 min on cold
# GitHub Actions runners. By having the upstream `build-uber-jar` job
# publish the JAR + launcher as a GitHub Actions artifact, consumer jobs
# now download it (~10s) instead.
#
# Expected input: the caller has already run
#   actions/download-artifact@v4 with name: trailblaze-uber-jar
# so `${PWD}/trailblaze.jar` and `${PWD}/trailblaze` (the launcher script)
# exist in the workflow's working directory. Override the download
# directory by setting `TRAILBLAZE_ARTIFACT_DIR=<path>` before running.
#
# Always uninstalls any prior trailblaze install first (a stale binary from
# an earlier run on the same self-hosted runner would silently shadow the
# new one — defensive against future migration off ephemeral runners).

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

INSTALL_DIR="${HOME}/.trailblaze/install"
ARTIFACT_DIR="${TRAILBLAZE_ARTIFACT_DIR:-$(pwd)}"

# Resolve the bin dir the same way install-trailblaze-source.sh does:
# prefer the active Homebrew prefix, fall back to /usr/local/bin.
if command -v brew >/dev/null 2>&1; then
  BIN_DIR="$(brew --prefix)/bin"
else
  BIN_DIR="/usr/local/bin"
fi

# ── Uninstall prior install ─────────────────────────────────────────────────
# Inline rather than calling out — install-trailblaze-source.sh has a much
# larger uninstall flow (sweeps multiple brew prefixes, detects published
# release installs) that's overkill for the CI artifact path. The runner is
# ephemeral on GitHub-hosted runners; we just need to clear any state from
# an in-job prior install.
echo "--- Removing any existing trailblaze install"
for name in trailblaze tb; do
  f="${BIN_DIR}/${name}"
  if [ -L "$f" ]; then
    echo "Removing symlink ${f} -> $(readlink "$f")"
    rm -f "$f"
  fi
done
if [ -d "$INSTALL_DIR" ]; then
  echo "Removing install dir ${INSTALL_DIR}"
  rm -rf "$INSTALL_DIR"
fi

# ── Locate the downloaded artifact ──────────────────────────────────────────
JAR_SRC="${ARTIFACT_DIR}/trailblaze.jar"
LAUNCHER_SRC="${ARTIFACT_DIR}/trailblaze"

if [ ! -s "$JAR_SRC" ]; then
  echo "Error: ${JAR_SRC} is missing or empty." >&2
  echo "  Expected the build-uber-jar job's artifact (trailblaze-uber-jar)" >&2
  echo "  to have been downloaded into ${ARTIFACT_DIR} before this script ran." >&2
  exit 1
fi
if [ ! -s "$LAUNCHER_SRC" ]; then
  echo "Error: ${LAUNCHER_SRC} is missing or empty." >&2
  echo "  The trailblaze-uber-jar artifact should contain both the JAR and" >&2
  echo "  the launcher script (laid down side-by-side by the releaseArtifacts" >&2
  echo "  Gradle task). Check the upstream build-uber-jar job upload step." >&2
  exit 1
fi
echo "Found artifact:"
echo "  JAR:      ${JAR_SRC} ($(du -h "$JAR_SRC" | cut -f1))"
echo "  Launcher: ${LAUNCHER_SRC}"

# ── Install into ~/.trailblaze/install/ ─────────────────────────────────────
mkdir -p "$INSTALL_DIR"
cp "$JAR_SRC" "${INSTALL_DIR}/trailblaze.jar"
cp "$LAUNCHER_SRC" "${INSTALL_DIR}/trailblaze-launcher"
chmod 755 "${INSTALL_DIR}/trailblaze-launcher"

# Wrapper script — matches install-trailblaze-source.sh exactly. The launcher
# resolves $SCRIPT_DIR from $BASH_SOURCE, which would be the symlink path on
# $PATH (e.g. /usr/local/bin/trailblaze), not the install dir; the wrapper
# sets TRAILBLAZE_JAR explicitly so the launcher's `$SCRIPT_DIR/trailblaze.jar`
# fallback isn't needed.
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
ln -sf "${INSTALL_DIR}/trailblaze" "${BIN_DIR}/trailblaze"
ln -sf "${INSTALL_DIR}/trailblaze" "${BIN_DIR}/tb"

# ── Verify ──────────────────────────────────────────────────────────────────
RESOLVED_TRAILBLAZE="$(command -v trailblaze 2>/dev/null || true)"
if [ -z "$RESOLVED_TRAILBLAZE" ]; then
  echo "Error: trailblaze is not resolvable on PATH after install." >&2
  echo "  Expected to resolve to ${BIN_DIR}/trailblaze." >&2
  echo "  Ensure ${BIN_DIR} is on PATH for this shell." >&2
  exit 1
fi

echo ""
echo "Installed trailblaze (from upstream artifact):"
echo "  JAR:      ${INSTALL_DIR}/trailblaze.jar"
echo "  Symlinks: ${BIN_DIR}/trailblaze, ${BIN_DIR}/tb"
echo "  Resolves: ${RESOLVED_TRAILBLAZE}"
