package xyz.block.trailblaze.cli

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import picocli.CommandLine
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import picocli.CommandLine.Parameters
import xyz.block.trailblaze.bundle.WorkspaceClientDtsGenerator
import xyz.block.trailblaze.config.project.LoadedTrailblazeTrailmapManifest
import xyz.block.trailblaze.config.project.TrailblazeTrailmapManifestLoader
import xyz.block.trailblaze.host.TrailTscValidator
import xyz.block.trailblaze.host.WorkspaceTypeScriptSetup
import xyz.block.trailblaze.llm.config.TrailblazeConfigPaths
import xyz.block.trailblaze.scripting.ScriptedToolDefinitionAnalyzer
import xyz.block.trailblaze.util.BunBinaryResolver
import xyz.block.trailblaze.scripting.ScriptedToolDefinitionCache
import xyz.block.trailblaze.scripting.ScriptedToolDefinitionException
import xyz.block.trailblaze.util.Console
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Callable
import java.util.concurrent.TimeUnit

/**
 * Single-command entry point that materializes a workspace, type-checks its
 * TypeScript / JavaScript trailmap tools via the bundled `tsc`, and runs `*.test.ts`
 * unit tests via `bun test` — the consolidation of the pre-issue-#3231
 * `trailblaze compile` + `trailblaze typecheck` two-step.
 *
 * Resolves the workspace in three ways:
 *  1. From inside a workspace tree (CWD or any subdirectory has
 *     `trails/config/trailmaps/` as an ancestor): walk-up finds the workspace root.
 *     If CWD is inside a specific trailmap, that trailmap is the default scope;
 *     otherwise, all trailmaps are checked.
 *  2. From outside any workspace, with `<trailmap-id>`: enumerate known workspace
 *     locations (currently the per-example `examples/[name]/trails/config/trailmaps/`
 *     trees) and resolve the id against the union. A unique match is used;
 *     ambiguity / no-match is an error naming the candidates.
 *  3. From outside any workspace, no `<trailmap-id>`: emit an actionable error.
 *
 * `--workspace <dir>` pins the workspace root explicitly — used by CI scripts
 * (e.g. `pr_validate_ts_tooling.sh` under `scripts/`) that run from a fixed
 * working directory and can't rely on the walk-up.
 *
 * Materialization (the compile half) is delegated to [CompileCommand], which
 * remains the source of truth for that behavior; the type-check and unit-test
 * phases live here directly (previously a sibling `TypecheckCommand`, folded in
 * after `trailblaze typecheck` was retired as a user-facing subcommand).
 *
 * **Type-check runtime.** Spawns `bun <tscJs> ...` — tsc is plain
 * Node-compatible JS and bun's Node-compatibility layer executes it identically.
 * Trailblaze's documented contract is "install bun; nothing else is required"
 * so the typecheck pass does not fall back to Node — a missing-bun host surfaces
 * a directed "install bun" diagnostic instead of silently switching runtimes.
 *
 * **Bundled tsc on-disk cache.** The framework-bundled tsc lives at
 * `<workspace>/trails/.trailblaze/typecheck/typescript/` after first run (~6 MB).
 * This directory is a regeneratable cache — `rm -rf` it any time to force
 * re-extraction. The framework prunes stale files on version upgrades
 * automatically (see [WorkspaceTypeScriptSetup.extractTypecheck]).
 *
 * **Per-trailmap tsc timeout.** Each `tsc` invocation is bounded by a 5-minute default
 * that catches infinite-include-glob misconfigurations without ever interrupting a
 * real type-check. Override via the [TIMEOUT_MS_ENV_VAR] env var (milliseconds)
 * for codebases with known-slow cyclic types — values below the 1-minute floor are
 * clamped up to keep `waitFor` semantically sane.
 */
@Command(
  name = "check",
  mixinStandardHelpOptions = true,
  description = [
    "Validate a trailmap: materialize manifests, type-check TypeScript/JavaScript sources, " +
      "and run `*.test.ts` unit tests via `bun test`. On first run, scaffolds a minimal " +
      "package.json at the workspace root if absent so `bun install` can be used as the " +
      "canonical bootstrap (its `postinstall` hook re-runs `trailblaze check`).",
  ],
)
class CheckCommand : Callable<Int> {

  @Parameters(
    arity = "0..1",
    paramLabel = "<trailmap-id>",
    description = [
      "Name of the trailmap to scope the type-check to (directory name under " +
        "<workspace>/trails/config/trailmaps/). Omit when running from inside a trailmap " +
        "tree (auto-detected) or pass --all to type-check every trailmap. Mutually " +
        "exclusive with --all.",
    ],
  )
  var trailmapId: String? = null

  @Option(
    names = ["--all"],
    description = [
      "Type-check every trailmap in the discovered workspace, even when running from " +
        "inside a specific trailmap tree. Mutually exclusive with the positional " +
        "<trailmap-id>.",
    ],
  )
  var checkAll: Boolean = false

  @Option(
    names = ["--workspace"],
    description = [
      "Pin the workspace root explicitly (the directory containing `trails/config/trailmaps/`). " +
        "Used by CI scripts that run with a fixed cwd; interactive users should rely on " +
        "the cwd walk-up instead.",
    ],
  )
  var workspaceDir: File? = null

  @Option(
    names = ["--no-typecheck"],
    description = [
      "Skip the bundled-tsc typecheck pass — materialize the workspace's SDK + per-trailmap " +
        "typed bindings and still run `*.test.ts` unit tests via bun. Intended for CI " +
        "scripts that run tsc with custom settings (e.g., excluding legacy embedded " +
        "sub-projects); interactive users should leave this off.",
    ],
  )
  var noTypecheck: Boolean = false

  @Option(
    names = ["--show-typed-tools"],
    description = [
      "Print the typed scripted tools (`trailblaze.tool<I, O>({...})`) discovered in each " +
        "trailmap, with a compact one-line schema summary per tool. Useful as a diagnostic " +
        "when authoring a new tool or chasing a missing-tool / wrong-schema bug; off by " +
        "default because the per-trailmap subprocess spawn it requires adds noticeable latency " +
        "to `check`. Has no effect when node, the SDK shim, or the SDK's " +
        "`ts-json-schema-generator` install are missing — the analyzer skips cleanly with " +
        "an explanatory log line.",
    ],
  )
  var showTypedTools: Boolean = false

  override fun call(): Int {
    if (checkAll && trailmapId != null) {
      Console.error("trailblaze check: --all and <trailmap-id> are mutually exclusive.")
      return TrailblazeExitCode.MISUSE.code
    }
    trailmapId?.let { id ->
      validateTrailmapId(id)?.let { reason ->
        Console.error("trailblaze check: invalid trailmap id '$id' — $reason.")
        return TrailblazeExitCode.MISUSE.code
      }
    }

    val callerCwd = CliCallerContext.callerCwd()
    val resolved = resolveWorkspace(callerCwd) ?: return TrailblazeExitCode.MISUSE.code

    // Bootstrap discoverability: scaffold a minimal `package.json` at the workspace
    // root if one isn't already present. Once committed, every future clone of that
    // workspace can be bootstrapped via standard `npm install` — its `postinstall`
    // hook fires `trailblaze check`, which populates `.trailblaze/sdk/` and the per-
    // trailmap typed bindings the IDE indexes. Strictly non-fatal — a failure here must
    // not block the actual check work below.
    scaffoldWorkspacePackageJson(resolved.workspaceRoot)

    // Step 1: materialize. CompileCommand discovers the workspace via the same walk-up
    // shape we just used, so setting `inputDir` explicitly keeps it from re-walking
    // (and gives a stable anchor when --workspace was passed).
    val compileExit = CliCallerContext.withCallerCwd(resolved.workspaceRoot.toPath()) {
      CompileCommand().apply {
        inputDir = File(resolved.workspaceRoot, TrailblazeConfigPaths.WORKSPACE_CONFIG_DIR)
        commandLabel = "check"
      }.call()
    }
    if (compileExit != 0) return compileExit

    // Dispatch shape shared by both the typecheck and unit-test phases below — keeps
    // them targeting the same trailmap set whether the user pinned `--all`, a single trailmap,
    // or relied on the cwd walk-up.
    val dispatch = decideTypecheckDispatch(
      resolved = resolved,
      callerCwd = callerCwd,
      checkAll = checkAll,
    )

    // Pre-flight: resolve the trailmap list once, up front. The result feeds both the
    // typecheck phase and the test phase, AND acts as an early-validation gate so a bad
    // input (unknown trailmap id, no enclosing trailmap from the walk-up) surfaces ONE error
    // here rather than two identical ones from the typecheck phase + the test-phase's
    // own resolution call. `dispatch.cwd` is passed as an explicit `callerCwd`
    // parameter — no [CliCallerContext] swap is needed because none of the post-compile
    // callees (`resolveTrailmapsToCheck`, `runTypecheckPhase`, `TrailmapUnitTestRunner.run`)
    // read the thread-local. The wrap is only retained around the [CompileCommand]
    // call above, which DOES walk up from the thread-local cwd internally.
    val trailmapsToValidate = resolveTrailmapsToCheck(
      workspaceRoot = resolved.workspaceRoot,
      callerCwd = dispatch.cwd,
      trailmapId = dispatch.trailmapId,
      typecheckAll = dispatch.typecheckAll,
    ) ?: return TrailblazeExitCode.MISUSE.code

    // Step 2: typecheck (skippable via `--no-typecheck`).
    //
    // `--no-typecheck` skips ONLY this phase, NOT the unit-test phase below. The CI
    // script (`pr_validate_ts_tooling.sh`) uses `--no-typecheck` because it runs its
    // own legacy-filtered tsc pass — but it still wants bun unit tests to run as part
    // of `check`. Tests are an independent validation axis from tsc.
    val typecheckExit = if (noTypecheck) {
      0
    } else {
      runTypecheckPhase(workspaceRoot = resolved.workspaceRoot, trailmaps = trailmapsToValidate)
    }

    // Step 3: per-trailmap unit tests via `bun test`, against the pre-resolved list above.
    // Tests run even if typecheck failed — gradle-check-style "surface every failure
    // in one run" so an author iterating doesn't have to fix one signal, rerun, fix
    // the next, rerun. The aggregate exit code below picks the worst of the two
    // phases.
    val testExit = TrailmapUnitTestRunner.run(trailmaps = trailmapsToValidate)

    // Opt-in diagnostic: print the typed scripted tools
    // (`trailblaze.tool<I, O>({...})`) discovered in each trailmap. Off by default
    // because the per-trailmap bun subprocess spawn adds noticeable latency; the
    // flag makes the diagnostic available as a permanent self-service tool for
    // authors wiring a new typed tool or chasing a missing-tool / wrong-schema
    // bug, rather than getting removed when downstream consumers (the d.ts
    // emitter, LLM tool registration, ajv runtime validation) wire the analyzer
    // in directly. **Strictly non-fatal** — any analyzer failure (missing bun
    // binary, missing shim, missing node_modules, subprocess crash, unexpected
    // schema shape) is caught and logged; this hook never influences the check
    // exit code.
    if (showTypedTools) {
      emitScriptedToolDefinitionsDebug(trailmapsToValidate, workspaceRoot = resolved.workspaceRoot)
    }

    // Type-validate `.trail.yaml` recordings against each trailmap's typed surface. Fails the build
    // by default when a NON-exempt target has findings or can't be validated; per-target
    // `trail_validation.exempt` (in trailmap.yaml) and a central transitional allow-list opt targets
    // out. Opt out of the whole phase with TRAILBLAZE_DISABLE_TRAIL_RECORDING_VALIDATION=1.
    // Infrastructure problems inside the phase stay non-fatal (skip + log) — only genuine findings
    // flip the exit code. See [TrailTscValidator].
    val validationExit = if (isTrailRecordingValidationDisabled()) {
      EXIT_OK
    } else {
      runTrailRecordingValidationPhase(
        workspaceRoot = resolved.workspaceRoot,
        trailmaps = trailmapsToValidate,
        // Only fold in the (large) classpath-bundled validation surfaces on an all-workspace run. A
        // scoped run (`check <id>`, or from inside a trailmap dir) shouldn't pull the whole
        // app-bundled corpus into its report — that scale is what `--all` opts into.
        allWorkspaceScope = dispatch.typecheckAll,
      )
    }

    // Worst-of-all wins. Exit codes are ordered OK(0) < TYPE_ERROR(1) < USAGE(2) <
    // OPERATIONAL_ERROR(3), so max() surfaces the most-severe / most-operator-fixable outcome across
    // the phases over a plain success.
    return maxOf(typecheckExit, testExit, validationExit)
  }

  /**
   * Opt-in `--show-typed-tools` diagnostic: runs [ScriptedToolDefinitionAnalyzer]
   * across each trailmap's `tools/` directory and emits a flat human-readable
   * listing of every typed scripted tool — name, source file (relative to the
   * trailmap root when displayable), and a compact one-line input/output summary.
   *
   * Off by default because each trailmap costs one bun subprocess spawn (~500ms
   * cold start); the flag makes the diagnostic available as a permanent self-
   * service tool for authors wiring a new typed tool or chasing a missing-tool
   * / wrong-schema bug.
   *
   * **Strictly non-fatal**: the entire body runs inside a top-level try-catch on
   * [Throwable] (not just [ScriptedToolDefinitionException]) so a subprocess
   * launch failure, an unexpected JSON shape, or a programmer error inside the
   * analyzer cannot abort `trailblaze check`. Errors are logged under the
   * `[ScriptedToolDefinitionAnalyzer]` prefix for grep-ability.
   */
  private fun emitScriptedToolDefinitionsDebug(trailmaps: List<Path>, workspaceRoot: File) {
    val bun = BunBinaryResolver.resolveBunBinary()
    val sdkDir = ScriptedToolDefinitionAnalyzer.resolveSdkDir()
    val shim = ScriptedToolDefinitionAnalyzer.resolveExtractorShim(sdkDir)
    if (bun == null || sdkDir == null || shim == null) {
      Console.info(
        "[ScriptedToolDefinitionAnalyzer] Skipping typed-scripted-tool extraction — " +
          "bun=${bun?.absolutePath ?: "<missing>"}, sdkDir=${sdkDir?.absolutePath ?: "<missing>"}, " +
          "shim=${shim?.absolutePath ?: "<missing>"}.",
      )
      return
    }
    // Preflight: the shim's deps must be resolvable — either a real SDK tree with
    // `ts-json-schema-generator` + `typescript` under `<sdkDir>/node_modules/`, OR the
    // framework-bundled self-contained shim (deps inlined). A bare shim with neither would
    // invoke bun and yield `ERR_MODULE_NOT_FOUND` for every trailmap. Skip cleanly with the
    // same message shape the analyzer-runnable assume in the test suite uses.
    if (!ScriptedToolDefinitionAnalyzer.analyzerToolingAvailable(sdkDir)) {
      Console.info(
        "[ScriptedToolDefinitionAnalyzer] Skipping typed-scripted-tool extraction — " +
          "ts-json-schema-generator not installed under ${sdkDir.absolutePath}/node_modules " +
          "and no bundled analyzer shim present; run `bun install` in sdks/typescript to enable.",
      )
      return
    }
    val analyzer = ScriptedToolDefinitionAnalyzer(
      bunBinary = bun,
      extractorShim = shim,
      sdkDir = sdkDir,
      // Anchor the cache to the explicit workspace, NOT to JVM cwd. With
      // `--workspace <dir>` and the documented CI script use case (where cwd is
      // a sibling directory), the default `resolveDefaultCacheDir()` would
      // otherwise land under cwd's `.trailblaze/` rather than the workspace
      // the rest of `check` operates on — every cache hit/miss would be
      // misaligned with what the user pinned. (Automated review feedback.)
      cacheDir = ScriptedToolDefinitionCache.resolveDefaultCacheDir(searchFrom = workspaceRoot),
    )

    val perTrailmap = try {
      runBlocking {
        trailmaps.map { trailmapDir ->
          val toolsDir = trailmapDir.resolve("tools").toFile()
          val trailmapId = trailmapDir.fileName?.toString() ?: trailmapDir.toString()
          try {
            Triple(trailmapId, trailmapDir, analyzer.analyze(toolsDir))
          } catch (e: ScriptedToolDefinitionException) {
            Console.error(
              "[ScriptedToolDefinitionAnalyzer] trailmap '$trailmapId': ${e.message}",
            )
            e.errors.forEach { err ->
              Console.error(
                "    - ${err.file}${err.toolName?.let { " (tool: $it)" }.orEmpty()}: ${err.message}",
              )
            }
            // Surface partial extractions through the debug print too — a trailmap
            // with 9 healthy tools and 1 broken tool should still show the
            // 9 in the listing, with the broken one's error already logged.
            Triple(trailmapId, trailmapDir, e.partialTools)
          }
        }
      }
    } catch (e: Throwable) {
      // Belt-and-suspenders catch-all to honor the strict non-fatal contract:
      // subprocess launch failures (`IOException`), `InterruptedException`s
      // bubbling out of `runBlocking`, schema-shape `require` failures inside
      // ScriptedToolDefinition's init block — any non-ScriptedToolDefinition-
      // Exception throwable would otherwise abort `trailblaze check` despite
      // this hook being marked debug-only. Log under the same prefix and
      // continue.
      Console.error(
        "[ScriptedToolDefinitionAnalyzer] Unexpected failure during debug " +
          "extraction (ignored — debug output is non-fatal): ${e.message ?: e::class.simpleName}",
      )
      return
    }
    val totalTools = perTrailmap.sumOf { it.third.size }
    Console.info(
      "trailblaze check: discovered $totalTools typed scripted tool(s) across " +
        "${perTrailmap.size} trailmap(s).",
    )
    perTrailmap.forEach { (trailmapId, trailmapDir, defs) ->
      if (defs.isEmpty()) return@forEach
      Console.info("  trailmap '$trailmapId':")
      defs.forEach { def ->
        Console.info(
          "    - ${def.name}  (${describeSchemaShape(def.inputSchemaObject)} → " +
            "${describeSchemaShape(def.outputSchemaObject)})  " +
            "[${relativizeToTrailmap(def.sourcePath, trailmapDir)}:${def.line}]",
        )
      }
    }
  }

  /**
   * Render a tool's [sourcePath] relative to its [trailmapDir] for the debug print —
   * makes the output stable across hosts (no `/Users/$USER/...` noise) and matches
   * the docstring promise that source paths are shown "relative to the trailmap root".
   * Falls back to the absolute path verbatim when the relativize fails (e.g.
   * source on a different filesystem volume) so we never silently truncate.
   *
   * **Local on purpose.** A grep across the host module's `.relativize(` call
   * sites (`PerTrailmapTsconfigEmitter`, `WorkspaceTypeScriptSetup`,
   * `WaypointLocateCommand`, `CompileCommand`, `TrailsBrowserTabComposable`) shows
   * each one uses raw `Path.relativize` without a `..`-prefix or
   * `IllegalArgumentException` fallback because they all assume the source IS
   * under the anchor (a guaranteed precondition in their call sites). This
   * helper exists because the debug print receives source paths the analyzer
   * extracted from a subprocess — those CAN land outside the trailmap root in
   * adversarial fixtures (a symlinked source, a different mount), and the
   * surface needs to degrade to a readable absolute path rather than throwing.
   * If a third caller emerges that needs the same defensive shape, lift this
   * into `CliPathUtils` then.
   */
  private fun relativizeToTrailmap(absoluteSourcePath: String, trailmapDir: Path): String {
    return try {
      val source = File(absoluteSourcePath).toPath().toAbsolutePath().normalize()
      val anchor = trailmapDir.toAbsolutePath().normalize()
      val rel = anchor.relativize(source)
      // `relativize` produces a `..`-prefixed result when the source isn't under
      // the anchor. Fall back to the absolute path in that case — relativizing
      // to a sibling tree would be more confusing than helpful.
      if (rel.toString().startsWith("..")) absoluteSourcePath else rel.toString()
    } catch (_: IllegalArgumentException) {
      absoluteSourcePath
    }
  }

  /**
   * One-line compact summary of a JSON Schema for the debug print — collapses an
   * object schema to `{ field1: type, field2?: type, ... }`, leaves other shapes
   * as their bare `type:` value (or `?` when unrecognized). Not meant for round-
   * trip, just for visually verifying that the analyzer extracted the right shape.
   */
  private fun describeSchemaShape(schema: JsonObject): String {
    val type = (schema["type"] as? JsonPrimitive)?.contentOrNull
    if (type != "object") {
      // anyOf / oneOf / non-object — surface as its discriminator key for the
      // debug print rather than expanding it.
      schema.keys.firstOrNull { it == "anyOf" || it == "oneOf" }?.let { return it }
      return type ?: "?"
    }
    val props = (schema["properties"] as? JsonObject) ?: return "{}"
    val required = (schema["required"] as? JsonArray)
      ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
      ?.toSet().orEmpty()
    val rendered = props.entries.joinToString(", ") { (name, propSchema) ->
      val isReq = name in required
      val propType = ((propSchema as? JsonObject)?.get("type") as? JsonPrimitive)?.contentOrNull ?: "?"
      "$name${if (isReq) "" else "?"}: $propType"
    }
    return "{ $rendered }"
  }

  private fun resolveWorkspace(callerCwd: Path): WorkspaceResolution? {
    // --workspace wins over walk-up. Reject if the explicit dir doesn't carry the
    // workspace marker — failing here is friendlier than letting CompileCommand
    // surface a missing-trailmaps error later.
    workspaceDir?.let { explicit ->
      val trailmapsDir = File(explicit, TrailblazeConfigPaths.WORKSPACE_TRAILMAPS_DIR)
      if (!trailmapsDir.isDirectory) {
        Console.error(
          "trailblaze check: --workspace ${explicit.absolutePath} does not contain " +
            "`${TrailblazeConfigPaths.WORKSPACE_TRAILMAPS_DIR}/`. Pass a directory whose " +
            "subtree has the workspace marker.",
        )
        return null
      }
      return resolveScopeInWorkspace(workspaceRoot = explicit.canonicalFile, callerCwd = callerCwd)
    }

    val walkedUp = CliPathUtils.findWorkspaceRoot(callerCwd)
    if (walkedUp != null) {
      return resolveScopeInWorkspace(workspaceRoot = walkedUp.toFile().canonicalFile, callerCwd = callerCwd)
    }

    // Outside any workspace.
    val pid = trailmapId
    if (pid == null) {
      val startAbs = callerCwd.toAbsolutePath().normalize()
      Console.error(
        "trailblaze check: not inside a Trailblaze workspace. Walked up from " +
          "$startAbs to the filesystem root and found no `${TrailblazeConfigPaths.WORKSPACE_TRAILMAPS_DIR}/` " +
          "marker. To resolve, either: (a) `cd` into a workspace tree, " +
          "(b) pass `<trailmap-id>` to enumerate workspaces under `./examples/`, or " +
          "(c) pass `--workspace <dir>` to pin the workspace root explicitly.",
      )
      return null
    }

    // Enumerate candidate workspaces under CWD and pick the one (or fail with the
    // candidate list) that carries the requested trailmap id. Today's enumeration is
    // narrow on purpose — `./examples/*/trails/` — which mirrors how the docs +
    // CI scripts arrange example workspaces. The npm-distribution future can extend
    // this with `./node_modules/*/trails/` without breaking the existing path.
    val candidates = enumerateWorkspaces(callerCwd)
    val matches = candidates.filter {
      File(it, "${TrailblazeConfigPaths.WORKSPACE_TRAILMAPS_DIR}/$pid/${TrailblazeConfigPaths.TRAILMAP_MANIFEST_FILENAME}").isFile
    }
    if (matches.isEmpty()) {
      val cwdAbs = callerCwd.toAbsolutePath().normalize()
      if (candidates.isEmpty()) {
        Console.error(
          "trailblaze check: trailmap '$pid' not found — no workspaces enumerable under $cwdAbs. " +
            "Run from a directory whose `examples/*/trails/config/trailmaps/$pid/` subtree exists, " +
            "or pass `--workspace <dir>`.",
        )
      } else {
        Console.error(
          "trailblaze check: trailmap '$pid' not found in any enumerated workspace. " +
            "Searched: ${candidates.joinToString(", ") { it.absolutePath }}",
        )
      }
      return null
    }
    if (matches.size > 1) {
      Console.error(
        "trailblaze check: trailmap '$pid' is ambiguous — found in: " +
          matches.joinToString(", ") { it.absolutePath } +
          ". Pass `--workspace <dir>` to disambiguate.",
      )
      return null
    }
    val workspaceRoot = matches.single()
    val scopeTrailmapDir = File(
      workspaceRoot,
      "${TrailblazeConfigPaths.WORKSPACE_TRAILMAPS_DIR}/$pid",
    )
    return WorkspaceResolution(
      workspaceRoot = workspaceRoot,
      scopeTrailmapId = pid,
      scopeTrailmapDir = scopeTrailmapDir,
      forceAll = false,
    )
  }

  /**
   * Decide the typecheck scope inside an already-located workspace. Three outputs to set,
   * one per branch:
   *  - **Explicit trailmap id**: fail fast if the trailmap dir doesn't exist (avoids a less
   *    actionable error from a deeper layer); otherwise resolve with that scope.
   *  - **--all**: workspace-wide scope, no trailmap dir.
   *  - **Neither**: defer to the typecheck-phase cwd walk-up. If the caller's cwd sits
   *    at or above the workspace's `trailmaps/` dir (i.e. there's no enclosing trailmap for the
   *    walk-up to latch onto), promote the resolution to `forceAll = true` so the caller
   *    doesn't have to re-run with an explicit flag. That promotion is the reason this
   *    function can't be inlined into [resolveWorkspace] — it's the only place that
   *    needs the caller cwd in addition to the workspace root.
   */
  private fun resolveScopeInWorkspace(workspaceRoot: File, callerCwd: Path): WorkspaceResolution? {
    val explicitTrailmapId = trailmapId
    val trailmapsDir = File(workspaceRoot, TrailblazeConfigPaths.WORKSPACE_TRAILMAPS_DIR)
    if (explicitTrailmapId != null) {
      val trailmapDir = File(trailmapsDir, explicitTrailmapId)
      if (!trailmapDir.isDirectory) {
        val availableTrailmaps = trailmapsDir.listFiles { f -> f.isDirectory }
          ?.map { it.name }
          ?.sorted()
          .orEmpty()
        val availableLabel = if (availableTrailmaps.isEmpty()) "<none>" else availableTrailmaps.joinToString(", ")
        Console.error(
          "trailblaze check: trailmap '$explicitTrailmapId' not found in workspace " +
            "${workspaceRoot.absolutePath}. Available: $availableLabel",
        )
        return null
      }
      return WorkspaceResolution(
        workspaceRoot = workspaceRoot,
        scopeTrailmapId = explicitTrailmapId,
        scopeTrailmapDir = trailmapDir,
        forceAll = false,
      )
    }
    if (checkAll) {
      return WorkspaceResolution(
        workspaceRoot = workspaceRoot,
        scopeTrailmapId = null,
        scopeTrailmapDir = null,
        forceAll = true,
      )
    }
    // No explicit scope — let the typecheck phase's walk-up decide based on cwd. If the
    // walk-up wouldn't find an enclosing trailmap (because the cwd is at or above the workspace
    // root, or sits in a sibling of `trailmaps/`), promote to `forceAll = true` here so the
    // user doesn't have to re-run with a flag.
    val forceAll = callerCwd.cwdHasNoEnclosingTrailmap(workspaceRoot.toPath())
    return WorkspaceResolution(
      workspaceRoot = workspaceRoot,
      scopeTrailmapId = null,
      scopeTrailmapDir = null,
      forceAll = forceAll,
    )
  }

  /**
   * Walk-down enumeration of candidate workspace roots, used only when the cwd-walkup
   * found nothing AND the user supplied a trailmap id. Looks under each `<cwd>/examples/[name]`
   * directory for the `trails/config/trailmaps/` marker. Limited to one filesystem depth so
   * a misplaced invocation at `$HOME` doesn't scan the whole tree.
   */
  internal fun enumerateWorkspaces(callerCwd: Path): List<File> {
    val examplesDir = File(callerCwd.toFile(), "examples")
    if (!examplesDir.isDirectory) return emptyList()
    val children = examplesDir.listFiles { f -> f.isDirectory } ?: return emptyList()
    return children
      .filter { File(it, TrailblazeConfigPaths.WORKSPACE_TRAILMAPS_DIR).isDirectory }
      .map { it.canonicalFile }
      .sortedBy { it.name }
  }

  /**
   * True when [callerCwd] is positioned such that the typecheck-phase walk-up would NOT
   * find an enclosing trailmap — either because the cwd is the workspace root itself, or
   * because it sits in a sibling of `trailmaps/` (e.g. `trails/config/dist/`,
   * `trails/scripts/`). Used to decide whether to promote a no-args invocation to
   * `forceAll = true` so the user doesn't have to re-run with an explicit flag.
   *
   * Precondition: the caller has already verified [callerCwd] is somewhere under
   * [workspaceRoot] (the only call site runs after `findWorkspaceRoot` succeeded).
   * Outside-workspace inputs would also return true here, which is technically wrong
   * but unreachable from the resolver.
   */
  private fun Path.cwdHasNoEnclosingTrailmap(workspaceRoot: Path): Boolean {
    val canonicalCwd = this.canonicalize()
    val canonicalWs = workspaceRoot.canonicalize()
    // Three ways `forceAll` should fire (the typecheck-phase walk-up has nothing to
    // latch onto): cwd is the workspace root, cwd is `trailmaps/` itself, or cwd doesn't
    // sit under `trailmaps/` at all (e.g. `trails/config/dist/`, `trails/scripts/`).
    // `Path.startsWith` is reflexive so the explicit `cwd == trailmapsDir` case must
    // come before the `!startsWith` fallback — otherwise it'd be silently swallowed.
    val trailmapsDir = canonicalWs.resolve(TrailblazeConfigPaths.WORKSPACE_TRAILMAPS_DIR)
    return canonicalCwd == canonicalWs ||
      canonicalCwd == trailmapsDir ||
      !canonicalCwd.startsWith(trailmapsDir)
  }

  // ----- Typecheck phase (folded in from the retired TypecheckCommand) ------------

  /**
   * Type-check the supplied trailmap list via the framework-bundled `tsc`.
   *
   * `trailblaze check`'s typecheck phase is the terminal-side equivalent of the IDE's
   * "save and watch for red squiggles" loop: spawns the framework-bundled `tsc`
   * (extracted to `<workspace>/trails/.trailblaze/typecheck/typescript/`) against
   * each trailmap's framework-generated `tools/tsconfig.json` and surfaces the compiler's
   * diagnostics verbatim. The bundled tsc gives a deterministic pinned version with no
   * per-trailmap `bun install` step, which was the forcing function — `bun install` inside
   * a trailmap dir fails when the trailmap's transitive npm closure can't be resolved through
   * corporate npm mirrors in some environments.
   *
   * **Exit codes** (aggregated across trailmaps into three buckets so shell consumers can
   * distinguish failure modes; the specific tsc exit code — which varies across
   * TypeScript versions, 5.x emits `1`, 6.x emits `2` for the same diagnostics — is
   * normalized to a single [EXIT_TYPE_ERROR] so callers don't have to track
   * tsc-version drift):
   *  - `0` — every trailmap passed (or no trailmaps supplied).
   *  - `1` — at least one trailmap reported tsc-side type errors, or the spawn failed.
   *  - `2` — usage error (missing JS runtime, missing `tools/tsconfig.json` because
   *    the user forgot the compile phase first).
   *  - `3` — operational error (framework JAR missing the bundled tsc payload, I/O
   *    failure during workspace setup).
   *
   * Visible for testing: `CheckCommandTest` calls this directly with a fixture trailmap
   * that has no `tools/tsconfig.json` to pin the phase's `"trailblaze check: ..."`
   * stderr prefix against accidental drift back to `"trailblaze typecheck:"`.
   */
  /** True when `TRAILBLAZE_DISABLE_TRAIL_RECORDING_VALIDATION` is set to `1`/`true` (case-insensitive). */
  private fun isTrailRecordingValidationDisabled(): Boolean {
    val v = System.getenv(TrailTscValidator.DISABLE_ENV_VAR)?.trim()?.lowercase()
    return v == "1" || v == "true"
  }

  /**
   * Trail-recording type-validation. Reuses the same workspace SDK + bundled tsc the typecheck phase
   * set up, so this runs after [runTypecheckPhase]. Fails the build ([EXIT_TYPE_ERROR]) only when the
   * validator reports a FATAL finding — a non-exempt target with type findings, or a non-exempt
   * target that can't be validated at all. Everything else returns [EXIT_OK]:
   *  - Findings on exempt targets and missing surfaces for exempt targets are reported but non-fatal
   *    (the validator computes this).
   *  - Infrastructure problems (bun/tsc missing) and any unexpected exception in the phase are
   *    logged and treated as non-fatal, so a transient environment issue can't spuriously fail a
   *    build — the typecheck phase already gated bun/tsc presence upstream.
   *
   * See [TrailTscValidator] for the exemption model and [buildTrailValidationManifestContext] for
   * how the exemption set and known-manifest set are assembled.
   */
  private fun runTrailRecordingValidationPhase(
    workspaceRoot: File,
    trailmaps: List<Path>,
    allWorkspaceScope: Boolean,
  ): Int {
    return try {
      val trailsRoot = workspaceRoot.toPath().resolve(TrailblazeConfigPaths.WORKSPACE_TRAILS_DIR)
      val tscJs = WorkspaceTypeScriptSetup.extractTypecheck(workspaceRoot = trailsRoot)
      val jsRuntime = resolveJsRuntime()
      if (tscJs == null || jsRuntime == null) {
        Console.error(
          "trailblaze check: trail-recording validation skipped — " +
            (if (jsRuntime == null) "bun not on PATH" else "bundled tsc payload missing") + ".",
        )
        return EXIT_OK
      }
      // Fold in the validation surfaces the compile phase materialized for classpath-bundled
      // trailmaps (app-bundled targets like `square` that have no workspace `tools/` dir of their
      // own) — but only on an all-workspace run (see the call site). Each surface dir is named by
      // trailmap id and carries `tools/{tsconfig.json, trailblaze-client.d.ts}`, so the validator
      // keys them the same way it keys workspace trailmap dirs. Without this, those targets read as
      // skipped-no-surface — the bulk of the corpus.
      //
      // Only touch disk to discover surfaces on an all-workspace run; the selection rule
      // (scope gating + workspace-id collision drop) is factored into the pure
      // [selectClasspathSurfacesForValidation] so it's unit-testable without a filesystem.
      val discovered = if (allWorkspaceScope) discoverClasspathValidationSurfaces(trailsRoot) else emptyList()
      val classpathSurfaces = selectClasspathSurfacesForValidation(
        workspaceTrailmaps = trailmaps,
        discoveredSurfaces = discovered,
        allWorkspaceScope = allWorkspaceScope,
      )
      val manifestContext = buildTrailValidationManifestContext(
        scopedTrailmaps = trailmaps,
        workspaceRoot = workspaceRoot,
      )
      val report = TrailTscValidator.validate(
        trailsRoot = trailsRoot.toFile(),
        trailmaps = trailmaps + classpathSurfaces,
        jsRuntime = jsRuntime,
        tscJs = tscJs,
        exemptTargets = manifestContext.exemptTargets,
        // Every target with a reachable manifest — a trail whose target: is absent here has no
        // manifest at all and is reported as a permanent skip, not a fatal missing surface.
        knownManifestTargets = manifestContext.knownManifestTargets,
        // A missing surface is only a real "uncovered target" signal on an all-workspace run, which
        // loads every surface. A scoped run (`check <id>` / cwd-scoped) still walks every trail under
        // the workspace but only loaded the selected trailmap's surface, so the OTHER workspace
        // targets legitimately have no surface here — treat those as out-of-scope skips, not defects.
        failOnMissingSurface = allWorkspaceScope,
      )
      Console.log(TrailTscValidator.renderReport(report))
      if (report.hasFatal()) {
        Console.error(
          "trailblaze check: trail-recording validation FAILED — see findings above. To exempt a " +
            "not-yet-ready target, add `trail_validation.exempt: \"<reason>\"` to its trailmap.yaml.",
        )
        EXIT_TYPE_ERROR
      } else {
        EXIT_OK
      }
    } catch (e: Exception) {
      // Non-fatal: an unexpected problem in this phase (a bug here, an IO hiccup) must not spuriously
      // fail a build. Catch Exception (not Throwable) so fatal VM errors — OOM, StackOverflow — still
      // propagate. Genuine type findings return through the normal path above, not here.
      Console.error(
        "trailblaze check: trail-recording validation phase failed (ignored): " +
          (e.message ?: e::class.simpleName),
      )
      EXIT_OK
    }
  }

  /**
   * Discover the classpath-trailmap validation surfaces the compile phase materialized under
   * `<trailsRoot>/.trailblaze/<CLASSPATH_VALIDATION_SURFACES_SUBDIR>/<id>/`. Returns each surface
   * dir (named by trailmap id) that carries a **complete** surface — BOTH `tools/tsconfig.json`
   * AND `tools/trailblaze-client.d.ts`. Requiring both means a half-written dir (e.g. surface
   * emission skipped a target on error but a tsconfig lingered) is not handed to the validator,
   * which would otherwise run `tsc` against the un-augmented SDK and report bogus missing-tool
   * findings. Returns an empty list when the base dir doesn't exist (no classpath trailmaps in
   * scope, or an older compile that predates this feature).
   */
  /**
   * PURE. Choose which discovered classpath validation-surface dirs to fold into the validator's
   * trailmap list. Two rules, both correctness-sensitive:
   *
   *  - **Scope gating**: returns empty unless [allWorkspaceScope]. A scoped `check <id>` (or a run
   *    from inside a trailmap dir) must not pull the large bundled corpora (square / dashboardapp)
   *    into its report — that scale is what `--all` opts into.
   *  - **Collision drop**: a surface whose id (dir name) matches a workspace trailmap id is
   *    dropped. [TrailTscValidator.validate] keys trailmaps by dir name (last-wins), so a workspace
   *    trailmap must always win over a (possibly stale) scratch surface of the same id — the
   *    workspace one carries the real, analyzer-upgraded typing.
   *
   * Visible for testing so the two rules are pinned without staging a filesystem.
   */
  internal fun selectClasspathSurfacesForValidation(
    workspaceTrailmaps: List<Path>,
    discoveredSurfaces: List<Path>,
    allWorkspaceScope: Boolean,
  ): List<Path> {
    if (!allWorkspaceScope) return emptyList()
    val workspaceIds = workspaceTrailmaps.mapNotNull { it.fileName?.toString() }.toSet()
    return discoveredSurfaces.filter { it.fileName?.toString() !in workspaceIds }
  }

  internal fun discoverClasspathValidationSurfaces(trailsRoot: Path): List<Path> {
    val base = TrailTscValidator.classpathValidationSurfacesBaseDir(trailsRoot)
    if (!Files.isDirectory(base)) return emptyList()
    return Files.list(base).use { stream ->
      stream
        .filter { Files.isDirectory(it) }
        .filter { dir ->
          val tools = dir.resolve("tools")
          Files.isRegularFile(tools.resolve("tsconfig.json")) &&
            Files.isRegularFile(tools.resolve(WorkspaceClientDtsGenerator.GENERATED_FILE_NAME))
        }
        .sorted()
        .toList()
    }
  }

  /** The exemption map plus the full set of targets that have a reachable trailmap manifest. */
  internal data class TrailValidationManifestContext(
    /** `target:` value → exemption reason for the targets opted out of the gate (see [assembleExemptTargets]). */
    val exemptTargets: Map<String, String>,
    /**
     * Every `target:` value that resolves to a reachable trailmap manifest — workspace trailmap ids
     * plus classpath-bundled manifest ids. Handed to [TrailTscValidator.validate] so a trail whose
     * target isn't in this set is classified as a permanent no-manifest skip rather than a fatal
     * missing surface.
     */
    val knownManifestTargets: Set<String>,
  )

  /**
   * Resolve the trail-recording validation context from every reachable trailmap manifest — the
   * exemption map AND the set of targets that have a manifest at all — in a single classpath
   * discovery pass.
   *
   * Exemptions come from two sources, unioned (later entries win, so a specific per-manifest reason
   * overrides the generic transitional one):
   *  1. [TRANSITIONAL_EXEMPT_TARGETS] — the central allow-list, now down to `default` (a surfaced
   *     Kotlin target with no `trailmap.yaml` to annotate). Its former manifest-LESS entries
   *     (placeholder / package-id / no-`target:`) are handled structurally instead: a target with no
   *     reachable manifest is a permanent [TrailTscValidator.Report.skippedNoManifest], not an
   *     exemption.
   *  2. Per-target `trail_validation.exempt` declared in any reachable `trailmap.yaml` — filesystem
   *     workspace trailmaps AND classpath-bundled trailmaps. This is the durable, co-located
   *     mechanism a target uses once its manifest is reachable but it's not yet clean.
   *
   * [scopedTrailmaps] is the set actually being validated this run (only the selected trailmap on a
   * `check <id>` / cwd-scoped run); exemptions are read from it. [knownManifestTargets], though, must
   * span the WHOLE workspace, not just the scope: the validator walks every trail under the workspace
   * regardless of scope, so a trail targeting an out-of-scope-but-real workspace trailmap must be
   * recognized as manifest-BACKED (→ `skippedNoSurface`, non-fatal on a scoped run) rather than
   * misclassified as a permanent `skippedNoManifest`. So the known set is built from every
   * `trails/config/trailmaps/<id>/trailmap.yaml` plus the classpath manifest ids, independent of scope.
   */
  private fun buildTrailValidationManifestContext(
    scopedTrailmaps: List<Path>,
    workspaceRoot: File,
  ): TrailValidationManifestContext {
    // Extract each reachable manifest's (id -> non-blank exempt reason). The pure merge with
    // precedence rules lives in [assembleExemptTargets] so it can be unit-tested without disk IO.
    fun exemptionOf(loaded: LoadedTrailblazeTrailmapManifest): Pair<String, String>? =
      loaded.manifest.trailValidation?.exempt?.takeIf { it.isNotBlank() }?.let { loaded.manifest.id to it }

    val scopedIds = scopedTrailmaps.mapNotNull { it.fileName?.toString() }.toSet()
    val workspaceExemptions = scopedTrailmaps.mapNotNull { dir ->
      val manifestFile = dir.resolve(TrailblazeConfigPaths.TRAILMAP_MANIFEST_FILENAME).toFile()
      if (!manifestFile.isFile) return@mapNotNull null
      runCatching { TrailblazeTrailmapManifestLoader.load(manifestFile) }
        .getOrNull()?.let(::exemptionOf)
    }
    // Classpath-bundled trailmap manifests (framework-bundled targets, plus any additional targets a
    // consumer ships on the classpath). Discovery failure is non-fatal — workspace exemptions still
    // apply — but log it so a manifest-loading regression is visible rather than silently dropping a
    // bundled target's exemption (or shrinking the known-manifest set).
    val classpathManifests =
      runCatching { TrailblazeTrailmapManifestLoader.discoverAndLoadFromClasspath() }
        .onFailure { e ->
          Console.error(
            "trailblaze check: classpath trailmap discovery failed while building the validation " +
              "context (continuing with workspace + transitional exemptions and workspace-only " +
              "known manifests; classpath-bundled exemptions and manifest ids are unavailable): " +
              (e.message ?: e::class.simpleName),
          )
        }
        .getOrDefault(emptyList())
    val classpathExemptions = classpathManifests.mapNotNull(::exemptionOf)
    val classpathIds = classpathManifests.map { it.manifest.id }.toSet()

    return TrailValidationManifestContext(
      exemptTargets = assembleExemptTargets(
        transitional = TRANSITIONAL_EXEMPT_TARGETS,
        workspaceExemptions = workspaceExemptions,
        classpathExemptions = classpathExemptions,
        workspaceIds = scopedIds,
      ),
      knownManifestTargets = listAllWorkspaceTrailmapIds(workspaceRoot) + classpathIds,
    )
  }

  /**
   * Every workspace trailmap id (the name of each `trails/config/trailmaps/<id>/` directory that
   * carries a `trailmap.yaml`), independent of the current check scope. Used to build the
   * known-manifest set so an out-of-scope-but-real workspace target isn't mistaken for one that has
   * no manifest at all. A missing trailmaps dir or an I/O error yields an empty set — a degraded but
   * safe input (targets then read as no-manifest skips rather than crashing the phase).
   *
   * Visible for testing so the scope-independence (whole workspace, not just the checked trailmap) is
   * pinned without driving a full `check` run.
   */
  internal fun listAllWorkspaceTrailmapIds(workspaceRoot: File): Set<String> {
    val trailmapsDir = File(workspaceRoot, TrailblazeConfigPaths.WORKSPACE_TRAILMAPS_DIR)
    val dirs = trailmapsDir.listFiles { f ->
      f.isDirectory && File(f, TrailblazeConfigPaths.TRAILMAP_MANIFEST_FILENAME).isFile
    } ?: return emptySet()
    return dirs.map { it.name }.toSet()
  }

  /**
   * PURE. Merge the exemption sources into a single `target -> reason` map with two precedence rules:
   *  - A per-manifest exemption overrides the generic [transitional] reason for the same id.
   *  - Workspace-over-classpath: a classpath-bundled exemption is dropped when its id is a workspace
   *    trailmap ([workspaceIds]) — the workspace manifest's decision (exempt or NOT) is authoritative,
   *    so a local non-exempt shadow of a bundled target keeps its findings fatal rather than being
   *    silently downgraded by the bundled manifest's exemption.
   *
   * Visible for testing so the precedence rules are pinned without staging manifests on disk.
   */
  internal fun assembleExemptTargets(
    transitional: Map<String, String>,
    workspaceExemptions: List<Pair<String, String>>,
    classpathExemptions: List<Pair<String, String>>,
    workspaceIds: Set<String>,
  ): Map<String, String> {
    val exempt = LinkedHashMap<String, String>()
    exempt.putAll(transitional)
    workspaceExemptions.forEach { (id, reason) -> exempt[id] = reason }
    classpathExemptions.forEach { (id, reason) -> if (id !in workspaceIds) exempt[id] = reason }
    return exempt
  }

  internal fun runTypecheckPhase(workspaceRoot: File, trailmaps: List<Path>): Int {
    if (trailmaps.isEmpty()) {
      Console.log("trailblaze check: no trailmaps to type-check.")
      return EXIT_OK
    }

    // A trailmap may legitimately carry ANY mix of Kotlin (class-backed `.tool.yaml`),
    // TypeScript, and YAML tools — including zero TypeScript. `tsc` fails with TS18003 ("No
    // inputs were found") when pointed at a tools/ dir with no `.ts`/`.js` sources, so a
    // Kotlin/YAML-only trailmap (e.g. a class-backed library trailmap) would otherwise fail the
    // typecheck for having nothing to check. Partition those out and skip them — there is no
    // TypeScript to validate. Only trailmaps with at least one typecheckable source reach `tsc`.
    val (typecheckable, skipped) = trailmaps.partition { hasTypeScriptToolSources(it) }
    skipped.forEach { trailmap ->
      Console.log("── typecheck: ${trailmap.fileName} (no TypeScript tools — skipped) ────")
    }
    if (typecheckable.isEmpty()) {
      Console.log("trailblaze check: no trailmaps with TypeScript tools to type-check.")
      return EXIT_OK
    }

    // [WorkspaceTypeScriptSetup.setUp] writes its outputs relative to a `trails/` directory
    // (same convention `CompileCommand` follows — see CompileCommand.generatorRoot). The
    // workspace root from the walk-up is the directory containing `trails/config/trailmaps/`,
    // so descend one level into `trails/` before handing it off — otherwise setUp would
    // emit `.trailblaze/sdk/` one level above where trailmap tsconfigs reference it.
    val trailsRoot = workspaceRoot.toPath().resolve(TrailblazeConfigPaths.WORKSPACE_TRAILS_DIR)

    // Set up the workspace's SDK declaration bundle. Cheap idempotent no-ops if
    // the compile phase already ran. Setup failures are operational (filesystem /
    // resource issues), not user input — map to EXIT_OPERATIONAL_ERROR so shell
    // consumers can distinguish "your invocation was wrong" (USAGE) from "the
    // framework couldn't do its work" (OPERATIONAL).
    try {
      WorkspaceTypeScriptSetup.setUp(workspaceRoot = trailsRoot)
    } catch (e: Exception) {
      Console.error(
        "trailblaze check: TypeScript workspace setup failed: ${e.message ?: e.javaClass.simpleName}",
      )
      return EXIT_OPERATIONAL_ERROR
    }

    // The tsc payload is opt-in (NOT part of setUp): only the typecheck phase needs it,
    // and we don't want the compile phase paying the 6 MB extraction cost.
    val tscJs = try {
      WorkspaceTypeScriptSetup.extractTypecheck(workspaceRoot = trailsRoot)
    } catch (e: Exception) {
      Console.error(
        "trailblaze check: tsc extraction failed: ${e.message ?: e.javaClass.simpleName}",
      )
      return EXIT_OPERATIONAL_ERROR
    }
    if (tscJs == null) {
      Console.error(
        "trailblaze check: the framework JAR did not ship a bundled tsc payload. " +
          "If you're running a framework built from source, run `bun install` in " +
          "`sdks/typescript/` and rebuild — pre-built distributions always " +
          "ship tsc.",
      )
      return EXIT_OPERATIONAL_ERROR
    }

    val jsRuntime = resolveJsRuntime()
    if (jsRuntime == null) {
      Console.error(
        "trailblaze check: `bun` not found on PATH. Install bun and re-run — " +
          "see https://bun.sh/ for the one-line install. Trailblaze uses bun as " +
          "its sole JavaScript runtime (no Node fallback).",
      )
      return EXIT_USAGE
    }

    // Run tsc once per trailmap and aggregate exit codes. We do NOT short-circuit on the
    // first failure — CI cares about the full picture, and `tsc`'s per-file diagnostics
    // are independent across trailmaps anyway. Failure precedence: USAGE (missing tsconfig)
    // beats TYPE_ERROR (tsc found problems) — if any trailmap is missing its tsconfig the
    // process exits USAGE so the operator knows the compile phase didn't materialize it.
    // A final summary names every failed trailmap so the operator doesn't have to scan
    // the full log on `--all`.
    var sawTypeError = false
    var sawMissingTsconfig = false
    val failedTrailmaps = mutableListOf<String>()
    for (trailmap in typecheckable) {
      val tsconfig = trailmap.resolve("tools").resolve("tsconfig.json")
      if (!Files.isRegularFile(tsconfig)) {
        Console.error(
          "trailblaze check: trailmap '${trailmap.fileName}' has no tools/tsconfig.json. " +
            "The compile phase should have emitted it — re-run `trailblaze check`.",
        )
        sawMissingTsconfig = true
        failedTrailmaps += trailmap.fileName.toString()
        continue
      }
      Console.log("── typecheck: ${trailmap.fileName} ────")
      val exit = runTsc(jsRuntime = jsRuntime, tscJs = tscJs, tsconfig = tsconfig)
      if (exit != 0) {
        // Normalize tsc's specific exit value (which drifted from `1` in 5.x to `2`
        // in 6.x for the same diagnostics) to a single [EXIT_TYPE_ERROR] so shell
        // consumers see a stable contract regardless of tsc version.
        sawTypeError = true
        failedTrailmaps += trailmap.fileName.toString()
      }
    }
    if (failedTrailmaps.isNotEmpty() && typecheckable.size > 1) {
      // One-line summary on multi-trailmap runs so a CI consumer can grep the tail of the
      // log and see exactly which trailmaps failed. Single-trailmap runs skip the summary —
      // the per-trailmap header already named the failing trailmap.
      Console.error("trailblaze check: failed trailmaps: ${failedTrailmaps.joinToString(", ")}")
    }
    return when {
      sawMissingTsconfig -> EXIT_USAGE
      sawTypeError -> EXIT_TYPE_ERROR
      else -> EXIT_OK
    }
  }

  /**
   * True if [trailmapDir]'s `tools/` subtree contains at least one source the per-trailmap `tsc`
   * pass would actually type-check — a `.ts` or `.js` file that is not a `.test.ts`. This mirrors
   * the emitted tsconfig's include globs (`.ts` + `.js`) and its `.test.ts` exclude, so it returns
   * true exactly when `tsc` would find at least one input.
   *
   * A trailmap whose tools are entirely Kotlin (class-backed `.tool.yaml`) and/or YAML has no
   * TypeScript to validate; [runTypecheckPhase] skips it rather than handing `tsc` an empty input
   * set (which fails with TS18003, "No inputs were found in config file"). This is what lets a
   * trailmap carry any mix — or zero — TypeScript tools.
   */
  internal fun hasTypeScriptToolSources(trailmapDir: Path): Boolean {
    val toolsDir = trailmapDir.resolve("tools")
    if (!Files.isDirectory(toolsDir)) return false
    Files.walk(toolsDir).use { stream ->
      return stream.anyMatch { p ->
        if (!Files.isRegularFile(p)) return@anyMatch false
        val name = p.fileName.toString()
        (name.endsWith(".ts") || name.endsWith(".js")) && !name.endsWith(".test.ts")
      }
    }
  }

  /**
   * Resolve the list of trailmap directories the typecheck + test phases should run against.
   * Mirrors [CompileCommand]'s discovery shape — we don't reuse the project-config loader
   * here because the per-trailmap typecheck only cares about per-trailmap tsconfig presence, not
   * the full dependency closure (transitive deps surface through the per-trailmap tsconfig's
   * generated `client.d.ts` already).
   *
   * Returns `null` when the user gave an unresolvable input (an unknown trailmap name, or no
   * trailmap at the caller's cwd) — caller maps that to [TrailblazeExitCode.MISUSE.code].
   *
   * `trailmapId` / `typecheckAll` come from the dispatch decision in [decideTypecheckDispatch]
   * rather than the CLI fields directly so a `--all` invocation routes through the same
   * code path as a `forceAll` coercion.
   */
  internal fun resolveTrailmapsToCheck(
    workspaceRoot: File,
    callerCwd: Path,
    trailmapId: String?,
    typecheckAll: Boolean,
  ): List<Path>? {
    val trailmapsDir = File(workspaceRoot, TrailblazeConfigPaths.WORKSPACE_TRAILMAPS_DIR)
    if (!trailmapsDir.isDirectory) {
      Console.error(
        "trailblaze check: no trailmaps/ directory found at ${trailmapsDir.absolutePath}.",
      )
      return null
    }

    if (typecheckAll) {
      val trailmapDirs = trailmapsDir.listFiles { f ->
        f.isDirectory && File(f, TrailblazeConfigPaths.TRAILMAP_MANIFEST_FILENAME).isFile
      }
      // `File.listFiles` returns `null` on I/O errors (permission denied, dir
      // disappeared mid-walk). Treating null as "no trailmaps" would silently hide a real
      // filesystem problem — surface it as a usage error with a pointer to the dir.
      if (trailmapDirs == null) {
        Console.error(
          "trailblaze check: failed to list trailmaps at ${trailmapsDir.absolutePath} " +
            "(I/O or permission error).",
        )
        return null
      }
      return trailmapDirs.sortedBy { it.name }.map { it.toPath() }
    }

    if (trailmapId != null) {
      val target = File(trailmapsDir, trailmapId)
      if (!File(target, TrailblazeConfigPaths.TRAILMAP_MANIFEST_FILENAME).isFile) {
        Console.error(
          "trailblaze check: unknown trailmap '$trailmapId' — no trailmap.yaml at " +
            "${target.absolutePath}.",
        )
        return null
      }
      return listOf(target.toPath())
    }

    // No --all and no positional arg → walk up from cwd to find the enclosing trailmap.
    val trailmap = findEnclosingTrailmap(callerCwd, trailmapsDirAbs = trailmapsDir.canonicalFile.toPath())
    if (trailmap == null) {
      Console.error(
        "trailblaze check: no trailmap to type-check. Pass a trailmap id (e.g. `trailblaze " +
          "check wikipedia`) or --all, or run from inside a trailmap's directory tree.",
      )
      return null
    }
    return listOf(trailmap)
  }

  /**
   * Walk up from [startPath] looking for the nearest ancestor containing a `trailmap.yaml`
   * sibling, stopping at [trailmapsDirAbs] (we don't want to walk past `trailmaps/` into the
   * workspace root — only directories under `trailmaps/<id>/` are valid trailmap roots).
   *
   * Visible for testing.
   */
  internal fun findEnclosingTrailmap(startPath: Path, trailmapsDirAbs: Path): Path? {
    // Canonicalize both sides via `toRealPath()` so symlink-prefixed temp roots like
    // macOS's `/tmp -> /private/tmp` don't make the `parent == trailmapsDirAbs` comparison
    // miss its target. Falls back to `toAbsolutePath().normalize()` if the path doesn't
    // exist yet — important for unit tests that pass paths constructed by string-
    // concatenation under a temp dir that hasn't been written to.
    val canonicalTrailmapsDir = trailmapsDirAbs.canonicalize()
    val startDir = startPath.canonicalize()
    var current: Path? = if (Files.isRegularFile(startDir)) startDir.parent else startDir
    while (current != null) {
      // A "trailmap root" is a directory directly under `trailmaps/` (depth 1) that contains a
      // `trailmap.yaml` file. Detecting both conditions guards against the edge where a user
      // drops a stray `trailmap.yaml` somewhere unrelated under their workspace.
      val parentCanonical = current.parent?.canonicalize()
      if (Files.isRegularFile(current.resolve(TrailblazeConfigPaths.TRAILMAP_MANIFEST_FILENAME)) &&
        parentCanonical != null &&
        parentCanonical == canonicalTrailmapsDir
      ) {
        return current
      }
      if (current.canonicalize() == canonicalTrailmapsDir) return null
      current = current.parent
    }
    return null
  }

  /**
   * Canonicalize a path so it compares stably to other canonicalized paths. Uses
   * `toRealPath()` when the path exists (resolves symlinks like macOS's `/tmp ->
   * /private/tmp`); falls back to `toAbsolutePath().normalize()` otherwise so callers
   * can hand us paths constructed by string-concatenation under not-yet-materialized
   * directories.
   */
  private fun Path.canonicalize(): Path = try {
    this.toRealPath()
  } catch (_: java.io.IOException) {
    this.toAbsolutePath().normalize()
  }

  /**
   * Resolve the `bun` binary on PATH — Trailblaze's sole supported JavaScript
   * runtime. The framework's scripted-tool analyzer, esbuild pipeline (see
   * [xyz.block.trailblaze.scripting.LazyYamlScriptedToolRegistration]), and
   * per-trailmap test runner all route through bun, so requiring it
   * uniformly keeps the developer-experience contract simple: "install bun,
   * everything else just works."
   *
   * Returns `null` when bun is missing. PATH probing delegates to
   * [CliPathUtils.isCommandOnPath] for cross-platform behavior (`.exe`
   * resolution on Windows via `PATHEXT`).
   */
  internal fun resolveJsRuntime(): String? {
    for (candidate in JS_RUNTIME_PREFERENCE) {
      if (CliPathUtils.isCommandOnPath(candidate)) return candidate
    }
    return null
  }

  /**
   * Spawn `<jsRuntime> <tscJs> --pretty --noEmit --project <tsconfig>` against [tsconfig]
   * and pipe the output to the caller's terminal verbatim. `--pretty` keeps tsc's
   * coloring; `--noEmit` is the standard "check only" flag that matches what the IDE's
   * language server does on save. We pass `--project` explicitly so tsc anchors its
   * include globs at the trailmap's `tools/` dir rather than the cwd.
   *
   * Returns the spawned process's exit code — tsc itself uses `0` for success and `1`
   * for type errors, which propagate upstream as [EXIT_TYPE_ERROR].
   */
  private fun runTsc(jsRuntime: String, tscJs: Path, tsconfig: Path): Int {
    val argv = listOf(
      jsRuntime,
      tscJs.toAbsolutePath().toString(),
      "--pretty",
      "--noEmit",
      "--project",
      tsconfig.toAbsolutePath().toString(),
    )
    val timeoutMs = resolveTscTimeoutMs()
    return try {
      val proc = ProcessBuilder(argv)
        .redirectErrorStream(true)
        // No `directory(...)` — `tsc --project <abs>` reads its include set from the
        // resolved tsconfig anchor, not the cwd.
        .inheritIO()
        .start()
      // Default cap is 5 minutes. tsc is usually well under 5 seconds even on cold
      // caches; a 5-minute ceiling catches pathological misconfigurations (infinite
      // include glob, runaway process) without ever interrupting a real type-check.
      // Override via [TIMEOUT_MS_ENV_VAR] when working with codebases that have known
      // pathological cyclic types — see [resolveTscTimeoutMs] for clamp behavior.
      if (proc.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
        proc.exitValue()
      } else {
        Console.error(
          "trailblaze check: tsc did not finish within ${timeoutMs}ms — killing. " +
            "Bump $TIMEOUT_MS_ENV_VAR if this is a known-slow codebase.",
        )
        proc.destroyForcibly()
        proc.waitFor(10, TimeUnit.SECONDS)
        EXIT_TYPE_ERROR
      }
    } catch (e: Exception) {
      Console.error(
        "trailblaze check: failed to spawn $jsRuntime: ${e.message ?: e.javaClass.simpleName}",
      )
      EXIT_TYPE_ERROR
    }
  }

  /**
   * Resolve the per-trailmap tsc subprocess timeout in milliseconds.
   *
   * Reads [TIMEOUT_MS_ENV_VAR] once. A missing / unparseable value falls back to
   * [DEFAULT_TSC_TIMEOUT_MS] (5 minutes). The result is clamped to a 1-minute lower
   * bound so a fat-fingered `=0` or `=1` doesn't make `waitFor` return false
   * immediately and report every typecheck as a timeout — same defensive clamp the
   * Gradle install-task helpers in this repo use for an analogous env var.
   *
   * Logged once per command invocation (only when overridden) so a CI consumer can
   * trace which value was actually in effect when an unexpected timeout fires.
   */
  internal fun resolveTscTimeoutMs(): Long {
    val raw = System.getenv(TIMEOUT_MS_ENV_VAR) ?: return DEFAULT_TSC_TIMEOUT_MS
    val parsed = raw.toLongOrNull()
    if (parsed == null) {
      Console.error(
        "trailblaze check: $TIMEOUT_MS_ENV_VAR='$raw' is not a valid number of " +
          "milliseconds — using default ${DEFAULT_TSC_TIMEOUT_MS}ms.",
      )
      return DEFAULT_TSC_TIMEOUT_MS
    }
    val clamped = parsed.coerceAtLeast(MIN_TSC_TIMEOUT_MS)
    if (clamped != parsed) {
      Console.error(
        "trailblaze check: $TIMEOUT_MS_ENV_VAR=${parsed}ms is below the 1-minute " +
          "floor — clamped to ${clamped}ms.",
      )
    }
    return clamped
  }

  /**
   * Result of [resolveWorkspace]. Carries everything step 2 needs to dispatch the
   * inner typecheck phase without re-deriving from cwd.
   *
   * - [scopeTrailmapId] / [scopeTrailmapDir]: the explicit trailmap the user asked for (or that we
   *   resolved by enumeration), or null when no specific trailmap was scoped.
   * - [forceAll]: true when the resolver determined the typecheck should run against every
   *   trailmap — either because `--all` was passed, or because the caller cwd has no enclosing
   *   trailmap for the inner walk-up to find. Lifting this here keeps the call site readable
   *   (`checkAll || resolved.forceAll`) and puts the decision next to the rest of the
   *   workspace-shape logic.
   */
  internal data class WorkspaceResolution(
    val workspaceRoot: File,
    val scopeTrailmapId: String?,
    val scopeTrailmapDir: File?,
    val forceAll: Boolean,
  ) {
    init {
      // Forcing function: a scoped trailmap already implies "typecheck this one trailmap", while
      // `forceAll` implies workspace-wide. The combination is meaningless and would feed
      // [decideTypecheckDispatch] a state that produces an internally inconsistent
      // dispatch (trailmap-dir cwd with `typecheckAll = true`), which the typecheck phase's
      // pre-resolution then rejects as a usage error. Catch it at construction so the
      // invariant lives next to the data instead of leaking out as a confusing error.
      require(!(scopeTrailmapDir != null && forceAll)) {
        "WorkspaceResolution: scopeTrailmapDir and forceAll are mutually exclusive — got " +
          "scopeTrailmapDir=$scopeTrailmapDir, forceAll=$forceAll. A scoped trailmap already implies " +
          "per-trailmap typecheck; forceAll implies workspace-wide. Pick one."
      }
    }
  }

  /**
   * What step 2 actually hands to the typecheck phase: a cwd to root its walk-up against,
   * a trailmap-id (or null), and a typecheck-all flag. Pure data — exposed for unit tests
   * of [decideTypecheckDispatch].
   */
  internal data class TypecheckDispatch(
    val cwd: Path,
    val trailmapId: String?,
    val typecheckAll: Boolean,
  )

  /**
   * `internal` (rather than `private`, which the sibling [CompileCommand] uses for
   * its companion) so the migrated typecheck-helper tests in `CheckCommandTest`
   * can reach the exit-code constants, `validateTrailmapId`, `JS_RUNTIME_PREFERENCE`,
   * and the timeout-clamp invariants directly. If the typecheck phase ever leaves
   * `CheckCommand`, tighten this back to `private` and lift these onto whatever
   * object takes ownership of the phase.
   */
  internal companion object {
    /** Picocli OK. */
    const val EXIT_OK = 0

    /**
     * The shell command this scaffold pins into `scripts.postinstall`. Guarded with
     * `command -v` so `npm install` succeeds gracefully when the `trailblaze` binary
     * isn't on PATH — important for in-repo contributors who invoke the wrapper
     * (`./trailblaze` from the repo root) rather than a globally installed binary.
     * If the CLI IS on PATH the postinstall hydrates `.trailblaze/sdk/` + the per-
     * trailmap typed bindings the IDE indexes; if it isn't, npm prints a one-line
     * advisory instead of failing the install. Exact-match string-compared in
     * [scaffoldWorkspacePackageJson]: a hand-edit that diverges by even a character
     * triggers the "Tip:" hint, intentionally — the bootstrap loop only closes when
     * the file matches verbatim.
     */
    internal const val POSTINSTALL_HOOK: String =
      "command -v trailblaze >/dev/null 2>&1 && trailblaze check || " +
        "printf 'trailblaze CLI not on PATH; skipping IDE bootstrap.\\n'"

    /**
     * Fallback `name` field for the scaffolded package.json when the workspace
     * directory's basename can't yield a valid npm-style identifier (empty,
     * dot-prefixed, or — after sanitization — produces an empty string).
     */
    internal const val DEFAULT_WORKSPACE_NAME: String = "trailblaze-workspace"

    /**
     * Lower-case the workspace dir's basename and replace anything outside
     * `[a-z0-9_-]` with `-`. Falls back to [DEFAULT_WORKSPACE_NAME] for inputs that
     * are blank, dot-prefixed (a hidden dir is never a great npm name), sanitize
     * down to an empty string (`"!!!"` etc.), or that sanitize to a leading-`-`
     * name (`"@scope"` → `"-scope"`, which npm rejects — defeats the whole point of
     * the scaffold). Matches npm's "private package" naming surface — strict enough
     * to avoid `npm install` warnings, loose enough to round-trip common workspace
     * dir names verbatim.
     */
    internal fun sanitizeWorkspaceName(basename: String): String {
      if (basename.isBlank() || basename.startsWith(".")) return DEFAULT_WORKSPACE_NAME
      val sanitized = basename.lowercase().replace(Regex("[^a-z0-9_-]"), "-")
      // npm rejects names starting with `-` or `.` — `npm install` would fail loudly
      // on the very command we're scaffolding. Strip a leading run before the
      // ifBlank fallback. Trailing hyphens are fine (npm accepts them).
      val trimmed = sanitized.trimStart('-', '.')
      return trimmed.ifBlank { DEFAULT_WORKSPACE_NAME }
    }

    /**
     * Build the scaffold's literal file contents.
     *
     * Built via [buildJsonObject] rather than raw string interpolation because
     * [POSTINSTALL_HOOK] contains characters that need JSON-escaping (`\n` inside
     * the `printf` fallback). Interpolating into a triple-quoted template would
     * produce invalid JSON (the raw `\n` would be parsed as a newline character
     * by any consumer, dropping the literal backslash the shell needs).
     *
     * Trailing newline so a `cat` of the file under tooling that line-counts (most
     * editors, `wc -l`) reports the same row count humans see; matches the
     * convention every other file we emit follows.
     */
    internal fun packageJsonTemplate(workspaceRoot: File): String {
      val name = sanitizeWorkspaceName(workspaceRoot.name)
      val obj = buildJsonObject {
        put("name", name)
        put("private", true)
        putJsonObject("scripts") {
          put("postinstall", POSTINSTALL_HOOK)
        }
      }
      return JSON_FORMATTER.encodeToString(JsonObject.serializer(), obj) + "\n"
    }

    /**
     * `Json` configured to pretty-print with the 2-space indent used by the rest of
     * this codebase's emitted files. Single shared instance because [Json] is
     * thread-safe and the configuration never changes per call.
     */
    private val JSON_FORMATTER: Json = Json {
      prettyPrint = true
      prettyPrintIndent = "  "
    }

    /**
     * Scaffold `<workspaceRoot>/package.json` on first run. Four branches:
     *
     *  - **Absent** → write [packageJsonTemplate] and ask the developer to commit it.
     *    Write failures (permission denied, disk full, parent dir missing) log to
     *    stderr under the `trailblaze check:` prefix and return without aborting
     *    the rest of `check`. Once the file lands, every future clone is
     *    bootstrapped by a standard `npm install` (its `postinstall` re-runs
     *    `trailblaze check`, which re-populates `.trailblaze/sdk/` and the per-trailmap
     *    typed bindings the IDE indexes).
     *  - **Present (a regular file), postinstall == [POSTINSTALL_HOOK]** → silent
     *    no-op. The file is already wired the way we'd write it.
     *  - **Present (a regular file), anything else** → leave the file untouched,
     *    print a one-line hint. Once a package.json exists it belongs to the
     *    developer; we never overwrite or auto-edit it.
     *  - **Present but unparseable / not a regular file** → silent no-op. We
     *    deliberately don't surface the read or parse error: a malformed
     *    package.json (or a path collision where `package.json` is a directory)
     *    will be flagged the next time the developer runs `npm install` (or any
     *    other JS tool) with a much better diagnostic than we could produce here.
     *
     * Visible for testing via the [CheckCommand] companion entrypoint so the four
     * branches can be exercised without spinning up the full compile + typecheck
     * pipeline. Strictly non-fatal — write-side I/O failures log to stderr; read-
     * side failures (including parse errors and non-regular-file path collisions)
     * are silent on purpose.
     */
    internal fun scaffoldWorkspacePackageJson(workspaceRoot: File) {
      val pkgJson = File(workspaceRoot, "package.json")
      if (!pkgJson.exists()) {
        try {
          pkgJson.writeText(packageJsonTemplate(workspaceRoot))
        } catch (e: Exception) {
          Console.error(
            "trailblaze check: failed to scaffold ${pkgJson.absolutePath}: " +
              (e.message ?: e.javaClass.simpleName),
          )
          return
        }
        Console.info(
          "Wrote ${pkgJson.absolutePath} — review and commit so `bun install` " +
            "auto-bootstraps for future clones.",
        )
        return
      }
      // `exists()` is reflexively true for directories too — guard with `isFile`
      // so a `<workspaceRoot>/package.json` directory (filesystem corruption, a
      // weird mis-clone) doesn't fall through to `readText()` and throw. Bail
      // silently per the kdoc contract.
      if (!pkgJson.isFile) return
      val postinstall = try {
        val root = Json.parseToJsonElement(pkgJson.readText()).jsonObject
        val scripts = root["scripts"] as? JsonObject
        (scripts?.get("postinstall") as? JsonPrimitive)?.contentOrNull
      } catch (_: Exception) {
        // Unparseable / unexpected shape — treat as "developer's problem" and stay
        // quiet. See the kdoc above for why we don't surface read/parse errors.
        return
      }
      if (postinstall == POSTINSTALL_HOOK) return
      Console.info(
        "Tip: set `\"postinstall\": \"$POSTINSTALL_HOOK\"` in " +
          "${pkgJson.absolutePath} so `bun install` auto-bootstraps the IDE for " +
          "future clones.",
      )
    }


    /**
     * Type errors (or any tsc-side spawn failure). Maps to picocli convention `1`.
     * Matches tsc's own non-zero exit semantics so shell scripts that already check
     * `tsc && deploy` keep working when swapped to `trailblaze check && deploy`.
     */
    const val EXIT_TYPE_ERROR = 1

    /** Usage errors (missing workspace, unknown / invalid trailmap, missing runtime, missing tsconfig). */
    const val EXIT_USAGE = 2

    /**
     * Operational failures: framework JAR missing the bundled tsc payload, filesystem
     * I/O errors during workspace setup, etc. Distinct from [EXIT_TYPE_ERROR] (which
     * is for tsc-reported failures) and [EXIT_USAGE] (which is for caller-side
     * mistakes), so a CI consumer can route "user error" vs "framework broken" vs
     * "code is broken" to different alerts. Maps to `3` to avoid colliding with the
     * other two exit codes.
     */
    const val EXIT_OPERATIONAL_ERROR = 3

    /**
     * Central allow-list of targets exempted from failing the trail-recording validation gate, keyed
     * by `target:` value with a human-readable reason. This is now only for targets that HAVE a
     * validatable surface but whose exemption can't live in a `trailmap.yaml` — i.e. a synthetic
     * Kotlin-defined target with no manifest file to annotate.
     *
     * It used to also carry the manifest-LESS placeholder / package-id targets (`trailrunner`,
     * `xyz.block.trailblaze.examples.sampleapp`) and the no-`target:` case. Those are no longer
     * exemptions: a trail whose `target:` resolves to no reachable trailmap manifest is now a
     * first-class permanent skip ([TrailTscValidator.Report.skippedNoManifest]), classified
     * structurally by absence from the known-manifest set rather than by a hand-maintained list.
     * A target that has its own `trailmap.yaml` uses the durable, co-located
     * `trail_validation.exempt` there instead.
     *
     * What remains:
     *  - **`default`** — [xyz.block.trailblaze.model.TrailblazeHostAppTarget.DefaultTrailblazeHostAppTarget],
     *    the generic stand-in target. It's a Kotlin `data object` (no `trailmap.yaml`) with the minimal
     *    built-in tool surface, but several smoke / eval / cross-target trails set `target: default`
     *    while recording tools from other trailmaps (`openUrl`, `runIf`, app-specific launch tools),
     *    which its surface doesn't advertise. Those are surface-fidelity findings, reported but non-fatal.
     *
     * See docs/devlog/2026-07-01-trail-recording-type-validation.md.
     */
    internal val TRANSITIONAL_EXEMPT_TARGETS: Map<String, String> = mapOf(
      "default" to
        "Synthetic generic-stand-in target (DefaultTrailblazeHostAppTarget, no trailmap.yaml); " +
        "smoke/eval/cross-target trails record tools its minimal built-in surface doesn't advertise.",
    )

    /**
     * The sole JavaScript runtime Trailblaze runs against. A single-entry list
     * rather than a literal `"bun"` so future extension points (e.g. allowing
     * a vendored-in runtime, or splitting Windows/non-Windows binary names)
     * have a single place to grow. Note: there is intentionally NO `node`
     * fallback — Trailblaze's contract on every host (CI agents, binary
     * installs, local dev) is that bun is the only JS runtime required.
     */
    internal val JS_RUNTIME_PREFERENCE: List<String> = listOf("bun")

    /**
     * Default per-trailmap tsc subprocess timeout. 5 minutes is more than 50× the typical
     * tsc cold-start + check time on a small trailmap; the override exists for codebases
     * with known pathological types (deeply-recursive zod schemas, cyclic interfaces).
     */
    internal const val DEFAULT_TSC_TIMEOUT_MS: Long = 5L * 60L * 1000L

    /**
     * Lower clamp for [TIMEOUT_MS_ENV_VAR]. A value below this is forced up to one
     * minute so a fat-fingered `=0` doesn't cause every type-check to report as a
     * timeout instantly (mirroring the same clamp `InstallAuthorToolDepsTask` uses
     * for its `trailblazeInstallTimeoutMinutes` override).
     */
    internal const val MIN_TSC_TIMEOUT_MS: Long = 60L * 1000L

    /**
     * Env var that overrides [DEFAULT_TSC_TIMEOUT_MS] in milliseconds. Read once per
     * trailmap via [resolveTscTimeoutMs]; an unparseable or below-floor value falls back
     * to the default / clamp with a single line of stderr telemetry so the operator
     * knows the override didn't take effect.
     */
    internal const val TIMEOUT_MS_ENV_VAR: String = "TRAILBLAZE_TYPECHECK_TIMEOUT_MS"

    /**
     * Reject trailmap ids that aren't a single directory-name segment under `trailmaps/`. The
     * downstream `File(trailmapsDir, trailmapId)` would otherwise let an explicit `..` escape
     * the trailmaps tree — the `trailmap.yaml` existence guard happens to catch the common
     * case ("no trailmap.yaml at /etc/foo/trailmap.yaml"), but defense-in-depth (and a clearer
     * error message) is cheap. Returns the reason as a human-readable string, or null
     * when the id is well-formed.
     */
    internal fun validateTrailmapId(id: String): String? {
      if (id.isBlank()) return "trailmap id must not be blank"
      if (id.contains('/') || id.contains('\\')) return "trailmap id must not contain path separators"
      if (id == "." || id == ".." || id.split('/', '\\').any { it == ".." }) {
        return "trailmap id must not contain `..` segments"
      }
      // Absolute Unix path (`/etc/foo`) or Windows drive letter (`C:\…`).
      if (id.startsWith("/") || id.startsWith("\\") || (id.length >= 2 && id[1] == ':')) {
        return "trailmap id must be a single directory name under trailmaps/, not an absolute path"
      }
      return null
    }

    /**
     * Decide how to drive the typecheck phase for the given resolution + caller state.
     * Pure function; no I/O. The branches:
     *  - **Resolved a trailmap directory** (explicit trailmap-id, or enumeration found one):
     *    point cwd at the trailmap so the typecheck phase's no-arg walk-up latches onto it.
     *    `typecheckAll = false`.
     *  - **`--all` (explicit or coerced via [WorkspaceResolution.forceAll])**: point cwd
     *    at the workspace root, set `typecheckAll = true`.
     *  - **Otherwise** (no scope, no force): leave cwd as the caller's original cwd —
     *    the typecheck phase's walk-up will find whatever enclosing trailmap the cwd sits in.
     *    `typecheckAll = false`.
     */
    internal fun decideTypecheckDispatch(
      resolved: WorkspaceResolution,
      callerCwd: Path,
      checkAll: Boolean,
    ): TypecheckDispatch {
      val typecheckAll = checkAll || resolved.forceAll
      val cwd = when {
        resolved.scopeTrailmapDir != null -> resolved.scopeTrailmapDir.toPath()
        typecheckAll -> resolved.workspaceRoot.toPath()
        else -> callerCwd
      }
      return TypecheckDispatch(
        cwd = cwd,
        trailmapId = resolved.scopeTrailmapId,
        typecheckAll = typecheckAll,
      )
    }
  }
}
