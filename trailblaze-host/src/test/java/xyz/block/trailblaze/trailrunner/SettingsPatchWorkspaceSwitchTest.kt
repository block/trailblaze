package xyz.block.trailblaze.trailrunner

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.model.TrailblazeHostAppTarget
import xyz.block.trailblaze.report.utils.LogsRepo
import xyz.block.trailblaze.ui.TrailblazeSettingsRepo
import xyz.block.trailblaze.ui.models.TrailblazeServerState.SavedTrailblazeAppConfig

/**
 * Pins the workspace-switch trigger on [buildSettingsPatchResponse]. Moving the trails directory
 * IS a workspace switch, and the app-target set is the one piece of workspace state that doesn't
 * follow along on its own (it's seeded once at daemon startup), so the patch has to reload it or
 * the picker keeps offering the previous workspace's targets until the daemon restarts.
 *
 * The reload is also the expensive part of a settings patch — it touches disk and can spawn the
 * scripted-tool analyzer — so a patch that doesn't move the workspace must not pay for it.
 */
class SettingsPatchWorkspaceSwitchTest {

  /**
   * Drives [buildSettingsPatchResponse] against a real settings repo, counting app-target reloads.
   * [block] receives the patch entry point, the reload count, and a factory for real workspace
   * directories — real ones because the patch only accepts a trails directory that exists on disk.
   */
  private fun withHarness(
    block: suspend (
      patch: suspend (SettingsPatchRequest) -> Unit,
      reloads: () -> Int,
      newWorkspace: (String) -> String,
    ) -> Unit,
  ) {
    val root = createTempDirectory("tb-settings-patch").toFile()
    try {
      val newWorkspace = { name: String -> File(root, name).apply { mkdirs() }.absolutePath }
      val repo = TrailblazeSettingsRepo(
        settingsFile = File(root, "trailblaze-settings.json"),
        initialConfig = SavedTrailblazeAppConfig(
          selectedTrailblazeDriverTypes = emptyMap(),
          trailsDirectory = newWorkspace("before"),
        ),
        defaultHostAppTarget = TrailblazeHostAppTarget.DefaultTrailblazeHostAppTarget,
        allTargetApps = { setOf(TrailblazeHostAppTarget.DefaultTrailblazeHostAppTarget) },
        supportedDriverTypes = setOf(TrailblazeDriverType.DEFAULT_ANDROID),
      )
      val deps = TrailRunnerDeps(
        trailsRootProvider = { File(root, "trails").apply { mkdirs() } },
        logsRepo = LogsRepo(logsDir = File(root, "logs").apply { mkdirs() }, watchFileSystem = false),
        settingsRepo = repo,
        deviceManager = null,
        integrationsProvider = null,
        integrationActionHandler = null,
        analyticsProvider = null,
        analyticsCaptureStarter = null,
        eventCaptureController = null,
      )
      var reloads = 0
      runBlocking {
        block(
          { request -> buildSettingsPatchResponse(deps, request, reloadAppTargets = { reloads++ }) },
          { reloads },
          newWorkspace,
        )
      }
    } finally {
      root.deleteRecursively()
    }
  }

  @Test
  fun `moving the trails directory reloads the app targets`() {
    withHarness { patch, reloads, newWorkspace ->
      patch(SettingsPatchRequest(trailsDirectory = newWorkspace("after")))

      assertEquals(1, reloads())
    }
  }

  @Test
  fun `re-sending the same trails directory does not reload`() {
    withHarness { patch, reloads, newWorkspace ->
      patch(SettingsPatchRequest(trailsDirectory = newWorkspace("before")))

      assertEquals(0, reloads())
    }
  }

  @Test
  fun `a patch that never mentions the trails directory does not reload`() {
    withHarness { patch, reloads, _ ->
      patch(SettingsPatchRequest(alwaysOnTop = true))

      assertEquals(0, reloads())
    }
  }

  @Test
  fun `a trails directory that does not exist is rejected and does not reload`() {
    withHarness { patch, reloads, _ ->
      patch(SettingsPatchRequest(trailsDirectory = "/definitely/not/a/workspace"))

      // The patch declines to move onto a path that isn't there, so there is no switch to react to.
      assertEquals(0, reloads())
    }
  }

  @Test
  fun `clearing the trails directory is a switch and reloads`() {
    withHarness { patch, reloads, _ ->
      // Blank is the clear-the-field sentinel, which drops the workspace back to the default root.
      patch(SettingsPatchRequest(trailsDirectory = ""))

      assertEquals(1, reloads())
    }
  }
}
