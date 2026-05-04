package xyz.block.trailblaze.cli

import picocli.CommandLine.Command
import picocli.CommandLine.Option
import xyz.block.trailblaze.compile.TrailblazeCompiler
import xyz.block.trailblaze.llm.config.TrailblazeConfigPaths
import xyz.block.trailblaze.util.Console
import java.io.File
import java.util.concurrent.Callable

/**
 * Compile pack manifests into resolved target YAMLs.
 *
 * `trailblaze compile` is the equivalent of `javac` for packs: it reads
 * source pack manifests (`packs/<id>/pack.yaml`), runs dependency resolution
 * with closest-wins inheritance, and emits one materialized
 * `targets/<id>.yaml` per app pack — a pack that declares a `target:` block.
 * Library packs (no `target:`) contribute defaults but produce no output.
 *
 * Runtime callers (the daemon, the desktop target picker, the CLI's
 * `toolbox` listing) read the materialized flat `targets/<id>.yaml` files —
 * they never re-resolve packs. This keeps pack semantics in one place
 * (the compiler) and the runtime hot path simple.
 *
 * The command resolves paths against the workspace root (the nearest
 * ancestor directory of the CWD containing `trails/config/`), not the CWD
 * itself, so running `trailblaze compile` from any subdirectory of a
 * workspace works the same as running it from the root — same UX as `git`.
 */
@Command(
  name = "compile",
  mixinStandardHelpOptions = true,
  description = ["Compile pack manifests into resolved target YAMLs"],
)
class CompileCommand : Callable<Int> {

  @Option(
    names = ["--input", "-i"],
    description = [
      "Directory containing one <id>/pack.yaml per pack. " +
        "Defaults to <workspace-root>/trails/config (workspace root is found by " +
        "walking up from the current directory looking for `trails/config/`).",
    ],
  )
  var inputDir: File? = null

  @Option(
    names = ["--output", "-o"],
    description = [
      "Directory to emit resolved <id>.yaml files into. " +
        "Defaults to <workspace-root>/trails/config/dist/targets.",
    ],
  )
  var outputDir: File? = null

  override fun call(): Int {
    val workspaceRoot = findWorkspaceRoot()
    val resolvedInputDir = inputDir ?: File(workspaceRoot, TrailblazeConfigPaths.WORKSPACE_CONFIG_DIR)
    val resolvedOutputDir = outputDir
      ?: File(
        workspaceRoot,
        "${TrailblazeConfigPaths.WORKSPACE_CONFIG_DIR}/" +
          TrailblazeConfigPaths.WORKSPACE_DIST_TARGETS_SUBPATH,
      )

    val packsDir = File(resolvedInputDir, "packs")
    if (!packsDir.isDirectory) {
      Console.error(
        "trailblaze compile: no packs/ directory found under ${resolvedInputDir.absolutePath}; " +
          "nothing to compile. Hint: run from a workspace whose `trails/config/packs/` " +
          "exists, or pass --input pointing at a directory that contains `packs/`.",
      )
      return EXIT_USAGE
    }

    val result = TrailblazeCompiler.compile(packsDir = packsDir, outputDir = resolvedOutputDir)
    if (!result.isSuccess) {
      Console.error("trailblaze compile: compilation failed:")
      result.errors.forEach { Console.error("  - $it") }
      return EXIT_COMPILE_ERROR
    }

    if (result.emittedTargets.isEmpty()) {
      Console.log(
        "trailblaze compile: no app packs found under ${packsDir.absolutePath} " +
          "(library packs without `target:` produce no output).",
      )
    } else {
      Console.log(
        "trailblaze compile: emitted ${result.emittedTargets.size} target(s) to " +
          resolvedOutputDir.absolutePath,
      )
      result.emittedTargets.forEach { Console.log("  - ${it.name}") }
    }
    if (result.deletedOrphans.isNotEmpty()) {
      Console.log(
        "trailblaze compile: cleaned up ${result.deletedOrphans.size} stale target(s) " +
          "from a previous compile:",
      )
      result.deletedOrphans.forEach { Console.log("  - ${it.name}") }
    }
    return EXIT_OK
  }

  /**
   * Walks up from the current directory looking for a `trails/config/` marker.
   * Returns the first ancestor that contains it, or the current directory when
   * no marker is found (so a fresh checkout / unrelated cwd still gets a
   * sensible default rather than an exception). Mirrors the discovery pattern
   * used by `git` and most monorepo CLIs — ergonomics for users who run the
   * command from a deep subdirectory.
   *
   * Visible for testing.
   */
  internal fun findWorkspaceRoot(startDir: File = File(".").canonicalFile): File {
    var current: File? = startDir
    while (current != null) {
      if (File(current, TrailblazeConfigPaths.WORKSPACE_CONFIG_DIR).isDirectory) {
        return current
      }
      current = current.parentFile
    }
    return startDir
  }

  private companion object {
    const val EXIT_OK = 0
    const val EXIT_COMPILE_ERROR = 1
    const val EXIT_USAGE = 2
  }
}
