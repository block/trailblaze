package xyz.block.trailblaze.mcp

import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StoppedOnDeviceRunnersTest {

  private val runner = "xyz.block.trailblaze.runner"
  private val device = TrailblazeDeviceId(
    instanceId = "emulator-5560",
    trailblazeDevicePlatform = TrailblazeDevicePlatform.ANDROID,
  )

  /**
   * The status is what sends the CLI's reuse probe down its reconnect path. Reported once and then
   * dropped, the next probe reads healthy, reuses the dead association, and every command fails
   * with "No device connected" until the daemon restarts.
   */
  @Test
  fun `a stopped runner is reported on every read until the agent is ready again`() {
    val stopped = StoppedOnDeviceRunners()
    stopped.markStopped(device.instanceId, runner)

    val first = stopped.status(device)
    assertNotNull(first)
    assertTrue(first.contains(runner) && first.contains("Reconnect the device"), first)
    assertEquals(first, stopped.status(device))

    stopped.clear(device.instanceId)
    assertNull(stopped.status(device))
  }

  @Test
  fun `a failed relaunch reports its own reason and stays actionable`() {
    val stopped = StoppedOnDeviceRunners()
    stopped.markStopped(device.instanceId, runner)

    stopped.relaunchFailed(device.instanceId, "INSTALL_FAILED_INSUFFICIENT_STORAGE")

    val status = assertNotNull(stopped.status(device))
    assertTrue(status.contains("INSTALL_FAILED_INSUFFICIENT_STORAGE"), status)
    assertTrue(status.contains("emulator-5560") && status.contains("Reconnect the device"), status)
  }

  /** A first connect that fails was never a stopped runner; its status path is unchanged. */
  @Test
  fun `a failure on a device whose runner was not found dead reports nothing`() {
    val stopped = StoppedOnDeviceRunners()

    stopped.relaunchFailed(device.instanceId, "boom")

    assertNull(stopped.status(device))
  }
}
