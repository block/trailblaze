package xyz.block.trailblaze.host.devices

import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.host.playwright.MaestroPlaywrightDriver

class HostWebDriverFactory {

  fun createWeb(
    headless: Boolean = isRunningOnCi(),
  ): TrailblazeConnectedDevice {
    val playwrightDriver = MaestroPlaywrightDriver(headless)
    return TrailblazeConnectedDevice(
      maestroDriver = playwrightDriver,
      trailblazeDriverType = TrailblazeDriverType.WEB_PLAYWRIGHT_HOST,
      instanceId = "web-playwright",
    )
  }

  companion object {
    fun isRunningOnCi(): Boolean = System.getenv("KOCHIKU_ENV") != null
  }
}
