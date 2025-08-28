package xyz.block.trailblaze.host.playwright

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
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
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
        listOf(
          "--no-sandbox",
          "--disable-dev-shm-usage",
          "--disable-gpu",
          "--disable-background-timer-throttling",
          "--disable-backgrounding-occluded-windows",
          "--disable-renderer-backgrounding",
          "--disable-web-security",
          "--no-first-run",
          "--disable-extensions",
        ),
      )
        .setEnv(customEnv)

      // Use custom executable path if available (for CI environments)
      System.getProperty("playwright.cli.dir")?.let { cliDir ->
        val executablePath = when {
          isCI -> "$cliDir/.local-browsers/chromium-*/chrome-linux/chrome"
          else -> null
        }
        executablePath?.let { path ->
          val chromiumDirs = java.io.File(cliDir).listFiles { file ->
            file.isDirectory && file.name.startsWith(".local-browsers")
          }
          chromiumDirs?.firstOrNull()?.let { browsersDir ->
            val chromiumVersionDirs = browsersDir.listFiles { file ->
              file.isDirectory && file.name.startsWith("chromium-")
            }
            chromiumVersionDirs?.firstOrNull()?.let { chromiumDir ->
              val chromeExecutable = java.io.File(chromiumDir, "chrome-linux/chrome")
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
      var browserInstance: com.microsoft.playwright.Browser? = null
      while (!connected && retryCount < 3) {
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
      if (!connected || browserInstance == null) {
        throw RuntimeException("Failed to connect to WebSocket endpoint after 3 retries")
      }
      browserInstance
    } else {
      throw RuntimeException("PW_TEST_CONNECT_WS_ENDPOINT is null")
    }
  } else {
    Playwright.create().chromium().launch(launchOptions)
  }

  var currentPage = browser.newPage()

  init {
    // Configure timeouts for CI environment
    val navigationTimeout: Double = if (isCI) 60000.0 else 30000.0 // 60s for CI, 30s for local
    val defaultTimeout: Double = if (isCI) 45000.0 else 30000.0 // 45s for CI, 30s for local

    currentPage.setDefaultNavigationTimeout(navigationTimeout)
    currentPage.setDefaultTimeout(defaultTimeout)

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
      val readyState = try {
        currentPage.evaluate("document.readyState") as? String ?: "<unknown>"
      } catch (_: Exception) {
        "<error>"
      }
      val canonicalHref = try {
        currentPage.querySelector("link[rel='canonical']")?.getAttribute("href")
      } catch (_: Exception) {
        null
      }
      val loadingState = try {
        currentPage.evaluate("window.performance.timing")?.toString()
      } catch (_: Exception) {
        "<error>"
      }
      println("  URL: $url")
      println("  Title: $title")
      println("  Document readyState: $readyState")
      println("  Canonical link: $canonicalHref")
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

  /**
   * Performs basic network connectivity check by making an HTTP GET request to verify DNS and TCP connectivity.
   * Returns true on success or false if network problems (DNS, timeout, or unreachable).
   */
  private fun testBasicNetworkConnectivity(testUrl: String = "https://www.google.com"): Boolean = try {
    println("[Playwright] Network connectivity check: Attempting connection to $testUrl")
    val connection = URL(testUrl).openConnection() as HttpURLConnection
    connection.connectTimeout = 5000
    connection.readTimeout = 5000
    val responseCode = connection.responseCode
    if (responseCode in 200..299) {
      println("[Playwright] Network connectivity check: Success (response code: $responseCode)")
      true
    } else {
      println("[Playwright] Network connectivity check: Failed (response code: $responseCode)")
      false
    }
  } catch (ex: IOException) {
    println("[Playwright] Network connectivity check failed: ${ex.message}")
    false
  }

  private fun testCurlNetworkConnectivity(testUrl: String = "https://www.google.com"): Boolean = try {
    println("[Playwright] Network connectivity check using curl: Attempting connection to $testUrl")
    val process = Runtime.getRuntime().exec("curl -s -f -m 5 $testUrl")
    process.waitFor()
    val responseCode = process.exitValue()
    if (responseCode == 0) {
      println("[Playwright] Network connectivity check using curl: Success")
      true
    } else {
      println("[Playwright] Network connectivity check using curl: Failed (response code: $responseCode)")
      false
    }
  } catch (ex: Exception) {
    println("[Playwright] Network connectivity check using curl failed: ${ex.message}")
    false
  }

  private fun testDnsResolution(host: String = "www.google.com"): Boolean = try {
    println("[Playwright] DNS resolution test: Attempting to resolve $host")
    val addresses = java.net.InetAddress.getAllByName(host)
    println("[Playwright] DNS resolution for $host succeeded: ${addresses.joinToString { it.hostAddress }}")
    true
  } catch (ex: Exception) {
    println("[Playwright] DNS resolution test failed for $host: ${ex.message}")
    false
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
    error("Unsupported Maestro Playwright Driver Call to ${this::class.simpleName}::close")
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

  override fun deviceInfo(): DeviceInfo = DeviceInfo(
    platform = Platform.WEB,
    widthPixels = currentPage.viewportSize().width,
    heightPixels = currentPage.viewportSize().height,
    widthGrid = currentPage.viewportSize().width,
    heightGrid = currentPage.viewportSize().height,
  )

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
    val waitTimeoutMs = if (isCI) 40000.0 else 20000.0
    val waitForSelectors = listOf(
      "body", // ensure body is present
      "html", // ensure html is present
      "[aria-busy=false]", // element is not busy (not always present, but useful)
    )
    var lastException: Exception? = null

    // Preprocess link if needed (for debugging or normalization)
    val processedLink = link.trim()
    println("[Playwright] openLink (raw): $link")
    println("[Playwright] openLink (processed): $processedLink")

    // Network-level connectivity check before navigation
    val hostToTest = try {
      URL(processedLink).host
    } catch (_: Exception) {
      // Fallback: try to parse as a URL, if fails, default to google
      "www.google.com"
    }

    var networkChecksPassed = false
    val dnsPassed = testDnsResolution(hostToTest)
    val httpPassed = testBasicNetworkConnectivity(processedLink)
    val curlPassed = testCurlNetworkConnectivity(processedLink)

    if (dnsPassed && (httpPassed || curlPassed)) {
      networkChecksPassed = true
    }

    if (!networkChecksPassed) {
      println("[Playwright] Comprehensive network connectivity check failed BEFORE navigation to '$processedLink'. Diagnostics:")
      println("  DNS resolution for host '$hostToTest': ${if (dnsPassed) "SUCCESS" else "FAILED"}")
      println("  HTTP connectivity: ${if (httpPassed) "SUCCESS" else "FAILED"}")
      println("  curl connectivity: ${if (curlPassed) "SUCCESS" else "FAILED"}")
      println("[Playwright] This might be due to DNS issues, lack of internet connectivity, or firewall restrictions.")
      throw RuntimeException("[Playwright] Network connectivity check failed before attempting to navigate to '$processedLink'")
    }

    val waitStrategies = listOf<() -> Unit>(
      { currentPage.waitForLoadState(com.microsoft.playwright.options.LoadState.NETWORKIDLE) },
      { currentPage.waitForLoadState(com.microsoft.playwright.options.LoadState.DOMCONTENTLOADED) },
      { currentPage.waitForLoadState(com.microsoft.playwright.options.LoadState.LOAD) },
    )
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

        // Optionally perform autoVerify
        if (autoVerify) {
          println("[Playwright] autoVerify enabled: checking for canonical link and title")
          val title = currentPage.title()
          val canonicalHref = try {
            currentPage.querySelector("link[rel='canonical']")?.getAttribute("href")
          } catch (_: Exception) {
            null
          }
          println("[Playwright] Page title: '$title', canonical: '$canonicalHref'")
        }

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
    currentPage.mouse().apply {
      down()
      move(start.x.toDouble(), start.y.toDouble())
      up()
    }
  }

  override fun swipe(elementPoint: Point, direction: SwipeDirection, durationMs: Long) {
    error("Unsupported Maestro Playwright Driver Call to ${this::class.simpleName}::swipe")
  }

  override fun swipe(swipeDirection: SwipeDirection, durationMs: Long) {
    error("Unsupported Maestro Playwright Driver Call to ${this::class.simpleName}::swipe")
  }

  override fun takeScreenshot(out: Sink, compressed: Boolean) {
    val screenshot = currentPage.screenshot()
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
