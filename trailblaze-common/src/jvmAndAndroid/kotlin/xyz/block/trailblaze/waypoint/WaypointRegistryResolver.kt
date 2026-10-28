package xyz.block.trailblaze.waypoint

import xyz.block.trailblaze.api.waypoint.WaypointDefinition
import xyz.block.trailblaze.config.project.LoadedTrailblazeProjectConfig
import xyz.block.trailblaze.config.project.ScriptedToolEnrichment
import xyz.block.trailblaze.config.project.TrailblazeProjectConfig
import xyz.block.trailblaze.config.project.TrailblazeProjectConfigLoader
import xyz.block.trailblaze.config.project.TrailblazeResolvedConfig
import xyz.block.trailblaze.config.project.TrailblazeWorkspaceConfigResolver
import xyz.block.trailblaze.llm.config.TrailblazeConfigPaths
import xyz.block.trailblaze.util.Console
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Host-side resolver of the loaded waypoint registry, used by the `assertWaypoint` framework tool
 * to look up a [WaypointDefinition] by id. Consolidates what used to be two near-identical private
 * helpers (`resolveWaypointsForRun` on the daemon path, `resolveWaypointsForTrail` on the
 * Maestro/iOS-host path) into a single shared, process-cached resolver.
 *
 * ## What it resolves
 *
 * Resolves the active **workspace** config so its declared `targets:` pull each app trailmap (and
 * its waypoints) in from the classpath — that's what lets an app waypoint such as
 * `square/ios/more-tab-no-sheet` resolve on a host-orchestrated run. It finds that workspace in
 * priority order:
 *
 *  1. **[workspaceConfigDirProvider]** — the host-installed active `trails/config/` dir. This is the
 *     canonical source that target/tool discovery uses (`WorkspaceConfigDirHolder` /
 *     `platformConfigResourceSource`), so it honors a workspace selected in the desktop app / Trail
 *     Runner, which installs it *without* changing the JVM working directory.
 *  2. **Working-directory walk-up** — `TrailblazeWorkspaceConfigResolver.resolve` from the working
 *     dir (also honors `TRAILBLAZE_CONFIG_DIR`). Covers callers that don't install the holder (CLI,
 *     the on-demand generated-test path).
 *  3. **Classpath-only** — no workspace anchor at all; the framework stdlib's bundled waypoints
 *     still resolve.
 *
 * This mirrors how the `waypoint` CLI commands (`WaypointDiscovery`) and host target discovery
 * (`AppTargetDiscovery`) load waypoints.
 *
 * The registry is stable for a process, so it's cached per working directory after the first build
 * (the `resolveRuntime` walk — including the analyzer subprocess for any meta-only scripted-tool
 * descriptors — is not free).
 *
 * Failure-tolerant by construction: a loader error returns a resolver that resolves nothing rather
 * than throwing, so a malformed trailmap manifest can't block trail execution. The `assertWaypoint`
 * tool then surfaces an unresolved id as a clear "unknown waypoint" tool error (and, when the whole
 * registry failed to load, points the operator at the `[WaypointRegistryResolver]` log line below).
 *
 * ## Enrichment seam (host-installed)
 *
 * [scriptedToolEnrichmentProvider] is the one piece a host must wire. It defaults to `{ null }`, and
 * with it unset the registry still loads for all classpath-bundled waypoints and for any workspace
 * that doesn't carry meta-only scripted-tool descriptors — so the common case needs no setup. Only a
 * workspace whose trailmaps contain meta-only scripted-tool descriptors needs the analyzer
 * enrichment, or `resolveRuntime` fails and `build` falls back (or resolves nothing).
 * The host's `HostWaypointWiring.ensureInstalled()` installs the real provider; every host entry
 * point (`TrailblazeHostYamlRunner` and the standalone JUnit `BaseHostTrailblazeTest`) calls it
 * first, so the provider is installed before any waypoint is resolved.
 */
object WaypointRegistryResolver {

  /**
   * Host-installed supplier of the analyzer-backed scripted-tool enrichment — see the class kdoc's
   * "Enrichment seam" section for when it matters and why the default is safe. Installed once by
   * the host's `HostWaypointWiring`.
   */
  @Volatile
  var scriptedToolEnrichmentProvider: () -> ScriptedToolEnrichment? = { null }

  /**
   * Host-installed supplier of the active workspace `trails/config/` directory — the same source
   * (`WorkspaceConfigDirHolder`) that `platformConfigResourceSource` and target/tool discovery read,
   * so a workspace selected in the desktop app / Trail Runner (which installs it without changing
   * the JVM working directory) is honored here too. Defaults to `{ null }`; `WorkspaceConfigDirHolder`
   * lives in `trailblaze-models` (JVM-only), so the host installs the delegation from
   * `HostWaypointWiring` rather than this common module importing it directly.
   * When it returns null, resolution falls back to the working-directory walk-up.
   *
   * Called on every resolve: the built registry is cached per working directory AND the directory
   * this returns, so selecting another workspace in a running daemon loads that workspace's
   * waypoints rather than reusing the first one's.
   */
  @Volatile
  var workspaceConfigDirProvider: () -> File? = { null }

  // Keyed by the active workspace dir plus the absolute working-dir path. A long-lived daemon keeps
  // a stable CWD but can switch workspaces, so both are part of the key.
  private val cache = ConcurrentHashMap<String, Registry>()

  /**
   * [loaded] is false when every source failed, or when the selected workspace failed and a fallback
   * stood in for it (and may lack its waypoints). Such a registry is not cached, so it is retried.
   */
  private class Registry(
    val byId: Map<String, WaypointDefinition>,
    val loaded: Boolean,
    /** Target id -> the id of the trailmap that declares it, where the two differ (`target.id`). */
    val trailmapIdByTargetId: Map<String, String> = emptyMap(),
  ) {
    val resolve: (String) -> WaypointDefinition? = { id -> byId[id] }
  }

  /** Returns an id -> [WaypointDefinition] resolver for the current working directory. */
  fun resolver(): (String) -> WaypointDefinition? = registry().resolve

  /**
   * The waypoints of the trailmap that declares [targetId]: ids under `<trailmap id>/`, which is
   * the target id unless the trailmap sets its own `target.id`. For callers that ask which ones a
   * screen matches. Throws when the registry failed to load, so a caller can tell that from a
   * target with no waypoints.
   */
  fun forTarget(targetId: String): Collection<WaypointDefinition> {
    val registry = registry()
    check(registry.loaded) { "see the '[WaypointRegistryResolver]' log line" }
    val prefix = "${registry.trailmapIdByTargetId[targetId] ?: targetId}/"
    return registry.byId.values.filter { it.id.startsWith(prefix) }
  }

  private fun registry(): Registry {
    val workingDir = File(".").absoluteFile
    // Read the selection once, so the key and the load agree even if it changes mid-load.
    val configDir = workspaceConfigDirProvider()
    val key = "${configDir?.absolutePath}|${workingDir.path}"
    cache[key]?.let { return it }
    // A failed load is not cached, so the next resolve retries once the workspace or analyzer recovers.
    return load(workingDir, configDir).also { if (it.loaded) cache.putIfAbsent(key, it) }
  }

  /** Drops the cached resolver(s). Primarily for tests. */
  fun clearCache() = cache.clear()

  /**
   * Builds the id -> definition resolver for [workingDir]. `internal` (not private) so a test can
   * drive it against a temp-workspace fixture without depending on the process working directory
   * that [resolver] uses.
   */
  internal fun build(workingDir: File): (String) -> WaypointDefinition? =
    load(workingDir, workspaceConfigDirProvider()).resolve

  private fun load(workingDir: File, configDir: File?): Registry {
    val enrichment = scriptedToolEnrichmentProvider()
    // Active workspace (holder) first — honors a desktop/Trail Runner selection that doesn't move
    // the JVM cwd. Then the working-dir walk-up (env + cwd). Then classpath-only so framework
    // waypoints still resolve when there's no workspace anchor at all. The `?:` chain short-circuits
    // on the first non-null tier (so a resolvable workspace is loaded once, not three times), and
    // `build` itself runs once per working dir and workspace — [resolver] caches the result — so this whole walk
    // (including any analyzer subprocess) is amortized to one-time per JVM, not per assertWaypoint.
    val active = loadFromActiveWorkspace(configDir, enrichment)
    val activeFailed = active == null && configDir != null && File(configDir, TrailblazeConfigPaths.CONFIG_FILENAME).isFile
    val resolved = active
      ?: loadFromWorkspaceWalkUp(workingDir, enrichment)
      ?: loadClasspathOnly(workingDir, enrichment)
    // Unknown ids resolve to null — assertWaypoint surfaces those with a "check the waypoint id
    // against the loaded trailmaps" hint.
    return Registry(
      byId = resolved?.waypoints?.associateBy { it.id }.orEmpty(),
      loaded = resolved != null && !activeFailed,
      trailmapIdByTargetId = resolved?.resolvedTrailmaps.orEmpty()
        .mapNotNull { trailmap -> trailmap.target?.id?.takeIf { it != trailmap.manifest.id }?.let { it to trailmap.manifest.id } }
        .toMap(),
    )
  }

  /**
   * Loads via the host-installed active-workspace dir ([workspaceConfigDirProvider]) — the same
   * source target/tool discovery uses, so a desktop/Trail Runner workspace selection is honored.
   * Returns `null` when no provider is installed, it reports no dir, the dir has no
   * `trailblaze.yaml`, or the load fails — the caller then falls back to [loadFromWorkspaceWalkUp].
   */
  private fun loadFromActiveWorkspace(configDir: File?, enrichment: ScriptedToolEnrichment?): TrailblazeResolvedConfig? {
    configDir ?: return null
    val configFile = File(configDir, TrailblazeConfigPaths.CONFIG_FILENAME)
    if (!configFile.isFile) return null
    return try {
      TrailblazeProjectConfigLoader.loadResolvedRuntime(
        configFile = configFile,
        includeClasspathTrailmaps = true,
        scriptedToolEnrichment = enrichment,
      )
    } catch (e: Exception) {
      Console.log(
        "[WaypointRegistryResolver] Active-workspace waypoint load failed ($configFile); " +
          "falling back to working-dir walk-up: ${e.message}",
      )
      null
    }
  }

  /**
   * Loads via the workspace anchor found by walking up from [workingDir] to
   * `trails/config/trailblaze.yaml` (also honors `TRAILBLAZE_CONFIG_DIR`). Returns `null` when
   * there's no workspace anchor OR the load fails — the caller then falls back to
   * [loadClasspathOnly].
   */
  private fun loadFromWorkspaceWalkUp(
    workingDir: File,
    enrichment: ScriptedToolEnrichment?,
  ): TrailblazeResolvedConfig? = try {
    TrailblazeWorkspaceConfigResolver.resolve(workingDir.toPath())
      .loadResolvedRuntime(scriptedToolEnrichment = enrichment)
  } catch (e: Exception) {
    Console.log(
      "[WaypointRegistryResolver] Workspace waypoint load failed; falling back to " +
        "classpath-only: ${e.message}",
    )
    null
  }

  /** Classpath-bundled (framework stdlib) waypoints only. Returns `null` (with a log) on failure. */
  private fun loadClasspathOnly(
    workingDir: File,
    enrichment: ScriptedToolEnrichment?,
  ): TrailblazeResolvedConfig? = try {
    TrailblazeProjectConfigLoader.resolveRuntime(
      loaded = LoadedTrailblazeProjectConfig(
        raw = TrailblazeProjectConfig(),
        sourceFile = workingDir,
      ),
      includeClasspathTrailmaps = true,
      scriptedToolEnrichment = enrichment,
    )
  } catch (e: Exception) {
    Console.log(
      "[WaypointRegistryResolver] Waypoint registry failed to load; every assertWaypoint will " +
        "report its waypoint as unknown: ${e.message}",
    )
    null
  }
}
