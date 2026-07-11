package xyz.block.trailblaze.cli

import picocli.CommandLine.Command
import picocli.CommandLine.Option
import xyz.block.trailblaze.compile.TrailblazeCompiler
import xyz.block.trailblaze.config.project.LoadedTrailblazeProjectConfig
import xyz.block.trailblaze.config.project.TrailblazeProjectConfig
import xyz.block.trailblaze.config.project.TrailblazeProjectConfigException
import xyz.block.trailblaze.config.project.TrailblazeProjectConfigLoader
import xyz.block.trailblaze.host.PerTrailmapClientDtsEmitter
import xyz.block.trailblaze.host.PerTrailmapTsconfigEmitter
import xyz.block.trailblaze.host.ResolvedTargetReportEmitter
import xyz.block.trailblaze.host.TrailTscValidator
import xyz.block.trailblaze.host.WorkspaceTypeScriptSetup
import xyz.block.trailblaze.scripting.AnalyzerScriptedToolEnrichment
import xyz.block.trailblaze.llm.config.ClasspathConfigResourceSource
import xyz.block.trailblaze.llm.config.TrailblazeConfigPaths
import xyz.block.trailblaze.util.Console
import java.io.File
import java.nio.file.Path
import java.util.concurrent.Callable

/**
 * Compile trailmap manifests into resolved target YAMLs.
 *
 * `trailblaze compile` is the equivalent of `javac` for trailmaps: it reads
 * source trailmap manifests (`trailmaps/<id>/trailmap.yaml`), runs dependency resolution
 * with closest-wins inheritance, and emits one materialized
 * `targets/<id>.yaml` per app trailmap — a trailmap that declares a `target:` block.
 * Library trailmaps (no `target:`) contribute defaults but produce no output.
 *
 * Runtime callers (the daemon, the desktop target picker, the CLI's
 * `toolbox` listing) read the materialized flat `targets/<id>.yaml` files —
 * they never re-resolve trailmaps. This keeps trailmap semantics in one place
 * (the compiler) and the runtime hot path simple.
 *
 * The command resolves paths against the workspace root (the nearest
 * ancestor directory of the CWD containing `trails/config/trailmaps/`), not the
 * CWD itself, so running `trailblaze compile` from any subdirectory of a
 * workspace — including a trailmap's `tools/` dir several levels deep — works
 * the same as running it from the root. Same UX as `git`.
 */
@Command(
  name = "compile",
  mixinStandardHelpOptions = true,
  description = ["Compile trailmap manifests into resolved target YAMLs"],
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
      "Directory whose `trailmaps/` subdirectory holds one <id>/trailmap.yaml per trailmap " +
        "(the compiler reads `<input>/trailmaps/<id>/trailmap.yaml`). " +
        "Defaults to <workspace-root>/trails/config — the workspace root is found by " +
        "walking up from the current directory looking for `trails/config/trailmaps/`, " +
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
          "$startAbs to the filesystem root and found no `trails/config/trailmaps/` " +
          "marker. Either `cd` into a workspace tree (so the walk-up can find the " +
          "workspace root) or pass --input pointing at a directory whose `trailmaps/` " +
          "subdirectory holds your trailmap manifests.",
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

    val trailmapsDir = File(resolvedInputDir, "trailmaps")
    if (!trailmapsDir.isDirectory) {
      Console.error(
        "trailblaze $commandLabel: no trailmaps/ directory found under ${resolvedInputDir.absolutePath}; " +
          "nothing to compile. Hint: run from a workspace whose `trails/config/trailmaps/` " +
          "exists, or pass --input pointing at a directory that contains `trailmaps/`.",
      )
      return EXIT_USAGE
    }

    // Wire enrichment so meta-only scripted-tool descriptors (`script:` + `_meta:`
    // with `name:` / `description:` / `inputSchema:` derived from the typed `.ts`
    // source) resolve here. Without this, a trailmap that's adopted the typed-authoring
    // shape fails compile with "scripted-tool descriptor uses the meta-only authoring
    // shape ... No `ScriptedToolEnrichment` was wired" even though daemon-time
    // codegen would resolve it correctly.
    //
    // When the resolver returns null (no `bun` on PATH, missing TRAILBLAZE_SDK_DIR,
    // ts-json-schema-generator not installed), log a one-line breadcrumb so the
    // downstream "enrichment not wired" loader error has a root cause sitting next
    // to it in the same log instead of arriving with no context.
    val scriptedToolEnrichment = AnalyzerScriptedToolEnrichment.resolveFromEnvironment()
    if (scriptedToolEnrichment == null) {
      Console.info(
        "trailblaze $commandLabel: scripted-tool analyzer unavailable — meta-only " +
          "descriptors will fail to load. Ensure `bun` is on PATH and the SDK at " +
          "TRAILBLAZE_SDK_DIR carries node_modules/ts-json-schema-generator.",
      )
    }
    val result = TrailblazeCompiler.compile(
      trailmapsDir = trailmapsDir,
      outputDir = resolvedOutputDir,
      referenceSource = ClasspathConfigResourceSource,
      commandLabel = commandLabel,
      scriptedToolEnrichment = scriptedToolEnrichment,
    )
    if (!result.isSuccess) {
      Console.error("trailblaze $commandLabel: compilation failed:")
      result.errors.forEach { Console.error("  - $it") }
      return EXIT_COMPILE_ERROR
    }

    if (result.emittedTargets.isEmpty()) {
      Console.log(
        "trailblaze $commandLabel: no app trailmaps found under ${trailmapsDir.absolutePath} " +
          "(library trailmaps without `target:` produce no output).",
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

    // Emit per-trailmap typed bindings (`<trailmapDir>/tools/trailblaze-client.d.ts`) so the IDE
    // picks up typed `client.callTool` overloads scoped to each trailmap's own platform
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

    val resolvedTrailmaps = try {
      // Re-resolve the trailmap pool via the project-config loader so codegen sees the full
      // set of trailmaps (target trailmaps + transitively-resolved library deps) with their
      // sibling content (scripted tools, source dirs). Use the auto-discovery path
      // (empty `targets:`) — same shape `WorkspaceCompileBootstrap` uses, so both
      // entry points produce the same per-trailmap `client.d.ts` set for a given workspace.
      // The compile pass above already validated dependency-graph integrity; this call
      // re-walks the same closure to surface per-trailmap manifests for codegen.
      val loaded = LoadedTrailblazeProjectConfig(
        raw = TrailblazeProjectConfig(),
        sourceFile = File(canonicalInputDir, TrailblazeProjectConfigLoader.CONFIG_FILENAME),
      )
      TrailblazeProjectConfigLoader.resolveRuntime(
        loaded,
        includeClasspathTrailmaps = true,
        // Reuse the analyzer instance already resolved above — re-calling
        // `resolveFromEnvironment()` here would re-walk the SDK dir and (when the
        // walk-up resolves) build a second `ScriptedToolDefinitionAnalyzer`. The
        // hoisted instance covers both the initial compile + this codegen
        // re-resolution.
        scriptedToolEnrichment = scriptedToolEnrichment,
      ).resolvedTrailmaps
    } catch (e: TrailblazeProjectConfigException) {
      Console.error("trailblaze $commandLabel: trailmap re-resolution for codegen failed: ${e.message ?: e.javaClass.simpleName}")
      return EXIT_COMPILE_ERROR
    }

    val emitted = try {
      PerTrailmapClientDtsEmitter.emit(resolvedTrailmaps = resolvedTrailmaps)
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
        resolvedTrailmaps = resolvedTrailmaps,
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
    // consumed via the per-trailmap tsconfig's `paths` mapping.
    try {
      WorkspaceTypeScriptSetup.setUp(workspaceRoot = generatorRoot)
    } catch (e: Exception) {
      Console.error("trailblaze $commandLabel: TypeScript workspace setup failed: ${e.message ?: e.javaClass.simpleName}")
      return EXIT_COMPILE_ERROR
    }

    // Write the framework-managed `tools/tsconfig.json` per trailmap so authors get IDE
    // autocomplete on `client.tools.<name>(args)` with zero hand-authored config, plus
    // a trailmap-root `.gitignore` so the framework artifacts under `tools/` don't show
    // up in `git status`. Must run AFTER `WorkspaceTypeScriptSetup.setUp` because the
    // per-trailmap tsconfig's `paths` entry points at the SDK declaration bundle that
    // setUp just wrote — fail-fast here would otherwise emit trailmaps pointing at a
    // non-existent bundle file.
    val tsconfigEmitted = try {
      PerTrailmapTsconfigEmitter.emit(workspaceRoot = generatorRoot, resolvedTrailmaps = resolvedTrailmaps)
    } catch (e: Exception) {
      Console.error("trailblaze $commandLabel: per-trailmap tsconfig emission failed: ${e.message ?: e.javaClass.simpleName}")
      return EXIT_COMPILE_ERROR
    }
    if (tsconfigEmitted.isNotEmpty()) {
      // "Ensured in sync" rather than "wrote" or "managed" — the emitter is content-
      // hash compared (no-op if bytes already match), AND it preserves hand-authored
      // tsconfigs verbatim when the framework banner is absent. The honest verb covers
      // both branches without overstating what happened on a no-op compile.
      Console.log(
        "trailblaze $commandLabel: ensured ${tsconfigEmitted.size} per-trailmap tsconfig/.gitignore file(s) in sync:",
      )
      val displayBase = generatorRoot.parent ?: generatorRoot
      tsconfigEmitted.forEach { Console.log("  - ${displayBase.relativize(it)}") }
    }

    // Emit validation-only typed surfaces for classpath-bundled targets (app-bundled trailmaps
    // that live inside a JAR, e.g. `square` / `dashboardapp`) into a gitignored scratch dir under
    // `<trails>/.trailblaze/`. These have no writable `tools/` dir of their own, so the normal
    // per-trailmap emit skips them — which is why `trailblaze check`'s trail-recording validator
    // previously reported the bulk of the corpus as skipped-no-surface. Writing a surface here
    // lets that validator type-check their recorded tool calls too.
    //
    // Sourced from the build-time-baked `targets/<id>.yaml` (via AppTargetYamlLoader), NOT from
    // `resolvedTrailmaps`: a bundled target whose scripted `target.tools:` need analyzer
    // enrichment (e.g. `square`) is dropped from the resolved pool at runtime (the analyzer can't
    // walk `.ts` inside a JAR), so keying off resolvedTrailmaps would miss exactly the biggest
    // target. The baked configs exist for every bundled target and carry the hoisted tool list.
    // Workspace filesystem trailmaps are excluded — they already got a real surface just above.
    // Report-only feature, so a failure here is logged and never fails the compile.
    try {
      val surfacesBase = TrailTscValidator.classpathValidationSurfacesBaseDir(generatorRoot)
      val filesystemTrailmapIds = resolvedTrailmaps
        .filter { it.source is xyz.block.trailblaze.config.project.TrailmapSource.Filesystem }
        .map { it.manifest.id }
        .toSet()
      val bundledTargetConfigs = xyz.block.trailblaze.config.AppTargetYamlLoader.discoverConfigs(
        resourceSource = ClasspathConfigResourceSource,
      )
      val surfacesEmitted = PerTrailmapClientDtsEmitter.emitClasspathValidationSurfaces(
        targetConfigs = bundledTargetConfigs,
        excludeIds = filesystemTrailmapIds,
        outputBaseDir = surfacesBase,
      )
      // Derive the tsconfig id set from the surfaces that were ACTUALLY written, NOT from the full
      // bundled-config list. A target whose `.d.ts` generation failed is skipped by the emitter
      // above and returns no path — writing a tsconfig for it anyway would leave a
      // `tools/tsconfig.json` with no sibling `.d.ts`, which the validator would run against the
      // un-augmented SDK and report as bogus missing-tool findings. `trailmapIdForSurfaceFile`
      // owns the `<base>/<id>/tools/<file>` layout knowledge so it lives in exactly one place.
      val surfaceIds = surfacesEmitted.mapNotNull { TrailTscValidator.trailmapIdForSurfaceFile(it) }
      PerTrailmapTsconfigEmitter.emitClasspathValidationTsconfigs(
        workspaceRoot = generatorRoot,
        trailmapIds = surfaceIds,
        outputBaseDir = surfacesBase,
      )
      val candidateCount = bundledTargetConfigs.count { it.id !in filesystemTrailmapIds }
      if (candidateCount > 0) {
        val skipped = candidateCount - surfacesEmitted.size
        Console.log(
          "trailblaze $commandLabel: emitted ${surfacesEmitted.size} of $candidateCount " +
            "classpath-target validation surface(s) for trail type-checking" +
            (if (skipped > 0) " ($skipped skipped — see [PerTrailmapClientDtsEmitter] errors above)" else "") +
            ".",
        )
      }
    } catch (e: Exception) {
      Console.error(
        "trailblaze $commandLabel: classpath-target validation surface emission failed " +
          "(ignored — surface emission is best-effort; a resulting missing surface is reported " +
          "by the validation phase, fatally for a gated target on --all): ${e.message ?: e.javaClass.simpleName}",
      )
    }
    return EXIT_OK
  }

  /**
   * Walks up from [startPath] looking for the `trails/config/trailmaps/` workspace
   * marker. Delegates to the shared [CliPathUtils.findWorkspaceRoot] so this
   * command, [CheckCommand], and any future trailmaps-walking subcommand stay
   * in sync on workspace discovery semantics.
   *
   * Visible for testing.
   */
  internal fun findWorkspaceRoot(startPath: Path = CliCallerContext.callerCwd()): Path? =
    CliPathUtils.findWorkspaceRoot(startPath)

  private companion object {
    val EXIT_OK: Int = TrailblazeExitCode.SUCCESS.code
    // Compile is a build step — a trailmap manifest that doesn't resolve is closer to
    // a misuse (the workspace's source-of-truth is malformed) than infra. But the
    // existing semantic ("non-zero on any compile failure, distinguish from arg
    // errors") fits ASSERTION_FAILED (1) better: chaining `trailblaze compile &&
    // trailblaze run …` should refuse to run when trailmaps don't compile, the same
    // way `make` chains stop on a build failure.
    val EXIT_COMPILE_ERROR: Int = TrailblazeExitCode.ASSERTION_FAILED.code
    val EXIT_USAGE: Int = TrailblazeExitCode.MISUSE.code
  }
}
