package xyz.block.trailblaze.waypoint

import xyz.block.trailblaze.api.waypoint.WaypointDefinition
import xyz.block.trailblaze.config.project.LoadedTrailblazeProjectConfig
import xyz.block.trailblaze.config.project.ScriptedToolEnrichment
import xyz.block.trailblaze.config.project.TrailblazeProjectConfig
import xyz.block.trailblaze.config.project.TrailblazeProjectConfigLoader
import xyz.block.trailblaze.util.Console
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Host-side resolver of the loaded waypoint registry, used by the `assertWaypoint` framework tool
 * to look up a [WaypointDefinition] by id. Consolidates what used to be two near-identical private
 * helpers (`resolveWaypointsForRun` on the daemon path, `resolveWaypointsForTrail` on the
 * Maestro/iOS-host path) into a single shared, process-cached resolver.
 *
 * Resolves classpath-bundled waypoint trailmaps (`includeClasspathTrailmaps = true`). Bundled
 * trailmaps are the in-tree waypoint coverage shipped with the compiled CLI; the registry is stable
 * for a process, so it's cached per working directory after the first build (the
 * `TrailblazeProjectConfigLoader.resolveRuntime` walk — including the analyzer subprocess for any
 * meta-only scripted-tool descriptors — is not free).
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
 * enrichment, or `resolveRuntime` throws and `build` falls back to the resolves-nothing resolver.
 * `TrailblazeHostYamlRunner`'s `init {}` installs the real provider; because every host trail run
 * (the only path that executes the host-backed `assertWaypoint`) routes through that object first,
 * the provider is installed before any waypoint is resolved. A host entrypoint that resolved the
 * registry WITHOUT ever touching `TrailblazeHostYamlRunner` would see the degraded (bundled-only)
 * behavior — acceptable because it never crashes, only narrows coverage.
 */
object WaypointRegistryResolver {

  /**
   * Host-installed supplier of the analyzer-backed scripted-tool enrichment — see the class kdoc's
   * "Enrichment seam" section for when it matters and why the default is safe. Installed once by
   * `TrailblazeHostYamlRunner`'s `init {}`.
   */
  @Volatile
  var scriptedToolEnrichmentProvider: () -> ScriptedToolEnrichment? = { null }

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

  private fun build(workingDir: File): (String) -> WaypointDefinition? = try {
    val resolved = TrailblazeProjectConfigLoader.resolveRuntime(
      loaded = LoadedTrailblazeProjectConfig(
        raw = TrailblazeProjectConfig(),
        sourceFile = workingDir,
      ),
      includeClasspathTrailmaps = true,
      scriptedToolEnrichment = scriptedToolEnrichmentProvider(),
    )
    val byId: Map<String, WaypointDefinition> = resolved.waypoints.associateBy { it.id }
    // Closure over the id -> definition map. Returns null for unknown ids — assertWaypoint
    // surfaces those with a "check the waypoint id against the loaded trailmaps" hint.
    ({ id -> byId[id] })
  } catch (e: Exception) {
    Console.log(
      "[WaypointRegistryResolver] Waypoint registry failed to load; every assertWaypoint will " +
        "report its waypoint as unknown: ${e.message}",
    )
    ({ _ -> null })
  }
}
