package xyz.block.trailblaze.playwright

import com.microsoft.playwright.Browser
import com.microsoft.playwright.BrowserContext
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
 * Manages a Playwright connection to an Electron app via CDP (Chrome DevTools Protocol).
 *
 * Unlike [PlaywrightBrowserManager] which launches a fresh Chromium browser, this manager
 * connects to an already-running Electron app's CDP endpoint and operates on the app's
 * existing pages.
 *
 * Key differences from [PlaywrightBrowserManager]:
 * - **Init**: `connectOverCDP(cdpUrl)` instead of `chromium().launch()`
 * - **Context/Page**: Uses existing `contexts()[0].pages()[0]` instead of creating new ones
 * - **Viewport**: Uses the Electron window's viewport (no override)
 * - **resetSession()**: Clears cookies + closes extra tabs (no `about:blank` navigation)
 * - **close()**: Disconnects only — does NOT close the browser (that would kill the app)
 */
class PlaywrightElectronBrowserManager(
  private val cdpUrl: String,
  override val idlingConfig: PlaywrightNativeIdlingConfig = PlaywrightNativeIdlingConfig(),
  val analyticsUrlPatterns: List<String> = emptyList(),
) : PlaywrightPageManager {

  companion object {
    const val DOM_STABILITY_QUIET_MS = PlaywrightBrowserManager.DOM_STABILITY_QUIET_MS
  }

  private lateinit var playwrightThread: Thread
  private val playwrightExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
    Thread(runnable, "playwright-electron").apply {
      isDaemon = true
      playwrightThread = this
    }
  }
  override val playwrightDispatcher: CoroutineDispatcher = playwrightExecutor.asCoroutineDispatcher()

  private val inFlightRequests = java.util.concurrent.ConcurrentHashMap<Request, Long>()

  private fun isAnalyticsUrl(url: String): Boolean =
    analyticsUrlPatterns.any { url.contains(it) }

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

  @Volatile private var pendingDetailRequests: Set<ViewHierarchyDetail> = emptySet()

  override fun requestDetails(details: Set<ViewHierarchyDetail>) {
    pendingDetailRequests = details
  }

  private val closed = AtomicBoolean(false)

  override lateinit var currentPage: Page
    private set

  /** Viewport dimensions read from the connected page. */
  private var viewportWidth: Int = PlaywrightBrowserManager.DEFAULT_VIEWPORT_WIDTH
  private var viewportHeight: Int = PlaywrightBrowserManager.DEFAULT_VIEWPORT_HEIGHT

  init {
    try {
      runBlocking(playwrightDispatcher) {
        playwright = Playwright.create()
        browser = playwright.chromium().connectOverCDP(cdpUrl)
        Console.log("Electron: connected to CDP at $cdpUrl")

        // Grab the first existing context and page from the Electron app
        val contexts = browser.contexts()
        browserContext = if (contexts.isNotEmpty()) {
          contexts[0]
        } else {
          // Unlikely for Electron, but fall back to creating a context
          Console.log("Electron: no existing contexts, creating new one")
          browser.newContext()
        }

        val pages = browserContext.pages()
        currentPage = if (pages.isNotEmpty()) {
          pages[0]
        } else {
          Console.log("Electron: no existing pages, creating new one")
          browserContext.newPage()
        }

        // Read the actual viewport size from the Electron window
        try {
          val viewportSize = currentPage.viewportSize()
          if (viewportSize != null) {
            viewportWidth = viewportSize.width
            viewportHeight = viewportSize.height
            Console.log("Electron: viewport ${viewportWidth}x${viewportHeight}")
          }
        } catch (_: Exception) {
          Console.log("Electron: could not read viewport size, using defaults")
        }

        setupPageForAutomation(currentPage)
      }
    } catch (e: Exception) {
      playwrightExecutor.shutdownNow()
      throw e
    }
  }

  private fun setupPageForAutomation(page: Page) {
    val navigationTimeout = 30000.0
    val defaultTimeout = 30000.0

    page.setDefaultNavigationTimeout(navigationTimeout)
    page.setDefaultTimeout(defaultTimeout)

    if (idlingConfig.disableAnimations) {
      page.addInitScript(disableAnimationsScript)
    }

    if (idlingConfig.waitForDomStability) {
      page.addInitScript(domStabilityObserverScript)
    }

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

    page.onPopup { popup ->
      Console.log("Electron: new window opened: ${popup.url()}")
      setupPageForAutomation(popup)
      currentPage = popup
    }
  }

  override fun waitForPageReady(domStabilityTimeoutMs: Double) {
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

  override fun captureScreenStateForLogging(): ScreenState {
    return TrailblazeTracer.trace("captureScreenStateForLogging", "browser") {
      PlaywrightScreenState(
        currentPage,
        viewportWidth,
        viewportHeight,
        browserEngine = BrowserEngine.CHROMIUM,
      )
    }
  }

  override fun getScreenState(): ScreenState = TrailblazeTracer.trace("getScreenState", "browser") {
    waitForPageReady()

    val details = pendingDetailRequests
    pendingDetailRequests = emptySet()

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
        browserEngine = BrowserEngine.CHROMIUM,
        tabContext = tabContext,
        requestedDetails = details,
      )
    }
  }

  /**
   * Resets the session by clearing cookies and closing extra tabs.
   * Does NOT navigate to about:blank — that would destroy the Electron app's UI.
   */
  override fun resetSession() {
    browserContext.clearCookies()
    inFlightRequests.clear()

    val pages = browserContext.pages()
    if (pages.size > 1) {
      pages.drop(1).forEach { it.close() }
    }

    val existingPage = browserContext.pages().firstOrNull()
    if (existingPage != null) {
      currentPage = existingPage
    }
  }

  /**
   * Disconnects from the Electron app. Does NOT close the browser — the Electron
   * app manages its own lifecycle.
   */
  override fun close() {
    if (!closed.compareAndSet(false, true)) return
    inFlightRequests.clear()
    val disconnectResources = {
      // Disconnect the CDP session. For connectOverCDP browsers, browser.close()
      // only disconnects — it does NOT terminate the Electron app.
      try {
        browser.close()
      } catch (_: Exception) {}
      try {
        playwright.close()
      } catch (_: Exception) {}
    }
    if (::playwrightThread.isInitialized && Thread.currentThread() === playwrightThread) {
      disconnectResources()
    } else {
      try {
        runBlocking(playwrightDispatcher) {
          disconnectResources()
        }
      } catch (_: Exception) {}
    }
    playwrightExecutor.shutdown()
    playwrightExecutor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)
  }
}
