package xyz.block.trailblaze.host

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import xyz.block.trailblaze.config.AppTargetYamlConfig
import xyz.block.trailblaze.llm.config.ClasspathResourceDiscovery
import xyz.block.trailblaze.util.Console

/**
 * One-stop TypeScript-side workspace setup: extracts the framework's vendored
 * `@trailblaze/scripting` SDK source from the trailblaze JAR's resources into the
 * workspace's `<workspace>/trails/config/tools/.trailblaze/sdk/typescript/` directory,
 * then runs `bun install` in every pack's `tools/` dir that ships a `package.json`.
 *
 * Output of this helper combines with [PerTargetClientDtsEmitter]'s typed-bindings
 * emission to give an OSS adopter complete IDE typing on a fresh clone with one command:
 *
 *  - `trailblaze compile` (or any daemon-aware command on first run): extracts SDK,
 *    emits per-target `client.<id>.d.ts`, runs `bun install` in each pack with a
 *    `package.json`. After this, `node_modules/@trailblaze/scripting` resolves the
 *    workspace-relative `file:` link to the extracted SDK, and the IDE's TypeScript
 *    server picks up the per-target `.d.ts` for typed `client.callTool(name, args)`
 *    autocomplete.
 *
 * **Why per-pack `bun install`, not workspace-global node_modules.** Per-pack
 * `package.json` lets each pack pin its own ad-hoc deps if it needs to (e.g. an
 * in-pack-only utility module the author bundled), and matches the existing in-repo
 * authoring flow (see the playwright-native example pack's `tools/package.json`).
 *
 * **Bun, not npm.** Bun is the in-repo standard, `bun install` is sub-second cold, and
 * one supported tool keeps docs and error messages clean. Authors who don't have bun
 * get a single error message pointing at the install link
 * (`curl -fsSL https://bun.sh/install | bash`).
 *
 * **Failure handling is the caller's call.** This helper just does the work or throws.
 * The compile-CLI wire-in lets exceptions propagate (fail-fast — author wants to see
 * errors immediately on an explicit `trailblaze compile`); the daemon-init wire-in
 * downgrades to a warning (the daemon must come up regardless — trail execution doesn't
 * depend on TypeScript-side typing).
 */
object WorkspaceTypeScriptSetup {

  /**
   * Top-level entry. Extracts the SDK and (optionally) runs `bun install` for every
   * pack-with-package.json. Returns a [SetupResult] describing what happened.
   *
   * @param workspaceRoot the workspace's `trails/` directory — same shape that
   *   [PerTargetClientDtsEmitter.emit] expects. Output lands at
   *   `<workspaceRoot>/config/tools/.trailblaze/sdk/typescript/`.
   * @param resolvedTargets the resolved app-target list (from `TrailblazeCompiler` /
   *   `WorkspaceCompileBootstrap`). Used to discover which pack `tools/` dirs to bun-install.
   * @param packsDir the workspace's `packs/` directory — needed to locate per-pack
   *   `<pack-id>/tools/package.json` files.
   * @param skipNpmInstall when `true` (e.g. via `TRAILBLAZE_SKIP_NPM_INSTALL=1`), the SDK
   *   is still extracted but `bun install` is skipped entirely. Useful for CI containers
   *   that handle node_modules separately or environments where bun is unavailable and
   *   the typing isn't needed.
   * @param onlyInstallIfMissing when `true`, `bun install` runs ONLY in pack dirs whose
   *   `node_modules/` is absent. Used by the daemon-init bootstrap so subsequent
   *   `trailblaze blaze` invocations don't re-pay the install cost. The compile CLI
   *   passes `false` so `trailblaze compile` always refreshes.
   */
  fun setUp(
    workspaceRoot: Path,
    resolvedTargets: List<AppTargetYamlConfig>,
    packsDir: File,
    skipNpmInstall: Boolean = System.getenv("TRAILBLAZE_SKIP_NPM_INSTALL") == "1",
    onlyInstallIfMissing: Boolean = false,
  ): SetupResult {
    val sdkDir = extractSdk(workspaceRoot)
    val installs = if (skipNpmInstall) {
      emptyList()
    } else {
      runPerPackBunInstall(resolvedTargets, packsDir, onlyInstallIfMissing)
    }
    return SetupResult(sdkDir = sdkDir, installs = installs, skippedInstall = skipNpmInstall)
  }

  /**
   * Extracts the SDK source from JAR resources rooted at `trailblaze-config/sdk/typescript/`
   * into `<workspaceRoot>/config/tools/.trailblaze/sdk/typescript/`. Idempotent —
   * skip-write-if-content-matches keeps mtimes stable across runs of the same SDK
   * version (so file watchers and TS language servers don't churn).
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
          "current `sdks/typescript/` source — check that " +
          "`copyTypescriptSdkResources` ran and produced files under " +
          "`build/generated-resources/sdk/`.",
      )
    }
    val sdkRoot = workspaceRoot
      .resolve(WORKSPACE_CONFIG_TOOLS_SUBDIR)
      .resolve(GENERATED_DIR_NAME)
      .resolve(SDK_DIR_NAME)
      .resolve("typescript")
      .toAbsolutePath()
    Files.createDirectories(sdkRoot)
    // Build the expected file set first so we can prune stale files from a previous SDK
    // version before writing the current one. Without this, on framework upgrade where the
    // SDK shape shrinks (e.g. `oldHelper.ts` removed), the stale `oldHelper.ts` would
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
          .forEach { Files.deleteIfExists(it) }
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
   * Scans every immediate subdirectory of [packsDir] for `<pack-id>/tools/package.json`
   * and runs `bun install` in each. Returns one [PackInstall] per pack-with-package.json
   * (skipped, ran, or failed).
   *
   * Iterates the whole `packsDir` rather than just the resolved-target list so library
   * packs that ship TypeScript tooling (rare but possible) get their `node_modules/`
   * populated too. The `resolvedTargets` parameter is kept for the empty-targets early
   * return — a workspace with no resolved targets has nothing meaningful to set up.
   */
  internal fun runPerPackBunInstall(
    resolvedTargets: List<AppTargetYamlConfig>,
    packsDir: File,
    onlyInstallIfMissing: Boolean,
    isBunAvailable: () -> Boolean = ::isBunOnPath,
  ): List<PackInstall> {
    if (resolvedTargets.isEmpty()) return emptyList()
    if (!packsDir.isDirectory) return emptyList()
    val packsWithPackageJson = packsDir
      .listFiles { f -> f.isDirectory }
      .orEmpty()
      .mapNotNull { packDir ->
        val toolsDir = File(packDir, "tools")
        if (File(toolsDir, "package.json").isFile) {
          packDir.name to toolsDir
        } else {
          null
        }
      }
      .sortedBy { it.first }
    if (packsWithPackageJson.isEmpty()) return emptyList()
    if (!isBunAvailable()) {
      Console.error(
        "trailblaze: bun not found in PATH. Per-pack node_modules not populated; IDE " +
          "typing for `@trailblaze/scripting` won't resolve until you install bun:\n" +
          "    curl -fsSL https://bun.sh/install | bash\n" +
          "Or set TRAILBLAZE_SKIP_NPM_INSTALL=1 to opt out of this step entirely (e.g. for " +
          "CI containers that handle node_modules separately). The SDK source and per-target " +
          ".d.ts files were emitted successfully — only the npm-side wiring is missing.",
      )
      return packsWithPackageJson.map { (packId, toolsDir) ->
        PackInstall.BunMissing(packId = packId, toolsDir = toolsDir)
      }
    }
    return packsWithPackageJson.map { (packId, toolsDir) ->
      if (onlyInstallIfMissing && File(toolsDir, "node_modules").isDirectory) {
        PackInstall.Skipped(packId = packId, toolsDir = toolsDir)
      } else {
        runBunInstall(packId = packId, toolsDir = toolsDir)
      }
    }
  }

  /**
   * Probe whether `bun` is on the PATH. We could try-and-recover on the first
   * `ProcessBuilder` exec, but the upfront check produces a single clean error message
   * to the user instead of one error per pack-tools-dir.
   *
   * The probe drains the merged stdout (otherwise the subprocess buffer can fill and
   * stall) and forces resource cleanup via `destroy()` even on the success path — leaking
   * the [Process] handle would tie up file descriptors across many compile/bootstrap
   * invocations on long-running sessions.
   */
  private fun isBunOnPath(): Boolean {
    var proc: Process? = null
    return try {
      proc = ProcessBuilder("bun", "--version")
        .redirectErrorStream(true)
        .start()
      // Drain to prevent the OS pipe buffer from filling up and blocking the subprocess.
      proc.inputStream.use { it.readBytes() }
      proc.waitFor()
      proc.exitValue() == 0
    } catch (_: Exception) {
      false
    } finally {
      proc?.destroy()
    }
  }

  private fun runBunInstall(packId: String, toolsDir: File): PackInstall {
    val output = StringBuilder()
    return try {
      val proc = ProcessBuilder("bun", "install")
        .directory(toolsDir)
        .redirectErrorStream(true)
        .start()
      // Drain stdout/stderr to avoid the subprocess buffer filling up and stalling. The
      // captured output is used for error messages; success path discards it (bun install
      // is verbose enough that propagating it on every command would be noisy).
      proc.inputStream.bufferedReader().use { reader ->
        reader.forEachLine { output.appendLine(it) }
      }
      val exit = proc.waitFor()
      if (exit == 0) {
        PackInstall.Succeeded(packId = packId, toolsDir = toolsDir)
      } else {
        PackInstall.Failed(packId = packId, toolsDir = toolsDir, exitCode = exit, output = output.toString())
      }
    } catch (e: Exception) {
      PackInstall.Failed(packId = packId, toolsDir = toolsDir, exitCode = -1, output = "${e.javaClass.simpleName}: ${e.message}\n$output")
    }
  }

  /** Outcome of a single pack's `bun install`, or its skip/missing-tooling state. */
  sealed interface PackInstall {
    val packId: String
    val toolsDir: File

    data class Succeeded(override val packId: String, override val toolsDir: File) : PackInstall
    data class Skipped(override val packId: String, override val toolsDir: File) : PackInstall
    data class BunMissing(override val packId: String, override val toolsDir: File) : PackInstall
    data class Failed(
      override val packId: String,
      override val toolsDir: File,
      val exitCode: Int,
      val output: String,
    ) : PackInstall
  }

  /** Aggregate result of [setUp]. */
  data class SetupResult(
    val sdkDir: Path,
    val installs: List<PackInstall>,
    val skippedInstall: Boolean,
  )

  // Resource-path constants — mirror the JAR layout from `:trailblaze-models`'s
  // `copyTypescriptSdkResources` Gradle task, which stages files under
  // `build/generated-resources/sdk/trailblaze-config/sdk/typescript/...` and ships them
  // at that classpath path.
  internal const val SDK_RESOURCE_PREFIX = "trailblaze-config/sdk/typescript"

  // Workspace-side path constants — mirror `WorkspaceClientDtsGenerator`'s output dir
  // so the SDK sits next to the generated `.d.ts` files. Per-pack `package.json` then
  // references the SDK via a stable workspace-relative `file:` link
  // (`file:../../../tools/.trailblaze/sdk/typescript`).
  internal const val WORKSPACE_CONFIG_TOOLS_SUBDIR = "config/tools"
  internal const val GENERATED_DIR_NAME = ".trailblaze"
  internal const val SDK_DIR_NAME = "sdk"
}
