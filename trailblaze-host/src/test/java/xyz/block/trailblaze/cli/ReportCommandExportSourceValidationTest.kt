package xyz.block.trailblaze.cli

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import picocli.CommandLine

/**
 * Pins which report the animated timeline exports record. Both HTML artifacts now implement the
 * autoplay-capture contract, so `--no-wasm-report --gif` is a legitimate combination (it used to
 * be rejected) and `--export-from` is what picks between them.
 *
 * Same throw-on-invoke pattern as [ReportCommandStoryboardValidationTest]: the providers throw, so
 * a MISUSE result proves the guard fired before the command reached the daemon, and a non-MISUSE
 * result proves it did not.
 */
class ReportCommandExportSourceValidationTest {

  @Test
  fun `--no-wasm-report with --gif is accepted`() {
    assertNotEquals(TrailblazeExitCode.MISUSE.code, runReport("--id", "x", "--no-wasm-report", "--gif"))
  }

  @Test
  fun `--no-wasm-report with --video is accepted`() {
    assertNotEquals(TrailblazeExitCode.MISUSE.code, runReport("--id", "x", "--no-wasm-report", "--video"))
  }

  @Test
  fun `--no-wasm-report with --webp is accepted`() {
    assertNotEquals(TrailblazeExitCode.MISUSE.code, runReport("--id", "x", "--no-wasm-report", "--webp"))
  }

  @Test
  fun `--export-from wasm with --no-wasm-report exits with MISUSE`() {
    assertEquals(
      TrailblazeExitCode.MISUSE.code,
      runReport("--id", "x", "--gif", "--export-from", "wasm", "--no-wasm-report"),
    )
  }

  @Test
  fun `--export-from with an unknown artifact exits with MISUSE`() {
    assertEquals(TrailblazeExitCode.MISUSE.code, runReport("--id", "x", "--gif", "--export-from", "legacy"))
  }

  @Test
  fun `--export-from without an animated export exits with MISUSE`() {
    assertEquals(TrailblazeExitCode.MISUSE.code, runReport("--id", "x", "--export-from", "interactive"))
  }

  @Test
  fun `--export-from accepts either artifact name, case-insensitively`() {
    assertNotEquals(TrailblazeExitCode.MISUSE.code, runReport("--id", "x", "--gif", "--export-from", "Interactive"))
    assertNotEquals(TrailblazeExitCode.MISUSE.code, runReport("--id", "x", "--gif", "--export-from", "WASM"))
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
