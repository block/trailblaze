package xyz.block.trailblaze.cli

import xyz.block.trailblaze.llm.config.TrailblazeConfigPaths
import xyz.block.trailblaze.util.Console
import xyz.block.trailblaze.util.isWindows
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/**
 * Cross-cutting filesystem / PATH helpers shared by the CLI subcommands.
 *
 * These primitives used to live as private methods on individual commands
 * ([CompileCommand], [CheckCommand]) but were copy-pasted by-construction —
 * lifting them here gives every command a single place to evolve the walk-up logic
 * (symlinks, future workspace marker changes) and the PATH-lookup behavior
 * (`PATHEXT` resolution on Windows) without one command silently drifting from
 * the other.
 */
internal object CliPathUtils {

  /**
   * Walks up from [startPath] looking for the workspace marker — a `trailmaps/` directory
   * inside either workspace config-dir layout (`trailblaze-config/trailmaps/` or the legacy
   * `trails/config/trailmaps/`). Returns the first ancestor that contains one, or
   * `null` when the walk reaches the filesystem root with no match.
   *
   * Walking continues straight through intermediate `trailmap.yaml`-bearing
   * directories (a trailmap inside a workspace is still inside the workspace) — the
   * only stop condition is the `trailmaps/` marker. Mirrors the discovery pattern
   * used by `git` walking up to `.git/` and `gh` walking up to a repo root.
   *
   * No depth cap. Terminates at the filesystem root when [Path.getParent]
   * returns null. Used by both `trailblaze compile` (entry into the trailmaps tree
   * to materialize target YAMLs) and `trailblaze typecheck` (entry into the
   * trailmaps tree to spawn `tsc` per trailmap).
   */
  fun findWorkspaceRoot(startPath: Path): Path? {
    val startDir = startPath.toAbsolutePath().normalize()
    var current: Path? = if (Files.isRegularFile(startDir)) startDir.parent else startDir
    while (current != null) {
      val ancestor: Path = current
      if (candidateTrailmapsDirs(ancestor).any { Files.isDirectory(it) }) {
        return ancestor
      }
      current = current.parent
    }
    return null
  }

  /**
   * The workspace config dir under [workspaceRoot], honoring both layouts: the standalone
   * `trailblaze-config/` wins over the legacy `trails/config/` when both carry a
   * `trailmaps/` subdirectory (with a one-time warning); a layout whose `trailmaps/`
   * exists wins over one that merely has the directory. Falls back to the legacy path
   * when neither exists, so error messages and scaffolding have a conventional default.
   */
  fun workspaceConfigDir(workspaceRoot: Path): Path {
    val withMarker = TrailblazeConfigPaths.WORKSPACE_CONFIG_DIR_CANDIDATES
      .map { workspaceRoot.resolve(it) }
      .filter { Files.isDirectory(it.resolve(TrailblazeConfigPaths.TRAILMAPS_SUBDIR)) }
    if (withMarker.size > 1) warnBothConfigDirsOnce(workspaceRoot)
    withMarker.firstOrNull()?.let { return it }
    return TrailblazeConfigPaths.WORKSPACE_CONFIG_DIR_CANDIDATES
      .map { workspaceRoot.resolve(it) }
      .firstOrNull { Files.isDirectory(it) }
      ?: workspaceRoot.resolve(TrailblazeConfigPaths.WORKSPACE_CONFIG_DIR)
  }

  /** The workspace trailmaps dir under [workspaceRoot] — `<workspaceConfigDir>/trailmaps`. */
  fun workspaceTrailmapsDir(workspaceRoot: Path): Path =
    workspaceConfigDir(workspaceRoot).resolve(TrailblazeConfigPaths.TRAILMAPS_SUBDIR)

  /**
   * True when [workspaceRoot] carries the workspace marker (a `trailmaps/` dir in either
   * config-dir layout) — the same predicate [findWorkspaceRoot] walks up on.
   */
  fun hasWorkspaceMarker(workspaceRoot: Path): Boolean =
    candidateTrailmapsDirs(workspaceRoot).any { Files.isDirectory(it) }

  /**
   * The directory generated artifacts (`.trailblaze/`) anchor under for [workspaceRoot]:
   * the parent of the resolved config dir — `<root>/trails` for the legacy layout, the
   * workspace root itself for the standalone layout. Matches the daemon-side rule
   * (`WorkspaceCompileBootstrap` anchors on `configDir.parentFile`), so the CLI and the
   * daemon generate into the same `.trailblaze/` tree for a given workspace.
   */
  fun workspaceGeneratedArtifactsRoot(workspaceRoot: Path): Path =
    workspaceConfigDir(workspaceRoot).parent ?: workspaceRoot

  /** Human-readable marker description for error messages that name the walk-up target. */
  val workspaceMarkerLabel: String =
    TrailblazeConfigPaths.WORKSPACE_CONFIG_DIR_CANDIDATES
      .joinToString("` or `") { "$it/${TrailblazeConfigPaths.TRAILMAPS_SUBDIR}/" }

  private fun candidateTrailmapsDirs(root: Path): List<Path> =
    TrailblazeConfigPaths.WORKSPACE_CONFIG_DIR_CANDIDATES
      .map { root.resolve(it).resolve(TrailblazeConfigPaths.TRAILMAPS_SUBDIR) }

  /** Once per JVM per root — the CLI resolves the config dir many times per invocation. */
  private val bothConfigDirsWarned = ConcurrentHashMap.newKeySet<Path>()

  private fun warnBothConfigDirsOnce(root: Path) {
    if (!bothConfigDirsWarned.add(root.toAbsolutePath().normalize())) return
    Console.info(
      "Warning: both `${TrailblazeConfigPaths.WORKSPACE_STANDALONE_CONFIG_DIR}/` and " +
        "`${TrailblazeConfigPaths.WORKSPACE_CONFIG_DIR}/` exist under $root. Using " +
        "`${TrailblazeConfigPaths.WORKSPACE_STANDALONE_CONFIG_DIR}/`; " +
        "consolidate into one directory to silence this warning.",
    )
  }

  /**
   * Windows-aware `PATHEXT`-list. On Windows, derived from the `PATHEXT` env var
   * (falling back to `.COM;.EXE;.BAT;.CMD`); on POSIX, just `""` so the bare
   * command name is probed unchanged.
   *
   * Mirrors the shape used by
   * [xyz.block.trailblaze.ui.utils.toolavailability.ToolAvailabilityChecker] so the
   * two PATH-lookup implementations agree on cross-platform handling. Cached lazily
   * because PATHEXT doesn't change during a JVM lifetime.
   */
  private val executableExtensions: List<String> by lazy {
    if (isWindows()) {
      val pathExt = System.getenv("PATHEXT") ?: ".COM;.EXE;.BAT;.CMD"
      listOf("") + pathExt.split(';').filter { it.isNotEmpty() }.map { it.lowercase() }
    } else {
      listOf("")
    }
  }

  /**
   * Returns true when [executable] resolves to an executable file on the system
   * `PATH`. On Windows, every `PATHEXT` extension is probed so `bun` matches
   * `bun.exe` / `bun.cmd` etc. Pure filesystem lookup — no subprocess spawn —
   * matching the discipline the existing `ToolAvailabilityChecker` uses for
   * `adb` / `xcrun`. Returns false when `PATH` is unset or no matching file is
   * found.
   */
  fun isCommandOnPath(executable: String): Boolean {
    val pathEnv = System.getenv("PATH") ?: return false
    return pathEnv.split(File.pathSeparatorChar).any { dir ->
      executableExtensions.any { ext ->
        val candidate = File(dir, executable + ext)
        candidate.isFile && candidate.canExecute()
      }
    }
  }
}
