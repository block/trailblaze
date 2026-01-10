package xyz.block.trailblaze.host.playwright

import com.microsoft.playwright.Browser
import com.microsoft.playwright.BrowserType
import com.microsoft.playwright.Playwright
import kotlinx.serialization.Serializable
import maestro.Capability
import maestro.DeviceInfo
import maestro.DeviceOrientation
import maestro.Driver
import maestro.KeyCode
import maestro.Platform
import maestro.Point
import maestro.ScreenRecording
import maestro.SwipeDirection
import maestro.TreeNode
import maestro.ViewHierarchy
import maestro.utils.ScreenshotUtils
import okio.Sink
import okio.buffer
import okio.gzip
import xyz.block.trailblaze.host.devices.HostWebDriverFactory.Companion.isRunningOnCi
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * A maestro driver for Playwright, the web automation framework
 * https://github.com/microsoft/playwright
 *
 * This driver is responsible for delegating commands to the Playwright SDK to perform actions on the browser.
 */
class MaestroPlaywrightDriver(headless: Boolean) : Driver {
  private val isCI = isRunningOnCi()

  val customEnv = HashMap<String, String>().apply {
    // Playwright/Chromium are happier with a real runtime dir; make one if none.
    if (System.getenv("XDG_RUNTIME_DIR").isNullOrBlank()) {
      put("XDG_RUNTIME_DIR", java.nio.file.Files.createTempDirectory("xdg").toString())
    }
    // Avoid bogus DBus noise in minimal containers.
    put("DBUS_SESSION_BUS_ADDRESS", "")
    put("DBUS_SYSTEM_BUS_ADDRESS", "")
    // Pass through Playwright browser path if set
    System.getenv("PLAYWRIGHT_BROWSERS_PATH")?.let { put("PLAYWRIGHT_BROWSERS_PATH", it) }
  }

  val launchOptions = BrowserType.LaunchOptions()
    .setHeadless(headless || isCI)
    .apply {
      setArgs(
        buildList {
          // Core stability flags
          add("--no-sandbox")
          add("--disable-dev-shm-usage")
          add("--no-first-run")
          add("--disable-extensions")
          add("--disable-web-security")

          // Prevent background throttling (important for automation)
          add("--disable-background-timer-throttling")
          add("--disable-backgrounding-occluded-windows")
          add("--disable-renderer-backgrounding")

          // Reduce visual flashing and flickering
          add("--disable-infobars") // Remove "controlled by automation" bar
          add("--disable-popup-blocking")
          add("--disable-features=TranslateUI") // No translate popups
          add("--force-color-profile=srgb") // Consistent color rendering
          add("--disable-ipc-flooding-protection") // Smoother IPC for automation

          // GPU handling - enable on macOS for smoother rendering, disable on Linux CI
          if (isRunningOnCi()) {
            add("--disable-gpu")
          } else {
            // On local dev machines (especially macOS), GPU often renders smoother
            add("--enable-gpu-rasterization")
            add("--enable-zero-copy") // Reduce memory copying/flashing
          }

          // Reduce compositor flicker
          add("--disable-smooth-scrolling")
          add("--animation-duration-scale=0") // Disable animations that cause flicker
        },
      )
        .setEnv(customEnv)

      // Use custom executable path if available (for CI environments)
      System.getProperty("playwright.cli.dir")?.let { cliDir ->
        val executablePath = when {
          isCI -> "$cliDir/.local-browsers/chromium-*/chrome-linux/chrome"
          else -> null
        }
        executablePath?.let { path ->
          val chromiumDirs = File(cliDir).listFiles { file ->
            file.isDirectory && file.name.startsWith(".local-browsers")
          }
          chromiumDirs?.firstOrNull()?.let { browsersDir ->
            val chromiumVersionDirs = browsersDir.listFiles { file ->
              file.isDirectory && file.name.startsWith("chromium-")
            }
            chromiumVersionDirs?.firstOrNull()?.let { chromiumDir ->
              val chromeExecutable = File(chromiumDir, "chrome-linux/chrome")
              if (chromeExecutable.exists()) {
                setExecutablePath(chromeExecutable.toPath())
              }
            }
          }
        }
      }
    }

  val browser = if (System.getenv("PW_TEST_CONNECT_WS_ENDPOINT") != null) {
    val endpoint = System.getenv("PW_TEST_CONNECT_WS_ENDPOINT")
    if (endpoint != null) {
      println("Connecting to WebSocket endpoint: $endpoint")
      var retryCount = 0
      var connected = false
      var browserInstance: Browser? = null
      while (!connected) {
        try {
          browserInstance = Playwright.create().chromium().connect(endpoint)
          connected = true
        } catch (e: Exception) {
          retryCount++
          if (retryCount < 3) {
            println("Error connecting to WebSocket endpoint: $e. Retrying in 1 second...")
            TimeUnit.SECONDS.sleep(1)
          } else {
            println("Error connecting to WebSocket endpoint: $e")
            throw e
          }
        }
      }
      if (browserInstance == null) {
        throw RuntimeException("Failed to connect to WebSocket endpoint after 3 retries")
      }
      browserInstance
    } else {
      throw RuntimeException("PW_TEST_CONNECT_WS_ENDPOINT is null")
    }
  } else {
    Playwright.create().chromium().launch(launchOptions)
  }

  // Default viewport size for web testing - landscape orientation suitable for most web apps
  companion object {
    const val DEFAULT_VIEWPORT_WIDTH = 1280
    const val DEFAULT_VIEWPORT_HEIGHT = 800
  }

  // Create a context with explicit viewport and matching deviceScaleFactor to prevent flicker.
  // The flicker issue (https://github.com/microsoft/playwright/issues/2576) is caused by
  // deviceScaleFactor mismatch: Playwright defaults to 1, but Mac Retina is 2.
  // By matching the native scale factor, we avoid the zoom/rescale during screenshots.
  private val browserContext = browser.newContext(
    Browser.NewContextOptions()
      .setViewportSize(DEFAULT_VIEWPORT_WIDTH, DEFAULT_VIEWPORT_HEIGHT)
      .setDeviceScaleFactor(if (isCI) 1.0 else 2.0), // Match native: 2 for Mac Retina, 1 for CI/Linux
  )

  var currentPage = browserContext.newPage()

  // Timeout configuration for CI vs local environments
  private val navigationTimeout: Double = if (isCI) 60000.0 else 30000.0 // 60s for CI, 30s for local
  private val defaultTimeout: Double = if (isCI) 45000.0 else 30000.0 // 45s for CI, 30s for local

  /**
   * JavaScript that injects CSS to disable all animations and transitions.
   * Using addInitScript ensures this runs on every page load/navigation.
   */
  private val disableAnimationsScript = """
    (function() {
      const style = document.createElement('style');
      style.textContent = `
        *, *::before, *::after {
          animation-duration: 0s !important;
          animation-delay: 0s !important;
          transition-duration: 0s !important;
          transition-delay: 0s !important;
          scroll-behavior: auto !important;
        }
      `;
      // Inject as early as possible
      if (document.head) {
        document.head.appendChild(style);
      } else {
        document.addEventListener('DOMContentLoaded', () => document.head.appendChild(style));
      }
    })();
  """.trimIndent()

  /**
   * Sets up a page for automation: popup handling, timeouts, and visual stability.
   * When a new tab opens (via target="_blank" or window.open), we automatically
   * switch currentPage to the new tab so the agent follows the navigation flow.
   */
  private fun setupPageForAutomation(page: com.microsoft.playwright.Page) {
    // Apply timeouts
    page.setDefaultNavigationTimeout(navigationTimeout)
    page.setDefaultTimeout(defaultTimeout)

    // Inject script to disable animations/transitions on every navigation (reduces flicker)
    page.addInitScript(disableAnimationsScript)

    // Handle new tabs: automatically switch to them
    page.onPopup { popup ->
      println("[Playwright] New tab detected, switching currentPage to: ${popup.url()}")
      // Set up the new page for automation too
      setupPageForAutomation(popup)
      // Switch to the new tab
      currentPage = popup
      // Wait for the new page to be ready
      try {
        popup.waitForLoadState(com.microsoft.playwright.options.LoadState.LOAD)
        println("[Playwright] New tab loaded: ${popup.url()}")
      } catch (e: Exception) {
        println("[Playwright] Warning: New tab load wait failed: ${e.message}")
      }
    }
  }

  init {
    // Set up the initial page for automation (timeouts, animations disabled, popup handling)
    setupPageForAutomation(currentPage)

    println("Playwright driver initialized with timeouts - Navigation: ${navigationTimeout}ms, Default: ${defaultTimeout}ms")
    println("CI environment detected: $isCI")
    println("Browser connected: ${browser.isConnected}")
  }

  // Debugging helpers for current page state and network
  private fun debugPageState(label: String = "") {
    try {
      println("=== Debug Page State ${if (label.isNotBlank()) "[$label]" else ""} ===")
      val url = currentPage.url()
      val title = try {
        currentPage.title()
      } catch (_: Exception) {
        "<error>"
      }
      val loadingState = try {
        currentPage.evaluate("window.performance.timing")?.toString()
      } catch (_: Exception) {
        "<error>"
      }
      println("  URL: $url")
      println("  Title: $title")
      println("  Performance timing: $loadingState")
      // Network info: active requests (using performance API)
      try {
        val activeRequests =
          currentPage.evaluate("window.performance.getEntriesByType('resource').filter(e => e.initiatorType === 'xmlhttprequest' || e.initiatorType === 'fetch').length")
        println("  Active XHR/fetch network requests: $activeRequests")
      } catch (_: Exception) {
      }
      println("=== End Debug Page State ===")
    } catch (ex: Exception) {
      println("Error during debugPageState: ${ex.message}")
    }
  }

  /**
   * Returns count of active resource requests (XMLHttpRequest or fetch) on the page.
   * If unable to determine, returns -1.
   */
  private fun activeNetworkRequestsCount(): Int = try {
    val count =
      currentPage.evaluate("window.performance.getEntriesByType('resource').filter(e => e.initiatorType === 'xmlhttprequest' || e.initiatorType === 'fetch').length")
    (count as? Number)?.toInt() ?: -1
  } catch (_: Exception) {
    -1
  }

  override fun addMedia(mediaFiles: List<File>) {
    TODO("Not yet implemented")
  }

  override fun backPress() {
    currentPage.goBack()
  }

  override fun capabilities(): List<Capability> = listOf(Capability.FAST_HIERARCHY)

  override fun clearAppState(appId: String) {
    error("Unsupported Maestro Playwright Driver Call to ${this::class.simpleName}::appId")
  }

  override fun clearKeychain() {
    error("Unsupported Maestro Playwright Driver Call to ${this::class.simpleName}::clearKeychain")
  }

  override fun close() {
    println("[Playwright] Closing browser...")
    try {
      // Close all pages in the context
      browserContext.pages().forEach { page ->
        try {
          page.close()
        } catch (e: Exception) {
          println("[Playwright] Warning: Failed to close page: ${e.message}")
        }
      }
      // Close the context
      browserContext.close()
      // Close the browser
      browser.close()
      println("[Playwright] Browser closed successfully")
    } catch (e: Exception) {
      println("[Playwright] Error closing browser: ${e.message}")
    }
  }

  /**
   * Resets the browser session without closing the browser.
   * This is faster than close() + create new driver, useful between tests.
   * - Navigates to about:blank
   * - Clears cookies and storage
   * - Closes any extra tabs
   */
  fun resetSession() {
    println("[Playwright] Resetting browser session...")
    try {
      // Close all extra pages, keep only one
      val pages = browserContext.pages()
      if (pages.size > 1) {
        pages.drop(1).forEach { page ->
          try {
            page.close()
          } catch (e: Exception) {
            println("[Playwright] Warning: Failed to close extra page: ${e.message}")
          }
        }
      }

      // Navigate the remaining page to blank
      currentPage = browserContext.pages().firstOrNull() ?: browserContext.newPage()
      setupPageForAutomation(currentPage)
      currentPage.navigate("about:blank")

      // Clear cookies and storage
      browserContext.clearCookies()

      println("[Playwright] Browser session reset successfully")
    } catch (e: Exception) {
      println("[Playwright] Error resetting session: ${e.message}")
    }
  }

  @Serializable
  data class DomNode(
    val tag: String,
    val id: String?,
    val classList: List<String>,
    val children: List<DomNode>,
  )

  override fun contentDescriptor(excludeKeyboardElements: Boolean): TreeNode {
    val treeNode = MaestroPlaywrightScreenStateUtils.getMaestroViewHierarchy(currentPage)
    return treeNode
  }

  override fun deviceInfo(): DeviceInfo {
    // Use viewport size, with fallback to window dimensions for robustness
    val viewportSize = currentPage.viewportSize()
    val width = viewportSize?.width ?: (currentPage.evaluate("window.innerWidth") as Number).toInt()
    val height = viewportSize?.height ?: (currentPage.evaluate("window.innerHeight") as Number).toInt()

    return DeviceInfo(
      platform = Platform.WEB,
      widthPixels = width,
      heightPixels = height,
      widthGrid = width,
      heightGrid = height,
    )
  }

  override fun eraseText(charactersToErase: Int) {
    error("Unsupported Maestro Playwright Driver Call to ${this::class.simpleName}::eraseText")
  }

  override fun hideKeyboard() {
    error("Unsupported Maestro Playwright Driver Call to ${this::class.simpleName}::hideKeyboard")
  }

  override fun inputText(text: String) {
    currentPage.keyboard().type(text)
  }

  override fun isAirplaneModeEnabled(): Boolean {
    error("Unsupported Maestro Playwright Driver Call to ${this::class.simpleName}::isAirplaneModeEnabled")
  }

  override fun isKeyboardVisible(): Boolean = false

  override fun isShutdown(): Boolean = !browser.isConnected

  override fun isUnicodeInputSupported(): Boolean = true

  override fun killApp(appId: String) {
    error("Unsupported Maestro Playwright Driver Call to ${this::class.simpleName}::killApp")
  }

  override fun launchApp(
    appId: String,
    launchArguments: Map<String, Any>,
  ) {
    error("Unsupported Maestro Playwright Driver Call to ${this::class.simpleName}::launchApp")
  }

  override fun longPress(point: Point) {
    TODO("Not yet implemented")
  }

  override fun name(): String {
    error("Unsupported Maestro Playwright Driver Call to ${this::class.simpleName}::name")
  }

  override fun open() {
    error("Unsupported Maestro Playwright Driver Call to ${this::class.simpleName}::open")
  }

  override fun openLink(
    link: String,
    appId: String?,
    autoVerify: Boolean,
    browser: Boolean,
  ) {
    var retryCount = 0
    val maxRetries = 3
    val waitTimeoutMs = if (isCI) 40000.0 else 10000.0
    val waitForSelectors = listOf(
      "body", // ensure body is present
    )
    var lastException: Exception? = null

    // Preprocess link if needed (for debugging or normalization)
    val processedLink = link.trim()
    println("[Playwright] openLink (raw): $link")
    println("[Playwright] openLink (processed): $processedLink")

    while (retryCount < maxRetries) {
      try {
        val startTimeMs = System.currentTimeMillis()
        println("[Playwright] Starting navigation to: $processedLink")
        debugPageState("Before navigation")
        currentPage.navigate(processedLink)
        println("[Playwright] Navigation to '$processedLink' successful")
        debugPageState("After navigation")

        // Wait for multiple selectors for robustness
        for (selector in waitForSelectors) {
          try {
            println("[Playwright] Waiting for selector: '$selector' (timeout ${waitTimeoutMs}ms)")
            currentPage.waitForSelector(
              selector,
              com.microsoft.playwright.Page.WaitForSelectorOptions()
                .setTimeout(waitTimeoutMs)
                .setState(com.microsoft.playwright.options.WaitForSelectorState.ATTACHED),
            )
            println("[Playwright] Selector '$selector' appeared")
          } catch (waitEx: Exception) {
            println("[Playwright] Selector '$selector' not found within timeout: ${waitEx.message}")
          }
        }

        // Progressive waiting strategy for navigation settle
        val settleStrategies = mutableListOf<() -> Unit>(
          {
            println("[Playwright] Waiting for NETWORKIDLE (timeout ${waitTimeoutMs}ms)")
            currentPage.waitForLoadState(
              com.microsoft.playwright.options.LoadState.NETWORKIDLE,
              com.microsoft.playwright.Page.WaitForLoadStateOptions().setTimeout(waitTimeoutMs),
            )
            println("[Playwright] NETWORKIDLE reached")
          },
          {
            println("[Playwright] Waiting for LOAD (timeout ${waitTimeoutMs}ms)")
            currentPage.waitForLoadState(
              com.microsoft.playwright.options.LoadState.LOAD,
              com.microsoft.playwright.Page.WaitForLoadStateOptions().setTimeout(waitTimeoutMs),
            )
            println("[Playwright] LOAD reached")
          },
          {
            println("[Playwright] Waiting for DOMCONTENTLOADED (timeout ${waitTimeoutMs}ms)")
            currentPage.waitForLoadState(
              com.microsoft.playwright.options.LoadState.DOMCONTENTLOADED,
              com.microsoft.playwright.Page.WaitForLoadStateOptions().setTimeout(waitTimeoutMs),
            )
            println("[Playwright] DOMCONTENTLOADED reached")
          },
          {
            // Fallback: active network requests <= 0, but only if isCI
            if (isCI) {
              println("[Playwright] Fallback: Polling for network requests to be idle (isCI) up to ${waitTimeoutMs.toLong()}ms")
              val pollStart = System.currentTimeMillis()
              var pollSucceeded = false
              while (System.currentTimeMillis() - pollStart < waitTimeoutMs.toLong()) {
                val activeRequests = activeNetworkRequestsCount()
                println("[Playwright] Fallback network poll: activeRequests=$activeRequests (${System.currentTimeMillis() - pollStart}ms elapsed)")
                if (activeRequests == 0) {
                  pollSucceeded = true
                  break
                }
                Thread.sleep(500)
              }
              if (pollSucceeded) {
                println("[Playwright] Fallback network idle achieved.")
              } else {
                println("[Playwright] Fallback network idle not achieved in time.")
              }
            }
          },
        )

        var successfullySettled = false
        var lastSettleException: Exception? = null
        for (strategy in settleStrategies) {
          try {
            strategy()
            successfullySettled = true
            break
          } catch (ex: Exception) {
            lastSettleException = ex
            println("[Playwright] Navigation settle strategy failed: ${ex.message}")
          }
        }
        if (!successfullySettled) {
          println("[Playwright] All navigation settle strategies failed.")
          lastSettleException?.let {
            println("[Playwright] Last settle exception: ${it.message}")
          }
        }

        // Debug diagnostics after attempted load
        debugPageState("After all waits")
        println("[Playwright] Active network requests after load: ${activeNetworkRequestsCount()}")

        // Debug: print timing
        val elapsedMs = System.currentTimeMillis() - startTimeMs
        println("[Playwright] openLink total elapsed: ${elapsedMs}ms")
        return
      } catch (ex: Exception) {
        lastException = ex
        retryCount += 1
        println("[Playwright] Error navigating to link ($processedLink) attempt $retryCount/$maxRetries: ${ex.message}")
        debugPageState("Navigation Failure $retryCount/$maxRetries")
        println("[Playwright] Active network requests after failure: ${activeNetworkRequestsCount()}")
        if (retryCount < maxRetries) {
          val delay = 1200L * retryCount
          println("[Playwright] Retrying in $delay ms")
          try {
            Thread.sleep(delay)
          } catch (_: InterruptedException) {
          }
        } else {
          println("[Playwright] All retries failed")
        }
      }
    }
    // If we reached here, navigation failed after all retries
    debugPageState("Final Failure")
    if (lastException != null) {
      throw RuntimeException(
        "[Playwright] Failed to open link '$processedLink' after $maxRetries attempts",
        lastException,
      )
    } else {
      throw RuntimeException("[Playwright] Failed to open link '$processedLink' for unknown reason")
    }
  }

  override fun pressKey(code: KeyCode) {
    error("Unsupported Maestro Playwright Driver Call to ${this::class.simpleName}::pressKey")
  }

  override fun resetProxy() {
    error("Unsupported Maestro Playwright Driver Call to ${this::class.simpleName}::resetProxy")
  }

  override fun scrollVertical() {
    currentPage.mouse().wheel(0.0, 100.0) // Scroll down
  }

  override fun setAirplaneMode(enabled: Boolean) {
    error("Unsupported Maestro Playwright Driver Call to ${this::class.simpleName}::setAirplaneMode")
  }

  override fun setLocation(latitude: Double, longitude: Double) {
    error("Unsupported Maestro Playwright Driver Call to ${this::class.simpleName}::setLocation")
  }

  override fun setOrientation(orientation: DeviceOrientation) {
    error("Unsupported Maestro Playwright Driver Call to ${this::class.simpleName}::setOrientation $orientation")
  }

  override fun setPermissions(appId: String, permissions: Map<String, String>) {
    error("Unsupported Maestro Playwright Driver Call to ${this::class.simpleName}::setPermissions")
  }

  override fun setProxy(host: String, port: Int) {
    error("Unsupported Maestro Playwright Driver Call to ${this::class.simpleName}::setProxy")
  }

  override fun startScreenRecording(out: Sink): ScreenRecording {
    error("Unsupported Maestro Playwright Driver Call to ${this::class.simpleName}::startScreenRecording")
  }

  override fun stopApp(appId: String) {
    error("Unsupported Maestro Playwright Driver Call to ${this::class.simpleName}::stopApp")
  }

  override fun swipe(start: Point, end: Point, durationMs: Long) {
    // Calculate scroll delta from start to end
    val deltaX = (start.x - end.x).toDouble()
    val deltaY = (start.y - end.y).toDouble()

    // Move mouse to start position and use wheel to scroll
    currentPage.mouse().move(start.x.toDouble(), start.y.toDouble())
    currentPage.mouse().wheel(deltaX, deltaY)
  }

  override fun swipe(elementPoint: Point, direction: SwipeDirection, durationMs: Long) {
    val scrollAmount = 300.0 // Reasonable scroll distance for web
    // Swipe UP = finger moves up = page scrolls down = positive wheel deltaY
    // Swipe DOWN = finger moves down = page scrolls up = negative wheel deltaY
    val (deltaX, deltaY) = when (direction) {
      SwipeDirection.UP -> 0.0 to scrollAmount
      SwipeDirection.DOWN -> 0.0 to -scrollAmount
      SwipeDirection.LEFT -> scrollAmount to 0.0
      SwipeDirection.RIGHT -> -scrollAmount to 0.0
    }

    // Move mouse to element position and scroll
    currentPage.mouse().move(elementPoint.x.toDouble(), elementPoint.y.toDouble())
    currentPage.mouse().wheel(deltaX, deltaY)
  }

  override fun swipe(swipeDirection: SwipeDirection, durationMs: Long) {
    // Swipe from center of viewport
    val viewportSize = currentPage.viewportSize()
    val centerX = (viewportSize?.width ?: DEFAULT_VIEWPORT_WIDTH) / 2.0
    val centerY = (viewportSize?.height ?: DEFAULT_VIEWPORT_HEIGHT) / 2.0

    val scrollAmount = 400.0 // Larger scroll for full-page swipe
    // Swipe UP = finger moves up = page scrolls down = positive wheel deltaY
    // Swipe DOWN = finger moves down = page scrolls up = negative wheel deltaY
    val (deltaX, deltaY) = when (swipeDirection) {
      SwipeDirection.UP -> 0.0 to scrollAmount
      SwipeDirection.DOWN -> 0.0 to -scrollAmount
      SwipeDirection.LEFT -> scrollAmount to 0.0
      SwipeDirection.RIGHT -> -scrollAmount to 0.0
    }

    // Move mouse to center and scroll
    currentPage.mouse().move(centerX, centerY)
    currentPage.mouse().wheel(deltaX, deltaY)
  }

  // Reusable screenshot options to minimize flicker
  private val screenshotOptions = com.microsoft.playwright.Page.ScreenshotOptions()
    .setAnimations(com.microsoft.playwright.options.ScreenshotAnimations.DISABLED) // Freeze animations
    .setCaret(com.microsoft.playwright.options.ScreenshotCaret.HIDE) // Hide blinking cursor

  override fun takeScreenshot(out: Sink, compressed: Boolean) {
    val screenshot = currentPage.screenshot(screenshotOptions)
    val finalSink = if (compressed) out.gzip() else out
    finalSink.buffer().use { sink ->
      sink.write(screenshot)
      sink.flush()
    }
  }

  override fun tap(point: Point) {
    currentPage.mouse().click(point.x.toDouble(), point.y.toDouble())
  }

  override fun waitForAppToSettle(
    initialHierarchy: ViewHierarchy?,
    appId: String?,
    timeoutMs: Int?,
  ): ViewHierarchy = ScreenshotUtils.waitForAppToSettle(initialHierarchy, this, timeoutMs)

  override fun waitUntilScreenIsStatic(timeoutMs: Long): Boolean {
    /** From AndroidDriver.kt */
    val screenshotDiffThreshold = 0.005
    return ScreenshotUtils.waitUntilScreenIsStatic(
      timeoutMs = timeoutMs,
      threshold = screenshotDiffThreshold,
      driver = this,
    )
  }
}
