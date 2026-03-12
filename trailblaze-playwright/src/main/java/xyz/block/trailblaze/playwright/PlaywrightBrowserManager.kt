package xyz.block.trailblaze.playwright

import com.microsoft.playwright.Browser
import com.microsoft.playwright.BrowserContext
import com.microsoft.playwright.BrowserType
import com.microsoft.playwright.Page
import com.microsoft.playwright.Playwright
import com.microsoft.playwright.Request
import com.microsoft.playwright.options.LoadState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Instant
import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.tracing.CompleteEvent
import xyz.block.trailblaze.tracing.PlatformIds
import xyz.block.trailblaze.tracing.TrailblazeTracer
import xyz.block.trailblaze.util.Console
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.milliseconds

/**
 * Snapshot of the browser's tab state at the time of screen capture.
 *
 * Provides the LLM with awareness of multiple open tabs so it can understand
 * when new tabs have opened (e.g., from `target="_blank"` links) and what
 * content exists in tabs it's not currently viewing.
 *
 * @property activeTabIndex 0-based index of the currently active tab
 * @property tabs List of all open tabs with their URL and title
 */
data class TabContext(
  val activeTabIndex: Int,
  val tabs: List<TabInfo>,
) {
  data class TabInfo(
    val url: String,
    val title: String,
  )
}

/**
 * The browser engine to use for Playwright automation.
 *
 * Currently only [CHROMIUM] is fully supported (with tuned launch args and CI configuration).
 * [FIREFOX] and [WEBKIT] are available for future use — when enabled, browser-specific
 * launch args and CI browser installation will need to be configured.
 */
enum class BrowserEngine(val displayName: String) {
  CHROMIUM("Chromium"),
  FIREFOX("Firefox"),
  WEBKIT("WebKit"),
}

/**
 * Controls which page-settling checks run after tool actions and before screen captures.
 *
 * All checks are enabled by default for maximum reliability. Disable individual checks
 * to speed up execution during development or when a site's behavior makes a check
 * counterproductive (e.g., persistent WebSocket connections cause network idle to always
 * time out).
 */
data class PlaywrightNativeIdlingConfig(
  /** Phase 1: Wait for DOM to be parsed and scripts executed. Almost always instant. */
  val waitForDomContentLoaded: Boolean = true,
  /**
   * Phase 2: Track HTTP requests and log them as trace events. Does NOT block execution —
   * all requests (fetch, XHR, document, WebSocket) are recorded to [TrailblazeTracer] for
   * retroactive analysis. Use the exported trace JSON to analyze network patterns,
   * identify slow endpoints, and understand page loading behavior.
   */
  val traceHttpRequests: Boolean = true,
  /** Phase 3: Wait until the DOM stops mutating for [PlaywrightBrowserManager.DOM_STABILITY_QUIET_MS]. */
  val waitForDomStability: Boolean = true,
  /** Inject CSS to disable all animations/transitions for stable screenshots. */
  val disableAnimations: Boolean = true,
) {
  companion object {
    /** All settling checks disabled — fastest possible execution. */
    val DISABLED = PlaywrightNativeIdlingConfig(
      waitForDomContentLoaded = false,
      traceHttpRequests = false,
      waitForDomStability = false,
      disableAnimations = false,
    )
  }
}

/**
 * Manages the Playwright browser lifecycle for the Playwright-native agent.
 *
 * Handles browser launch, context management, page tracking, and cleanup.
 * Launch options are tuned for stable, reproducible browser automation.
 */
class PlaywrightBrowserManager(
  private val browserEngine: BrowserEngine = BrowserEngine.CHROMIUM,
  private val headless: Boolean = true,
  private val viewportWidth: Int = DEFAULT_VIEWPORT_WIDTH,
  private val viewportHeight: Int = DEFAULT_VIEWPORT_HEIGHT,
  override val idlingConfig: PlaywrightNativeIdlingConfig = PlaywrightNativeIdlingConfig(),
  val analyticsUrlPatterns: List<String> = emptyList(),
) : PlaywrightPageManager {

  companion object {
    const val DEFAULT_VIEWPORT_WIDTH = 1280
    const val DEFAULT_VIEWPORT_HEIGHT = 800

    /**
     * Default timeout for the DOM stability phase of [waitForPageReady].
     * 2 seconds allows most JS-driven re-renders and animations to complete.
     */
    const val DEFAULT_DOM_STABILITY_TIMEOUT_MS = 2000.0

    /**
     * How long the DOM must be quiet (no mutations) before it's considered stable.
     * 300ms balances responsiveness with reliability — most React/Vue re-renders
     * complete within a single frame (~16ms), but chained updates or transitions
     * can take a few hundred milliseconds.
     */
    const val DOM_STABILITY_QUIET_MS = 300
  }

  /**
   * Single-threaded executor and dispatcher for all Playwright API calls.
   *
   * Playwright's browser process communication requires thread affinity — all API calls
   * must happen on the same thread that created the browser connection. This dispatcher
   * confines all Playwright operations to a dedicated thread, preventing issues when
   * coroutines resume on different threads after suspend points (e.g., LLM calls on
   * [kotlinx.coroutines.Dispatchers.IO]).
   *
   * Use [playwrightDispatcher] with `withContext` to ensure agent loop code runs on
   * the correct thread after suspending for LLM calls.
   */
  private lateinit var playwrightThread: Thread
  private val playwrightExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
    Thread(runnable, "playwright-browser").apply {
      isDaemon = true
      playwrightThread = this
    }
  }
  override val playwrightDispatcher: CoroutineDispatcher = playwrightExecutor.asCoroutineDispatcher()

  /**
   * Tracks in-flight HTTP requests for trace logging. Each request is recorded as a
   * span in [TrailblazeTracer] with its URL, resource type, and duration — giving
   * full visibility into network activity during test execution without blocking.
   *
   * Key: request instance, Value: start timestamp (epoch ms)
   */
  private val inFlightRequests = java.util.concurrent.ConcurrentHashMap<Request, Long>()

  /** Returns true if the URL matches a known analytics/monitoring endpoint. */
  private fun isAnalyticsUrl(url: String): Boolean =
    analyticsUrlPatterns.any { url.contains(it) }

  private val isCI = isRunningOnCi()

  private val xdgTempDir: java.nio.file.Path? =
    if (System.getenv("XDG_RUNTIME_DIR").isNullOrBlank()) {
      java.nio.file.Files.createTempDirectory("xdg")
    } else {
      null
    }

  private val customEnv =
    HashMap<String, String>().apply {
      xdgTempDir?.let { put("XDG_RUNTIME_DIR", it.toString()) }
      put("DBUS_SESSION_BUS_ADDRESS", "")
      put("DBUS_SYSTEM_BUS_ADDRESS", "")
      System.getenv("PLAYWRIGHT_BROWSERS_PATH")?.let { put("PLAYWRIGHT_BROWSERS_PATH", it) }
    }

  private val launchOptions =
    BrowserType.LaunchOptions()
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

            // Prevent background throttling
            add("--disable-background-timer-throttling")
            add("--disable-backgrounding-occluded-windows")
            add("--disable-renderer-backgrounding")

            // Reduce visual flashing and flickering
            add("--disable-infobars")
            add("--disable-popup-blocking")
            add("--disable-features=TranslateUI,WebAuthentication")
            add("--force-color-profile=srgb")
            add("--disable-ipc-flooding-protection")

            // GPU handling
            if (isCI) {
              add("--disable-gpu")
            } else {
              add("--enable-gpu-rasterization")
              add("--enable-zero-copy")
            }

            add("--disable-smooth-scrolling")
            add("--animation-duration-scale=0")
          },
        ).setEnv(customEnv)
      }

  private val disableAnimationsScript =
    """
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
      if (document.head) {
        document.head.appendChild(style);
      } else {
        document.addEventListener('DOMContentLoaded', () => document.head.appendChild(style));
      }
    })();
    """.trimIndent()

  /**
   * Init script that installs a `MutationObserver` to track the timestamp of the last
   * DOM change. Registered via [Page.addInitScript] so it runs on every navigation
   * (including SPA route changes that trigger full reloads) and is available immediately.
   *
   * The observer watches for all types of DOM mutations (child list, attributes, text
   * content) across the entire subtree. The timestamp is stored on `window.__tbLastDomChange`
   * and checked by [waitForPageReady] phase 3.
   *
   * By installing eagerly rather than lazily, we avoid an artificial 300ms wait on
   * already-settled pages — the observer has been tracking since page load, so
   * [waitForPageReady] can immediately see that no recent mutations occurred.
   */
  private val domStabilityObserverScript =
    """
    (function() {
      window.__tbLastDomChange = Date.now();
      const target = document.body || document.documentElement;
      if (target) {
        window.__tbDomObserver = new MutationObserver(() => {
          window.__tbLastDomChange = Date.now();
        });
        window.__tbDomObserver.observe(target, {
          childList: true,
          subtree: true,
          attributes: true,
          characterData: true,
        });
      } else {
        // Body not ready yet — install once DOM is available
        document.addEventListener('DOMContentLoaded', () => {
          window.__tbDomObserver = new MutationObserver(() => {
            window.__tbLastDomChange = Date.now();
          });
          window.__tbDomObserver.observe(document.body, {
            childList: true,
            subtree: true,
            attributes: true,
            characterData: true,
          });
        });
      }
    })();
    """.trimIndent()

  private lateinit var playwright: Playwright
  private lateinit var browser: Browser
  private lateinit var browserContext: BrowserContext

  /**
   * Details requested by the LLM for the next view hierarchy snapshot.
   *
   * When the LLM calls [PlaywrightNativeRequestDetailsTool], the requested detail types
   * are stored here. The next call to [getScreenState] will pass these to
   * [PlaywrightScreenState] for enrichment, then auto-clear so subsequent turns
   * return to the compact default format.
   */
  @Volatile private var pendingDetailRequests: Set<ViewHierarchyDetail> = emptySet()

  /**
   * Requests that the next view hierarchy snapshot include the specified details.
   * Called by [PlaywrightNativeRequestDetailsTool] during tool execution.
   */
  override fun requestDetails(details: Set<ViewHierarchyDetail>) {
    pendingDetailRequests = details
  }

  private val closed = AtomicBoolean(false)

  override lateinit var currentPage: Page
    private set

  init {
    // Initialize Playwright on the dedicated thread to ensure thread affinity.
    // All subsequent Playwright API calls must happen on this same thread.
    try {
      // Direct the Playwright driver extraction to a stable cache directory instead of the
      // system temp dir (/var/folders/.../T/ on macOS). The system temp dir is periodically
      // cleaned by the OS, which can delete the extracted Node.js driver files while the
      // desktop app is still running, causing "Cannot find module .../package/cli.js" errors.
      // See: https://github.com/microsoft/playwright-java/issues/1294
      if (System.getProperty("playwright.driver.tmpdir") == null) {
        val cacheDir = java.nio.file.Path.of(
          System.getProperty("user.home"), ".cache", "trailblaze", "playwright-driver"
        )
        java.nio.file.Files.createDirectories(cacheDir)
        System.setProperty("playwright.driver.tmpdir", cacheDir.toString())
      }
      runBlocking(playwrightDispatcher) {
        playwright = Playwright.create()
        val browserType =
          when (browserEngine) {
            BrowserEngine.CHROMIUM -> playwright.chromium()
            BrowserEngine.FIREFOX -> playwright.firefox()
            BrowserEngine.WEBKIT -> playwright.webkit()
          }
        browser = browserType.launch(launchOptions)
        browserContext =
          browser.newContext(
            Browser.NewContextOptions()
              .setViewportSize(viewportWidth, viewportHeight)
              .setDeviceScaleFactor(if (isCI) 1.0 else 2.0),
          )
        currentPage = browserContext.newPage()
        setupPageForAutomation(currentPage)
        disableWebAuthn()
      }
    } catch (e: Exception) {
      playwrightExecutor.shutdownNow()
      throw e
    }
  }

  /**
   * Disables the browser's native WebAuthn/passkey UI via CDP.
   *
   * Calling `WebAuthn.enable` takes over WebAuthn handling from the browser,
   * which suppresses the native "Use a saved passkey" prompt that automated
   * tests cannot interact with. Without a virtual authenticator registered,
   * WebAuthn requests simply fail silently instead of showing a dialog.
   */
  private fun disableWebAuthn() {
    if (browserEngine != BrowserEngine.CHROMIUM) return
    val cdpSession = browserContext.newCDPSession(currentPage)
    try {
      cdpSession.send("WebAuthn.enable", com.google.gson.JsonObject())
    } finally {
      cdpSession.detach()
    }
  }

  private fun setupPageForAutomation(page: Page) {
    val navigationTimeout = if (isCI) 60000.0 else 30000.0
    val defaultTimeout = if (isCI) 45000.0 else 30000.0

    page.setDefaultNavigationTimeout(navigationTimeout)
    page.setDefaultTimeout(defaultTimeout)

    // Disable animations for stable screenshots
    if (idlingConfig.disableAnimations) {
      page.addInitScript(disableAnimationsScript)
    }

    // Install DOM stability observer for waitForPageReady() phase 3
    if (idlingConfig.waitForDomStability) {
      page.addInitScript(domStabilityObserverScript)
    }

    // Trace all HTTP requests as spans in TrailblazeTracer for retroactive analysis.
    // This does NOT block execution — requests are logged as they complete, giving
    // full visibility into network activity (URLs, resource types, durations, analytics tags).
    if (idlingConfig.traceHttpRequests) {
      page.onRequest { request ->
        inFlightRequests[request] = System.currentTimeMillis()
      }
      page.onRequestFinished { request ->
        emitHttpTraceEvent(request, "ok")
      }
      page.onRequestFailed { request ->
        emitHttpTraceEvent(request, "failed")
      }
    }

    // Track new tabs/popups
    page.onPopup { popup ->
      Console.log("New tab opened: ${popup.url()}")
      setupPageForAutomation(popup)
      currentPage = popup
    }
  }

  /**
   * Waits for the page to reach a "ready" state before capturing a screen snapshot.
   *
   * Uses a two-phase approach:
   *
   * 1. **`DOMCONTENTLOADED`** — Fast and reliable. Ensures HTML is parsed and scripts have
   *    executed. This is the minimum bar for a meaningful ARIA snapshot.
   *
   * 2. **DOM stability** — Uses a `MutationObserver` to wait until the DOM stops changing
   *    for [DOM_STABILITY_QUIET_MS] milliseconds. This catches:
   *    - React/Vue/Angular re-renders after state updates
   *    - Loading spinners disappearing
   *    - Modals or toasts animating in (JS-driven, since CSS animations are disabled)
   *    - Debounced search results appearing
   *    - Any JavaScript-driven DOM mutation
   *
   *    Uses [page.waitForFunction][Page.waitForFunction] to poll the observer's timestamp
   *    from the browser context, avoiding extra round-trips.
   *
   * Network activity is tracked separately via [traceHttpRequests][PlaywrightNativeIdlingConfig.traceHttpRequests]
   * and logged as spans in [TrailblazeTracer] for retroactive analysis. We do NOT block on
   * network idle because real-world SPAs have persistent connections (WebSocket, SSE, analytics
   * polling) that prevent it from ever resolving.
   *
   * This is especially important for **recorded test playback** where tool calls execute
   * very fast (no LLM latency between turns) and there's no AI to recover from stale state.
   * For AI mode, the cost is negligible since the LLM call itself takes 1-5 seconds.
   *
   * @param domStabilityTimeoutMs How long to wait for DOM stability. Set to 0 to skip
   *   the DOM stability phase. Default is 2000ms.
   */
  override fun waitForPageReady(
    domStabilityTimeoutMs: Double,
  ) {
    if (idlingConfig.waitForDomContentLoaded) {
      val result = try {
        Console.log("  [idle] Waiting for DOM content loaded...")
        val start = System.currentTimeMillis()
        currentPage.waitForLoadState(
          LoadState.DOMCONTENTLOADED,
          Page.WaitForLoadStateOptions().setTimeout(5000.0),
        )
        val elapsed = System.currentTimeMillis() - start
        Console.log("  [idle] DOM content loaded (${elapsed}ms)")
        "ok:${elapsed}ms"
      } catch (_: Exception) {
        Console.log("  [idle] DOM content loaded timed out")
        "timeout"
      }
      TrailblazeTracer.trace("waitForDomContentLoaded", "idle", mapOf("result" to result)) {}
    }

    if (idlingConfig.waitForDomStability && domStabilityTimeoutMs > 0) {
      val result = try {
        Console.log("  [idle] Waiting for DOM stability (quiet: ${DOM_STABILITY_QUIET_MS}ms, timeout: ${domStabilityTimeoutMs.toLong()}ms)...")
        val start = System.currentTimeMillis()
        currentPage.waitForFunction(
          """() => {
              if (typeof window.__tbLastDomChange === 'undefined') return true;
              return (Date.now() - window.__tbLastDomChange) > $DOM_STABILITY_QUIET_MS;
            }""",
          null,
          Page.WaitForFunctionOptions().setTimeout(domStabilityTimeoutMs),
        )
        val elapsed = System.currentTimeMillis() - start
        Console.log("  [idle] DOM stable (${elapsed}ms)")
        "ok:${elapsed}ms"
      } catch (_: Exception) {
        Console.log("  [idle] DOM stability timed out after ${domStabilityTimeoutMs.toLong()}ms")
        "timeout:${domStabilityTimeoutMs.toLong()}ms"
      }
      TrailblazeTracer.trace("waitForDomStability", "idle", mapOf("result" to result)) {}
    }
  }

  /**
   * Emits a [CompleteEvent] trace span for an HTTP request that has finished or failed.
   * Called from Playwright's `onRequestFinished` / `onRequestFailed` event handlers.
   *
   * The span records: URL (truncated), resource type, status (ok/failed), duration,
   * and whether the URL matches a known [analyticsUrlPatterns] pattern.
   */
  private fun emitHttpTraceEvent(request: Request, status: String) {
    val startMs = inFlightRequests.remove(request) ?: return
    val durationMs = System.currentTimeMillis() - startMs
    val url = request.url()
    val resourceType = request.resourceType()
    val isAnalytics = isAnalyticsUrl(url)
    TrailblazeTracer.traceRecorder.add(
      CompleteEvent(
        name = url.take(120),
        cat = "http",
        ts = Instant.fromEpochMilliseconds(startMs),
        dur = durationMs.milliseconds,
        pid = PlatformIds.pid(),
        tid = PlatformIds.tid(),
        args = buildMap {
          put("resourceType", resourceType)
          put("status", status)
          if (isAnalytics) put("analytics", "true")
        },
      ).toJsonObject(),
    )
  }

  /**
   * Captures the current screen state for logging purposes (screenshot + view hierarchy).
   *
   * Unlike [getScreenState], this does NOT consume [pendingDetailRequests] — those are
   * reserved for the next LLM-facing snapshot. This keeps logging independent from the
   * agent's detail request lifecycle.
   */
  override fun captureScreenStateForLogging(): ScreenState {
    return TrailblazeTracer.trace("captureScreenStateForLogging", "browser") {
      PlaywrightScreenState(
        currentPage,
        viewportWidth,
        viewportHeight,
        browserEngine = browserEngine,
      )
    }
  }

  /** Captures the current screen state from the active page. */
  override fun getScreenState(): ScreenState = TrailblazeTracer.trace("getScreenState", "browser") {
    // Ensure the page is settled before capturing the snapshot.
    // This is critical for recorded playback where there's no AI recovery.
    waitForPageReady()

    // Consume pending detail requests — they apply to this snapshot only
    val details = pendingDetailRequests
    pendingDetailRequests = emptySet()

    // Build tab context from all open pages in the browser context
    val tabContext = try {
      val pages = browserContext.pages()
      val activeIndex = pages.indexOf(currentPage).coerceAtLeast(0)
      TabContext(
        activeTabIndex = activeIndex,
        tabs = pages.map { page ->
          TabContext.TabInfo(
            url = try { page.url() ?: "" } catch (_: Exception) { "" },
            title = try { page.title() ?: "" } catch (_: Exception) { "" },
          )
        },
      )
    } catch (_: Exception) {
      null
    }

    TrailblazeTracer.trace("PlaywrightScreenState", "browser") {
      PlaywrightScreenState(
        currentPage,
        viewportWidth,
        viewportHeight,
        browserEngine = browserEngine,
        tabContext = tabContext,
        requestedDetails = details,
      )
    }
  }

  /** Resets the browser session - clears cookies, closes extra tabs, navigates to blank. */
  override fun resetSession() {
    browserContext.clearCookies()
    inFlightRequests.clear()

    // Close extra pages, keep only the first
    val pages = browserContext.pages()
    if (pages.size > 1) {
      pages.drop(1).forEach { it.close() }
    }

    val existingPage = browserContext.pages().firstOrNull()
    if (existingPage != null) {
      currentPage = existingPage
    } else {
      currentPage = browserContext.newPage()
      setupPageForAutomation(currentPage)
    }
    currentPage.navigate("about:blank")
  }

  /** Closes the browser and cleans up Playwright resources. */
  override fun close() {
    if (!closed.compareAndSet(false, true)) return
    inFlightRequests.clear()
    val closeResources = {
      try {
        browserContext.close()
      } catch (_: Exception) {}
      try {
        browser.close()
      } catch (_: Exception) {}
      try {
        playwright.close()
      } catch (_: Exception) {}
    }
    if (::playwrightThread.isInitialized && Thread.currentThread() === playwrightThread) {
      closeResources()
    } else {
      try {
        runBlocking(playwrightDispatcher) {
          closeResources()
        }
      } catch (_: Exception) {}
    }
    playwrightExecutor.shutdown()
    playwrightExecutor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)
    xdgTempDir?.let { dir ->
      try {
        dir.toFile().deleteRecursively()
      } catch (_: Exception) {}
    }
  }

  private fun isRunningOnCi(): Boolean {
    val isGitHubActions = (System.getenv("GITHUB_ACTIONS") == "true")
    val isBuildkite = System.getenv("BUILDKITE") != null
    val isCI = System.getenv("CI") != null
    return isGitHubActions || isBuildkite || isCI
  }
}
