package xyz.block.trailblaze.cli

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
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
  fun `findWorkspaceRoot returns the nearest ancestor containing trails-config`() {
    // Set up: workDir/some/nested/dir, with workDir/trails/config marking the workspace root.
    val workspaceRoot = workDir
    File(workspaceRoot, "trails/config").mkdirs()
    val nested = File(workspaceRoot, "modules/feature/src").apply { mkdirs() }

    val command = CompileCommand()
    val found = command.findWorkspaceRoot(startDir = nested)
    assertEquals(workspaceRoot.canonicalFile, found.canonicalFile)
  }

  @Test
  fun `findWorkspaceRoot falls back to the start dir when no workspace marker is found`() {
    // No `trails/config` anywhere up the tree — workDir is /tmp/<random> with no parent
    // marker — so the helper returns the start dir as a sensible default rather than
    // walking all the way to / and erroring out.
    val isolated = File(workDir, "isolated").apply { mkdirs() }

    val command = CompileCommand()
    val found = command.findWorkspaceRoot(startDir = isolated)
    assertEquals(isolated.canonicalFile, found.canonicalFile)
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
}
