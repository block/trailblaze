package xyz.block.trailblaze.host

import java.nio.file.Files
import java.nio.file.Path
import xyz.block.trailblaze.llm.config.ClasspathResourceDiscovery
import xyz.block.trailblaze.util.Console

/**
 * Workspace-side TypeScript wiring for the Trailblaze SDK.
 *
 * On every `trailblaze compile` / daemon bootstrap:
 *
 *   1. Extracts the framework's vendored `@trailblaze/scripting` declaration bundle
 *      (`dist/index.d.ts`) from the trailblaze JAR's resources into
 *      `<workspaceRoot>/.trailblaze/sdk/` once, idempotently
 *      (skip-write-if-content-matches keeps mtimes stable across runs of the same SDK).
 *      The bundle is a single self-contained `.d.ts` file with zod types inlined — a pack's
 *      `tsconfig.json` points its `paths` mapping at it via a workspace-relative path. No
 *      `node_modules/`, no per-pack `package.json`, no `bun install`.
 *
 * **What changed when the bundled-.d.ts approach landed.** Pre-Phase-D the workspace
 * also wrote a `tsconfig.base.json` that per-pack tsconfigs `extends:`-ed. That coupling
 * made packs non-portable for the future npm-distribution direction: a trailmap installed
 * into a different workspace's `node_modules/` would have a broken `extends:` chain. The
 * declaration bundle lets each per-pack tsconfig be fully self-contained (compiler
 * options + paths inlined), so the workspace base file is gone — see
 * [PerPackTsconfigEmitter] for the per-pack render.
 *
 * **Failure handling is the caller's call.** This helper just does the work or throws.
 * The compile-CLI wire-in lets exceptions propagate (fail-fast — author wants to see
 * errors immediately on an explicit `trailblaze compile`); the daemon-init wire-in
 * downgrades to a warning (the daemon must come up regardless — trail execution doesn't
 * depend on TypeScript-side typing).
 */
object WorkspaceTypeScriptSetup {

  /** Outcome of [setUp]. */
  data class SetupResult(
    val sdkDir: Path,
    val sdkDtsBundle: Path,
  )

  /**
   * Extracts the declaration bundle. Idempotent — a second call with an unchanged SDK
   * leaves the extracted file byte-equal (and mtime stable).
   *
   * @param workspaceRoot the workspace's `trails/` directory. The SDK bundle lands at
   *   `<workspaceRoot>/.trailblaze/sdk/dist/index.d.ts`.
   */
  fun setUp(workspaceRoot: Path): SetupResult {
    val sdkDir = extractSdk(workspaceRoot)
    pruneStaleWorkspaceTsconfigBase(workspaceRoot)
    val sdkDtsBundle = sdkDir.resolve(SDK_DTS_BUNDLE_RELATIVE_PATH).toAbsolutePath()
    return SetupResult(sdkDir = sdkDir, sdkDtsBundle = sdkDtsBundle)
  }

  /**
   * Delete `<workspaceRoot>/.trailblaze/tsconfig.base.json` left behind from the
   * pre-bundled-.d.ts era, where per-pack tsconfigs `extends:`-ed a workspace-level
   * base file. The current setup writes no workspace base — per-pack tsconfigs are
   * fully self-contained (see [PerPackTsconfigEmitter]) — so the old file is inert
   * cruft. Without this prune, every workspace that touched the prior version keeps
   * the orphaned file forever; over time that's "what is this for?" friction for
   * authors and a foot-gun for anyone debugging tsconfig resolution who finds a
   * stale base file the framework no longer reads.
   *
   * `deleteIfExists` is a no-op when the file isn't present (the common case for
   * fresh workspaces). Logged at info level when the deletion actually fires so
   * upgrade behavior is auditable from session logs.
   */
  private fun pruneStaleWorkspaceTsconfigBase(workspaceRoot: Path) {
    val stale = workspaceRoot.resolve(GENERATED_DIR_NAME).resolve(LEGACY_WORKSPACE_TSCONFIG_BASE)
    // Guard against the path resolving to a non-regular-file (directory, symlink, or
    // similar) — a corrupt checkout where a user manually created `.trailblaze/
    // tsconfig.base.json` as a directory would otherwise trip `Files.deleteIfExists`
    // into `DirectoryNotEmptyException` and tear down daemon startup for an extreme
    // edge case that's not the framework's responsibility to repair.
    if (!Files.isRegularFile(stale)) return
    if (Files.deleteIfExists(stale)) {
      Console.info(
        "[WorkspaceTypeScriptSetup] Pruned stale workspace tsconfig.base.json " +
          "(per-pack tsconfigs are now self-contained): $stale",
      )
    }
  }

  /**
   * Extract the SDK declaration bundle from JAR resources rooted at
   * `trailblaze-config/sdk/typescript/` into `<workspaceRoot>/.trailblaze/sdk/`.
   * Idempotent — skip-write-if-content-matches keeps mtimes stable across runs of the
   * same SDK version (so file watchers and TS language servers don't churn).
   *
   * The resource tree is intentionally minimal today (just `dist/index.d.ts`), but the
   * extractor still walks the prefix recursively so a future expansion (e.g. a sibling
   * `dist/index.d.cts` for CommonJS consumers) automatically flows through without a
   * matching code change here.
   *
   * Returns the absolute path of the extracted SDK dir.
   */
  internal fun extractSdk(workspaceRoot: Path): Path {
    val sdkResources = ClasspathResourceDiscovery.discoverAndLoadRecursive(
      directoryPath = SDK_RESOURCE_PREFIX,
      suffix = "", // any file under the prefix
      anchorClass = WorkspaceTypeScriptSetup::class.java,
    )
    if (sdkResources.isEmpty()) {
      // Building/running against a JAR that doesn't ship the SDK (e.g. a stripped
      // distribution or a custom build that forgot the resource bundling step). Surface
      // this clearly rather than silently emitting an empty `.trailblaze/sdk/`.
      error(
        "trailblaze framework JAR is missing the bundled TypeScript SDK at " +
          "classpath resource '$SDK_RESOURCE_PREFIX'. Rebuild :trailblaze-models against the " +
          "current `sdks/typescript/` source — check that `copyTypescriptSdkResources` ran " +
          "and produced `dist/index.d.ts` under `build/generated-resources/sdk/`. The " +
          "declaration bundle itself is regenerated by `./gradlew " +
          ":trailblaze-models:bundleTrailblazeSdkDts` (manual after SDK source edits).",
      )
    }
    val sdkRoot = workspaceRoot
      .resolve(GENERATED_DIR_NAME)
      .resolve(SDK_DIR_NAME)
      .toAbsolutePath()
    Files.createDirectories(sdkRoot)
    // Build the expected file set first so we can prune stale files from a previous SDK
    // version before writing the current one. Without this, on framework upgrade where the
    // SDK shape shrinks (e.g. a former `dist/extras.d.ts` removed), the stale file would
    // linger in `.trailblaze/sdk/` and could confuse module resolution or IDE indexing.
    // Mtime-stable for unchanged content (skip-write-if-content-matches preserved below).
    val expectedRelativePaths = sdkResources.keys.map { it.replace('/', java.io.File.separatorChar) }.toSet()
    if (Files.isDirectory(sdkRoot)) {
      Files.walk(sdkRoot).use { stream ->
        stream
          .filter { Files.isRegularFile(it) }
          .filter { p ->
            val rel = sdkRoot.relativize(p).toString()
            rel !in expectedRelativePaths
          }
          .forEach { p ->
            // Log AFTER the delete returns true so a failed delete (read-only mount,
            // permissions, file locked) doesn't leave a misleading "Pruned X" breadcrumb
            // in session logs while X is still on disk. Info-level so it's visible
            // without --verbose; cardinality is bounded by the SDK file count so log
            // volume stays trivial. The upgrade-from-prior-layout case wipes the old
            // `src/**` SDK tree — exactly the kind of operation a user wants traced.
            val rel = sdkRoot.relativize(p)
            if (Files.deleteIfExists(p)) {
              Console.info("[WorkspaceTypeScriptSetup] Pruned stale SDK file: $rel")
            }
          }
      }
    }
    sdkResources.forEach { (relativePath, content) ->
      val outputFile = sdkRoot.resolve(relativePath)
      Files.createDirectories(outputFile.parent)
      val existing = if (Files.isRegularFile(outputFile)) Files.readString(outputFile) else null
      if (existing != content) {
        Files.writeString(outputFile, content)
      }
    }
    return sdkRoot
  }

  /**
   * Extract the bundled TypeScript compiler (`_tsc.js` + `lib.*.d.ts`) from JAR resources
   * rooted at `trailblaze-config/typecheck/typescript/` into
   * `<workspaceRoot>/.trailblaze/typecheck/typescript/`. Idempotent —
   * skip-write-if-content-matches keeps mtimes stable across runs of the same tsc version,
   * so file watchers don't churn.
   *
   * **Opt-in.** Deliberately NOT called from [setUp]: the ~6 MB of tsc resource I/O is
   * only useful to `trailblaze typecheck`, and folding it into `setUp` would force every
   * `trailblaze compile` and daemon-init bootstrap to pay the same cost. The typecheck
   * subcommand calls this method explicitly after `setUp` completes.
   *
   * Returns the absolute path of the extracted `_tsc.js`, or `null` when the framework
   * JAR does not ship the typecheck payload. The null case lets `trailblaze typecheck`
   * emit a single actionable error ("run `bun install` in `sdks/typescript`") rather
   * than tripping every `trailblaze compile` — developers who never run typecheck
   * shouldn't be blocked by a missing payload they don't need.
   *
   * Mirrors [extractSdk]'s prune + skip-write discipline. Kept here rather than in a
   * sibling object so the workspace's `.trailblaze/` layout is owned end-to-end by
   * [WorkspaceTypeScriptSetup].
   */
  fun extractTypecheck(workspaceRoot: Path): Path? {
    val resources = ClasspathResourceDiscovery.discoverAndLoadRecursive(
      directoryPath = TYPECHECK_RESOURCE_PREFIX,
      suffix = "",
      anchorClass = WorkspaceTypeScriptSetup::class.java,
    )
    if (resources.isEmpty()) return null
    val tscRoot = workspaceRoot
      .resolve(GENERATED_DIR_NAME)
      .resolve(TYPECHECK_DIR_NAME)
      .resolve("typescript")
      .toAbsolutePath()
    Files.createDirectories(tscRoot)
    // Normalize resource keys once so the prune set (built from `Path.relativize` which
    // yields platform-separator strings) compares cleanly to the resource keys on Windows.
    val normalizedResources = resources.mapKeys { (k, _) -> k.replace('/', java.io.File.separatorChar) }
    val expectedRelativePaths = normalizedResources.keys
    if (Files.isDirectory(tscRoot)) {
      Files.walk(tscRoot).use { stream ->
        stream
          .filter { Files.isRegularFile(it) }
          .filter { p ->
            val rel = tscRoot.relativize(p).toString()
            rel !in expectedRelativePaths
          }
          .forEach { p ->
            // Log AFTER the delete returns true so a failed delete (read-only mount,
            // permissions, file locked) doesn't leave a misleading "Pruned X" breadcrumb
            // in session logs while X is still on disk. Mirrors [extractSdk]'s discipline
            // so framework-upgrade purges of the bundled tsc payload are auditable from
            // session logs the same way SDK upgrades already are.
            val rel = tscRoot.relativize(p)
            if (Files.deleteIfExists(p)) {
              Console.info("[WorkspaceTypeScriptSetup] Pruned stale typecheck file: $rel")
            }
          }
      }
    }
    normalizedResources.forEach { (relativePath, content) ->
      val outputFile = tscRoot.resolve(relativePath)
      Files.createDirectories(outputFile.parent)
      val existing = if (Files.isRegularFile(outputFile)) Files.readString(outputFile) else null
      if (existing != content) {
        Files.writeString(outputFile, content)
      }
    }
    val tscJs = tscRoot.resolve(TSC_ENTRY_RELATIVE_PATH)
    // Defensive — every shipping build has `lib/_tsc.js`, but a stripped distribution
    // could produce a non-empty resource set that lacks the entry point. Surface this
    // as null (same shape as "no payload at all") so callers don't trust a partial
    // extraction.
    return if (Files.isRegularFile(tscJs)) tscJs else null
  }

  // Resource-path constants — mirror the JAR layout from `:trailblaze-models`'s
  // `copyTypescriptSdkResources` Gradle task, which stages files under
  // `build/generated-resources/sdk/trailblaze-config/sdk/typescript/...` and ships them
  // at that classpath path.
  internal const val SDK_RESOURCE_PREFIX = "trailblaze-config/sdk/typescript"

  /**
   * JAR classpath prefix for the bundled tsc payload. Mirrors `:trailblaze-host`'s
   * `copyTypescriptCompilerResources` Gradle task, which stages files under
   * `build/generated-resources/typecheck/trailblaze-config/typecheck/typescript/...`.
   */
  internal const val TYPECHECK_RESOURCE_PREFIX = "trailblaze-config/typecheck/typescript"

  // Workspace-side path constants — written at the workspace root (`trails/`) rather than
  // under `config/tools/` so the SDK lives above per-pack content and is unambiguously
  // workspace-level. Packs reference it via a relative `paths:` entry computed at
  // `trailblaze compile` time — see [PerPackTsconfigEmitter].
  internal const val GENERATED_DIR_NAME = ".trailblaze"
  internal const val SDK_DIR_NAME = "sdk"

  /**
   * Subdirectory of `.trailblaze/` where the bundled tsc payload lands. Sibling of
   * `sdk/` so the on-disk layout reads as "workspace-level TypeScript wiring lives here."
   */
  internal const val TYPECHECK_DIR_NAME = "typecheck"

  /**
   * Pack-aware tsc entry point relative to the typescript install root. Used to compute
   * the absolute path returned from [extractTypecheck] and consumed by `trailblaze
   * typecheck` when spawning `bun|node <tscJs> --project <packTsconfig>`.
   */
  internal const val TSC_ENTRY_RELATIVE_PATH = "lib/_tsc.js"

  /**
   * Path of the rolled-up declaration bundle relative to [SDK_DIR_NAME]. Must match the
   * resource layout the `:trailblaze-models` Gradle build produces (`dist/index.d.ts`)
   * and the path [PerPackTsconfigEmitter] inlines into per-pack tsconfigs as the `paths`
   * target. POSIX separator is correct on both sides — `Path.resolve` accepts `/` and the
   * tsconfig consumer is TypeScript, which is platform-neutral.
   */
  internal const val SDK_DTS_BUNDLE_RELATIVE_PATH: String = "dist/index.d.ts"

  /**
   * Filename of the pre-Phase-D workspace base tsconfig that per-pack tsconfigs used
   * to `extends:`. No longer written — see [pruneStaleWorkspaceTsconfigBase]. Pulled
   * into a const for symmetry with [SDK_DTS_BUNDLE_RELATIVE_PATH] and so future
   * readers grep-find the legacy path from one place.
   */
  private const val LEGACY_WORKSPACE_TSCONFIG_BASE: String = "tsconfig.base.json"
}
