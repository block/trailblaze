package xyz.block.trailblaze.cli

import picocli.CommandLine
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins the early-validation guards in [cliOneShotWithDevice] and
 * [cliReusableWithDevice]. Both helpers must reject null/blank `--device`
 * with [CommandLine.ExitCode.USAGE] before any daemon traffic, so a
 * misconfigured invocation never starts an MCP session.
 *
 * Network-level wrapper behavior is covered by `CliMcpClient*Test` classes
 * — those exercise [CliMcpClient.connectOneShot] / [CliMcpClient.connectReusable]
 * directly with mock servers.
 */
class CliInfrastructureHelpersTest {

  @Test
  fun `cliOneShotWithDevice returns USAGE when --device is null`() {
    val exit = cliOneShotWithDevice(verbose = true, device = null) { _ ->
      error("action must not run when --device is null")
    }
    assertEquals(CommandLine.ExitCode.USAGE, exit)
  }

  @Test
  fun `cliOneShotWithDevice returns USAGE when --device is blank`() {
    val exit = cliOneShotWithDevice(verbose = true, device = "   ") { _ ->
      error("action must not run when --device is blank")
    }
    assertEquals(CommandLine.ExitCode.USAGE, exit)
  }

  @Test
  fun `cliReusableWithDevice returns USAGE when --device is null`() {
    val exit = cliReusableWithDevice(
      verbose = true,
      device = null,
      sessionScope = "blaze-android",
    ) { _ -> error("action must not run when --device is null") }
    assertEquals(CommandLine.ExitCode.USAGE, exit)
  }

  @Test
  fun `cliReusableWithDevice returns USAGE when --device is blank`() {
    val exit = cliReusableWithDevice(
      verbose = true,
      device = "",
      sessionScope = "blaze-android",
    ) { _ -> error("action must not run when --device is blank") }
    assertEquals(CommandLine.ExitCode.USAGE, exit)
  }
}
