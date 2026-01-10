package xyz.block.trailblaze.host.devices

import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.host.playwright.MaestroPlaywrightDriver

class HostWebDriverFactory {

  fun createWeb(
    headless: Boolean = isRunningOnCi(),
  ): TrailblazeConnectedDevice {
    return getOrCreateDriver(headless)
  }

  companion object {
    private val isKochiku = System.getenv("KOCHIKU_ENV") != null
    private val isBuildkite = System.getenv("BUILDKITE") == "true"

    fun isRunningOnCi(): Boolean = isKochiku || isBuildkite

    // Cached driver instance - reused across tests to avoid multiple browser windows
    private var cachedDriver: MaestroPlaywrightDriver? = null
    private var cachedHeadless: Boolean? = null

    /**
     * Gets an existing Playwright driver or creates a new one.
     * Reuses the cached driver if:
     * - A cached driver exists
     * - The headless mode matches
     * - The driver is not shutdown
     *
     * If a cached driver exists but is shutdown or has different settings,
     * it will be closed and a new one created.
     *
     * @param resetSession If true and reusing an existing driver, resets the browser state
     *                     (clears cookies, navigates to about:blank, closes extra tabs)
     */
    @Synchronized
    fun getOrCreateDriver(headless: Boolean, resetSession: Boolean = true): TrailblazeConnectedDevice {
      val existingDriver = cachedDriver

      // Reuse existing driver if it's still valid and settings match
      if (existingDriver != null && cachedHeadless == headless && !existingDriver.isShutdown()) {
        println("[Playwright] Reusing existing browser instance")
        if (resetSession) {
          existingDriver.resetSession()
        }
        return TrailblazeConnectedDevice(
          maestroDriver = existingDriver,
          trailblazeDriverType = TrailblazeDriverType.WEB_PLAYWRIGHT_HOST,
          instanceId = DEFAULT_PLAYWRIGHT_WEB_TRAILBLAZE_DEVICE_ID.instanceId,
        )
      }

      // Close existing driver if it exists (might be shutdown or have different settings)
      if (existingDriver != null) {
        println("[Playwright] Closing existing browser instance before creating new one")
        try {
          existingDriver.close()
        } catch (e: Exception) {
          println("[Playwright] Warning: Error closing existing driver: ${e.message}")
        }
      }

      // Create new driver
      println("[Playwright] Creating new browser instance (headless=$headless)")
      val newDriver = MaestroPlaywrightDriver(headless)
      cachedDriver = newDriver
      cachedHeadless = headless

      return TrailblazeConnectedDevice(
        maestroDriver = newDriver,
        trailblazeDriverType = TrailblazeDriverType.WEB_PLAYWRIGHT_HOST,
        instanceId = DEFAULT_PLAYWRIGHT_WEB_TRAILBLAZE_DEVICE_ID.instanceId,
      )
    }

    /**
     * Clears the cached driver, closing it if necessary.
     * Call this to force a fresh browser instance on next test.
     */
    @Synchronized
    fun clearCache() {
      cachedDriver?.let { driver ->
        println("[Playwright] Clearing cached browser instance")
        try {
          driver.close()
        } catch (e: Exception) {
          println("[Playwright] Warning: Error closing cached driver: ${e.message}")
        }
      }
      cachedDriver = null
      cachedHeadless = null
    }

    /**
     * Currently we only support a single web browser instance, the default from Playwright
     */
    val DEFAULT_PLAYWRIGHT_WEB_TRAILBLAZE_DEVICE_ID = TrailblazeDeviceId(
      instanceId = "web-playwright",
      trailblazeDevicePlatform = TrailblazeDevicePlatform.WEB
    )
  }
}
