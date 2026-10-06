package xyz.block.trailblaze.config

import java.io.File
import java.nio.file.Path
import xyz.block.trailblaze.config.project.TrailblazeTrailmapManifestLoader
import xyz.block.trailblaze.config.project.TrailblazeWorkspaceConfigResolver
import xyz.block.trailblaze.config.project.WorkspaceRoot
import xyz.block.trailblaze.llm.config.TrailblazeConfigPaths
import xyz.block.trailblaze.model.TrailblazeHostAppTarget

/**
 * Keeps a workspace's own trailmaps ahead of every other copy a daemon might be serving.
 *
 * A daemon loads its targets once, from the workspace it was anchored at. The CLI also ships some
 * trailmaps on its classpath, and those fill in any target id the daemon's workspace did not load.
 * So a command run from a workspace that has its own copy of a trailmap can silently get a
 * different copy — the bundled one, or another checkout's — when the daemon was started from
 * another directory, is anchored at another workspace, or failed to load the workspace copy. A
 * stale copy that runs looks exactly like a pass, so commands refuse instead: [shadowedFor] finds
 * those trailmaps and [refusal] says how to fix it.
 *
 * Only the target a command uses is checked — its trailmap and every trailmap it depends on — so a
 * broken trailmap elsewhere in the workspace does not block unrelated commands. Target ids match
 * ignoring case, as target lookup does: the CLI lowercases the ids it is given.
 *
 * The caller's workspace is resolved from its directory and the `TRAILBLAZE_CONFIG_DIR` the CALLER
 * set, passed as `callerConfigDir`. This process's own environment is never read: in the daemon it
 * is the daemon's, and would name the daemon's workspace as the caller's.
 */
object ServedTrailmaps {

  /** Target id → [originsOf] that target, for every target the daemon serves. */
  fun of(targets: Collection<TrailblazeHostAppTarget>): Map<String, Map<String, String?>> =
    targets.associate { it.id to originsOf(it) }

  /**
   * Where [target]'s trailmap and its dependencies were loaded from: manifest id → canonical
   * directory, or null for a copy bundled with the CLI. Empty for a target that came from no
   * trailmap: a prebuilt target file, or one defined in code.
   */
  fun originsOf(target: TrailblazeHostAppTarget): Map<String, String?> =
    (target as? YamlBackedHostAppTarget)?.trailmapDirs
      ?.mapValues { (_, dir) -> dir?.let(::canonicalPath) }
      .orEmpty()

  /** A trailmap in the caller's workspace that the daemon is serving from somewhere else. */
  data class Shadowed(
    val id: String,
    val workspaceDir: String,
    /** The trailmap directory the daemon's copy came from; null when it came from no trailmap directory. */
    val servedFrom: String?,
  )

  /**
   * The caller's workspace trailmaps that target [targetId] would run from another copy, when the
   * daemon serves it with [origins] (see [originsOf]).
   *
   * Starts from the workspace trailmap that declares [targetId] (its `target.id`, else its manifest
   * id) and follows its `dependencies:` through the workspace. A dependency the workspace does not
   * have comes from elsewhere by design, so it is not checked. Empty when [targetId] is null, the
   * workspace declares no such target, or [callerDir] is not inside a workspace.
   */
  fun shadowedFor(
    callerDir: Path,
    targetId: String?,
    origins: Map<String, String?>,
    callerConfigDir: String? = null,
  ): List<Shadowed> {
    targetId ?: return emptyList()
    val workspace = workspaceTrailmaps(callerDir, callerConfigDir)
    val root = workspace.firstOrNull { it.targetId.equals(targetId, ignoreCase = true) } ?: return emptyList()
    val byId = workspace.associateBy { it.id }
    val used = linkedMapOf<String, WorkspaceTrailmap>()
    val pending = ArrayDeque(listOf(root.id))
    while (pending.isNotEmpty()) {
      val trailmap = byId[pending.removeFirst()] ?: continue
      if (used.putIfAbsent(trailmap.id, trailmap) == null) pending.addAll(trailmap.dependencies)
    }
    // A target from no trailmap tells nothing about its dependencies: report the target alone.
    val checked = if (origins.isEmpty()) listOf(root) else used.values
    return checked.mapNotNull { trailmap ->
      val servedFrom = origins[trailmap.id]
      if (servedFrom == trailmap.dir) null else Shadowed(trailmap.id, trailmap.dir, servedFrom)
    }
  }

  /**
   * [shadowedFor] against a daemon reporting [served] (see [of]). Empty when the daemon does not
   * serve [targetId] at all — nothing stands in for it, so the command fails with "not found" — or
   * [served] is null (a daemon too old to report its sources).
   */
  fun shadowedIn(
    callerDir: Path,
    targetId: String?,
    served: Map<String, Map<String, String?>>?,
    callerConfigDir: String? = null,
  ): List<Shadowed> {
    targetId ?: return emptyList()
    val origins = served?.entries?.firstOrNull { it.key.equals(targetId, ignoreCase = true) }?.value
      ?: return emptyList()
    return shadowedFor(callerDir, targetId, origins, callerConfigDir)
  }

  /** Whether [callerDir]'s workspace has its own trailmap declaring target [targetId]. */
  fun declaresTarget(callerDir: Path, targetId: String, callerConfigDir: String? = null): Boolean =
    workspaceTrailmaps(callerDir, callerConfigDir).any { it.targetId.equals(targetId, ignoreCase = true) }

  /** Whether [callerDir]'s workspace has any trailmaps of its own. */
  fun hasWorkspaceTrailmaps(callerDir: Path, callerConfigDir: String? = null): Boolean =
    workspaceTrailmaps(callerDir, callerConfigDir).isNotEmpty()

  /**
   * The error a command prints instead of using target [targetId] on a daemon too old to say where
   * its trailmaps came from (see [declaresTarget]).
   */
  fun unverifiableRefusal(targetId: String): String = buildString {
    appendLine("✗ The running Trailblaze daemon is too old to say which copy of `$targetId` it would use")
    appendLine("  This workspace has its own `$targetId` trailmap, and the daemon may run another copy.")
    appendLine("  Restart it on this version: `trailblaze app --stop`, then re-run your command.")
  }.trimEnd()

  /** The error a command prints instead of running against [shadowed] copies. */
  fun refusal(shadowed: List<Shadowed>): String = buildString {
    appendLine("✗ This workspace has its own copy of a trailmap the Trailblaze daemon is not using")
    shadowed.forEach { (id, workspaceDir, servedFrom) ->
      appendLine("  $id")
      appendLine("    yours:   $workspaceDir")
      appendLine("    daemon:  ${servedFrom ?: "a bundled or prebuilt copy"}")
    }
    appendLine("  Running anyway would use the daemon's copy, not your files.")
    appendLine("  Point the daemon at this workspace: run `trailblaze app` from here, or")
    appendLine("  `trailblaze app --stop` and re-run your command.")
    if (shadowed.any { it.servedFrom == null }) {
      appendLine("  If the daemon already runs from here, your trailmap failed to load and another")
      appendLine("  copy filled in: `trailblaze check` shows why.")
    }
  }.trimEnd()

  private data class WorkspaceTrailmap(
    val id: String,
    val targetId: String?,
    val dependencies: List<String>,
    val dir: String,
  )

  /**
   * Every trailmap directory in [callerDir]'s workspace, keyed the way the loader keys them: by
   * manifest id, with the target id the manifest declares. A manifest that does not parse falls
   * back to its directory name as both, so a broken copy is still compared rather than skipped.
   */
  private fun workspaceTrailmaps(callerDir: Path, callerConfigDir: String?): List<WorkspaceTrailmap> {
    val trailmapsDir = workspaceTrailmapsDir(callerDir, callerConfigDir) ?: return emptyList()
    return trailmapsDir.listFiles().orEmpty()
      .sortedBy { it.name }
      .mapNotNull { dir ->
        val manifestFile = File(dir, TrailblazeConfigPaths.TRAILMAP_MANIFEST_FILENAME)
        if (!manifestFile.isFile) return@mapNotNull null
        val manifest = runCatching { TrailblazeTrailmapManifestLoader.load(manifestFile).manifest }.getOrNull()
        val id = manifest?.id ?: dir.name
        WorkspaceTrailmap(
          id = id,
          targetId = if (manifest == null) id else manifest.target?.let { it.id ?: id },
          dependencies = manifest?.dependencies.orEmpty(),
          dir = canonicalPath(dir),
        )
      }
  }

  private fun workspaceTrailmapsDir(callerDir: Path, callerConfigDir: String?): File? {
    val resolved = try {
      TrailblazeWorkspaceConfigResolver.resolve(callerDir, envReader = { callerConfigDir })
    } catch (_: Exception) {
      return null
    }
    if (resolved.workspaceRoot !is WorkspaceRoot.Configured) return null
    return resolved.configDir
      ?.let { File(it, TrailblazeConfigPaths.TRAILMAPS_SUBDIR) }
      ?.takeIf { it.isDirectory }
  }

  private fun canonicalPath(dir: File): String = try {
    dir.canonicalPath
  } catch (_: Exception) {
    dir.absolutePath
  }
}
