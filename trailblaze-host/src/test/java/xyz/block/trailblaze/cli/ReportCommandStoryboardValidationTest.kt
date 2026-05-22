package xyz.block.trailblaze.cli

import kotlin.test.Test
import kotlin.test.assertEquals
import picocli.CommandLine

/**
 * Pins the picocli validation guards [ReportCommand] enforces around the storyboard
 * flags. Runs the full command tree through picocli with throw-on-invoke `appProvider`
 * / `configProvider` lambdas — the guards must fire BEFORE the daemon is reached, so
 * the throw-on-invoke pattern would surface an `error("appProvider must not be …")` if
 * the guards regressed and accidentally fell through to execution.
 *
 * Sibling to [ReportCommandSharedCaptureTest] (which pins the GIF/WebP auto-promote
 * matrix). Both target the orchestrator's USAGE-exit behavior without the cost of a
 * real session, MCP daemon, or Playwright capture.
 */
class ReportCommandStoryboardValidationTest {

  @Test
  fun `--storyboard-columns above the valid range exits with USAGE`() {
    val exit = runReport("--storyboard", "/tmp/sb.webp", "--id", "x", "--storyboard-columns", "13")
    assertEquals(CommandLine.ExitCode.USAGE, exit)
  }

  @Test
  fun `--storyboard-columns below the valid range exits with USAGE`() {
    val exit = runReport("--storyboard", "/tmp/sb.webp", "--id", "x", "--storyboard-columns", "0")
    assertEquals(CommandLine.ExitCode.USAGE, exit)
  }

  @Test
  fun `picocli rejects passing both --storyboard-yaml and --no-storyboard-yaml on the same invocation`() {
    // Picocli's default for negatable boolean options is to forbid specifying the same
    // option (positive or negated form) more than once on a single invocation — it
    // throws OverwrittenOptionException, which picocli's run() handler surfaces as a
    // USAGE exit. Pin that behavior so a future picocli upgrade or
    // setOverwrittenOptionsAllowed change doesn't silently turn the intended-error
    // case into a confusing "last wins" without us noticing.
    val exit = runReport(
      "--storyboard", "/tmp/sb.webp", "--id", "x",
      "--storyboard-yaml", "--no-storyboard-yaml",
    )
    assertEquals(
      CommandLine.ExitCode.USAGE,
      exit,
      "Specifying both the positive and negated form should fail with USAGE — picocli " +
        "treats this as ambiguous user input rather than applying \"last wins\".",
    )
  }

  private fun runReport(vararg args: String): Int {
    val root = CommandLine(
      TrailblazeCliCommand(
        appProvider = { error("appProvider must not be invoked when validation rejects the args") },
        configProvider = { error("configProvider must not be invoked when validation rejects the args") },
      ),
    ).setCaseInsensitiveEnumValuesAllowed(true)
    return root.execute("report", *args)
  }
}
