package xyz.block.trailblaze.cli

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import picocli.CommandLine

/**
 * `trailblaze report` still parses the flags that were retired with the Compose/WebAssembly
 * report. picocli aborts the whole command on an unknown option, so a caller that predates the
 * removal — `block/trailblaze`'s `.github/pr_generate_report_assets.sh` passes `--no-wasm-report`
 * on both of its invocations — would fail its report step over a flag that now describes the only
 * behavior there is.
 *
 * Parses rather than executes: acceptance is a parser-level property, and running the command
 * would need a daemon and a real session.
 */
class ReportCommandRetiredFlagTest {

  @Test
  fun `the flags retired with the legacy report still parse`() {
    // Both forms a script can write. --export-from took a value, so it must still consume one.
    parseReport("--no-wasm-report", "--id", "x")
    parseReport("--export-from", "wasm", "--id", "x")
    parseReport("--export-from=interactive", "--id", "x")
  }

  @Test
  fun `an unknown option is still rejected`() {
    // Guards the test above against vacuity: if picocli tolerated anything, accepting the
    // retired flags would prove nothing.
    assertFailsWith<CommandLine.UnmatchedArgumentException> {
      parseReport("--no-such-report-flag", "--id", "x")
    }
  }

  @Test
  fun `the retired flags stay out of help and the generated CLI docs`() {
    val reportSpec = commandLine().subcommands["report"]!!.commandSpec
    listOf("--no-wasm-report", "--export-from").forEach { name ->
      val option = reportSpec.findOption(name)
      assertTrue(option != null, "$name must remain declared so an older caller's argv parses")
      assertTrue(option.hidden(), "$name is a no-op and must not be advertised in --help")
    }
    assertFalse(
      CommandLine(reportSpec).usageMessage.contains("wasm"),
      "a retired no-op must not appear in the report command's help text",
    )
  }

  private fun commandLine() = CommandLine(
    TrailblazeCliCommand(
      appProvider = { error("appProvider must not be invoked while only parsing args") },
      configProvider = { error("configProvider must not be invoked while only parsing args") },
    ),
  ).setCaseInsensitiveEnumValuesAllowed(true)

  private fun parseReport(vararg args: String) {
    commandLine().parseArgs("report", *args)
  }
}
