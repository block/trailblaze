package xyz.block.trailblaze.llm.config

import xyz.block.trailblaze.util.Console
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Builds a [ConfigResourceSource] that layers the classpath under a workspace's
 * `trails/config/` directory and its `dist/` compile output.
 *
 * The layering shape is:
 *  1. [ClasspathConfigResourceSource] — framework defaults bundled into the jars.
 *  2. `FilesystemConfigResourceSource(rootDir = configDir)` — hand-authored target /
 *     toolset / tool YAMLs the user edits directly under `trails/config/`.
 *  3. `FilesystemConfigResourceSource(rootDir = configDir/dist)` — materialized YAMLs
 *     emitted by `trailblaze compile` (and the daemon-init `WorkspaceCompileBootstrap`).
 *     LAST so generated targets win on filename collisions — trailmaps are the
 *     authoritative source for any trailmap-backed target, and a stale hand-authored copy
 *     from before the trailmap migration shouldn't shadow the freshly-compiled output.
 *
 * `configDir` MUST be the already-resolved workspace `trails/config/` directory — pass
 * what `TrailblazeWorkspaceConfigResolver.resolve(...).configDir` (or
 * `TrailblazeSettingsRepo.getCurrentTrailblazeConfigDir()`) returns. Deliberately NOT a
 * `trailsDir/trails-root` so the helper doesn't bake in the WORKSPACE_CONFIG_SUBDIR
 * convention — different bootstraps may resolve the config dir via different paths
 * (env-var override, walk-up search, etc.) and the helper stays oblivious to that
 * resolution policy.
 *
 * `null` `configDir` falls back to [ClasspathConfigResourceSource] — i.e. the daemon
 * was started outside a workspace root. Callers don't need to special-case "no workspace."
 *
 * `logPrefix` is included in the layering log line for grep-discoverability — keep the
 * caller's existing prefix (e.g. `"[AppTargetDiscovery]"`, `"[ToolDiscoveryToolSet]"`)
 * so logs from this code path show up under the call site that produced them.
 */
fun workspaceLayeredConfigResourceSource(
  configDir: File?,
  logPrefix: String = "[WorkspaceLayeredConfigResourceSource]",
): ConfigResourceSource {
  if (configDir == null || !configDir.isDirectory) return ClasspathConfigResourceSource
  // Log once per (logPrefix, configDir) pair so an empty `toolbox trailheads` result has an
  // observable trail back to "did we even find your workspace?" — but DON'T log on every
  // discovery call. Since PR #3518 routed every discovery default through this factory, the
  // unconditional log produced one line per `ToolYamlLoader.discoverAndLoadAll(...)` and per
  // role-listing in the MCP daemon. Logging once per call-site/configDir keeps the original
  // diagnostic signal (one line per fresh layering decision) without the per-call noise.
  val logKey = "$logPrefix:${configDir.absolutePath}"
  if (layeringLogged.add(logKey)) {
    Console.log("$logPrefix Layering user config from: ${configDir.absolutePath}")
  }
  return CompositeConfigResourceSource(
    sources = listOf(
      ClasspathConfigResourceSource,
      FilesystemConfigResourceSource(rootDir = configDir),
      FilesystemConfigResourceSource(
        rootDir = File(configDir, TrailblazeConfigPaths.WORKSPACE_DIST_SUBDIR),
      ),
    ),
  )
}

/**
 * Set-once-per-process dedup key store for the layering log line above. Sized to the
 * realistic upper bound — a process talks to a small handful of call-site prefixes
 * (`[platformConfigResourceSource]`, `[AppTargetDiscovery]`, `[ToolDiscoveryToolSet]`, …)
 * crossed with at most one workspace at a time.
 */
private val layeringLogged: MutableSet<String> = ConcurrentHashMap.newKeySet()
