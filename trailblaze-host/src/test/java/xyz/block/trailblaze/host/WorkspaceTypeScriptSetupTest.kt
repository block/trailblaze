package xyz.block.trailblaze.host

import java.io.File
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import xyz.block.trailblaze.config.AppTargetYamlConfig
import xyz.block.trailblaze.config.PlatformConfig

/**
 * Tests for [WorkspaceTypeScriptSetup] — the orchestration layer that extracts the
 * vendored TypeScript SDK + runs `bun install` per pack-with-package.json after a
 * successful compile/bootstrap.
 *
 * Coverage:
 *  - SDK extraction from JAR resources writes the expected file layout under
 *    `<workspace>/config/tools/.trailblaze/sdk/typescript/`.
 *  - `skipNpmInstall = true` emits the SDK but skips bun install entirely.
 *  - `onlyInstallIfMissing = true` skips bun install in pack dirs whose `node_modules/`
 *    already exists — the daemon-init optimization that keeps subsequent commands fast.
 *  - Empty `resolvedTargets` is a no-op for the install loop (SDK still extracts, but
 *    no bun install is attempted because there are no per-pack tools dirs to walk).
 *
 * NOT covered here: the actual `bun install` subprocess (would require bun on the test
 * runner's PATH). The `bun.missing` warning path is exercised by the production code's
 * `isBunOnPath()` check; CI containers typically don't have bun, so we just assert the
 * helper degrades gracefully via the `skipNpmInstall` opt-out instead.
 */
class WorkspaceTypeScriptSetupTest {

  private val tempDirs = mutableListOf<File>()

  @AfterTest
  fun cleanup() {
    tempDirs.forEach { it.deleteRecursively() }
    tempDirs.clear()
  }

  @Test
  fun `extractSdk writes bundled SDK source under config-tools-dot-trailblaze-sdk-typescript`() {
    val workspace = newWorkspaceRoot()

    val sdkDir = WorkspaceTypeScriptSetup.extractSdk(workspace.toPath())

    // Top-level layout:
    val sdkRoot = File(workspace, "config/tools/.trailblaze/sdk/typescript")
    assertTrue(sdkRoot.isDirectory, "expected SDK root at $sdkRoot")
    assertEquals(sdkRoot.absolutePath, sdkDir.toAbsolutePath().toString())

    // Spot-check the canonical files the JAR resource bundling step ships:
    assertTrue(File(sdkRoot, "package.json").isFile, "expected package.json")
    assertTrue(File(sdkRoot, "src/index.ts").isFile, "expected src/index.ts")
    assertTrue(File(sdkRoot, "src/context.ts").isFile, "expected src/context.ts")

    // package.json content sanity (it's the actual SDK package.json, not a stub):
    val packageJson = File(sdkRoot, "package.json").readText()
    assertTrue(
      packageJson.contains("@trailblaze/scripting"),
      "expected SDK package.json to declare @trailblaze/scripting; got: ${packageJson.take(200)}",
    )
  }

  @Test
  fun `extractSdk is idempotent — re-run with same input doesn't churn mtimes`() {
    val workspace = newWorkspaceRoot()

    val sdkDir = WorkspaceTypeScriptSetup.extractSdk(workspace.toPath())
    val indexTs = sdkDir.resolve("src/index.ts")
    val firstMtime = Files.getLastModifiedTime(indexTs)

    Thread.sleep(1_100) // bigger than 1s mtime granularity on older filesystems

    WorkspaceTypeScriptSetup.extractSdk(workspace.toPath())
    val secondMtime = Files.getLastModifiedTime(indexTs)

    assertEquals(
      firstMtime,
      secondMtime,
      "Idempotent extractor should not rewrite files on identical input " +
        "(firstMtime=$firstMtime, secondMtime=$secondMtime)",
    )
  }

  @Test
  fun `setUp with skipNpmInstall emits SDK but reports skipped install`() {
    val workspace = newWorkspaceRoot()
    val packsDir = packsDirFor(workspace)
    // Even with a target pack having a package.json, skipNpmInstall=true bypasses the
    // install loop entirely.
    val packId = "alpha"
    val toolsDir = File(packsDir, "$packId/tools").apply { mkdirs() }
    File(toolsDir, "package.json").writeText("""{"name":"alpha-tools","private":true}""")

    val target = AppTargetYamlConfig(
      id = packId,
      displayName = "Alpha",
      platforms = mapOf("android" to PlatformConfig(appIds = listOf("com.example.alpha"))),
      tools = emptyList(),
    )

    val result = WorkspaceTypeScriptSetup.setUp(
      workspaceRoot = workspace.toPath(),
      resolvedTargets = listOf(target),
      packsDir = packsDir,
      skipNpmInstall = true,
    )

    // SDK was still extracted.
    assertTrue(File(workspace, "config/tools/.trailblaze/sdk/typescript/package.json").isFile)
    // Install was skipped.
    assertTrue(result.skippedInstall)
    assertTrue(result.installs.isEmpty(), "expected no install attempts when skipped: ${result.installs}")
    // Critically, no node_modules was created.
    assertFalse(File(toolsDir, "node_modules").exists())
  }

  @Test
  fun `setUp with empty resolvedTargets extracts SDK and runs no installs`() {
    // Library-only workspace (no target packs) — no per-pack tools dirs to walk.
    val workspace = newWorkspaceRoot()
    val packsDir = packsDirFor(workspace)

    val result = WorkspaceTypeScriptSetup.setUp(
      workspaceRoot = workspace.toPath(),
      resolvedTargets = emptyList(),
      packsDir = packsDir,
      skipNpmInstall = true, // either way, but explicit for clarity
    )

    // SDK extracted...
    assertTrue(File(workspace, "config/tools/.trailblaze/sdk/typescript/package.json").isFile)
    // ...but no install loop iterations.
    assertTrue(result.installs.isEmpty())
  }

  @Test
  fun `runPerPackBunInstall with onlyInstallIfMissing skips packs whose node_modules already exists`() {
    // Daemon-init optimization: subsequent `trailblaze blaze` etc. don't re-pay install
    // cost when node_modules is already populated.
    val workspace = newWorkspaceRoot()
    val packsDir = packsDirFor(workspace)
    val packId = "alpha"
    val toolsDir = File(packsDir, "$packId/tools").apply { mkdirs() }
    File(toolsDir, "package.json").writeText("""{"name":"alpha-tools","private":true}""")
    File(toolsDir, "node_modules").mkdirs()

    val target = AppTargetYamlConfig(
      id = packId,
      displayName = "Alpha",
      platforms = mapOf("android" to PlatformConfig(appIds = listOf("com.example.alpha"))),
      tools = emptyList(),
    )

    val installs = WorkspaceTypeScriptSetup.runPerPackBunInstall(
      resolvedTargets = listOf(target),
      packsDir = packsDir,
      onlyInstallIfMissing = true,
      isBunAvailable = { true },
    )

    // Exactly one entry, of the Skipped variant — pack had package.json AND node_modules.
    assertEquals(1, installs.size)
    val install = installs.single()
    assertTrue(
      install is WorkspaceTypeScriptSetup.PackInstall.Skipped,
      "expected Skipped variant, got: ${install.javaClass.simpleName}",
    )
    assertEquals(packId, install.packId)
  }

  @Test
  fun `runPerPackBunInstall ignores packs without a package_json`() {
    // A pack without authored TypeScript tooling — no package.json — produces no install
    // entry at all (not Skipped, not anything). It just doesn't show up.
    val workspace = newWorkspaceRoot()
    val packsDir = packsDirFor(workspace)
    val packId = "alpha"
    File(packsDir, "$packId/tools").mkdirs() // tools dir exists, no package.json inside

    val target = AppTargetYamlConfig(
      id = packId,
      displayName = "Alpha",
      tools = emptyList(),
    )

    val installs = WorkspaceTypeScriptSetup.runPerPackBunInstall(
      resolvedTargets = listOf(target),
      packsDir = packsDir,
      onlyInstallIfMissing = false,
    )

    assertTrue(installs.isEmpty(), "expected no install entries for pack without package.json: $installs")
  }

  // ---- helpers ----------------------------------------------------------------------------

  /**
   * Mirrors production layout: `<temp>/trails/` is the generator's `workspaceRoot`, and
   * `<temp>/trails/config/packs/` is the `packsDir`. Tests pass these paths to the
   * production code so any path-derivation regression (e.g. an off-by-one
   * `parentFile`/`resolve` in the wire-in code) would fail here too.
   */
  private fun newWorkspaceRoot(): File {
    val dir = createTempDirectory("workspace-typescript-setup-test").toFile()
    tempDirs += dir
    val trailsDir = File(dir, "trails")
    File(trailsDir, "config/packs").mkdirs()
    return trailsDir
  }

  /** Resolves the `<workspaceRoot>/config/packs` path used by production callers. */
  private fun packsDirFor(workspaceRoot: File): File = File(workspaceRoot, "config/packs")
}
