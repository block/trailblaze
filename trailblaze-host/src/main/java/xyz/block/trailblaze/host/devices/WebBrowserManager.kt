package xyz.block.trailblaze.host.devices

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import xyz.block.trailblaze.devices.TrailblazeConnectedDeviceSummary
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.playwright.PlaywrightBrowserManager
import xyz.block.trailblaze.util.Console

/**
 * Represents the state of the managed web browser.
 */
sealed class WebBrowserState {
  /** No browser is running */
  data object Idle : WebBrowserState()

  /** Browser is in the process of launching */
  data object Launching : WebBrowserState()

  /** Browser is running and ready for tests */
  data object Running : WebBrowserState()

  /** Browser encountered an error */
  data class Error(val message: String) : WebBrowserState()
}

/**
 * Manages the lifecycle of the web browser instance for Trailblaze testing.
 *
 * Unlike Android/iOS devices which are discovered via USB/network, web browsers
 * are launched on-demand by the user. This manager provides a UI-friendly wrapper
 * around [PlaywrightBrowserManager] to:
 * - Launch the browser with a visible window (non-headless)
 * - Track browser state reactively via StateFlow
 * - Detect browser close/crash
 * - Provide the browser as a "device" for test execution
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
  val playwrightInstaller = PlaywrightBrowserInstaller()

  /** The PlaywrightBrowserManager instance when a browser is running. */
  private var browserManager: PlaywrightBrowserManager? = null

  init {
    // Register shutdown hook to close browser when the app exits
    // This ensures we don't leave orphaned browser processes
    Runtime.getRuntime().addShutdownHook(Thread {
      if (isRunning()) {
        Console.log("WebBrowserManager: Closing browser on app shutdown")
        try {
          browserManager?.close()
          browserManager = null
        } catch (e: Exception) {
          Console.log("WebBrowserManager: Error closing browser on shutdown: ${e.message}")
        }
      }
    })

    // Check Playwright browser installation status on startup
    playwrightInstaller.checkInstallStatus()
  }

  private val launchMutex = Mutex()

  private val _browserState = MutableStateFlow<WebBrowserState>(WebBrowserState.Idle)

  /** Current state of the browser */
  val browserStateFlow: StateFlow<WebBrowserState> = _browserState.asStateFlow()

  /**
   * Launches a new web browser instance asynchronously on [Dispatchers.IO].
   *
   * The browser will be visible (non-headless) for debugging purposes.
   *
   * If a browser is already running, this will reuse the existing instance.
   *
   * @param onComplete optional callback invoked (on [Dispatchers.IO]) after the browser is ready.
   */
  fun launchBrowser(onComplete: (() -> Unit)? = null) {
    scope.launch {
      launchMutex.withLock {
        // If already running, reuse existing instance
        if (_browserState.value is WebBrowserState.Running) {
          Console.log("WebBrowserManager: Browser already running, reusing existing instance")
          onComplete?.invoke()
          return@withLock
        }

        // Update state to launching
        _browserState.value = WebBrowserState.Launching

        try {
          // Launch browser - always visible for desktop app usage
          val newBrowserManager = PlaywrightBrowserManager(headless = false)
          browserManager = newBrowserManager

          // Update state to running
          _browserState.value = WebBrowserState.Running

          // Start monitoring for browser close/crash
          startBrowserMonitor()

          Console.log("WebBrowserManager: Launched browser instance")
          onComplete?.invoke()
        } catch (e: Exception) {
          Console.log("WebBrowserManager: Failed to launch browser: ${e.message}")
          _browserState.value = WebBrowserState.Error(e.message ?: "Unknown error launching browser")
        }
      }
    }
  }

  /**
   * Closes the browser instance asynchronously on [Dispatchers.IO].
   *
   * @param onComplete optional callback invoked (on [Dispatchers.IO]) after the browser is closed.
   */
  fun closeBrowser(onComplete: (() -> Unit)? = null) {
    scope.launch {
      try {
        browserManager?.close()
        Console.log("WebBrowserManager: Closed browser instance")
      } catch (e: Exception) {
        Console.log("WebBrowserManager: Error closing browser: ${e.message}")
      }
      browserManager = null
      _browserState.value = WebBrowserState.Idle
      onComplete?.invoke()
    }
  }

  /**
   * Gets the device summary for the running browser, if any.
   * This can be included in the device list alongside Android/iOS devices.
   */
  fun getRunningBrowserSummary(): TrailblazeConnectedDeviceSummary? {
    val state = _browserState.value
    if (state !is WebBrowserState.Running) return null

    return TrailblazeConnectedDeviceSummary(
      trailblazeDriverType = TrailblazeDriverType.PLAYWRIGHT_NATIVE,
      instanceId = PLAYWRIGHT_NATIVE_INSTANCE_ID,
      description = "Chrome Browser",
    )
  }

  /**
   * Checks if a browser is currently running.
   */
  fun isRunning(): Boolean = _browserState.value is WebBrowserState.Running

  /**
   * Returns the current [PlaywrightBrowserManager] if a browser is running, or null.
   * Used by interactive recording to create a [DeviceScreenStream].
   */
  fun getPageManager(): PlaywrightBrowserManager? = browserManager

  /**
   * Monitors the browser for disconnection/crash.
   */
  private fun startBrowserMonitor() {
    scope.launch {
      while (isActive && _browserState.value is WebBrowserState.Running) {
        try {
          val isConnected = checkBrowserConnected()
          if (!isConnected) {
            Console.log("WebBrowserManager: Browser disconnected (user closed or crashed)")
            handleBrowserDisconnected()
            break
          }
        } catch (e: Exception) {
          Console.log("WebBrowserManager: Error monitoring browser: ${e.message}")
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
    val manager = browserManager ?: return false
    return try {
      // If the page is accessible, the browser is still connected
      manager.currentPage.url()
      true
    } catch (_: Exception) {
      false
    }
  }

  private fun handleBrowserDisconnected() {
    _browserState.value = WebBrowserState.Idle
    try {
      browserManager?.close()
    } catch (_: Exception) {
      // Ignore - browser is already gone
    }
    browserManager = null
  }

  fun close() {
    playwrightInstaller.close()
    scope.cancel()
    try {
      browserManager?.close()
    } catch (_: Exception) {
      // Ignore - best-effort cleanup
    }
    browserManager = null
    _browserState.value = WebBrowserState.Idle
  }

  companion object {
    const val PLAYWRIGHT_NATIVE_INSTANCE_ID = "playwright-native"
  }
}
