package xyz.block.trailblaze.cli

import picocli.CommandLine.Command
import picocli.CommandLine.Option
import xyz.block.trailblaze.compile.TrailblazeCompiler
import xyz.block.trailblaze.config.project.LoadedTrailblazeProjectConfig
import xyz.block.trailblaze.config.project.TrailblazeProjectConfig
import xyz.block.trailblaze.config.project.TrailblazeProjectConfigException
import xyz.block.trailblaze.config.project.TrailblazeProjectConfigLoader
import xyz.block.trailblaze.host.PerPackClientDtsEmitter
import xyz.block.trailblaze.host.PerPackTsconfigEmitter
import xyz.block.trailblaze.host.ResolvedTargetReportEmitter
import xyz.block.trailblaze.host.WorkspaceTypeScriptSetup
import xyz.block.trailblaze.llm.config.TrailblazeConfigPaths
import xyz.block.trailblaze.util.Console
import java.io.File
import java.nio.file.Path
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
 * ancestor directory of the CWD containing `trails/config/packs/`), not the
 * CWD itself, so running `trailblaze compile` from any subdirectory of a
 * workspace — including a pack's `tools/` dir several levels deep — works
 * the same as running it from the root. Same UX as `git`.
 */
@Command(
  name = "compile",
  mixinStandardHelpOptions = true,
  description = ["Compile pack manifests into resolved target YAMLs"],
)
class CompileCommand : Callable<Int> {

  /**
   * Label spliced into every user-facing `trailblaze <label>:` message this command emits.
   * Defaults to `compile` for direct invocation; [CheckCommand] sets it to `check` when
   * delegating so the user sees one consistent CLI verb regardless of which inner command
   * is currently raising. Internal only — not a picocli option.
   */
  internal var commandLabel: String = "compile"

  @Option(
    names = ["--input", "-i"],
    description = [
      "Directory whose `packs/` subdirectory holds one <id>/pack.yaml per pack " +
        "(the compiler reads `<input>/packs/<id>/pack.yaml`). " +
        "Defaults to <workspace-root>/trails/config — the workspace root is found by " +
        "walking up from the current directory looking for `trails/config/packs/`, " +
        "the same way `git` walks up to find `.git/`.",
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
    val callerCwd = CliCallerContext.callerCwd()
    // Only walk up when the user didn't pass --input — explicit input is authoritative,
    // so the walk-up would be wasted I/O and could yield a confusing "discovery failed"
    // signal in an unrelated parent tree.
    val discoveredWorkspaceRoot = if (inputDir == null) findWorkspaceRoot(callerCwd) else null
    if (inputDir == null && discoveredWorkspaceRoot == null) {
      val startAbs = callerCwd.toAbsolutePath().normalize()
      Console.error(
        "trailblaze $commandLabel: not inside a Trailblaze workspace. Walked up from " +
          "$startAbs to the filesystem root and found no `trails/config/packs/` " +
          "marker. Either `cd` into a workspace tree (so the walk-up can find the " +
          "workspace root) or pass --input pointing at a directory whose `packs/` " +
          "subdirectory holds your pack manifests.",
      )
      return EXIT_USAGE
    }
    val resolvedInputDir = inputDir ?: File(discoveredWorkspaceRoot!!.toFile(), TrailblazeConfigPaths.WORKSPACE_CONFIG_DIR)
    // When --input is explicit and discovery found nothing, anchor default paths off
    // <inputDir>/.. (the conventional `<root>/trails/config` shape becomes `<root>`).
    // Log it so the user knows TS setup is about to write into a non-workspace tree.
    val workspaceRoot = discoveredWorkspaceRoot?.toFile() ?: run {
      val derived = resolvedInputDir.canonicalFile.parentFile?.parentFile
        ?: resolvedInputDir.canonicalFile
      Console.log(
        "trailblaze $commandLabel: no workspace marker discovered above ${callerCwd.toAbsolutePath().normalize()}; " +
          "using ${derived.absolutePath} as the workspace root (derived from --input).",
      )
      derived
    }
    val resolvedOutputDir = outputDir
      ?: File(
        workspaceRoot,
        "${TrailblazeConfigPaths.WORKSPACE_CONFIG_DIR}/" +
          TrailblazeConfigPaths.WORKSPACE_DIST_TARGETS_SUBPATH,
      )

    val packsDir = File(resolvedInputDir, "packs")
    if (!packsDir.isDirectory) {
      Console.error(
        "trailblaze $commandLabel: no packs/ directory found under ${resolvedInputDir.absolutePath}; " +
          "nothing to compile. Hint: run from a workspace whose `trails/config/packs/` " +
          "exists, or pass --input pointing at a directory that contains `packs/`.",
      )
      return EXIT_USAGE
    }

    val result = TrailblazeCompiler.compile(
      packsDir = packsDir,
      outputDir = resolvedOutputDir,
      commandLabel = commandLabel,
    )
    if (!result.isSuccess) {
      Console.error("trailblaze $commandLabel: compilation failed:")
      result.errors.forEach { Console.error("  - $it") }
      return EXIT_COMPILE_ERROR
    }

    if (result.emittedTargets.isEmpty()) {
      Console.log(
        "trailblaze $commandLabel: no app packs found under ${packsDir.absolutePath} " +
          "(library packs without `target:` produce no output).",
      )
    } else {
      Console.log(
        "trailblaze $commandLabel: emitted ${result.emittedTargets.size} target(s) to " +
          resolvedOutputDir.absolutePath,
      )
      result.emittedTargets.forEach { Console.log("  - ${it.name}") }
    }
    if (result.deletedOrphans.isNotEmpty()) {
      Console.log(
        "trailblaze $commandLabel: cleaned up ${result.deletedOrphans.size} stale target(s) " +
          "from a previous compile:",
      )
      result.deletedOrphans.forEach { Console.log("  - ${it.name}") }
    }

    // Emit per-pack typed bindings (`<packDir>/tools/.trailblaze/client.d.ts`) so the IDE
    // picks up typed `client.callTool` overloads scoped to each pack's own platform
    // `tool_sets:` + scripted tools + transitively-inherited exports. Mirrors what the
    // daemon-init bootstrap does — running `trailblaze compile` is the explicit-
    // foregrounded version of the same path. Fail-fast on codegen errors here so authors
    // see breakage immediately rather than discovering missing bindings later when their
    // IDE shows `any` everywhere.
    //
    // `generatorRoot` is the workspace root (`trails/` dir). Derived from
    // `resolvedInputDir.parentFile` so a `--input` override points codegen at the same
    // workspace tree as the inputs. Canonicalize first: `--input .` or any relative path
    // with no parent segment would give `parentFile == null`. `canonicalFile` resolves to
    // an absolute path, which always has a non-null parent except at the filesystem root
    // (handled by the elvis fallback to the cwd-walked workspaceRoot).
    val canonicalInputDir = resolvedInputDir.canonicalFile
    val generatorRoot = (canonicalInputDir.parentFile ?: workspaceRoot).toPath()

    val resolvedPacks = try {
      // Re-resolve the pack pool via the project-config loader so codegen sees the full
      // set of packs (target packs + transitively-resolved library deps) with their
      // sibling content (scripted tools, source dirs). Use the auto-discovery path
      // (empty `targets:`) — same shape `WorkspaceCompileBootstrap` uses, so both
      // entry points produce the same per-pack `client.d.ts` set for a given workspace.
      // The compile pass above already validated dependency-graph integrity; this call
      // re-walks the same closure to surface per-pack manifests for codegen.
      val loaded = LoadedTrailblazeProjectConfig(
        raw = TrailblazeProjectConfig(),
        sourceFile = File(canonicalInputDir, TrailblazeProjectConfigLoader.CONFIG_FILENAME),
      )
      TrailblazeProjectConfigLoader.resolveRuntime(loaded, includeClasspathPacks = true).resolvedPacks
    } catch (e: TrailblazeProjectConfigException) {
      Console.error("trailblaze $commandLabel: pack re-resolution for codegen failed: ${e.message ?: e.javaClass.simpleName}")
      return EXIT_COMPILE_ERROR
    }

    val emitted = try {
      PerPackClientDtsEmitter.emit(resolvedPacks = resolvedPacks)
    } catch (e: Exception) {
      Console.error("trailblaze $commandLabel: typed-bindings codegen failed: ${e.message ?: e.javaClass.simpleName}")
      return EXIT_COMPILE_ERROR
    }
    if (emitted.isNotEmpty()) {
      Console.log(
        "trailblaze $commandLabel: emitted ${emitted.size} typed-binding file(s) for IDE autocomplete:",
      )
      val displayBase = generatorRoot.parent ?: generatorRoot
      emitted.forEach { Console.log("  - ${displayBase.relativize(it)}") }
    }

    // Emit a Markdown agent-toolbox report per target alongside the resolved YAML so
    // authors can browse "what is the agent told about for this target, and where did
    // each piece come from?" without grepping Kotlin source. Idempotent (content-hash
    // compared before write) so unchanged generations don't churn `git status`.
    val reportEmitted = try {
      ResolvedTargetReportEmitter.emit(
        resolvedTargets = result.resolvedTargets,
        resolvedPacks = resolvedPacks,
        outputDir = resolvedOutputDir,
      )
    } catch (e: Exception) {
      Console.error(
        "trailblaze $commandLabel: resolved-target report emission failed: ${e.message ?: e.javaClass.simpleName}",
      )
      return EXIT_COMPILE_ERROR
    }
    if (reportEmitted.isNotEmpty()) {
      Console.log(
        "trailblaze $commandLabel: refreshed ${reportEmitted.size} resolved-target report(s):",
      )
      reportEmitted.forEach { Console.log("  - ${it.name}") }
    }

    // Extract the bundled `@trailblaze/scripting` declaration bundle to
    // `<workspaceRoot>/.trailblaze/sdk/dist/index.d.ts`. No `bun install` step — the SDK
    // type surface is delivered as a single self-contained `.d.ts` (zod types inlined),
    // consumed via the per-pack tsconfig's `paths` mapping.
    try {
      WorkspaceTypeScriptSetup.setUp(workspaceRoot = generatorRoot)
    } catch (e: Exception) {
      Console.error("trailblaze $commandLabel: TypeScript workspace setup failed: ${e.message ?: e.javaClass.simpleName}")
      return EXIT_COMPILE_ERROR
    }

    // Write the framework-managed `tools/tsconfig.json` per pack so authors get IDE
    // autocomplete on `client.tools.<name>(args)` with zero hand-authored config, plus
    // a pack-root `.gitignore` so the framework artifacts under `tools/` don't show
    // up in `git status`. Must run AFTER `WorkspaceTypeScriptSetup.setUp` because the
    // per-pack tsconfig's `paths` entry points at the SDK declaration bundle that
    // setUp just wrote — fail-fast here would otherwise emit packs pointing at a
    // non-existent bundle file.
    val tsconfigEmitted = try {
      PerPackTsconfigEmitter.emit(workspaceRoot = generatorRoot, resolvedPacks = resolvedPacks)
    } catch (e: Exception) {
      Console.error("trailblaze $commandLabel: per-pack tsconfig emission failed: ${e.message ?: e.javaClass.simpleName}")
      return EXIT_COMPILE_ERROR
    }
    if (tsconfigEmitted.isNotEmpty()) {
      // "Ensured in sync" rather than "wrote" or "managed" — the emitter is content-
      // hash compared (no-op if bytes already match), AND it preserves hand-authored
      // tsconfigs verbatim when the framework banner is absent. The honest verb covers
      // both branches without overstating what happened on a no-op compile.
      Console.log(
        "trailblaze $commandLabel: ensured ${tsconfigEmitted.size} per-pack tsconfig/.gitignore file(s) in sync:",
      )
      val displayBase = generatorRoot.parent ?: generatorRoot
      tsconfigEmitted.forEach { Console.log("  - ${displayBase.relativize(it)}") }
    }
    return EXIT_OK
  }

  /**
   * Walks up from [startPath] looking for the `trails/config/packs/` workspace
   * marker. Delegates to the shared [CliPathUtils.findWorkspaceRoot] so this
   * command, [TypecheckCommand], and any future packs-walking subcommand stay
   * in sync on workspace discovery semantics.
   *
   * Visible for testing.
   */
  internal fun findWorkspaceRoot(startPath: Path = CliCallerContext.callerCwd()): Path? =
    CliPathUtils.findWorkspaceRoot(startPath)

  private companion object {
    const val EXIT_OK = 0
    const val EXIT_COMPILE_ERROR = 1
    const val EXIT_USAGE = 2
  }
}
