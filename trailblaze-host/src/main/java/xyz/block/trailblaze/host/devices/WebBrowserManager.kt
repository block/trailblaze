package xyz.block.trailblaze.host.devices

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import xyz.block.trailblaze.devices.TrailblazeConnectedDeviceSummary
import xyz.block.trailblaze.devices.TrailblazeDriverType

/**
 * Represents the state of the managed web browser.
 */
sealed class WebBrowserState {
  /** No browser is running */
  data object Idle : WebBrowserState()

  /** Browser is in the process of launching */
  data object Launching : WebBrowserState()

  /** Browser is running and ready for tests */
  data class Running(
    val connectedDevice: TrailblazeConnectedDevice,
  ) : WebBrowserState()

  /** Browser encountered an error */
  data class Error(val message: String) : WebBrowserState()
}

/**
 * Manages the lifecycle of the web browser instance for Trailblaze testing.
 *
 * Unlike Android/iOS devices which are discovered via USB/network, web browsers
 * are launched on-demand by the user. This manager provides a UI-friendly wrapper
 * around [HostWebDriverFactory] to:
 * - Launch the browser with a visible window (non-headless)
 * - Track browser state reactively via StateFlow
 * - Detect browser close/crash
 * - Provide the browser as a "device" for test execution
 *
 * Note: Currently supports a single browser instance (managed by HostWebDriverFactory).
 * Multiple browser support could be added in the future if needed.
 *
 * Usage:
 * ```
 * val manager = WebBrowserManager()
 * manager.launchBrowser() // Opens Chrome window
 * // Browser appears in device list, user can run tests
 * manager.closeBrowser() // Closes the browser
 * ```
 */
class WebBrowserManager {

  private val scope = CoroutineScope(Dispatchers.IO)

  init {
    // Register shutdown hook to close browser when the app exits
    // This ensures we don't leave orphaned browser processes
    Runtime.getRuntime().addShutdownHook(Thread {
      if (isRunning()) {
        println("WebBrowserManager: Closing browser on app shutdown")
        try {
          HostWebDriverFactory.clearCache()
        } catch (e: Exception) {
          println("WebBrowserManager: Error closing browser on shutdown: ${e.message}")
        }
      }
    })
  }

  private val _browserState = MutableStateFlow<WebBrowserState>(WebBrowserState.Idle)

  /** Current state of the browser */
  val browserStateFlow: StateFlow<WebBrowserState> = _browserState.asStateFlow()

  /** The currently running browser device, if any */
  private var currentDevice: TrailblazeConnectedDevice? = null

  /**
   * Launches a new web browser instance.
   *
   * The browser will be visible (non-headless) for debugging purposes.
   * TODO: Add headless parameter if needed for CI/automated scenarios.
   *
   * If a browser is already running, this will reuse the existing instance.
   *
   * @return The connected device representing the browser, or null if launch failed
   */
  fun launchBrowser(): TrailblazeConnectedDevice? {
    // If already running, return existing device
    val existingState = _browserState.value
    if (existingState is WebBrowserState.Running) {
      println("WebBrowserManager: Browser already running, reusing existing instance")
      return existingState.connectedDevice
    }

    // Update state to launching
    _browserState.value = WebBrowserState.Launching

    return try {
      // Launch browser - always visible for now
      // TODO: Add headless parameter if needed for CI scenarios
      val connectedDevice = HostWebDriverFactory().createWeb(headless = false)

      currentDevice = connectedDevice

      // Update state to running
      _browserState.value = WebBrowserState.Running(connectedDevice = connectedDevice)

      // Start monitoring for browser close/crash
      startBrowserMonitor()

      println("WebBrowserManager: Launched browser instance")
      connectedDevice
    } catch (e: Exception) {
      println("WebBrowserManager: Failed to launch browser: ${e.message}")
      e.printStackTrace()
      _browserState.value = WebBrowserState.Error(e.message ?: "Unknown error launching browser")
      null
    }
  }

  /**
   * Closes the browser instance.
   */
  fun closeBrowser() {
    val device = currentDevice
    if (device != null) {
      try {
        // Use the factory's clearCache to properly close the browser
        HostWebDriverFactory.clearCache()
        println("WebBrowserManager: Closed browser instance")
      } catch (e: Exception) {
        println("WebBrowserManager: Error closing browser: ${e.message}")
      }
    }
    currentDevice = null
    _browserState.value = WebBrowserState.Idle
  }

  /**
   * Gets the device summary for the running browser, if any.
   * This can be included in the device list alongside Android/iOS devices.
   */
  fun getRunningBrowserSummary(): TrailblazeConnectedDeviceSummary? {
    val state = _browserState.value
    if (state !is WebBrowserState.Running) return null

    return TrailblazeConnectedDeviceSummary(
      trailblazeDriverType = TrailblazeDriverType.WEB_PLAYWRIGHT_HOST,
      instanceId = HostWebDriverFactory.DEFAULT_PLAYWRIGHT_WEB_TRAILBLAZE_DEVICE_ID.instanceId,
      description = "Chrome Browser",
    )
  }

  /**
   * Gets the connected device for the running browser, if any.
   */
  fun getConnectedDevice(): TrailblazeConnectedDevice? {
    return currentDevice
  }

  /**
   * Checks if a browser is currently running.
   */
  fun isRunning(): Boolean = _browserState.value is WebBrowserState.Running

  /**
   * Monitors the browser for disconnection/crash.
   */
  private fun startBrowserMonitor() {
    scope.launch {
      while (isActive && _browserState.value is WebBrowserState.Running) {
        try {
          // Check if the browser is still connected by checking the cached driver
          val isConnected = checkBrowserConnected()
          if (!isConnected) {
            println("WebBrowserManager: Browser disconnected (user closed or crashed)")
            handleBrowserDisconnected()
            break
          }
        } catch (e: Exception) {
          println("WebBrowserManager: Error monitoring browser: ${e.message}")
          handleBrowserDisconnected()
          break
        }
        delay(1000) // Check every second
      }
    }
  }

  /**
   * Checks if the browser is still connected.
   */
  private fun checkBrowserConnected(): Boolean {
    return HostWebDriverFactory.isBrowserConnected()
  }

  private fun handleBrowserDisconnected() {
    currentDevice = null
    _browserState.value = WebBrowserState.Idle
    // Clear the factory cache since the browser is gone
    try {
      HostWebDriverFactory.clearCache()
    } catch (e: Exception) {
      // Ignore - browser is already gone
    }
  }
}
