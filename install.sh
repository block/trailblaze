#!/usr/bin/env bash
# Trailblaze installer
#
# Downloads the latest Trailblaze release (JAR + launcher) from GitHub.
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

check_dependency() {
  command -v "$1" > /dev/null 2>&1 || error "'$1' is required but not found. Please install it first."
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

info "Downloading trailblaze.jar..."
curl -fSL --progress-bar "$RELEASE_URL/trailblaze.jar" -o "$BIN_DIR/trailblaze.jar"

info "Downloading launcher script..."
curl -fsSL "$RELEASE_URL/trailblaze" -o "$BIN_DIR/trailblaze"
chmod +x "$BIN_DIR/trailblaze"

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
  echo "Run this to start using it now:"
  echo "  $PATH_LINE"
  echo ""
fi
echo "Then:"
echo "  trailblaze            # Start Trailblaze"
echo "  trailblaze --help     # See all commands"
