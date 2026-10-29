#!/usr/bin/env bash
# Trailblaze installer
#
# Downloads the latest Trailblaze release JAR from GitHub and extracts the launcher
# script the JAR carries (its `trailblaze-launcher` entry), so the two always match.
#
# Install:
#   curl -fsSL https://raw.githubusercontent.com/block/trailblaze/main/install.sh -o /tmp/trailblaze-install.sh && bash /tmp/trailblaze-install.sh
#
# Environment variables:
#   TRAILBLAZE_VERSION  - Install a specific version (e.g. "0.3.0"). Default: latest.
#   TRAILBLAZE_DIR      - Install directory. Default: ~/.trailblaze

set -euo pipefail

REPO="block/trailblaze"
INSTALL_DIR="${TRAILBLAZE_DIR:-$HOME/.trailblaze}"
BIN_DIR="$INSTALL_DIR/bin"

# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

info()  { echo "  $*"; }
error() { echo "ERROR: $*" >&2; exit 1; }

# Writes one entry of a JAR to <out>. A JAR is a zip, so this needs no Java: unzip where
# it exists, else bsdtar or python3, which minimal Linux images often carry instead.
extract_jar_entry() {
  local jar="$1" entry="$2" out="$3" rc=0
  if command -v unzip > /dev/null 2>&1; then
    # Status 1 is only a warning (bytes prepended to the archive, say); the entry was still written.
    unzip -p "$jar" "$entry" > "$out" 2> /dev/null || rc=$?
    [ "$rc" -le 1 ] || return 1
  elif command -v bsdtar > /dev/null 2>&1; then
    bsdtar -xOf "$jar" "$entry" > "$out" 2> /dev/null || return 1
  elif command -v python3 > /dev/null 2>&1; then
    python3 -c 'import sys, zipfile; sys.stdout.buffer.write(zipfile.ZipFile(sys.argv[1]).read(sys.argv[2]))' \
      "$jar" "$entry" > "$out" 2> /dev/null || return 1
  else
    return 1
  fi
  [ -s "$out" ]
}

check_dependency() {
  command -v "$1" > /dev/null 2>&1 || error "'$1' is required but not found. Please install it first."
}

# Warn (don't error) when an optional binary that gates a specific runtime feature is missing.
optional_dependency() {
  local bin="$1"
  local why="$2"
  if ! command -v "$bin" > /dev/null 2>&1; then
    echo "WARNING: '$bin' is not on PATH — $why" >&2
    if command -v brew > /dev/null 2>&1; then
      echo "         Install with: brew install $bin" >&2
    fi
  fi
}

# Ensure bun is available. Unlike esbuild/ffmpeg (truly optional), bun is the sole
# supported JS runtime behind the @trailblaze/scripting build path, so we install it
# automatically — the no-package-manager equivalent of what `brew install bun` would
# do — rather than only warning. Uses bun's official installer (macOS + Linux). Stays
# non-fatal: a download failure or restricted environment still leaves a working JAR
# for pre-recorded trails. Set TRAILBLAZE_SKIP_BUN_INSTALL=1 to opt out entirely.
ensure_bun() {
  if command -v bun > /dev/null 2>&1; then
    info "bun found ($(bun --version 2>/dev/null))"
    return 0
  fi

  if [ "${TRAILBLAZE_SKIP_BUN_INSTALL:-}" = "1" ]; then
    echo "WARNING: 'bun' is not on PATH and TRAILBLAZE_SKIP_BUN_INSTALL=1 was set —" >&2
    echo "         scripted-tool authoring/dispatch will be unavailable. Install from" >&2
    echo "         https://bun.sh/ to enable it." >&2
    return 0
  fi

  info "bun not found — installing via https://bun.sh/install ..."
  if curl -fsSL https://bun.sh/install | bash; then
    # bun's installer drops the binary in $BUN_INSTALL/bin (default ~/.bun/bin) and
    # appends a PATH line to the user's shell rc. Mirror that into this script's PATH
    # so downstream steps (and the post-install hints) see bun immediately.
    export BUN_INSTALL="${BUN_INSTALL:-$HOME/.bun}"
    export PATH="$BUN_INSTALL/bin:$PATH"
    if command -v bun > /dev/null 2>&1; then
      info "bun installed ($(bun --version 2>/dev/null))"
    else
      echo "WARNING: bun installer completed but 'bun' is still not on PATH. Open a new" >&2
      echo "         shell, or add \"$BUN_INSTALL/bin\" to PATH manually." >&2
    fi
  else
    echo "WARNING: Failed to auto-install bun. Scripted-tool authoring/dispatch will be" >&2
    echo "         unavailable until you install it from https://bun.sh/." >&2
  fi
}

# ---------------------------------------------------------------------------
# Preflight
# ---------------------------------------------------------------------------

check_dependency curl
check_dependency java

JAVA_VERSION=$(java -version 2>&1 | head -1 | sed 's/.*"\([0-9]*\).*/\1/')
if [ "$JAVA_VERSION" -lt 17 ] 2>/dev/null; then
  error "Java 17+ is required (found Java $JAVA_VERSION)."
fi

# Optional runtime tools — surface a warning rather than aborting the install so users who
# only run pre-recorded trails (no scripted tool authoring, no video capture) aren't blocked.
optional_dependency esbuild \
  "scripted-tool authoring (\`.ts\` trailmap tools) won't bundle. Required only if you run trails that exercise trailmap-defined scripted tools."
optional_dependency ffmpeg \
  "trail video capture / sprite extraction will be skipped. Trails still run; only the visual playback artifacts are unavailable."

# bun is the sole supported JS runtime for scripted tools — auto-install it rather
# than just warning (see ensure_bun above).
ensure_bun

# ---------------------------------------------------------------------------
# Resolve version
# ---------------------------------------------------------------------------

if [ -n "${TRAILBLAZE_VERSION:-}" ]; then
  TAG="v${TRAILBLAZE_VERSION}"
  info "Installing Trailblaze $TAG"
else
  info "Fetching latest release..."
  TAG=$(curl -fsSL "https://api.github.com/repos/$REPO/releases/latest" | grep '"tag_name"' | sed 's/.*"tag_name": *"\([^"]*\)".*/\1/')
  [ -n "$TAG" ] || error "Could not determine latest release. Set TRAILBLAZE_VERSION manually."
  info "Latest release: $TAG"
fi

VERSION="${TAG#v}"

# ---------------------------------------------------------------------------
# Download
# ---------------------------------------------------------------------------

RELEASE_URL="https://github.com/$REPO/releases/download/$TAG"

mkdir -p "$BIN_DIR"

# Both files are staged under names private to this run, and only promoted once both are in
# hand: a failure part way leaves the existing install as it was, never a new JAR beside an old
# launcher. Renaming into place also lets a launcher that is running right now keep reading the
# file it opened.
JAR_STAGE="$BIN_DIR/.trailblaze.jar.$$"
LAUNCHER_STAGE="$BIN_DIR/.trailblaze.$$"
trap 'rm -f "$JAR_STAGE" "$LAUNCHER_STAGE"' EXIT

info "Downloading trailblaze.jar..."
curl -fSL --progress-bar "$RELEASE_URL/trailblaze.jar" -o "$JAR_STAGE"

# Zip entries carry no exec bit, hence the chmod.
if extract_jar_entry "$JAR_STAGE" trailblaze-launcher "$LAUNCHER_STAGE"; then
  info "Extracted the launcher from trailblaze.jar"
else
  # Releases from before the launcher was embedded in the JAR publish it as its own asset.
  info "Downloading launcher script..."
  curl -fsSL "$RELEASE_URL/trailblaze" -o "$LAUNCHER_STAGE" ||
    error "Could not extract 'trailblaze-launcher' from trailblaze.jar (that needs unzip, bsdtar, or python3), and $TAG publishes no separate launcher."
fi
chmod +x "$LAUNCHER_STAGE"
# The launcher first: a new launcher can still start the old JAR, but an old one may not know
# how to start the new JAR.
mv -f "$LAUNCHER_STAGE" "$BIN_DIR/trailblaze"
mv -f "$JAR_STAGE" "$BIN_DIR/trailblaze.jar"

# ---------------------------------------------------------------------------
# PATH setup
# ---------------------------------------------------------------------------

SHELL_NAME=$(basename "${SHELL:-/bin/bash}")
case "$SHELL_NAME" in
  zsh)  RC_FILE="$HOME/.zshrc" ;;
  bash) RC_FILE="$HOME/.bashrc" ;;
  *)    RC_FILE="" ;;
esac

PATH_LINE="export PATH=\"$BIN_DIR:\$PATH\""
ALREADY_IN_PATH=false

if echo "$PATH" | tr ':' '\n' | grep -qx "$BIN_DIR"; then
  ALREADY_IN_PATH=true
fi

if [ "$ALREADY_IN_PATH" = false ] && [ -n "$RC_FILE" ]; then
  if ! grep -qF "$BIN_DIR" "$RC_FILE" 2>/dev/null; then
    echo "" >> "$RC_FILE"
    echo "# Trailblaze" >> "$RC_FILE"
    echo "$PATH_LINE" >> "$RC_FILE"
    info "Added $BIN_DIR to PATH in $RC_FILE"
  fi
fi

# ---------------------------------------------------------------------------
# Done
# ---------------------------------------------------------------------------

echo ""
echo "Trailblaze $VERSION installed to $BIN_DIR"
echo ""
if [ "$ALREADY_IN_PATH" = false ]; then
  if [ -n "$RC_FILE" ]; then
    echo "To start using it now, run:"
    echo ""
    echo "  source $RC_FILE"
  else
    echo "To start using it now, run:"
    echo ""
    echo "  $PATH_LINE"
  fi
  echo ""
fi
echo "Then:"
echo "  trailblaze            # Start Trailblaze"
echo "  trailblaze --help     # See all commands"
