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
 * `TrailblazeHostYamlRunner`'s `init {}` installs the real provider; because every host trail run
 * (the only path that executes the host-backed `assertWaypoint`) routes through that object first,
 * the provider is installed before any waypoint is resolved.
 */
object WaypointRegistryResolver {

  /**
   * Host-installed supplier of the analyzer-backed scripted-tool enrichment — see the class kdoc's
   * "Enrichment seam" section for when it matters and why the default is safe. Installed once by
   * `TrailblazeHostYamlRunner`'s `init {}`.
   */
  @Volatile
  var scriptedToolEnrichmentProvider: () -> ScriptedToolEnrichment? = { null }

  /**
   * Host-installed supplier of the active workspace `trails/config/` directory — the same source
   * (`WorkspaceConfigDirHolder`) that `platformConfigResourceSource` and target/tool discovery read,
   * so a workspace selected in the desktop app / Trail Runner (which installs it without changing
   * the JVM working directory) is honored here too. Defaults to `{ null }`; `WorkspaceConfigDirHolder`
   * lives in `trailblaze-models` (JVM-only), so the host installs the delegation from
   * `TrailblazeHostYamlRunner`'s `init {}` rather than this common module importing it directly.
   * When it returns null, resolution falls back to the working-directory walk-up.
   *
   * Install once at startup, before any waypoint is resolved: the built resolver is cached per
   * working directory ([resolver]), so reassigning this after the first resolve for a directory has
   * no effect until [clearCache] is called (tests do exactly that).
   */
  @Volatile
  var workspaceConfigDirProvider: () -> File? = { null }

  // Keyed by absolute working-dir path. A long-lived daemon keeps a stable CWD, so this is
  // effectively a single entry; keying by path keeps it correct if a host ever resolves from
  // more than one workspace root in the same process.
  private val cache = ConcurrentHashMap<String, (String) -> WaypointDefinition?>()

  /** Returns an id -> [WaypointDefinition] resolver for the current working directory. */
  fun resolver(): (String) -> WaypointDefinition? {
    val workingDir = File(".").absoluteFile
    return cache.getOrPut(workingDir.path) { build(workingDir) }
  }

  /** Drops the cached resolver(s). Primarily for tests. */
  fun clearCache() = cache.clear()

  /**
   * Builds the id -> definition resolver for [workingDir]. `internal` (not private) so a test can
   * drive it against a temp-workspace fixture without depending on the process working directory
   * that [resolver] uses.
   */
  internal fun build(workingDir: File): (String) -> WaypointDefinition? {
    val enrichment = scriptedToolEnrichmentProvider()
    // Active workspace (holder) first — honors a desktop/Trail Runner selection that doesn't move
    // the JVM cwd. Then the working-dir walk-up (env + cwd). Then classpath-only so framework
    // waypoints still resolve when there's no workspace anchor at all. The `?:` chain short-circuits
    // on the first non-null tier (so a resolvable workspace is loaded once, not three times), and
    // `build` itself runs once per working dir — [resolver] caches the result — so this whole walk
    // (including any analyzer subprocess) is amortized to one-time per JVM, not per assertWaypoint.
    val resolved = loadFromActiveWorkspace(enrichment)
      ?: loadFromWorkspaceWalkUp(workingDir, enrichment)
      ?: loadClasspathOnly(workingDir, enrichment)
    val byId: Map<String, WaypointDefinition> = resolved?.waypoints?.associateBy { it.id } ?: emptyMap()
    // Closure over the id -> definition map. Returns null for unknown ids — assertWaypoint
    // surfaces those with a "check the waypoint id against the loaded trailmaps" hint.
    return { id -> byId[id] }
  }

  /**
   * Loads via the host-installed active-workspace dir ([workspaceConfigDirProvider]) — the same
   * source target/tool discovery uses, so a desktop/Trail Runner workspace selection is honored.
   * Returns `null` when no provider is installed, it reports no dir, the dir has no
   * `trailblaze.yaml`, or the load fails — the caller then falls back to [loadFromWorkspaceWalkUp].
   */
  private fun loadFromActiveWorkspace(enrichment: ScriptedToolEnrichment?): TrailblazeResolvedConfig? {
    val configDir = workspaceConfigDirProvider() ?: return null
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
