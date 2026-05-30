package xyz.block.trailblaze.cli

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import picocli.CommandLine

/**
 * Tests for the `trailblaze migrate-trails` picocli wrapper. Covers exit codes
 * for usage errors (missing dir, non-dir argument) and the happy paths
 * (default output filename in the input directory, override via `--output`).
 *
 * The deep migration-algorithm coverage lives in `V3MigratorTest`; this file
 * pins the CLI shell that calls it.
 */
class MigrateTrailsCommandTest {

  private val workDir: File = createTempDirectory("migrate-trails-command-test").toFile()

  @AfterTest fun cleanup() {
    workDir.deleteRecursively()
  }

  @Test
  fun `missing input directory argument exits EXIT_USAGE`() {
    val exit = CommandLine(MigrateTrailsCommand()).execute()
    // Picocli returns its own usage-error code (2) on missing required args
    // before our `call()` body runs. We treat that as functionally the same
    // as the EXIT_USAGE branch — both signal "user invoked the command
    // incorrectly."
    // Picocli's default ParameterException handler still returns 2 (its USAGE
    // constant) when no IParameterExceptionHandler is installed on the test
    // CommandLine. Production wiring (via installTrailblazeExceptionHandlers)
    // remaps that to MISUSE.code (3); the test exercises the *command's* own
    // EXIT_USAGE constant via the other cases below.
    assertEquals(2, exit, "expected picocli's default USAGE code when <input-dir> is omitted")
  }

  @Test
  fun `non-directory argument exits EXIT_USAGE with a clear error`() {
    val notADir = File(workDir, "regular-file.txt").apply { writeText("hi") }
    val exit = CommandLine(MigrateTrailsCommand()).execute(notADir.absolutePath)
    assertEquals(TrailblazeExitCode.MISUSE.code, exit, "expected MISUSE when the argument is a file, not a directory")
  }

  @Test
  fun `directory with no trail files exits EXIT_USAGE`() {
    val empty = File(workDir, "empty").apply { mkdirs() }
    val exit = CommandLine(MigrateTrailsCommand()).execute(empty.absolutePath)
    assertEquals(TrailblazeExitCode.MISUSE.code, exit, "expected MISUSE when the directory contains no *.trail.yaml files")
  }

  @Test
  fun `happy path writes trail yaml to the input directory by default`() {
    val dir = File(workDir, "case").apply { mkdirs() }
    File(dir, "android-phone.trail.yaml").writeText(
      """
      - config: {id: x, target: x, platform: android}
      - prompts:
        - step: Open the app
          recording:
            tools:
            - tapOnPoint: {x: 1, y: 2}
      """.trimIndent(),
    )
    val exit = CommandLine(MigrateTrailsCommand()).execute(dir.absolutePath)
    assertEquals(TrailblazeExitCode.SUCCESS.code, exit, "expected success exit code")
    val out = File(dir, "trail.yaml")
    assertTrue(out.isFile, "expected trail.yaml under the input dir; got: ${dir.list()?.toList()}")
    // Quick sanity check that the output looks like v3.
    val text = out.readText()
    assertTrue("config:" in text, "expected v3 `config:` block in output")
    assertTrue("trail:" in text, "expected v3 `trail:` block in output")
  }

  @Test
  fun `--output that points at an input file is refused with EXIT_USAGE`() {
    // Guard against data loss: if the user accidentally passes one of the
    // input *.trail.yaml files as --output, the migration would silently
    // overwrite that source file with the unified output.
    val dir = File(workDir, "case").apply { mkdirs() }
    val sourceFile = File(dir, "android-phone.trail.yaml").apply {
      writeText(
        """
        - config: {id: x, target: x, platform: android}
        - prompts:
          - step: Open the app
            recording:
              tools:
              - tapOnPoint: {x: 1, y: 2}
        """.trimIndent(),
      )
    }
    val originalContent = sourceFile.readText()
    val exit = CommandLine(MigrateTrailsCommand()).execute(
      dir.absolutePath,
      "--output", sourceFile.absolutePath,
    )
    assertEquals(TrailblazeExitCode.MISUSE.code, exit, "expected MISUSE when --output points at an input")
    assertEquals(
      originalContent,
      sourceFile.readText(),
      "the source file must not be modified when the guard fires",
    )
  }

  @Test
  fun `--output flag overrides the default output path`() {
    val dir = File(workDir, "case").apply { mkdirs() }
    File(dir, "android-phone.trail.yaml").writeText(
      """
      - config: {id: x, target: x, platform: android}
      - prompts:
        - step: Open the app
          recording:
            tools:
            - tapOnPoint: {x: 1, y: 2}
      """.trimIndent(),
    )
    val customOut = File(workDir, "custom-output.yaml")
    val exit = CommandLine(MigrateTrailsCommand()).execute(
      dir.absolutePath,
      "--output", customOut.absolutePath,
    )
    assertEquals(TrailblazeExitCode.SUCCESS.code, exit, "expected success exit code with --output")
    assertTrue(customOut.isFile, "expected v3 file at the custom path")
    assertFalse(
      File(dir, "trail.yaml").exists(),
      "should NOT write the default trail.yaml when --output overrides",
    )
  }
}
