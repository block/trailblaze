package xyz.block.trailblaze.cli

import xyz.block.trailblaze.llm.config.TrailblazeConfigPaths
import xyz.block.trailblaze.util.isWindows
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

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
   * Walks up from [startPath] looking for the workspace marker
   * (`trails/config/trailmaps/`). Returns the first ancestor that contains it, or
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
      val marker = current.resolve(TrailblazeConfigPaths.WORKSPACE_TRAILMAPS_DIR)
      if (Files.isDirectory(marker)) {
        return current
      }
      current = current.parent
    }
    return null
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
