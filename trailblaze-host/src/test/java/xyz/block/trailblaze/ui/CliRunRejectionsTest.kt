package xyz.block.trailblaze.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import xyz.block.trailblaze.cli.TrailblazeExitCode
import xyz.block.trailblaze.cli.daemonRunFailureExitCode
import xyz.block.trailblaze.model.TrailExecutionResult

/**
 * Pins the daemon's `/cli/run` rejection responses to their exit-code contract: a request the
 * daemon rejects as invalid (no YAML, ambiguous device) must exit MISUSE (3) on a delegated
 * CLI — the same code the in-process path uses for the same mistake. The old-daemon
 * degradation (no `errorKind` → exit 1) is pinned in TrailblazeExitCodePolicyTest.
 */
class CliRunRejectionsTest {

  @Test
  fun `no-YAML rejection exits MISUSE on a delegated CLI`() {
    val response = cliRunNoYamlResponse()
    assertFalse(response.success)
    assertEquals(TrailblazeExitCode.MISUSE, daemonRunFailureExitCode(response))
  }

  @Test
  fun `ambiguous-device rejection exits MISUSE and names every candidate device`() {
    val specs = listOf("android/emulator-5554", "ios/ABC-123")
    val response = cliRunMultipleDevicesResponse(specs)
    assertFalse(response.success)
    assertEquals(TrailblazeExitCode.MISUSE, daemonRunFailureExitCode(response))
    for (spec in specs) {
      assertTrue(
        response.error.orEmpty().contains(spec),
        "error should name candidate $spec: ${response.error}",
      )
    }
  }

  @Test
  fun `runner misuse rejection exits MISUSE with the rejection message`() {
    val response = cliRunRunnerRejectionResponse(
      TrailExecutionResult.Failed("unknown driver type 'ANDROID_TYPO_DRIVER'", misuse = true),
    )
    assertNotNull(response)
    assertFalse(response.success)
    assertEquals(TrailblazeExitCode.MISUSE, daemonRunFailureExitCode(response))
    assertTrue(
      response.error.orEmpty().contains("ANDROID_TYPO_DRIVER"),
      "error should carry the rejection message: ${response.error}",
    )
  }

  @Test
  fun `ordinary run outcomes keep the normal result flow`() {
    // An attempted-and-failed run, a success, and a cancellation are not rejections —
    // the handler must fall through to its session-based result handling.
    assertNull(cliRunRunnerRejectionResponse(TrailExecutionResult.Failed("assertion failed")))
    assertNull(cliRunRunnerRejectionResponse(TrailExecutionResult.Success()))
    assertNull(cliRunRunnerRejectionResponse(TrailExecutionResult.Cancelled))
  }
}
