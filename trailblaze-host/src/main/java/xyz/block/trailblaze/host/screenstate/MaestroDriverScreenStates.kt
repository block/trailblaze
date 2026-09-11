package xyz.block.trailblaze.host.screenstate

import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.host.driver.HostScreenStateDeps
import xyz.block.trailblaze.util.Console

/**
 * The Maestro-path capture, shared by every descriptor whose driver reads its screen through a
 * Maestro driver — both Playwright descriptors and [xyz.block.trailblaze.host.ios.IosHostDriverDescriptor].
 *
 * The live driver for a device still lives on the shared manager (it is written by the Maestro run
 * path), so capture reads it through
 * [HostScreenStateDeps.activeMaestroDriver] until that state moves into descriptors.
 */
internal object MaestroDriverScreenStates {

  /** Screen state from the device's active Maestro driver, or null when no session is live. */
  fun fromActiveDriver(
    deps: HostScreenStateDeps,
    deviceId: TrailblazeDeviceId,
  ): ScreenState? {
    val driver = deps.activeMaestroDriver(deviceId) ?: return null
    return try {
      HostMaestroDriverScreenState(maestroDriver = driver)
    } catch (e: Exception) {
      Console.log("❌ Exception getting screen state via driver: ${e.message}")
      e.printStackTrace()
      null
    }
  }
}
