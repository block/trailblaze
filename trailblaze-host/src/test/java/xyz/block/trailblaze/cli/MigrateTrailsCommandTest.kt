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
  fun `writes dropped-content warnings into the migrated file when an input drops a key`() {
    // End-to-end proof the CLI wires TrailRoundTripDropDetector's findings into the file it writes.
    // The input carries a dedented `below:` anchor at the tool-item level, which the tool decoder
    // silently drops; the migrated trail.yaml must lead with a DROPPED warning naming that key so a
    // reviewer sees it in the artifact (not just the transient console line). Still a success exit —
    // the warning is diagnostic, not a failure.
    val dir = File(workDir, "case").apply { mkdirs() }
    File(dir, "android-phone.trail.yaml").writeText(
      """
      - config: {id: x, target: x, platform: android}
      - prompts:
        - step: Verify the total row
          recording:
            tools:
            - assertVisibleBySelector:
                nodeSelector:
                  androidAccessibility:
                    textRegex: Total
              below:
                androidAccessibility:
                  textRegex: Subtotal
      """.trimIndent(),
    )
    val exit = CommandLine(MigrateTrailsCommand()).execute(dir.absolutePath)
    assertEquals(TrailblazeExitCode.SUCCESS.code, exit, "dropped content is diagnostic, not a failure")
    val text = File(dir, "trail.yaml").readText()
    assertTrue("DROPPED" in text, "migrated file must carry the dropped-content warning; got:\n$text")
    assertTrue("below" in text, "the warning must name the dropped key; got:\n$text")
  }

  @Test
  fun `--fail-on-dropped-content turns a lossy migration into a non-zero exit`() {
    // Same dropped-content input as the diagnostic case above, but with the opt-in gate on: the
    // migration is lossy (the tool-item-level `below:` anchor is dropped), so the command must exit
    // ASSERTION_FAILED instead of SUCCESS — the signal a chained `migrate-trails && commit` needs.
    // The usable file is STILL written with its DROPPED warning; only the exit code changes.
    val dir = File(workDir, "case").apply { mkdirs() }
    File(dir, "android-phone.trail.yaml").writeText(
      """
      - config: {id: x, target: x, platform: android}
      - prompts:
        - step: Verify the total row
          recording:
            tools:
            - assertVisibleBySelector:
                nodeSelector:
                  androidAccessibility:
                    textRegex: Total
              below:
                androidAccessibility:
                  textRegex: Subtotal
      """.trimIndent(),
    )
    val exit = CommandLine(MigrateTrailsCommand()).execute(
      dir.absolutePath,
      "--fail-on-dropped-content",
    )
    assertEquals(
      TrailblazeExitCode.ASSERTION_FAILED.code,
      exit,
      "expected ASSERTION_FAILED when dropped content exists and the gate is on",
    )
    val out = File(dir, "trail.yaml")
    assertTrue(out.isFile, "the usable file must still be written even when the gate fails the exit code")
    assertTrue("DROPPED" in out.readText(), "the written file must still carry the dropped-content warning")
  }

  @Test
  fun `--fail-on-dropped-content on a clean migration still exits SUCCESS`() {
    // The gate must not fire spuriously: a clean input (nothing dropped) exits 0 even with the flag on.
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
    val exit = CommandLine(MigrateTrailsCommand()).execute(
      dir.absolutePath,
      "--fail-on-dropped-content",
    )
    assertEquals(
      TrailblazeExitCode.SUCCESS.code,
      exit,
      "a clean migration exits SUCCESS regardless of the gate",
    )
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
  fun `--output blaze_yaml on a blaze-only migration is refused with EXIT_USAGE`() {
    // The overwrite guard must also protect a blaze-only migration's single source: on such a
    // directory `blaze.yaml` is the input, so `--output <dir>/blaze.yaml` would destroy it. The
    // guard now folds blaze.yaml into the input set (report.blazeLoaded), so this is refused.
    val dir = File(workDir, "case").apply { mkdirs() }
    val blazeFile = File(dir, "blaze.yaml").apply {
      writeText(
        """
        - config: {id: x/y, title: A prose-only case}
        - prompts:
          - step: Open the app
        """.trimIndent(),
      )
    }
    val originalContent = blazeFile.readText()
    val exit = CommandLine(MigrateTrailsCommand()).execute(
      dir.absolutePath,
      "--output", blazeFile.absolutePath,
    )
    assertEquals(TrailblazeExitCode.MISUSE.code, exit, "expected MISUSE when --output points at the blaze.yaml input")
    assertEquals(
      originalContent,
      blazeFile.readText(),
      "the blaze.yaml source must not be modified when the guard fires",
    )
  }

  @Test
  fun `blaze-only directory migrates to trail_yaml by default`() {
    // A prose-only case (blaze.yaml and nothing else) migrates through the CLI to a unified
    // trail.yaml in the input dir — the migrator accepts a recording-less blaze-only directory.
    val dir = File(workDir, "case").apply { mkdirs() }
    File(dir, "blaze.yaml").writeText(
      """
      - config: {id: x/y, title: A prose-only case}
      - prompts:
        - step: Open the app
        - verify: The home screen displays
      """.trimIndent(),
    )
    val exit = CommandLine(MigrateTrailsCommand()).execute(dir.absolutePath)
    assertEquals(TrailblazeExitCode.SUCCESS.code, exit, "expected success migrating a blaze-only directory")
    val out = File(dir, "trail.yaml")
    assertTrue(out.isFile, "expected trail.yaml under the input dir; got: ${dir.list()?.toList()}")
    val text = out.readText()
    assertTrue("config:" in text, "expected unified `config:` block in output")
    assertTrue("trail:" in text, "expected unified `trail:` block in output")
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
