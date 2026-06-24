#!/usr/bin/env bash
#
# Installs this repo's Git hooks into the active hooks directory.
#
# Currently installs `pre-push`, which refuses to push agent-generated branch
# names (claude/* and codex/*) so temporary worktree branches don't leak into
# the public remote. Run once per clone:
#
#   ./scripts/install-git-hooks.sh
#
# Hooks live in `.git/hooks` (shared across all worktrees of this clone), so a
# single install covers every worktree. Honors `core.hooksPath` if it's set.

set -euo pipefail

repo_root="$(git rev-parse --show-toplevel)"

hooks_path="$(git config --path --get core.hooksPath || true)"
if [ -n "$hooks_path" ]; then
  case "$hooks_path" in
    /*) hook_dir="$hooks_path" ;;
    *) hook_dir="$repo_root/$hooks_path" ;;
  esac
else
  hook_dir="$(dirname "$(git rev-parse --git-path hooks/pre-push)")"
fi

mkdir -p "$hook_dir"
for hook in pre-push; do
  src="$repo_root/scripts/git-hooks/$hook"
  dest="$hook_dir/$hook"
  cp "$src" "$dest"
  chmod +x "$dest"
  echo "Installed $hook hook at $dest"
done
