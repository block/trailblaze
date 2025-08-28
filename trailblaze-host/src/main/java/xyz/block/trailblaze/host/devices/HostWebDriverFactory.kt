package xyz.block.trailblaze.host.devices

import xyz.block.trailblaze.host.playwright.MaestroPlaywrightDriver

class HostWebDriverFactory {

  fun createWeb(
    headless: Boolean = isRunningOnCi(),
  ): TrailblazeConnectedDevice {
    val playwrightDriver = MaestroPlaywrightDriver(headless)
    return TrailblazeConnectedDevice(playwrightDriver)
  }

  companion object {
    fun isRunningOnCi(): Boolean = System.getenv("KOCHIKU_ENV") != null
  }
}
