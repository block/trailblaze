package xyz.block.trailblaze.cli

import kotlin.test.Test
import kotlin.test.assertEquals
import picocli.CommandLine

/**
 * Pins the `--no-wasm-report` guard in [ReportCommand]: the animated timeline exports
 * capture the legacy WASM report's autoplay, so requesting one while skipping that report
 * is a usage error. Same throw-on-invoke pattern as
 * [ReportCommandStoryboardValidationTest] — the guard must fire before the daemon is
 * reached.
 */
class ReportCommandNoWasmReportValidationTest {

  @Test
  fun `--no-wasm-report with --gif exits with MISUSE`() {
    assertEquals(TrailblazeExitCode.MISUSE.code, runReport("--id", "x", "--no-wasm-report", "--gif"))
  }

  @Test
  fun `--no-wasm-report with --video exits with MISUSE`() {
    assertEquals(TrailblazeExitCode.MISUSE.code, runReport("--id", "x", "--no-wasm-report", "--video"))
  }

  @Test
  fun `--no-wasm-report with --webp exits with MISUSE`() {
    assertEquals(TrailblazeExitCode.MISUSE.code, runReport("--id", "x", "--no-wasm-report", "--webp"))
  }

  private fun runReport(vararg args: String): Int {
    val root = CommandLine(
      TrailblazeCliCommand(
        appProvider = { error("appProvider must not be invoked when validation rejects the args") },
        configProvider = { error("configProvider must not be invoked when validation rejects the args") },
      ),
    ).setCaseInsensitiveEnumValuesAllowed(true)
    installTrailblazeExceptionHandlers(root)
    return root.execute("report", *args)
  }
}
