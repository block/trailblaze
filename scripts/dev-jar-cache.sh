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

# Source fingerprint: HEAD commit + content hash of modified/new build-relevant files.
# Uses `git diff` for tracked files (captures actual content changes) and file stat
# for untracked files. ~0.2s — fast enough for a launcher.
dev_source_hash() {
  {
    git rev-parse HEAD
    # Content diff of tracked files (catches edits to already-dirty files)
    git diff HEAD -- '*.kt' '*.java' '*.kts' '*.properties' '*.toml' '*.xml' '*.json' '*.yaml' '*.yml' '*.pro' '*.sql' 2>/dev/null
    # Untracked files: list names + sizes so new files are detected
    git ls-files --others --exclude-standard \
      | grep -E '\.(kt|java|kts|properties|toml|xml|html|json|yaml|yml|pro|sql)$' \
      | while read -r f; do stat -f '%N %z' "$f" 2>/dev/null || stat --format='%n %s' "$f" 2>/dev/null; done
  } | if command -v sha256sum >/dev/null 2>&1; then sha256sum; else shasum -a 256; fi | cut -d' ' -f1
}

# Map TRAILBLAZE_MODULE to the filesystem JAR directory.
dev_jar_dir() {
  local module_dir="${TRAILBLAZE_MODULE#:}"
  module_dir="${module_dir//://}"
  if [ -d "opensource/$module_dir" ]; then
    module_dir="opensource/$module_dir"
  fi
  echo "$module_dir/build/compose/jars"
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
  elif [ "$current_hash" != "$stored_hash" ]; then
    echo "Source changes detected, rebuilding uber JAR (this may take a minute)..." >&2
    need_build=true
  fi

  if [ "$need_build" = true ]; then
    # Kill the daemon BEFORE building — it has stale code and must not survive
    # into the new JAR. It auto-starts on the next command, so this is safe.
    # We confirm the port is free before proceeding to the build.
    local http_port="${TRAILBLAZE_PORT:-52525}"
    local pids
    pids=$(lsof -ti "tcp:$http_port" 2>/dev/null || true)
    if [ -n "$pids" ]; then
      echo "Stopping daemon (stale code)..." >&2
      curl -sf -X POST "http://localhost:$http_port/shutdown" > /dev/null 2>&1 || true
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
    ./gradlew -q --console=plain "${TRAILBLAZE_MODULE}:packageUberJarForCurrentOS"
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
    echo "$current_hash" > "$hash_file"
  fi

  DEV_JAR_PATH="$jar_path"
  return 0
}

# Rebuild the uber JAR so the next default run (JAR mode) starts instantly.
# The Gradle daemon is already warm, so this is just incremental packaging.
dev_update_jar_cache() {
  local jar_dir="$1"
  ./gradlew -q --console=plain "${TRAILBLAZE_MODULE}:packageUberJarForCurrentOS"
  if [ $? -eq 0 ]; then
    local hash=$(dev_source_hash)
    echo "$hash" > "$jar_dir/.blaze-source-hash"
  fi
}

# JVM args matching production launcher (opensource/scripts/trailblaze).
DEV_JVM_ARGS="-Xmx4g"
if [[ "$(uname)" == "Darwin" ]]; then
  DEV_JVM_ARGS="$DEV_JVM_ARGS --add-opens java.desktop/sun.awt=ALL-UNNAMED --add-opens java.desktop/sun.lwawt=ALL-UNNAMED --add-opens java.desktop/sun.lwawt.macosx=ALL-UNNAMED"
fi
