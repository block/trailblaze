#!/bin/bash

# Shared JAR caching logic for development launchers (blaze, trailblaze).
# Builds an uber JAR and only rebuilds when source files change.
#
# Usage:
#   source "$SCRIPT_DIR/scripts/dev-jar-cache.sh"
#   JAR_DIR=$(dev_jar_dir)
#   if dev_ensure_jar "$JAR_DIR"; then
#     echo "JAR ready at $DEV_JAR_PATH"
#   fi

# Repo root that owns the launcher — every git / gradle / JAR-path operation in
# this file is anchored here, never the caller's cwd. MCP clients (Claude Code,
# Cursor, etc.) spawn `trailblaze mcp` with the *client project's* cwd; before
# this anchor existed, the git calls below silently failed there, the source
# hash came out wrong, and the launcher declared the JAR stale — stopping a
# healthy running daemon and then dying on a `./gradlew` that doesn't exist in
# that cwd. The launcher wrapper (`$TRAILBLAZE_LAUNCHER`) always sits at the
# repo root the user invokes builds from, in both the standalone layout and a
# monorepo that nests this tree, so its directory is the correct gradle root.
# Fallback (launcher env unset): this file lives at `<root>/scripts/`, so walk
# up one level from the sourced file's own location.
if [ -n "${TRAILBLAZE_LAUNCHER:-}" ] && [ -f "$TRAILBLAZE_LAUNCHER" ]; then
  DEV_JAR_REPO_ROOT="$(cd "$(dirname "$TRAILBLAZE_LAUNCHER")" && pwd)"
else
  DEV_JAR_REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
fi

# Source fingerprint: HEAD commit + content hash of modified/new build-relevant
# files (`git diff` for tracked, file stat for untracked). ~0.2s.
#
# Scope = files that invalidate the JVM uber JAR:
#  - Kotlin/Java sources + build config anywhere (`.kt`/`.kts`/`.java`/
#    `.properties`/`.toml`/`.xml`/`.pro`).
#  - YAML/JSON/HTML/TS/JS *classpath resources* under `**/src/**/resources/**`
#    (tool descriptors, provider configs, bundled framework trailmaps, etc.
#    that get baked straight into the JAR).
#  - The TypeScript SDK source (`sdks/typescript/{src,tools}/**`,
#    `package.json`, `bun.lock`, `runtime-globals.d.ts`). Lives under `src/`
#    but not `src/**/resources/**`, so it needs its own entry — yet
#    `bundleTrailblazeSdkDts`/`bundleTrailblazeSdk`/`bundleScriptedToolAnalyzerShim`
#    (TrailblazeSdkDtsBundlePlugin.kt / TrailblazeSdkBundlePlugin.kt) bake
#    derived bundles from it into the JAR. Without this entry, an SDK edit
#    doesn't invalidate the cached JAR and `./trailblaze check` silently
#    type-checks against a stale declaration surface — keep this list in sync
#    with those plugins' Gradle inputs, and with the untracked-file `grep`
#    below.
#
#    The leading `*` is a git pathspec, not a shell glob — it crosses `/` by
#    default, so `*sdks/typescript/src/*` matches both a repo-root and a
#    nested monorepo layout with no `**` needed (verified against this repo).
#    `dist/` and `node_modules/` are gitignored, so they're excluded already.
#
# Excluded: trailmap-author content (`trails/config/trailmaps/**`,
# `examples/**`) — inputs to the running daemon, not the JAR build; rebuilding
# on every author save killed the edit-test loop (60s → sub-second). Also
# excluded: generated `.trailblaze/**` artifacts.
# Prints nothing (instead of a hash of empty input) when the repo root isn't a
# usable git checkout — callers treat an empty hash as "unknown", which must
# never be conflated with "changed" (see dev_ensure_jar).
dev_source_hash() {
  git -C "$DEV_JAR_REPO_ROOT" rev-parse HEAD >/dev/null 2>&1 || return 0
  (
    cd "$DEV_JAR_REPO_ROOT" || exit 0
    {
    git rev-parse HEAD
    # Content diff of tracked files (catches edits to already-dirty files).
    git diff HEAD -- \
      '*.kt' '*.kts' '*.java' '*.properties' '*.toml' '*.xml' '*.pro' \
      '**/src/**/resources/**/*.yaml' '**/src/**/resources/**/*.yml' \
      '**/src/**/resources/**/*.json' '**/src/**/resources/**/*.html' \
      '**/src/**/resources/**/*.ts' '**/src/**/resources/**/*.tsx' \
      '**/src/**/resources/**/*.js' '**/src/**/resources/**/*.jsx' \
      '**/src/**/resources/**/*.mjs' '**/src/**/resources/**/*.cjs' \
      '*sdks/typescript/src/*' '*sdks/typescript/tools/*' \
      '*sdks/typescript/package.json' '*sdks/typescript/bun.lock' \
      '*sdks/typescript/runtime-globals.d.ts' \
      ':!.trailblaze/**' ':!**/.trailblaze/**' 2>/dev/null
    # Untracked files: list names + sizes so new files are detected. Same
    # scope as the diff filter above (grep regex instead of pathspec, since
    # this leg's input is a plain filename list) — keep both in sync.
    git ls-files --others --exclude-standard \
      | grep -E '\.(kt|kts|java|properties|toml|xml|pro)$|(^|/)src/.*/resources/.*\.(yaml|yml|json|html|ts|tsx|js|jsx|mjs|cjs)$|(^|/)sdks/typescript/(src|tools)/|(^|/)sdks/typescript/(package\.json|bun\.lock|runtime-globals\.d\.ts)$' \
      | grep -vE '(^|/)\.trailblaze/' \
      | while read -r f; do stat -f '%N %z' "$f" 2>/dev/null || stat --format='%n %s' "$f" 2>/dev/null; done
    } | if command -v sha256sum >/dev/null 2>&1; then sha256sum; else shasum -a 256; fi | cut -d' ' -f1
  )
}

# Map TRAILBLAZE_MODULE to the filesystem JAR directory (absolute, so JAR
# lookup works regardless of the caller's cwd).
dev_jar_dir() {
  local module_dir="${TRAILBLAZE_MODULE#:}"
  module_dir="${module_dir//://}"
  echo "$DEV_JAR_REPO_ROOT/$module_dir/build/compose/jars"
}

# Find the most recent JAR in the given directory.
dev_find_jar() {
  ls -t "$1"/*.jar 2>/dev/null | head -1
}

# Ensure the uber JAR is up-to-date.
# On success: sets DEV_JAR_PATH and returns 0.
# On failure: clears DEV_JAR_PATH and returns 1 (caller should fall back to Gradle).
dev_ensure_jar() {
  local jar_dir="$1"
  local jar_path=$(dev_find_jar "$jar_dir")
  local hash_file="$jar_dir/.blaze-source-hash"
  local current_hash=$(dev_source_hash)
  local stored_hash=""
  [ -f "$hash_file" ] && stored_hash=$(cat "$hash_file")

  local need_build=false
  if [ -z "$jar_path" ]; then
    echo "Building uber JAR (first time, this may take a minute)..." >&2
    need_build=true
  elif [ -z "$current_hash" ]; then
    # Hash unavailable (repo root not a usable git checkout). Unknown is NOT
    # stale: a false "stale" here stops a healthy daemon and forces a rebuild
    # that may not even be possible in this environment. Trust the existing JAR.
    echo "Warning: cannot fingerprint sources at $DEV_JAR_REPO_ROOT — using existing JAR without staleness check." >&2
  elif [ "$current_hash" != "$stored_hash" ]; then
    echo "Source changes detected, rebuilding uber JAR (this may take a minute)..." >&2
    need_build=true
  fi

  if [ "$need_build" = true ]; then
    # Stop the daemon BEFORE building — it has stale code and must not survive into the
    # new JAR. It auto-starts on the next command, so this is safe. EXCEPTION: a daemon
    # with in-flight runs is left running (see the busy-daemon guard below); it picks up
    # the new JAR at its next restart. We confirm the port is free before building only
    # when we actually stopped the daemon.
    local http_port="${TRAILBLAZE_PORT:-52525}"
    local pids
    pids=$(lsof -ti "tcp:$http_port" 2>/dev/null || true)
    # NEVER stop a daemon with in-flight runs (unless TRAILBLAZE_FORCE_DAEMON_STOP is set).
    # The daemon on this port may belong to a DIFFERENT checkout/worktree (jar staleness is
    # per-checkout, the port is machine-global), so "stale from here" can mean "mid-run for
    # someone else" — killing it severs that run (truncated /agentlog uploads, then 'Daemon
    # unreachable' for the victim CLI). A busy daemon is left alone; it picks up the new JAR
    # at its next restart. This staleness-stop only exists in the dev launcher — installed
    # CLIs never rebuild JARs, so none of this applies outside a source checkout.
    if [ -n "$pids" ]; then
      local status_json curl_exit keep_reason=""
      # --max-time bounds the WHOLE transfer, not just the connect (--connect-timeout): a
      # wedged daemon can accept the TCP connection and then hang its /cli/status handler
      # forever, and without --max-time this probe would block dev_ensure_jar indefinitely.
      status_json=$(curl -s --connect-timeout 2 --max-time 5 "http://localhost:$http_port/cli/status" 2>/dev/null)
      curl_exit=$?
      if [ "$curl_exit" -ne 0 ]; then
        # Something is listening (lsof found the pid) but /cli/status didn't answer within the
        # timeout. The most likely cause is a daemon busy running a trail (saturated event
        # loop) — precisely the daemon we must NOT kill. We can't confirm it's idle, so fail
        # CLOSED (keep it) rather than open. A truly wedged daemon is handled by the FORCE
        # override below. NOTE: an old daemon with no /cli/status route answers fast with 404
        # (curl_exit 0), so it still falls through to the stop — only a genuine no-answer keeps.
        keep_reason="it is listening but /cli/status did not respond within the timeout (it may be busy running a trail)"
      else
        local active_runs
        active_runs=$(printf '%s' "$status_json" | sed -n 's/.*"activeRuns"[[:space:]]*:[[:space:]]*\([0-9][0-9]*\).*/\1/p')
        # Empty/absent activeRuns (older daemon, or a genuinely idle one) → no keep_reason →
        # fall through to the stop, same as before.
        if [ -n "$active_runs" ] && [ "$active_runs" -gt 0 ]; then
          keep_reason="stopping it would kill $active_runs in-flight run(s)"
        fi
      fi
      if [ -n "$keep_reason" ]; then
        # tr instead of ${var,,}: macOS ships bash 3.2, which lacks case conversion.
        case "$(printf '%s' "${TRAILBLAZE_FORCE_DAEMON_STOP:-}" | tr '[:upper:]' '[:lower:]')" in
          1|true)
            echo "Daemon on port $http_port: $keep_reason, but TRAILBLAZE_FORCE_DAEMON_STOP is set — stopping it anyway (in-flight runs will fail)." >&2
            ;;
          *)
            echo "NOT stopping the daemon on port $http_port: its code is stale, but $keep_reason." >&2
            # Only parse run details when we actually got a status body (curl_exit 0).
            if [ "$curl_exit" -eq 0 ] && command -v jq >/dev/null 2>&1; then
              printf '%s' "$status_json" | jq -r '(.activeRunSummaries // [])[] | "  - " + .' >&2 2>/dev/null || true
              local daemon_workspace
              daemon_workspace=$(printf '%s' "$status_json" | jq -r '.workspaceAnchor // empty' 2>/dev/null || true)
              [ -n "$daemon_workspace" ] && echo "  daemon workspace: $daemon_workspace" >&2
            fi
            echo "The daemon keeps serving and picks up the newly built JAR at its next (re)start." >&2
            echo "To stop it now anyway: 'trailblaze stop', or re-run with TRAILBLAZE_FORCE_DAEMON_STOP=1." >&2
            pids=""
            ;;
        esac
      fi
    fi
    if [ -n "$pids" ]; then
      echo "Stopping daemon (stale code)..." >&2
      # Path must match CliEndpoints.SHUTDOWN ("/cli/shutdown"). Posting to the
      # bare "/shutdown" lands on the daemon's catchall 404 route — daemon logs
      # `Unhandled route: /shutdown [POST]` and the graceful path silently fails,
      # which then forces us into the `kill -9` fallback below. That SIGKILL'd
      # the daemon mid-Compose-shutdown (the `Killed: 9` line in the wrapper
      # script's stdout). See test DaemonClientShutdownTest for the pin.
      curl -sf -X POST "http://localhost:$http_port/cli/shutdown" > /dev/null 2>&1 || true
      sleep 1
      # Force-kill anything still on the port
      pids=$(lsof -ti "tcp:$http_port" 2>/dev/null || true)
      if [ -n "$pids" ]; then
        echo "$pids" | xargs kill -9 2>/dev/null || true
      fi
      # Wait until port is confirmed free (up to 5s)
      local waited=0
      while [ $waited -lt 5 ]; do
        pids=$(lsof -ti "tcp:$http_port" 2>/dev/null || true)
        [ -z "$pids" ] && break
        sleep 1
        waited=$((waited + 1))
      done
      if [ -n "$pids" ]; then
        echo "Warning: daemon still alive on port $http_port after kill" >&2
      fi
    fi
    (cd "$DEV_JAR_REPO_ROOT" && ./gradlew -q --console=plain "${TRAILBLAZE_MODULE}:packageUberJarForCurrentOS")
    local build_exit=$?
    if [ $build_exit -ne 0 ]; then
      echo "JAR build failed (exit $build_exit). Falling back to Gradle mode." >&2
      DEV_JAR_PATH=""
      return 1
    fi
    jar_path=$(dev_find_jar "$jar_dir")
    if [ -z "$jar_path" ]; then
      echo "JAR not found after build. Falling back to Gradle mode." >&2
      DEV_JAR_PATH=""
      return 1
    fi
    # An empty (unknown) hash is never written: writing it would make the next
    # git-visible invocation always read as "changed".
    [ -n "$current_hash" ] && echo "$current_hash" > "$hash_file"
    dev_prune_stale_siblings "$jar_dir" "$jar_path"
  fi

  DEV_JAR_PATH="$jar_path"
  return 0
}

# Delete old timestamped JARs (and their CDS .jsa siblings) kept around by the
# Compose plugin — we only ever use the newest one, and each old JAR/JSA pair
# is ~270MB + ~30MB of stale debris.
dev_prune_stale_siblings() {
  local jar_dir="$1"
  local keep_jar="$2"
  find "$jar_dir" -maxdepth 1 -type f \( -name '*.jar' -o -name '*.jsa' \) 2>/dev/null \
    | while IFS= read -r f; do
        case "$f" in
          "$keep_jar"|"${keep_jar%.jar}.jsa") ;;  # keep the current pair
          *) rm -f "$f" ;;
        esac
      done
}

# Rebuild the uber JAR so the next default run (JAR mode) starts instantly.
# The Gradle daemon is already warm, so this is just incremental packaging.
dev_update_jar_cache() {
  local jar_dir="$1"
  (cd "$DEV_JAR_REPO_ROOT" && ./gradlew -q --console=plain "${TRAILBLAZE_MODULE}:packageUberJarForCurrentOS")
  if [ $? -eq 0 ]; then
    local hash=$(dev_source_hash)
    [ -n "$hash" ] && echo "$hash" > "$jar_dir/.blaze-source-hash"
    local jar_path
    jar_path=$(dev_find_jar "$jar_dir")
    [ -n "$jar_path" ] && dev_prune_stale_siblings "$jar_dir" "$jar_path"
  fi
}

# JVM args matching production launcher (scripts/trailblaze).
DEV_JVM_ARGS="-Xmx4g"
if [[ "$(uname)" == "Darwin" ]]; then
  DEV_JVM_ARGS="$DEV_JVM_ARGS --add-opens java.desktop/sun.awt=ALL-UNNAMED --add-opens java.desktop/sun.lwawt=ALL-UNNAMED --add-opens java.desktop/sun.lwawt.macosx=ALL-UNNAMED"
fi
