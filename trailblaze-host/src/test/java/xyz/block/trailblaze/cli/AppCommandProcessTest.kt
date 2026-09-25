package xyz.block.trailblaze.cli

import xyz.block.trailblaze.ui.TrailblazePortManager
import xyz.block.trailblaze.ui.DESKTOP_GUI_READY_FILE_ENV_VAR
import xyz.block.trailblaze.ui.signalDesktopGuiReady
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

class AppCommandProcessTest {
  @Test
  fun `Trail Runner launch receives the effective daemon port`() {
    val launcher = File(System.getProperty("java.io.tmpdir"), "trailblaze")
    val builder = trailRunnerLaunchProcessBuilder(launcher, 53_053)

    assertEquals(listOf(launcher.absolutePath, "trailrunner"), builder.command())
    assertEquals("53053", builder.environment()[TrailblazePortManager.HTTP_PORT_ENV_VAR])
  }

  @Test
  fun `desktop GUI startup accepts only a registered window`() {
    val result = waitForDesktopGuiStartup(
      isWindowReady = { true },
      isSpawnAlive = { true },
      spawnExitCode = { error("a live process has no exit code") },
    )

    assertEquals(DesktopGuiStartupResult.Ready, result)
  }

  @Test
  fun `desktop GUI startup rejects a child that exits before registering a window`() {
    val result = waitForDesktopGuiStartup(
      isWindowReady = { false },
      isSpawnAlive = { false },
      spawnExitCode = { 17 },
    )

    assertEquals(DesktopGuiStartupResult.Exited(17), result)
  }

  @Test
  fun `desktop GUI startup rejects a clean no-op without a window`() {
    val result = waitForDesktopGuiStartup(
      isWindowReady = { false },
      isSpawnAlive = { false },
      spawnExitCode = { 0 },
    )

    assertEquals(DesktopGuiStartupResult.Exited(0), result)
  }

  @Test
  fun `desktop GUI startup accepts a window handed off as the child exits`() {
    var windowIsReady = false
    val result = waitForDesktopGuiStartup(
      isWindowReady = { windowIsReady },
      isSpawnAlive = {
        windowIsReady = true
        false
      },
      spawnExitCode = { 0 },
      sleep = {},
    )

    assertEquals(DesktopGuiStartupResult.Ready, result)
  }

  @Test
  fun `desktop GUI startup times out when a live child never registers a window`() {
    var nowMs = 0L
    val result = waitForDesktopGuiStartup(
      isWindowReady = { false },
      isSpawnAlive = { true },
      spawnExitCode = { error("a live process has no exit code") },
      maxWaitMs = 1_000,
      pollIntervalMs = 100,
      nowMs = { nowMs },
      sleep = { nowMs += it },
    )

    assertEquals(DesktopGuiStartupResult.TimedOut, result)
  }

  @Test
  fun `desktop GUI readiness signal creates the parent marker`() {
    val marker = File.createTempFile("trailblaze-desktop-ready-", ".marker")
    marker.delete()

    try {
      signalDesktopGuiReady(mapOf(DESKTOP_GUI_READY_FILE_ENV_VAR to marker.absolutePath))

      assertEquals(true, marker.isFile)
    } finally {
      marker.delete()
    }
  }
}
