package xyz.block.trailblaze.cli

import picocli.CommandLine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins the misuse-envelope contract for the three pre-dispatch validation paths in
 * [StepCommand.call] that do NOT enter [cliReusableWithDevice]:
 *
 *  - missing positional step description
 *  - `--setup` / `--no-setup` passed without `--save`
 *  - `--setup` and `--no-setup` passed together
 *
 * Pre-fix these paths emitted bare `Error: …` prose via [xyz.block.trailblaze.util.Console]
 * and returned exit 3. The exit code was already correct; the rendering was not — every
 * other misuse case in the CLI follows the structured `✗ <Verb> failed / reason: … /
 * hint: …` envelope ([reportCliError]). The originally-reported regression was the
 * OOBE-review finding that `trailblaze step` (no args) printed bare prose alongside
 * sibling commands that emitted the structured envelope, breaking the visual contract.
 *
 * Each test runs the command's `call()` with stderr captured and asserts:
 *  1. exit code is [TrailblazeExitCode.MISUSE] (3) — unchanged from pre-fix behavior,
 *  2. stderr opens with the `✗ Step failed` header (proves we're on the envelope path),
 *  3. stderr contains the expected `reason:` and `hint:` lines (proves the envelope
 *     carries the actionable recovery, not a bare diagnostic),
 *  4. stderr does NOT contain the legacy `Error: ` prose prefix (would silently
 *     regress callers grepping for either shape during a migration window).
 */
class StepCommandMisuseEnvelopeTest {

  private fun runWithArgs(vararg args: String): Pair<Int, String> {
    val command = StepCommand()
    return captureStderr {
      // Use parseArgs + call() rather than CommandLine.execute() to avoid the
      // installed exception handlers; we're testing the in-call() validation
      // path, not the framework-level handlers (covered separately).
      CommandLine(command).parseArgs(*args)
      command.call()
    }
  }

  @Test
  fun `missing step description emits structured envelope with MISUSE code`() {
    val (exitCode, stderr) = runWithArgs()

    assertEquals(TrailblazeExitCode.MISUSE.code, exitCode)
    assertTrue(
      stderr.startsWith("✗ Step failed"),
      "stderr should open with the envelope header; got:\n$stderr",
    )
    assertTrue(
      "reason: step requires a description" in stderr,
      "stderr should carry the reason line; got:\n$stderr",
    )
    assertTrue(
      """`trailblaze step "Tap login"`""" in stderr,
      "stderr should carry the hint with the example invocation; got:\n$stderr",
    )
    assertFalse(
      "Error: step requires a description" in stderr,
      "stderr should not retain the legacy bare-prose `Error: …` form; got:\n$stderr",
    )
  }

  @Test
  fun `setup without save emits structured envelope with MISUSE code`() {
    val (exitCode, stderr) = runWithArgs("--setup", "1-3")

    assertEquals(TrailblazeExitCode.MISUSE.code, exitCode)
    assertTrue(stderr.startsWith("✗ Step failed"), stderr)
    assertTrue("reason: --setup / --no-setup require --save" in stderr, stderr)
    assertTrue("--save <path>" in stderr, stderr)
    assertFalse("Error: --setup and --no-setup require --save" in stderr, stderr)
  }

  @Test
  fun `no-setup without save emits structured envelope with MISUSE code`() {
    val (exitCode, stderr) = runWithArgs("--no-setup")

    assertEquals(TrailblazeExitCode.MISUSE.code, exitCode)
    assertTrue(stderr.startsWith("✗ Step failed"), stderr)
    assertTrue("reason: --setup / --no-setup require --save" in stderr, stderr)
  }

  @Test
  fun `setup and no-setup together emits mutually-exclusive envelope`() {
    val (exitCode, stderr) = runWithArgs("--save", "out.trail.yaml", "--setup", "1-3", "--no-setup")

    assertEquals(TrailblazeExitCode.MISUSE.code, exitCode)
    assertTrue(stderr.startsWith("✗ Step failed"), stderr)
    assertTrue("reason: --setup and --no-setup are mutually exclusive" in stderr, stderr)
    assertTrue("pass one or the other" in stderr, stderr)
    assertFalse("Error: --setup and --no-setup are mutually exclusive" in stderr, stderr)
  }
}
