package xyz.block.trailblaze.cli

import java.io.File
import java.nio.file.Path
import java.nio.file.Paths
import xyz.block.trailblaze.api.waypoint.WaypointDefinition
import xyz.block.trailblaze.config.project.LoadedTrailblazeProjectConfig
import xyz.block.trailblaze.config.project.TrailblazeProjectConfig
import xyz.block.trailblaze.config.project.TrailblazeProjectConfigException
import xyz.block.trailblaze.config.project.TrailblazeProjectConfigLoader
import xyz.block.trailblaze.config.project.TrailblazeWorkspaceConfigResolver
import xyz.block.trailblaze.util.Console
import xyz.block.trailblaze.waypoint.WaypointLoader

/**
 * Combined waypoint source for the `trailblaze waypoint` CLI commands.
 *
 * Two contributors:
 *  - The active workspace's pack manifests (workspace `packs:` plus classpath-bundled
 *    framework packs like `clock`/`wikipedia`/`contacts`). Pack-declared `waypoints:`
 *    files are resolved by the pack manifest loader and arrive here as already-parsed
 *    [WaypointDefinition] objects.
 *  - The legacy `--root` filesystem walk over `*.waypoint.yaml` files. Preserved so
 *    users authoring waypoints in a scratch workspace still get listing/locate/validate
 *    against their on-disk drafts.
 *
 * Results are deduplicated by waypoint id — pack waypoints come first, so a user-authored
 * file that shadows a pack waypoint (same id under `--root`) is silently dropped. That
 * matches the existing CLI's "first match wins" loading semantics.
 */
object WaypointDiscovery {

  data class Result(
    /** Deduplicated waypoints — pack-first, then filesystem-walked. */
    val definitions: List<WaypointDefinition>,
    /** Per-file parse failures from the `--root` filesystem walk. */
    val rootFailures: WaypointLoader.LoadResult,
    /**
     * `true` when at least one pack-load attempt (workspace and/or classpath) raised a
     * typed [TrailblazeProjectConfigException] that was caught and logged. Callers can
     * surface a hint in CLI output ("some packs failed — see warnings above") so a
     * silent empty result doesn't look identical to "no packs configured."
     */
    val packLoadFailed: Boolean,
  )

  fun discover(root: File): Result = discover(root = root, fromPath = Paths.get(""))

  /**
   * Test-friendly overload. [fromPath] is the cwd-anchor passed to
   * [TrailblazeWorkspaceConfigResolver.resolve]; production callers default to the
   * JVM's actual working directory via the no-arg [discover]. Tests pass a temp
   * directory so workspace resolution is isolated from whatever directory happens
   * to host the test JVM.
   */
  internal fun discover(root: File, fromPath: Path): Result {
    // Surface obvious user mistakes loudly. WaypointLoader.discover() already returns
    // an empty list for non-directories, but a silent "no waypoints found" can leave
    // the user staring at unhelpful output when their `--root` was a typo or pointed
    // at a file. Pack waypoints still load below regardless of `--root` validity.
    if (root.exists() && !root.isDirectory) {
      Console.error(
        "Warning: --root is not a directory: ${root.absolutePath} " +
          "(filesystem-walk waypoints will be empty; pack waypoints unaffected)",
      )
    }

    val packLoad = loadPackWaypoints(fromPath)
    val filesystemResult = WaypointLoader.loadAllResilient(root)
    val combined = linkedMapOf<String, WaypointDefinition>()
    packLoad.definitions.forEach { def -> combined.putIfAbsent(def.id, def) }
    filesystemResult.definitions.forEach { def -> combined.putIfAbsent(def.id, def) }
    return Result(
      definitions = combined.values.toList(),
      rootFailures = filesystemResult,
      packLoadFailed = packLoad.packLoadFailed,
    )
  }

  /** Internal aggregation: which pack-load attempts hit typed errors during resolution. */
  private data class PackLoadOutcome(
    val definitions: List<WaypointDefinition>,
    val packLoadFailed: Boolean,
  )

  /**
   * Loads pack-bundled waypoints. First tries to resolve the active workspace (so
   * workspace-declared `packs:` contribute too); on any workspace failure, falls back
   * to a classpath-only resolution so framework-bundled packs still load. A malformed
   * workspace `trailblaze.yaml` or a single bad pack ref must not silently drop the
   * bundled `clock`/`wikipedia`/`contacts` waypoints.
   *
   * The boolean carried back via [PackLoadOutcome] tells [discover] whether to mark
   * its [Result.packLoadFailed] flag — caller CLIs use it to print "some packs failed"
   * hints rather than just an empty list.
   */
  private fun loadPackWaypoints(fromPath: Path): PackLoadOutcome {
    var packLoadFailed = false
    // Try workspace + classpath together first.
    val workspaceWaypoints = tryLoadWorkspaceWaypoints(fromPath, onError = { packLoadFailed = true })
    if (workspaceWaypoints != null) {
      return PackLoadOutcome(workspaceWaypoints, packLoadFailed)
    }
    // Workspace path returned null — either no workspace anchor, or a typed error
    // happened (in which case `packLoadFailed` is already set). Fall back to a
    // classpath-only resolution so framework-bundled packs still surface.
    val classpathWaypoints = tryLoadClasspathOnlyWaypoints(onError = { packLoadFailed = true })
    return PackLoadOutcome(classpathWaypoints, packLoadFailed)
  }

  /**
   * Attempts to load waypoints from the active workspace's resolved config (which
   * also pulls in classpath-bundled packs).
   *
   * Returns:
   *  - the resolved waypoints when the workspace anchor exists and loads successfully
   *  - `null` when no workspace anchor exists, OR the workspace config fails to load
   *    with a typed [TrailblazeProjectConfigException] — null signals the caller to
   *    fall back to [tryLoadClasspathOnlyWaypoints]
   *
   * The asymmetric return type vs. [tryLoadClasspathOnlyWaypoints] is intentional:
   * a workspace failure has a meaningful fallback, so it returns a sentinel; a
   * classpath failure has no further fallback, so it returns an empty list directly.
   */
  private fun tryLoadWorkspaceWaypoints(
    fromPath: Path,
    onError: () -> Unit,
  ): List<WaypointDefinition>? {
    return try {
      val workspace = TrailblazeWorkspaceConfigResolver.resolve(fromPath)
      val configFile = workspace.configFile ?: return null
      val resolved = TrailblazeProjectConfigLoader.loadResolvedRuntime(
        configFile = configFile,
        includeClasspathPacks = true,
      )
      resolved?.waypoints
    } catch (e: TrailblazeProjectConfigException) {
      // Typed loader error — workspace config is malformed or a workspace pack ref
      // is broken. Logged at error level so the message stays visible above the
      // CLI's normal output (silent fallback is exactly the kind of thing we don't
      // want to bury in `Console.log`). Classpath-only path runs next.
      // We deliberately do NOT catch generic Exception here so genuine bugs (NPE,
      // ISE, etc.) surface as stack traces instead of being silently swallowed.
      Console.error(
        "Warning: failed to load workspace pack waypoints; falling back to classpath-only: ${e.message}",
      )
      onError()
      null
    }
  }

  /**
   * Attempts to load waypoints from classpath-bundled packs only — used when there is
   * no workspace anchor, or when [tryLoadWorkspaceWaypoints] hit a typed error and
   * fell through.
   *
   * Returns the list directly (never null) because there's no further fallback after
   * this — if classpath discovery itself errors, the CLI proceeds with an empty
   * waypoint set rather than crashing.
   */
  private fun tryLoadClasspathOnlyWaypoints(onError: () -> Unit): List<WaypointDefinition> {
    return try {
      TrailblazeProjectConfigLoader.resolveRuntime(
        loaded = LoadedTrailblazeProjectConfig(
          raw = TrailblazeProjectConfig(),
          sourceFile = File(".").absoluteFile,
        ),
        includeClasspathPacks = true,
      ).waypoints
    } catch (e: TrailblazeProjectConfigException) {
      // Typed loader error from a malformed classpath pack manifest. Logged at error
      // level so it's visible but not fatal — the CLI continues with an empty
      // waypoint set rather than crashing.
      // Generic Exception is intentionally not caught (see tryLoadWorkspaceWaypoints).
      Console.error("Warning: failed to load classpath pack waypoints: ${e.message}")
      onError()
      emptyList()
    }
  }
}
