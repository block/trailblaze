package xyz.block.trailblaze.ui

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import xyz.block.trailblaze.cli.TrailblazeExitCode
import xyz.block.trailblaze.cli.daemonRunFailureExitCode
import xyz.block.trailblaze.config.AppTargetYamlConfig
import xyz.block.trailblaze.config.ToolNameResolver
import xyz.block.trailblaze.config.YamlBackedHostAppTarget
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

  @Test
  fun `a run from a workspace whose trailmap the daemon serves from a bundled copy fails`() {
    val workspace = kotlin.io.path.createTempDirectory("shadowed-run").toFile()
    try {
      File(workspace, "trailblaze-config/trailmaps/app").apply { mkdirs() }
        .resolve("trailmap.yaml").writeText("id: app\ntarget:\n  display_name: App\n")
      File(workspace, "trailblaze-config/trailblaze.yaml").writeText("")
      val bundledApp = YamlBackedHostAppTarget(
        config = AppTargetYamlConfig(id = "app", displayName = "App"),
        toolNameResolver = ToolNameResolver.fromBuiltInAndCustomTools(),
        trailmapDirs = mapOf("app" to null),
      )
      val ownApp = YamlBackedHostAppTarget(
        config = AppTargetYamlConfig(id = "app", displayName = "App"),
        toolNameResolver = ToolNameResolver.fromBuiltInAndCustomTools(),
        trailmapDirs = mapOf("app" to File(workspace, "trailblaze-config/trailmaps/app")),
      )

      val refused = cliRunShadowedTrailmapsResponse(workspace.absolutePath, bundledApp)

      assertNotNull(refused)
      assertFalse(refused.success)
      assertTrue(refused.error.orEmpty().contains("app"), "${refused.error}")
      // The trail never ran: an infra failure, not a failed assertion.
      assertEquals(TrailblazeExitCode.INFRA_FAILED, daemonRunFailureExitCode(refused))
      assertNull(cliRunShadowedTrailmapsResponse(workspace.absolutePath, ownApp))
      // A submission with no caller directory (an MCP or HTTP client) has no workspace to protect.
      assertNull(cliRunShadowedTrailmapsResponse(null, bundledApp))
      // A caller whose TRAILBLAZE_CONFIG_DIR names the workspace is checked from anywhere.
      val elsewhere = kotlin.io.path.createTempDirectory("not-a-workspace").toFile()
      try {
        assertNull(cliRunShadowedTrailmapsResponse(elsewhere.absolutePath, bundledApp))
        assertNotNull(
          cliRunShadowedTrailmapsResponse(
            elsewhere.absolutePath,
            bundledApp,
            callerConfigDir = File(workspace, "trailblaze-config").absolutePath,
          ),
        )
      } finally {
        elsewhere.deleteRecursively()
      }
    } finally {
      workspace.deleteRecursively()
    }
  }
}
