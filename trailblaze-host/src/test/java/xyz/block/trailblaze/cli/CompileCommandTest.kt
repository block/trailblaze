package xyz.block.trailblaze.cli

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import picocli.CommandLine

/**
 * Tests for [CompileCommand] — the user-facing `trailblaze compile` picocli
 * command. Covers: option defaults, the missing-`packs/` `EXIT_USAGE` path,
 * a successful end-to-end compile, the workspace-root walk-up that lets the
 * command run from any subdirectory of a workspace, and the fall-back when
 * no workspace marker is found.
 *
 * Sister test: `TrailblazeCompilerMainTest` covers the same compile-then-emit
 * flow at the lighter `TrailblazeCompilerMain` entry point. Together they pin
 * both layers of the compile UX (build-time `JavaExec` and user CLI).
 */
class CompileCommandTest {

  private val workDir: File = createTempDirectory("trailblaze-compile-command-test").toFile()

  @AfterTest fun cleanup() {
    workDir.deleteRecursively()
  }

  @Test
  fun `findWorkspaceRoot returns the workspace itself when invoked from the root`() {
    val workspaceRoot = workDir
    File(workspaceRoot, "trails/config/packs").mkdirs()

    val command = CompileCommand()
    val found = command.findWorkspaceRoot(startPath = workspaceRoot.toPath())
    assertEquals(workspaceRoot.canonicalFile.toPath(), found?.toRealPath())
  }

  @Test
  fun `findWorkspaceRoot walks up from a pack root to the workspace root`() {
    val workspaceRoot = workDir
    val packRoot = File(workspaceRoot, "trails/config/packs/wikipedia").apply { mkdirs() }
    File(packRoot, "pack.yaml").writeText("id: wikipedia\n")

    val command = CompileCommand()
    val found = command.findWorkspaceRoot(startPath = packRoot.toPath())
    assertEquals(workspaceRoot.canonicalFile.toPath(), found?.toRealPath())
  }

  @Test
  fun `findWorkspaceRoot walks up from a pack tools dir to the workspace root`() {
    // The canonical "deep dir" scenario — running `trailblaze compile` from
    // inside a pack's tools/ directory should still find the workspace root
    // without the user counting `../` segments to construct an --input path.
    val workspaceRoot = workDir
    val packToolsDir = File(workspaceRoot, "trails/config/packs/wikipedia/tools").apply { mkdirs() }

    val command = CompileCommand()
    val found = command.findWorkspaceRoot(startPath = packToolsDir.toPath())
    assertEquals(workspaceRoot.canonicalFile.toPath(), found?.toRealPath())
  }

  @Test
  fun `findWorkspaceRoot returns null when no workspace marker is found`() {
    // workDir is /tmp/<random> with no `trails/config/packs/` anywhere up the tree,
    // so the helper returns null and the caller is expected to emit a usage error
    // rather than silently defaulting to a bogus root.
    val isolated = File(workDir, "isolated").apply { mkdirs() }

    val command = CompileCommand()
    val found = command.findWorkspaceRoot(startPath = isolated.toPath())
    assertNull(found)
  }

  @Test
  fun `findWorkspaceRoot still finds a marker many levels above the start dir`() {
    // No depth cap: a deeply-nested start (deeper than what monorepo CLIs would
    // typically encounter) must still walk all the way up to the marker. Pins
    // the "uncapped walk" contract so a future depth-limit regression fails here.
    File(workDir, "trails/config/packs").mkdirs()
    var deep = workDir
    repeat(20) { deep = File(deep, "level").apply { mkdirs() } }

    val command = CompileCommand()
    val found = command.findWorkspaceRoot(startPath = deep.toPath())
    assertEquals(workDir.canonicalFile.toPath(), found?.toRealPath())
  }

  @Test
  fun `compile from inside a workspace exits OK with no flags`() {
    // End-to-end coverage for the headline UX fix: cwd inside a workspace tree
    // (here, the pack tools/ dir 4 levels deep), no flags → exits 0 and emits
    // the materialized target. Uses `CliCallerContext.withCallerCwd` to pin the
    // walk-up start dir without mutating the JVM-wide cwd.
    val workspaceRoot = File(workDir, "workspace").apply { mkdirs() }
    val packToolsDir = File(workspaceRoot, "trails/config/packs/alpha/tools").apply { mkdirs() }
    File(workspaceRoot, "trails/config/packs/alpha/pack.yaml").writeText(
      """
      id: alpha
      target:
        display_name: Alpha
        platforms:
          android:
            app_ids: [com.example.alpha]
      """.trimIndent(),
    )

    val exit = CliCallerContext.withCallerCwd(packToolsDir.toPath()) {
      CommandLine(CompileCommand()).execute()
    }

    assertEquals(0, exit, "Expected EXIT_OK from a no-flag run inside a workspace")
    assertTrue(
      File(workspaceRoot, "trails/config/dist/targets/alpha.yaml").exists(),
      "alpha.yaml should land at <workspace>/trails/config/dist/targets/",
    )
  }

  @Test
  fun `compile from outside any workspace exits EXIT_USAGE with no flags`() {
    // End-to-end coverage for the negative branch: cwd outside any workspace,
    // no --input → walks to filesystem root, finds nothing, exits EXIT_USAGE.
    val isolated = File(workDir, "isolated").apply { mkdirs() }

    val exit = CliCallerContext.withCallerCwd(isolated.toPath()) {
      CommandLine(CompileCommand()).execute()
    }

    assertEquals(2, exit, "Expected EXIT_USAGE when run with no flags outside any workspace")
  }

  @Test
  fun `compile emits target yaml when invoked with explicit input and output`() {
    val packsDir = File(workDir, "packs").apply { mkdirs() }
    File(packsDir, "alpha").mkdirs()
    File(packsDir, "alpha/pack.yaml").writeText(
      """
      id: alpha
      target:
        display_name: Alpha
        platforms:
          android:
            app_ids: [com.example.alpha]
      """.trimIndent(),
    )
    val outputDir = File(workDir, "out")

    val command = CompileCommand()
    val exit = CommandLine(command).execute(
      "--input", workDir.absolutePath,
      "--output", outputDir.absolutePath,
    )

    assertEquals(0, exit, "Expected EXIT_OK from a clean compile")
    assertTrue(File(outputDir, "alpha.yaml").exists(), "alpha.yaml should be emitted")
  }

  @Test
  fun `compile returns EXIT_USAGE when no packs directory is present under input`() {
    val emptyInput = File(workDir, "no-packs").apply { mkdirs() }
    val outputDir = File(workDir, "out")

    val command = CompileCommand()
    val exit = CommandLine(command).execute(
      "--input", emptyInput.absolutePath,
      "--output", outputDir.absolutePath,
    )

    assertEquals(2, exit, "Expected EXIT_USAGE when --input has no packs/ dir")
    assertTrue(!outputDir.exists(), "outputDir should not be created on usage error")
  }

  @Test
  fun `compile returns EXIT_COMPILE_ERROR when a pack has a missing dependency`() {
    val packsDir = File(workDir, "packs").apply { mkdirs() }
    File(packsDir, "consumer").mkdirs()
    File(packsDir, "consumer/pack.yaml").writeText(
      """
      id: consumer
      dependencies:
        - missing-pack
      target:
        display_name: Consumer
        platforms:
          android:
            app_ids: [com.example]
      """.trimIndent(),
    )
    val outputDir = File(workDir, "out")

    val command = CompileCommand()
    val exit = CommandLine(command).execute(
      "--input", workDir.absolutePath,
      "--output", outputDir.absolutePath,
    )

    assertEquals(1, exit, "Expected EXIT_COMPILE_ERROR on resolution failure")
  }

  @Test
  fun `compile uses workspace-root defaults when --input and --output are omitted`() {
    // Mock a workspace at workDir/workspace with the standard layout.
    val workspaceRoot = File(workDir, "workspace").apply { mkdirs() }
    val packsDir = File(workspaceRoot, "trails/config/packs").apply { mkdirs() }
    File(packsDir, "alpha").mkdirs()
    File(packsDir, "alpha/pack.yaml").writeText(
      """
      id: alpha
      target:
        display_name: Alpha
        platforms:
          android:
            app_ids: [com.example.alpha]
      """.trimIndent(),
    )
    val expectedOutputDir = File(workspaceRoot, "trails/config/dist/targets")

    // Inject the workspace root via the test-visible findWorkspaceRoot helper. We can't
    // reliably swap the CWD inside a JVM test, so we set the option fields explicitly to
    // simulate "user ran with no flags + we discovered the workspace root."
    val command = CompileCommand().apply {
      inputDir = File(workspaceRoot, "trails/config")
      outputDir = expectedOutputDir
    }
    val exit = command.call()

    assertEquals(0, exit)
    assertTrue(File(expectedOutputDir, "alpha.yaml").exists(), "Default output dir should land at workspace-root/trails/config/dist/targets")
  }

  @Test
  fun `--help exits zero and prints usage`() {
    // Picocli routes `--help` through `mixinStandardHelpOptions = true`, exiting 0.
    // This pins the convention so a future refactor can't accidentally make help
    // exit non-zero (which would break shell scripts that check `cli --help`).
    val command = CompileCommand()
    val exit = CommandLine(command).execute("--help")
    assertEquals(0, exit)
  }

  @Test
  fun `commandLabel defaults to 'compile' for direct invocation`() {
    // Regression marker: a freshly-constructed CompileCommand emits error prefixes
    // under `trailblaze compile:` unless CheckCommand routes it via
    // `apply { commandLabel = "check" }`. A silent default rename here would break
    // the routing contract without surfacing in any other test.
    assertEquals("compile", CompileCommand().commandLabel)
  }
}
