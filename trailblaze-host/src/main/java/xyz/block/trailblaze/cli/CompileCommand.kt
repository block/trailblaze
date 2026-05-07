package xyz.block.trailblaze.cli

import picocli.CommandLine.Command
import picocli.CommandLine.Option
import xyz.block.trailblaze.compile.TrailblazeCompiler
import xyz.block.trailblaze.host.PerTargetClientDtsEmitter
import xyz.block.trailblaze.host.WorkspaceTypeScriptSetup
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

    // Emit per-target typed bindings (`client.<target-id>.d.ts`) so the IDE picks up
    // typed `client.callTool(name, args)` overloads scoped to each target. Mirrors what
    // the daemon-init bootstrap does on every workspace-aware command — running
    // `trailblaze compile` is just the explicit-foregrounded version of the same path.
    // Failures here are reported but not fatal: the resolved-target YAMLs already
    // emitted are useful even without the typed bindings.
    //
    // Generator's `workspaceRoot` is the directory containing `config/tools/...` —
    // derive it from `resolvedInputDir.parentFile` so a `--input` override points the
    // bindings at the same workspace tree as the inputs. Falling back to the cwd-walked
    // `workspaceRoot` would silently emit to a different (possibly stale) `trails/`
    // dir when `--input` is used to compile a workspace outside the cwd.
    //
    // Canonicalize first: `--input .` or any relative path with no parent segment would
    // give `parentFile == null` and crash the `.toPath()` call below. `canonicalFile`
    // resolves to an absolute path, which always has a non-null parent except at the
    // filesystem root (handled by the elvis fallback to the cwd-walked workspaceRoot).
    val canonicalInputDir = resolvedInputDir.canonicalFile
    val generatorRoot = (canonicalInputDir.parentFile ?: workspaceRoot).toPath()
    val emitted = try {
      PerTargetClientDtsEmitter.emit(
        workspaceRoot = generatorRoot,
        resolvedTargets = result.resolvedTargets,
      )
    } catch (e: Exception) {
      // CLI fail-fast: surface codegen failures with a non-zero exit. Distinguishes from
      // the daemon-init path (`WorkspaceCompileBootstrap.bootstrap`), which downgrades the
      // same failure to a warning because the daemon must come up regardless. `trailblaze
      // compile` is an explicit foreground operation — authors want to see the error, not
      // discover the binding is missing later when their IDE shows `any` everywhere.
      Console.error("trailblaze compile: typed-bindings codegen failed: ${e.message ?: e.javaClass.simpleName}")
      return EXIT_COMPILE_ERROR
    }
    if (emitted.isNotEmpty()) {
      Console.log(
        "trailblaze compile: emitted ${emitted.size} typed-binding file(s) for IDE autocomplete:",
      )
      // Relativize from `--input`'s parent (`generatorRoot.parent`) so the printed paths
      // are stable regardless of where the user invoked the CLI from. When `--input` is
      // unset this is the workspaceRoot, same as before.
      val displayBase = generatorRoot.parent ?: generatorRoot
      emitted.forEach { Console.log("  - ${displayBase.relativize(it)}") }
    }

    // Extract the bundled `@trailblaze/scripting` SDK and run `bun install` per
    // pack-with-package.json. CLI path always refreshes (`onlyInstallIfMissing = false`) so
    // re-running `trailblaze compile` after a framework upgrade picks up the new SDK and
    // re-resolves node_modules. Fail-fast on errors here too — same reasoning as the
    // bindings emit above; an explicit `trailblaze compile` should surface every problem.
    // Reuse the same `packsDir` declared earlier (line 67) — same workspace.
    val setup = try {
      WorkspaceTypeScriptSetup.setUp(
        workspaceRoot = generatorRoot,
        resolvedTargets = result.resolvedTargets,
        packsDir = packsDir,
        onlyInstallIfMissing = false,
      )
    } catch (e: Exception) {
      Console.error("trailblaze compile: TypeScript workspace setup failed: ${e.message ?: e.javaClass.simpleName}")
      return EXIT_COMPILE_ERROR
    }
    if (setup.installs.isNotEmpty() || !setup.skippedInstall) {
      val ran = setup.installs.count { it is WorkspaceTypeScriptSetup.PackInstall.Succeeded }
      val skipped = setup.installs.count { it is WorkspaceTypeScriptSetup.PackInstall.Skipped }
      val failed = setup.installs.filterIsInstance<WorkspaceTypeScriptSetup.PackInstall.Failed>()
      val bunMissing = setup.installs.filterIsInstance<WorkspaceTypeScriptSetup.PackInstall.BunMissing>()
      if (ran > 0 || skipped > 0 || failed.isNotEmpty() || bunMissing.isNotEmpty()) {
        Console.log(
          "trailblaze compile: TypeScript workspace setup — bun install ran in $ran pack(s), " +
            "skipped $skipped (already up-to-date), failed ${failed.size}, " +
            "bun-missing ${bunMissing.size}",
        )
        failed.forEach { Console.error("  - ${it.packId}: bun install exited ${it.exitCode}\n${it.output.lines().take(5).joinToString("\n")}") }
      }
      // Both per-pack `bun install` failures AND `bun` being absent from PATH count as
      // compile errors — the "one command setup" guarantee says `trailblaze compile`
      // either populates `node_modules/@trailblaze/scripting` or fails loudly. Silent
      // exit-zero on `bun-missing` would leave automation/users believing setup
      // succeeded while their IDE shows `any` everywhere. To opt out (CI containers that
      // handle `node_modules/` separately), set `TRAILBLAZE_SKIP_NPM_INSTALL=1`.
      if (failed.isNotEmpty() || bunMissing.isNotEmpty()) return EXIT_COMPILE_ERROR
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
