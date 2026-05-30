package xyz.block.trailblaze.cli

import kotlin.test.Test

/**
 * Pins the rejection contract for [cliOneShotWithDevice] and [cliReusableWithDevice]:
 * a bare invocation (no `--device` flag, no `TRAILBLAZE_DEVICE` env, and the
 * resolver cannot autodetect a single connected device) must NOT proceed to
 * the action lambda. See [assertRejectsBareDeviceInvocation] and
 * [canAutoresolveSingleDevice] for the env-probe that keeps the assertion
 * deterministic across test environments.
 *
 * Network-level wrapper behavior is covered by `CliMcpClient*Test` classes
 * — those exercise [CliMcpClient.connectOneShot] / [CliMcpClient.connectReusable]
 * directly with mock servers.
 */
class CliInfrastructureHelpersTest {

  @Test
  fun `cliOneShotWithDevice rejects when --device is null and no env`() {
    if (!System.getenv("TRAILBLAZE_DEVICE").isNullOrBlank()) return
    if (canAutoresolveSingleDevice) return
    val exit = cliOneShotWithDevice(verbose = true, device = null) { _ ->
      error("action must not run when --device is null")
    }
    assertRejectsBareDeviceInvocation(exit)
  }

  @Test
  fun `cliOneShotWithDevice rejects when --device is blank and no env`() {
    if (!System.getenv("TRAILBLAZE_DEVICE").isNullOrBlank()) return
    if (canAutoresolveSingleDevice) return
    val exit = cliOneShotWithDevice(verbose = true, device = "   ") { _ ->
      error("action must not run when --device is blank")
    }
    assertRejectsBareDeviceInvocation(exit)
  }

  @Test
  fun `cliReusableWithDevice rejects when --device is null and no env`() {
    if (!System.getenv("TRAILBLAZE_DEVICE").isNullOrBlank()) return
    if (canAutoresolveSingleDevice) return
    val exit = cliReusableWithDevice(
      verbose = true,
      device = null,
    ) { _ -> error("action must not run when --device is null") }
    assertRejectsBareDeviceInvocation(exit)
  }

  @Test
  fun `cliReusableWithDevice rejects when --device is blank and no env`() {
    if (!System.getenv("TRAILBLAZE_DEVICE").isNullOrBlank()) return
    if (canAutoresolveSingleDevice) return
    val exit = cliReusableWithDevice(
      verbose = true,
      device = "",
    ) { _ -> error("action must not run when --device is blank") }
    assertRejectsBareDeviceInvocation(exit)
  }
}
