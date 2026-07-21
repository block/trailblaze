package xyz.block.trailblaze.host

import xyz.block.trailblaze.TrailblazeVersion
import xyz.block.trailblaze.bundle.WorkspaceClientDtsGenerator
import xyz.block.trailblaze.compile.TrailblazeCompiler
import xyz.block.trailblaze.config.project.LoadedTrailblazeProjectConfig
import xyz.block.trailblaze.config.project.ScriptedToolEnrichment
import xyz.block.trailblaze.config.project.TrailblazeProjectConfig
import xyz.block.trailblaze.config.project.TrailblazeProjectConfigException
import xyz.block.trailblaze.config.project.TrailblazeProjectConfigLoader
import xyz.block.trailblaze.config.project.TrailblazeWorkspaceConfigResolver
import xyz.block.trailblaze.scripting.AnalyzerScriptedToolEnrichment
import xyz.block.trailblaze.scripting.ScriptedToolDefinitionAnalyzer
import xyz.block.trailblaze.llm.config.ClasspathConfigResourceSource
import xyz.block.trailblaze.llm.config.CompositeConfigResourceSource
import xyz.block.trailblaze.llm.config.FilesystemConfigResourceSource
import xyz.block.trailblaze.llm.config.TrailblazeConfigPaths
import xyz.block.trailblaze.util.Console
import xyz.block.trailblaze.util.toLowerHex
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.channels.FileLock
import java.nio.file.Paths
import java.security.MessageDigest

/**
 * Lazy daemon-init rebundle of workspace trailmap manifests.
 *
 * The framework JAR ships its bundled `targets/<id>.yaml` pre-compiled at build time
 * via the `:trailblaze-models` build-logic generator, so a vanilla checkout has working
 * targets before `trailblaze compile` is ever run. Workspace-authored trailmaps under
 * `trails/config/trailmaps/<id>/trailmap.yaml` are NOT covered by that build-time pass, though,
 * so until this bootstrap ran the user had to manually invoke `trailblaze compile` after
 * each manifest edit. This is the same pain `cargo run` saves Rust users from.
 *
 * On daemon startup, [bootstrap] computes a hash of the workspace trailmap manifests plus
 * the running framework version and compares it against the hash stored from the last
 * successful compile. If they match, the existing `dist/targets/` is fresh and we skip;
 * if they don't (or the hash file is absent), [TrailblazeCompiler.compile] runs in-process
 * before [AppTargetDiscovery.discover] is called, so the discovery flow sees the freshly
 * materialized targets without any further wiring — the discovery layer already reads the
 * workspace's filesystem `ConfigResourceSource`.
 *
 * Compile errors abort daemon startup. Same UX bar as `cargo run` against unresolvable
 * deps: refuse to start, print the resolver errors, let the user fix the manifest.
 *
 * Out of scope here:
 * - Watching the workspace for live edits (full IDE-style hot reload).
 * - Sharing the bundle cache across workspaces — each workspace has its own `dist/`.
 */
object WorkspaceCompileBootstrap {

  /**
   * Filename storing the hex SHA-256 of the last successful compile inputs.
   * Lives under `<config>/dist/` so it's colocated with the compile output and
   * gets cleaned up alongside it when a user blows away `dist/`.
   */
  internal const val HASH_FILENAME = ".bundle.hash"

  /**
   * Filename storing the target YAML filenames emitted by the last successful compile.
   *
   * A trailmap manifest is not necessarily an app target: library-only trailmaps contribute
   * dependencies and exports but intentionally emit no `dist/targets/<id>.yaml`. The old cache
   * validator treated every manifest id as an expected target file, so any workspace containing a
   * library trailmap recompiled on every daemon start. Persisting the compiler's actual output set
   * lets the hot path validate missing files without guessing from manifest shape.
   */
  internal const val TARGETS_FILENAME = ".bundle.targets"

  /**
   * Filename storing workspace-relative generated typed-binding paths from the last successful
   * codegen pass. The typed-bindings input hash covers authored workspace config and the framework
   * version; this file adds the other half of the cache contract by detecting a user who deleted
   * one generated artifact while leaving the inputs unchanged.
   */
  internal const val CODEGEN_FILES_FILENAME = ".typed-bindings.files"

  /**
   * Filename storing the full workspace-content hash from the last successful typed-bindings
   * pass. Typed surfaces depend on more than `trailmap.yaml` + local scripted tools: project
   * config, toolsets, providers, and exported dependency metadata can all change their output.
   * Keeping a separate broad hash avoids both stale bindings and analyzer work on every startup.
   */
  internal const val CODEGEN_HASH_FILENAME = ".typed-bindings.hash"

  /**
   * Filename of the inter-process lock that serializes concurrent daemon-init
   * compiles in the same workspace. Grabbed via [FileLock] so the OS handles
   * cross-process coordination — required because two `trailblaze` invocations
   * starting at the same time would otherwise both compile and race on the
   * `dist/targets/` writes.
   */
  internal const val LOCK_FILENAME = ".bundle.lock"

  /**
   * Outcome of a [bootstrap] call. Returned for callers that want to log or assert on
   * the result; failure cases throw [WorkspaceCompileException] rather than being modeled
   * as a result variant, because the daemon's response to a compile error is to abort
   * startup, not to keep going with stale outputs.
   */
  sealed interface BootstrapResult {
    /** No workspace was discovered above CWD — nothing to compile. */
    data object NoWorkspace : BootstrapResult

    /**
     * Workspace exists but has no `trailmaps/` directory or no trailmap manifests
     * inside it — nothing to compile. Distinguished from [NoWorkspace] only for
     * test legibility; downstream code treats both identically.
     */
    data object NoWorkspaceTrailmaps : BootstrapResult

    /** Stored hash matched and `dist/targets/` exists; compile was skipped. */
    data object UpToDate : BootstrapResult

    /** Compile ran and emitted [emitted] target file(s). */
    data class Recompiled(val emitted: Int) : BootstrapResult
  }

  /**
   * Thrown when [TrailblazeCompiler.compile] returns errors. The daemon converts this
   * into a fatal startup failure with the error list printed to stderr — the user fixes
   * their manifest and retries, same as `cargo run` against an unresolvable dep graph.
   */
  class WorkspaceCompileException(
    val errors: List<String>,
  ) : RuntimeException(buildErrorMessage(errors))

  /**
   * Discovers the workspace from CWD and rebundles workspace trailmaps if their hash has
   * changed since the last successful compile. Safe to call from any process that's
   * about to initialize the daemon — the cost is one SHA-256 walk over `trailmap.yaml` files
   * when the bundle is fresh.
   *
   * @throws WorkspaceCompileException if the compile fails. Daemon startup should let
   *   this propagate so the user sees the resolver errors instead of a daemon that
   *   silently runs against stale (or missing) targets.
   */
  fun bootstrap(): BootstrapResult {
    val resolved = TrailblazeWorkspaceConfigResolver.resolve(Paths.get(""))
    val configDir = resolved.configDir ?: return BootstrapResult.NoWorkspace
    // Capture the load-time content hash before the compile dance below so it reflects
    // exactly the on-disk state the daemon is about to load — any edit that lands after
    // this line will diverge from the captured hash and trip the drift warning. Done at
    // every bootstrap call (NoWorkspaceTrailmaps/UpToDate/Recompiled all reach this) so a
    // trailmaps-less workspace still gets drift coverage for its tool/toolset/provider YAMLs.
    xyz.block.trailblaze.config.project.WorkspaceContentHasher
      .captureForDaemon(configDir, TrailblazeVersion.version)
    return bootstrap(configDir = configDir, version = TrailblazeVersion.version)
  }

  /**
   * Convenience wrapper for daemon-startup entry points. Identical to [bootstrap], but
   * a [WorkspaceCompileException] prints to stderr and calls `exitProcess(1)` instead
   * of propagating — appropriate when there is no useful caller-level recovery path
   * and the alternative is an uncaught-exception stack trace that duplicates the
   * resolver error message we already printed cleanly.
   */
  fun bootstrapOrExit(): BootstrapResult = try {
    bootstrap()
  } catch (e: WorkspaceCompileException) {
    Console.error(e.message ?: "Workspace trailmap compilation failed.")
    kotlin.system.exitProcess(1)
  }

  /**
   * Visible for testing. Lets tests pass an explicit `trails/config/` directory and
   * version string so the hash-skip logic can be exercised without touching CWD or the
   * baked-in [TrailblazeVersion].
   */
  internal fun bootstrap(configDir: File, version: String): BootstrapResult {
    val trailmapsDir = File(configDir, TrailblazeConfigPaths.TRAILMAPS_SUBDIR)

    // SDK extraction lives outside the workspace-trailmaps gate below. Two regeneration
    // invariants ride on the bootstrap ordering:
    //   1. A deleted `<trailmapDir>/tools/trailblaze-client.d.ts` is detected by the generated
    //      files manifest and regenerates on the next daemon start.
    //   2. The workspace SDK declaration bundle exists as soon as the daemon sees a
    //      workspace, even when there are no workspace trailmaps yet (a fresh clone or a
    //      classpath-only consumer). Without this, the very first trailmap a user authors
    //      would point at a non-existent `.trailblaze/sdk/dist/index.d.ts`.
    //
    // SDK setup is idempotent (skip-write-if-content-matches). Typed-bindings generation is
    // analyzer-backed and can be expensive, so the full workspace-content hash plus generated
    // files manifest gates it below. Failures downgrade to `Console.error` rather than aborting
    // the daemon; the CLI path (`trailblaze compile`) elevates the same failures to non-zero exit.
    // Order matters: per-trailmap codegen asserts the SDK bundle exists (so it
    // doesn't write per-trailmap tsconfigs pointing at a missing `paths` target), and
    // that file is written by `runWorkspaceTypeScriptSetup`. If setup fails,
    // skip per-trailmap codegen with a single explanatory line — running it anyway
    // would produce a misleading second "codegen failed" diagnostic that masks
    // the SDK-extraction root cause.
    val workspaceSetupOk = runWorkspaceTypeScriptSetup(configDir)

    if (!trailmapsDir.isDirectory) return BootstrapResult.NoWorkspaceTrailmaps

    val trailmapManifests = listTrailmapManifests(trailmapsDir)
    if (trailmapManifests.isEmpty()) return BootstrapResult.NoWorkspaceTrailmaps

    val outputDir = File(configDir, TrailblazeConfigPaths.WORKSPACE_DIST_TARGETS_SUBPATH)
    val distDir = File(configDir, TrailblazeConfigPaths.WORKSPACE_DIST_SUBDIR)
    val hashFile = File(distDir, HASH_FILENAME)
    val targetsFile = File(distDir, TARGETS_FILENAME)
    val codegenFilesFile = File(distDir, CODEGEN_FILES_FILENAME)
    val codegenHashFile = File(distDir, CODEGEN_HASH_FILENAME)

    val expectedHash = computeWorkspaceHash(trailmapManifests, version)

    // Cross-process serialization. Two daemons starting at once in the same workspace
    // would both observe a stale hash, both call TrailblazeCompiler.compile() against
    // the same outputDir, and both writeText() the hash file — last-writer-wins on the
    // hash but the per-target YAML writes can interleave. FileLock makes the second
    // process wait for the first to finish; by the time it acquires the lock the bundle
    // is fresh and the hash check below short-circuits.
    return withDistLock(distDir) {
      val storedHash = readStoredHash(hashFile)
      val inputsUnchanged = storedHash == expectedHash
      val expectedCodegenHash =
        xyz.block.trailblaze.config.project.WorkspaceContentHasher.compute(configDir, version)
      val codegenInputsUnchanged = readStoredHash(codegenHashFile) == expectedCodegenHash

      // Analyzer-backed typed bindings are expensive in a large workspace. The input hash already
      // invalidates them on every authored config/tool/framework change, so only re-resolve and
      // re-analyze when those inputs changed or a path recorded by the previous pass disappeared.
      // WorkspaceTypeScriptSetup remains outside this gate: its cheap idempotent extraction must
      // still restore a deleted SDK bundle and prune legacy files on every daemon start.
      if (workspaceSetupOk) {
        if (!codegenInputsUnchanged || !allGeneratedFilesPresent(configDir.parentFile, codegenFilesFile)) {
          val generatedFiles = runPerTrailmapTypedBindingsCodegen(configDir)
          if (generatedFiles != null) {
            writeGeneratedFilesManifest(
              workspaceRoot = configDir.parentFile,
              manifestFile = codegenFilesFile,
              generatedFiles = generatedFiles,
            )
            // Compute after emission because the framework-owned tools/tsconfig.json participates
            // in the broad workspace hash. This makes the freshly generated state the cache key;
            // the next unchanged startup compares equal instead of paying one extra regeneration.
            writeHash(
              codegenHashFile,
              xyz.block.trailblaze.config.project.WorkspaceContentHasher.compute(configDir, version),
            )
          } else {
            // A manifest from an older successful pass must not make this failed pass appear
            // current on the next startup. Codegen is best-effort, but it should keep retrying.
            codegenFilesFile.delete()
            codegenHashFile.delete()
          }
        }
      } else {
        codegenHashFile.delete()
        Console.error(
          "Per-trailmap typed-bindings codegen skipped because workspace TypeScript setup " +
            "failed above (see prior error). Restart the daemon after fixing the SDK " +
            "extraction failure to regenerate per-trailmap trailblaze-client.d.ts / tsconfig.json / .gitignore.",
        )
      }

      if (inputsUnchanged && allTargetsPresent(outputDir, targetsFile)) {
        return@withDistLock BootstrapResult.UpToDate
      }

      // Console.info (not Console.log) so users running with --quiet still see the
      // multi-second startup pause attributed to a recompile. CompileCommand uses
      // Console.log for explicit `trailblaze compile` invocations, but those are
      // already a foregrounded operation the user opted into; the daemon-init
      // rebundle is implicit and hiding it in quiet mode would look like a hang.
      Console.info("Recompiling workspace trailmaps...")
      // Reference validation needs to see workspace-authored toolsets and tools that live
      // on the filesystem under the user's `trails/config/`, not only classpath-bundled
      // ids. Pass a composite source layered like `AppTargetDiscovery.buildResourceSource`
      // (classpath + workspace filesystem) so a workspace
      // `trails/config/trailmaps/<id>/toolsets/<name>.yaml` or
      // `trails/config/trailmaps/<id>/tools/<name>.tool.yaml` resolves cleanly. Without
      // this layering, a trailmap that references its own workspace toolset would fail
      // compile with "unknown toolset" even though the toolset is right there on disk.
      val referenceSource = CompositeConfigResourceSource(
        sources = listOf(
          ClasspathConfigResourceSource,
          FilesystemConfigResourceSource(rootDir = configDir),
        ),
      )
      // Wire enrichment so meta-only scripted-tool descriptors (`script:` + `_meta:`
      // with `name:` / `description:` / `inputSchema:` derived from the typed `.ts`
      // source) resolve at daemon-init recompile time. Without this, a workspace
      // that's adopted the typed-authoring shape fails the bootstrap compile and the
      // daemon refuses to come up, even though the per-trailmap codegen path below would
      // resolve it correctly.
      //
      // When the resolver returns null (no `bun` on PATH, missing TRAILBLAZE_SDK_DIR,
      // ts-json-schema-generator not installed), log a one-line breadcrumb so the
      // downstream "enrichment not wired" loader error has a root cause sitting next
      // to it in the same log instead of arriving with no context.
      val scriptedToolEnrichment = resolveScriptedToolEnrichment()
      if (scriptedToolEnrichment == null) {
        Console.info(
          "Workspace recompile: scripted-tool analyzer unavailable — meta-only " +
            "descriptors will fail to load. Ensure `bun` is on PATH and the SDK at " +
            "TRAILBLAZE_SDK_DIR carries node_modules/ts-json-schema-generator.",
        )
      }
      val result = TrailblazeCompiler.compile(
        trailmapsDir = trailmapsDir,
        outputDir = outputDir,
        referenceSource = referenceSource,
        commandLabel = "compile",
        scriptedToolEnrichment = scriptedToolEnrichment,
      )
      if (!result.isSuccess) {
        // Drop a stale hash file so a subsequent edit-and-retry doesn't accidentally pass
        // the up-to-date check against last run's hash. The user expects "fix and retry"
        // semantics, not "succeed because nothing changed since the last failure".
        hashFile.delete()
        throw WorkspaceCompileException(result.errors)
      }
      // Write the output manifest before the hash commit marker. If the process dies between the
      // writes, the absent/stale hash forces a clean compile next time; the inverse order could
      // incorrectly bless an incomplete output manifest as current.
      writeTargetManifest(targetsFile, result.emittedTargets)
      writeHash(hashFile, expectedHash)
      BootstrapResult.Recompiled(emitted = result.emittedTargets.size)
    }
  }

  /**
   * Extract the bundled `@trailblaze/scripting` declaration bundle to
   * `<workspaceRoot>/.trailblaze/sdk/dist/index.d.ts`. No `bun install` — the SDK type
   * surface is delivered as a single self-contained `.d.ts` consumed via the per-trailmap
   * tsconfig's `paths` mapping. Failures are non-fatal: the daemon comes up regardless
   * because trail execution doesn't depend on IDE typing.
   *
   * Returns `true` when setup completed (or there was nothing to do), `false` when an
   * exception was caught. The caller uses this to decide whether to run downstream
   * per-trailmap codegen (which would otherwise hit the missing-bundle assert and produce a
   * confusing second error).
   */
  private fun runWorkspaceTypeScriptSetup(configDir: File): Boolean = try {
    WorkspaceTypeScriptSetup.setUp(workspaceRoot = configDir.parentFile.toPath())
    true
  } catch (e: Exception) {
    Console.error(
      "TypeScript workspace setup failed (SDK declaration-bundle extraction — daemon will " +
        "continue but IDE typing for `@trailblaze/scripting` may be missing): " +
        "${e.message ?: e.javaClass.simpleName}",
    )
    false
  }

  /**
   * Re-resolve the trailmap pool from [configDir] and emit per-trailmap `trailblaze-client.d.ts` files
   * plus framework-managed `tools/tsconfig.json` + trailmap-root `.gitignore` artifacts.
   * Zero-trailmap pools no-op cleanly inside the emitters. Failures downgrade to a
   * warning for the same daemon-must-come-up reason as [runWorkspaceTypeScriptSetup].
   */
  private fun runPerTrailmapTypedBindingsCodegen(configDir: File): List<java.nio.file.Path>? {
    return try {
      val loaded = LoadedTrailblazeProjectConfig(
        raw = TrailblazeProjectConfig(),
        sourceFile = File(configDir, TrailblazeProjectConfigLoader.CONFIG_FILENAME),
      )
      val resolvedTrailmaps = TrailblazeProjectConfigLoader
        .resolveRuntime(
          loaded,
          includeClasspathTrailmaps = true,
          scriptedToolEnrichment = resolveScriptedToolEnrichment(),
        )
        .resolvedTrailmaps
      val clientDtsFiles = PerTrailmapClientDtsEmitter.emit(resolvedTrailmaps = resolvedTrailmaps)
      val configFiles = PerTrailmapTsconfigEmitter.emit(
        workspaceRoot = configDir.parentFile.toPath(),
        resolvedTrailmaps = resolvedTrailmaps,
      )
      // The client emitter writes a validation sidecar next to each d.ts but returns only the d.ts
      // paths. Record sidecars that were actually created so deleting one invalidates the codegen
      // cache without turning a best-effort sidecar write failure into a permanent startup loop.
      val sidecarFiles = clientDtsFiles.map { dts ->
        dts.parent.resolve(TrailValidationDescriptorSidecar.FILE_NAME)
      }.filter { java.nio.file.Files.isRegularFile(it) }
      (clientDtsFiles + sidecarFiles + configFiles).distinct()
    } catch (e: TrailblazeProjectConfigException) {
      Console.error(
        "Typed-bindings codegen skipped — trailmap re-resolution failed " +
          "(daemon will continue without per-trailmap trailblaze-client.d.ts / tsconfig.json / .gitignore files): " +
          "${e.message ?: e.javaClass.simpleName}",
      )
      null
    } catch (e: Exception) {
      Console.error(
        "Typed-bindings codegen failed (daemon will continue without per-trailmap " +
          "trailblaze-client.d.ts / tsconfig.json / .gitignore files): ${e.message ?: e.javaClass.simpleName}",
      )
      null
    }
  }

  /**
   * Delegates to [AnalyzerScriptedToolEnrichment.Companion.resolveFromEnvironment] —
   * the shared resolver every host call site uses. Kept as a local one-liner so the
   * call-site at line ~276 reads cleanly without dragging the companion's fully-qualified
   * name through the resolver chain.
   */
  private fun resolveScriptedToolEnrichment(): ScriptedToolEnrichment? =
    AnalyzerScriptedToolEnrichment.resolveFromEnvironment()

  /**
   * Hashes every `<id>/trailmap.yaml` referenced by [manifests], plus [version] and the
   * sibling `tools/` subtree content, in deterministic order. Including the version
   * covers the "framework upgrade ⇒ stale compile output" case: framework-bundled
   * trailmaps that the workspace transitively depends on can change shape across releases,
   * so a bundle compiled against v1.2 is not necessarily valid against v1.3. Tying
   * the hash to the running CLI version forces a recompile across upgrades regardless
   * of whether any workspace `trailmap.yaml` was edited.
   *
   * **Tools/ subtree inclusion (meta-only authoring).** Each trailmap's `tools/<id>.yaml`
   * (the per-tool descriptor) AND each `tools/<id>.ts` (the typed-authoring source)
   * are hashed alongside `trailmap.yaml`. With the meta-only authoring shape introduced
   * by PR #3338, the `.ts` file's `trailblaze.tool<I, O>(handler)` declaration is
   * the source of truth for the tool's `name:` / `inputSchema:` / `description:` —
   * the analyzer reads it during compile and bakes the extracted metadata into the
   * resolved target YAML. An author who edits only the `.ts` (e.g., adds an input
   * field or changes the TSDoc description) now has their change reflected in
   * `dist/targets/<trailmap>.yaml`, so a recompile must run. Without this, the daemon's
   * hash-skip would silently keep the stale compile until the user touches
   * `trailmap.yaml`.
   *
   * Files under `tools/` outside the `<name>.yaml` / `<name>.ts` pair (subdirs,
   * `.gitignore`, generated `.trailblaze/` artifacts, framework-written
   * `tsconfig.json`) are NOT hashed — those are either build-time-managed by the
   * framework itself or unrelated to compile output. Files are listed
   * lexicographically by name so the hash is stable across filesystems with
   * different directory-listing orders.
   *
   * Manifest content is normalized to LF before hashing so a workspace with CRLF
   * line endings (Windows checkout, mixed-EOL editor) doesn't produce a different
   * hash than the same workspace on macOS / Linux. Without this, switching machines
   * would force a spurious recompile. The same LF-normalization applies to tool
   * descriptors and `.ts` sources hashed below.
   *
   * Visible for testing.
   */
  internal fun computeWorkspaceHash(manifests: List<TrailmapManifest>, version: String): String {
    val md = MessageDigest.getInstance("SHA-256")
    md.update(version.toByteArray(Charsets.UTF_8))
    md.update(SEPARATOR)

    for (manifest in manifests) {
      md.update(manifest.id.toByteArray(Charsets.UTF_8))
      md.update(SEPARATOR)
      val normalized = try {
        manifest.file.readText(Charsets.UTF_8).replace("\r\n", "\n")
      } catch (e: IOException) {
        throw WorkspaceCompileException(
          listOf("Cannot read trailmap manifest at ${manifest.file.absolutePath}: ${e.message}"),
        )
      }
      md.update(normalized.toByteArray(Charsets.UTF_8))
      md.update(SEPARATOR)

      // Fold the trailmap's `tools/` content into the hash so meta-only authoring edits
      // (a `.ts`-only change to a tool's TSDoc / type parameters) invalidate the
      // bundle. Listed lexicographically for cross-filesystem determinism. We hash
      // both `.yaml` (file-level _meta + script:) and `.ts` (analyzer source) so
      // either-side edits land — full-YAML descriptors get their .ts ignored at
      // compile time but the hash still fires (cheap, and avoids growing the hash
      // logic with a "is this descriptor meta-only?" branch the compiler will
      // happily skip anyway).
      //
      // String literal "tools" mirrors `TRAILMAP_SCRIPTED_TOOLS_DIR` in
      // `TrailblazeProjectConfigLoader` (private there, intentionally not exported —
      // the convention is stable and matches the analyzer + bundler's hard-coded
      // path).
      val toolsDir = File(manifest.file.parentFile, "tools")
      if (toolsDir.isDirectory) {
        // Framework-generated `trailblaze-client.d.ts` lives at the same depth as
        // author-owned `.ts` source but is NOT input — it's a downstream codegen
        // artifact. Including it would make every regen invalidate the hash and
        // force a spurious recompile on the next boot. Filter by exact filename
        // (sourced from the codegen constant so a future rename stays in sync).
        //
        // Assumption: the codegen pipeline writes exactly ONE such file per trailmap.
        // If a future generator drops additional framework artifacts into `tools/`
        // (e.g., a sibling `.d.ts`, or a structured-data file), this filter MUST be
        // widened — otherwise those new artifacts would silently re-enter the hash
        // and re-introduce the cache-thrash this filter was added to prevent.
        val toolFiles = toolsDir
          .listFiles { f ->
            f.isFile &&
              (f.name.endsWith(".yaml") || f.name.endsWith(".ts")) &&
              f.name != WorkspaceClientDtsGenerator.GENERATED_FILE_NAME
          }
          ?.sortedBy { it.name }
          .orEmpty()
        for (toolFile in toolFiles) {
          md.update(toolFile.name.toByteArray(Charsets.UTF_8))
          md.update(SEPARATOR)
          val toolNormalized = try {
            toolFile.readText(Charsets.UTF_8).replace("\r\n", "\n")
          } catch (e: IOException) {
            throw WorkspaceCompileException(
              listOf("Cannot read trailmap tool file at ${toolFile.absolutePath}: ${e.message}"),
            )
          }
          md.update(toolNormalized.toByteArray(Charsets.UTF_8))
          md.update(SEPARATOR)
        }
      }
    }
    return md.digest().toLowerHex()
  }

  /**
   * Test convenience overload. Walks [trailmapsDir] for `trailmap.yaml` files and forwards
   * to the manifest-list overload above. Production callers use the manifest list
   * directly so the listing happens once per [bootstrap] invocation.
   */
  internal fun computeWorkspaceHash(trailmapsDir: File, version: String): String =
    computeWorkspaceHash(listTrailmapManifests(trailmapsDir), version)

  /**
   * A workspace trailmap manifest discovered under `trailmaps/`. Carries both the trailmap id
   * (the directory name) and the manifest file so the hash and the per-target
   * existence check can both reference the same source of truth without relisting
   * the directory.
   */
  internal data class TrailmapManifest(val id: String, val file: File)

  private fun listTrailmapManifests(trailmapsDir: File): List<TrailmapManifest> =
    trailmapsDir.listFiles { f -> f.isDirectory }
      ?.sortedBy { it.name }
      ?.mapNotNull { dir ->
        val manifest = File(dir, TrailblazeConfigPaths.TRAILMAP_MANIFEST_FILENAME)
        if (manifest.isFile) TrailmapManifest(id = dir.name, file = manifest) else null
      }
      .orEmpty()

  /**
   * The hash-skip path also has to verify the materialized output is intact — the user
   * could have manually deleted a single `dist/targets/<id>.yaml` while leaving the
   * directory and `.bundle.hash` in place, in which case the bundle is no longer fresh
   * even though the input hash matches. We don't try to distinguish "user-deleted the
   * file" from "compile never produced it"; either way the right answer is recompile.
   *
   * [targetsFile] records the compiler's actual prior output set. This matters because library
   * trailmaps intentionally produce no target YAML; deriving expected filenames from every
   * manifest id would make a valid library look like a missing output forever.
   */
  private fun allTargetsPresent(outputDir: File, targetsFile: File): Boolean {
    val expectedFiles = readManifestLines(targetsFile) ?: return false
    return expectedFiles.all { name -> File(outputDir, name).isFile }
  }

  private fun allGeneratedFilesPresent(workspaceRoot: File, manifestFile: File): Boolean {
    val expectedFiles = readManifestLines(manifestFile) ?: return false
    return expectedFiles.all { relativePath -> File(workspaceRoot, relativePath).isFile }
  }

  private fun readManifestLines(file: File): List<String>? = try {
    if (!file.isFile) null else file.readLines().map { it.trim() }.filter { it.isNotEmpty() }
  } catch (_: Exception) {
    null
  }

  private fun writeTargetManifest(file: File, emittedTargets: List<File>) {
    writeManifestLines(file, emittedTargets.map { it.name })
  }

  private fun writeGeneratedFilesManifest(
    workspaceRoot: File,
    manifestFile: File,
    generatedFiles: List<java.nio.file.Path>,
  ) {
    val root = workspaceRoot.toPath().toAbsolutePath().normalize()
    val relativePaths = generatedFiles.mapNotNull { path ->
      val normalized = path.toAbsolutePath().normalize()
      if (normalized.startsWith(root)) root.relativize(normalized).toString().replace(File.separatorChar, '/')
      else null
    }
    writeManifestLines(manifestFile, relativePaths)
  }

  private fun writeManifestLines(file: File, lines: List<String>) {
    file.parentFile?.mkdirs()
    val content = lines.distinct().sorted().joinToString(separator = "\n", postfix = if (lines.isEmpty()) "" else "\n")
    file.writeText(content)
  }

  private fun readStoredHash(hashFile: File): String? = try {
    if (hashFile.isFile) hashFile.readText().trim().ifEmpty { null } else null
  } catch (_: Exception) {
    null
  }

  private fun writeHash(hashFile: File, hash: String) {
    hashFile.parentFile?.mkdirs()
    hashFile.writeText(hash)
  }

  /**
   * Acquires an exclusive [FileLock] on `<distDir>/.bundle.lock` for the duration of
   * [block], creating the lock file (and its parent dir) if needed. The lock is held
   * across `compile()` + `writeHash()` so a second process waits until the first has
   * finished writing both before re-evaluating freshness.
   */
  private inline fun <T> withDistLock(distDir: File, block: () -> T): T {
    distDir.mkdirs()
    val lockFile = File(distDir, LOCK_FILENAME)
    RandomAccessFile(lockFile, "rw").use { raf ->
      raf.channel.use { channel ->
        val lock: FileLock = channel.lock()
        try {
          return block()
        } finally {
          if (lock.isValid) lock.release()
        }
      }
    }
  }

  /** Field separator (NUL) for hashing — disambiguates concatenated entries. */
  private val SEPARATOR = byteArrayOf(0)

  private fun buildErrorMessage(errors: List<String>): String =
    if (errors.isEmpty()) {
      "Workspace trailmap compilation failed."
    } else {
      buildString {
        append("Workspace trailmap compilation failed:")
        for (error in errors) {
          append('\n')
          append("  - ")
          append(error)
        }
      }
    }
}
