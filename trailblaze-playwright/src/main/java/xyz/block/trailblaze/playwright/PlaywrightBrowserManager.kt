package xyz.block.trailblaze.playwright

import com.microsoft.playwright.Browser
import com.microsoft.playwright.BrowserContext
import com.microsoft.playwright.BrowserType
import com.microsoft.playwright.Page
import com.microsoft.playwright.Playwright
import com.microsoft.playwright.Request
import com.microsoft.playwright.options.LoadState
import xyz.block.trailblaze.devices.ResolvedWebViewport
import xyz.block.trailblaze.devices.WebViewportSpec
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Instant
import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.capture.video.PlaywrightVideoRecordDir
import xyz.block.trailblaze.tracing.CompleteEvent
import xyz.block.trailblaze.tracing.PlatformIds
import xyz.block.trailblaze.tracing.TrailblazeTracer
import xyz.block.trailblaze.util.Console
import java.io.File
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
  /** Phase 3: Wait until the DOM stops mutating for [PlaywrightPageManager.DOM_STABILITY_QUIET_MS]. */
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
  /**
   * User-facing viewport spec — Playwright preset name (`"iPhone 14"`) or raw
   * `WIDTHxHEIGHT` (`"375x812"`). Resolved against Playwright's bundled device
   * registry via [PlaywrightDeviceRegistry] during init on the Playwright
   * dispatcher thread; the resolved values populate [resolvedViewport] and
   * drive [createFreshContextAndPage]'s `Browser.NewContextOptions`. Null =
   * default desktop viewport ([DEFAULT_VIEWPORT_WIDTH] x [DEFAULT_VIEWPORT_HEIGHT]).
   */
  private val viewportSpec: String? = null,
  override val idlingConfig: PlaywrightNativeIdlingConfig = PlaywrightNativeIdlingConfig(),
  val analyticsUrlPatterns: List<String> = emptyList(),
  /** Called during first-time Chromium install with (percentComplete 0–100, statusMessage). */
  private val onBrowserInstallProgress: ((Int, String) -> Unit)? = null,
  /**
   * Trailblaze device id used to look up a per-session video-record directory in
   * [PlaywrightVideoRecordDir]. When `null` (e.g. internal unit tests, or the recording-
   * tab browser that is not driven through `CaptureSession`), video recording is left
   * disabled and `Browser.NewContextOptions` is built without `setRecordVideoDir`.
   */
  private val deviceId: String? = null,
) : PlaywrightPageManager {

  companion object {
    const val DEFAULT_VIEWPORT_WIDTH = 1280
    const val DEFAULT_VIEWPORT_HEIGHT = 800

    // Settle constants live on PlaywrightPageManager.Companion alongside their consumers
    // (`dispatchAndAwaitSettle`, `waitForPageReady` default param). See that file for definitions.

    /**
     * JVM-wide guard against concurrent `Playwright.create()` invocations. The upstream
     * `com.microsoft.playwright` Java client is not thread-safe under simultaneous init —
     * observed under parallel test workloads as "Failed to read message from driver,
     * pipe closed" when two [PlaywrightBrowserManager] instances initialized at the same
     * instant. Each manager has its own dedicated [playwrightExecutor] thread + its own
     * driver subprocess, but the upstream library's process-spawning code races on
     * internal static state (driver-binary resolution, stdio handshake setup).
     *
     * Serializing `Playwright.create()` JVM-wide is the cheapest fix: concurrent
     * BrowserManager inits spend at most ~200ms back-to-back on the init handshake
     * instead of in parallel, but the resulting Playwright instances + driver
     * subprocesses + browsers all run fully in parallel after init. A future
     * higher-parallelism setup may prefer a pre-warmed singleton model instead, but
     * this lock is sufficient for the parallel-test-runner workloads we ship today.
     */
    @kotlin.jvm.JvmStatic
    private val playwrightCreateLock = Any()
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
   * Init script that neuters `navigator.credentials` so WebAuthn / passkey requests
   * fail fast instead of opening native browser dialogs ("Use saved passkey", "PIN
   * required", "Set up a new PIN for your security key", etc.) that automated tests
   * cannot interact with.
   *
   * The Chrome `--disable-features=WebAuthentication` launch flag and the
   * `WebAuthn.enable` CDP call together suppress the "use saved passkey" prompt, but
   * sites that initiate **registration** (e.g. Square's post-login passkey upsell)
   * still surface the native PIN-setup dialog. Rejecting `create()` / `get()` at the
   * JS layer makes the site's passkey path bail out and fall through to whatever
   * non-passkey flow follows.
   *
   * Both the prototype methods and the bound `navigator.credentials.create/get`
   * properties are replaced because some sites cache the bound function reference at
   * page load.
   */
  private val disableWebAuthnScript =
    """
    (function() {
      try {
        const reject = () => Promise.reject(new DOMException(
          'WebAuthn disabled by Trailblaze automation',
          'NotAllowedError'
        ));
        if (navigator.credentials) {
          // Replace bound methods on the live navigator.credentials object.
          try { navigator.credentials.create = reject; } catch (e) {}
          try { navigator.credentials.get = reject; } catch (e) {}
        }
        if (typeof CredentialsContainer !== 'undefined') {
          // Replace prototype methods so any later-cached binding still rejects.
          try { CredentialsContainer.prototype.create = reject; } catch (e) {}
          try { CredentialsContainer.prototype.get = reject; } catch (e) {}
        }
        if (typeof PublicKeyCredential !== 'undefined') {
          // Tell sites that platform authenticator probes should report no platform
          // authenticator available, so they skip the passkey upsell entirely.
          try {
            PublicKeyCredential.isUserVerifyingPlatformAuthenticatorAvailable =
              () => Promise.resolve(false);
          } catch (e) {}
          try {
            PublicKeyCredential.isConditionalMediationAvailable =
              () => Promise.resolve(false);
          } catch (e) {}
        }
      } catch (e) {
        // Best-effort — never break the page if the override fails.
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
   * Concrete viewport in effect for this manager. Resolved on [init] from the
   * caller's [viewportSpec] against the running Playwright instance's bundled
   * device registry — preset names expand to (width, height, deviceScaleFactor,
   * userAgent, isMobile, hasTouch); raw `WIDTHxHEIGHT` populates the two
   * dimensions and leaves the rest null. A null spec resolves to the desktop
   * default ([DEFAULT_VIEWPORT_WIDTH] x [DEFAULT_VIEWPORT_HEIGHT]). Consumed by
   * [createFreshContextAndPage], [captureScreenStateForLogging], and
   * [getScreenState].
   */
  lateinit var resolvedViewport: ResolvedWebViewport
    private set

  /**
   * Directory the *current* `browserContext` is recording video into, or null when the
   * context was built without video. Compared against the latest [PlaywrightVideoRecordDir]
   * registration by [syncRecordingWithRegistry] so a cached browser can pick up a freshly
   * published per-session record dir without the caller having to know whether the manager
   * is new or reused.
   */
  @Volatile private var currentRecordingDir: File? = null

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
      // Ensure the Playwright driver is available (downloads on first use if driver-bundle
      // is not on the classpath, e.g., when running from the uber JAR).
      PlaywrightDriverManager.ensureDriverAvailable()
      // Ensure the Chromium browser binary is installed. This is a no-op after first install.
      PlaywrightDriverManager.ensureBrowserInstalled(onProgress = onBrowserInstallProgress)
      runBlocking(playwrightDispatcher) {
        // See [playwrightCreateLock] kdoc for why this is serialized JVM-wide. Each
        // manager still runs on its own [playwrightDispatcher] thread; the lock only
        // serializes the upstream library's racy init handshake. After this call
        // returns, all further Playwright operations on the resulting instance happen
        // independently from any other manager's instance.
        playwright = synchronized(playwrightCreateLock) { Playwright.create() }
        // Resolve the user-facing viewport spec against the live Playwright registry
        // before any context is built — preset typos surface here as a clean
        // IllegalArgumentException ("Unknown Playwright device preset 'iPhne 14'…")
        // rather than later as a confusing context-creation failure.
        resolvedViewport = PlaywrightDeviceRegistry.resolve(
          playwright = playwright,
          spec = WebViewportSpec.parse(viewportSpec),
          defaultWidth = DEFAULT_VIEWPORT_WIDTH,
          defaultHeight = DEFAULT_VIEWPORT_HEIGHT,
        )
        val browserType =
          when (browserEngine) {
            BrowserEngine.CHROMIUM -> playwright.chromium()
            BrowserEngine.FIREFOX -> playwright.firefox()
            BrowserEngine.WEBKIT -> playwright.webkit()
          }
        browser = browserType.launch(launchOptions)
        createFreshContextAndPage()
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

    // Reject WebAuthn / passkey calls so native browser dialogs (PIN required,
    // saved passkey picker, security key registration) never appear.
    page.addInitScript(disableWebAuthnScript)

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
   *    for [PlaywrightPageManager.DOM_STABILITY_QUIET_MS] milliseconds. This catches:
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
        Console.log("  [idle] Waiting for DOM stability (quiet: ${PlaywrightPageManager.DOM_STABILITY_QUIET_MS}ms, timeout: ${domStabilityTimeoutMs.toLong()}ms)...")
        val start = System.currentTimeMillis()
        currentPage.waitForFunction(
          """() => {
              if (typeof window.__tbLastDomChange === 'undefined') return true;
              return (Date.now() - window.__tbLastDomChange) > ${PlaywrightPageManager.DOM_STABILITY_QUIET_MS};
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
        resolvedViewport.width,
        resolvedViewport.height,
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
        resolvedViewport.width,
        resolvedViewport.height,
        browserEngine = browserEngine,
        tabContext = tabContext,
        requestedDetails = details,
      )
    }
  }

  /**
   * Resets the browser session for true Playwright-style isolation between Trailblaze
   * sessions. Closes the current [BrowserContext] and creates a fresh one on the same
   * long-lived [Browser] process — equivalent to what `@playwright/test` gives each
   * test by default via per-test contexts.
   *
   * `clearCookies()` alone would NOT wipe `localStorage`, `sessionStorage`, `IndexedDB`,
   * HTTP cache, service workers, or permissions — so any site that stores its auth
   * token in `localStorage` (the modern SPA pattern) would survive a cookie-only
   * reset and the next session would inherit the prior session's login. Closing and
   * recreating the context wipes all of it in one shot.
   *
   * The Chromium process + Playwright driver are untouched, so this is ~instant and
   * preserves the "browser is the device" model — only session-scoped state turns over.
   */
  override fun resetSession() {
    inFlightRequests.clear()
    try {
      browserContext.close()
    } catch (_: Exception) {
      // Best-effort — a context that's already half-torn-down from a crash should
      // not block creation of a fresh one below.
    }
    createFreshContextAndPage()
  }

  /**
   * Reconciles the active `BrowserContext`'s video recording with the latest
   * [PlaywrightVideoRecordDir] registration. If the registry's dir for this manager's
   * [deviceId] differs from what the current context is recording to (including the
   * "registry empty / context recording" and "registry set / context not recording" cases),
   * tears down the current context and builds a new one with the right `setRecordVideoDir`.
   *
   * Trail runners call this at the start of every trail so a cached manager (constructed
   * before the per-session capture dir was published) picks up the recording before the
   * first action runs. No-op when [deviceId] is null (manager not wired to a capture session
   * — e.g. recording-tab browser, unit tests).
   */
  fun syncRecordingWithRegistry() {
    val dev = deviceId ?: return
    val desired = PlaywrightVideoRecordDir.getRecordDir(dev)
    if (desired == currentRecordingDir) return
    if (closed.get()) return
    Console.log(
      "[PlaywrightBrowserManager] reconciling recordVideoDir: current=$currentRecordingDir desired=$desired",
    )
    // Callers reach this method from two places: the manager's own init() block
    // (off the Playwright thread), and from inside `runTrailblazeYamlSuspend` which is
    // already `withContext(playwrightDispatcher)`. The on-thread case must NOT use
    // `runBlocking(playwrightDispatcher)` or it deadlocks (single-threaded dispatcher
    // blocked waiting for a coroutine that wants to run on the blocked thread). The
    // [PlaywrightThreadBridge] helper picks the right strategy.
    PlaywrightThreadBridge.runOnPlaywrightThread(
      currentThread = Thread.currentThread(),
      playwrightThread = if (::playwrightThread.isInitialized) playwrightThread else null,
      dispatcher = playwrightDispatcher,
    ) {
      try {
        browserContext.close()
      } catch (_: Exception) {}
      createFreshContextAndPage()
    }
  }

  /**
   * Creates a new [BrowserContext] on the long-lived [browser] and opens an initial
   * page wired up for automation (init scripts, timeouts, popup tracking, WebAuthn
   * suppression). Assigns the new context/page to the manager's lateinit fields.
   *
   * Must be called on the Playwright dispatcher thread.
   */
  private fun createFreshContextAndPage() {
    val recordVideoDir = deviceId?.let { PlaywrightVideoRecordDir.getRecordDir(it) }
    val width = resolvedViewport.width
    val height = resolvedViewport.height
    val options = Browser.NewContextOptions()
      .setViewportSize(width, height)
    // Preset-supplied emulation properties take precedence over our defaults; for raw
    // dimensions (or no spec) we keep the legacy CI=1.0 / dev=2.0 scale heuristic so
    // existing desktop screenshots stay byte-identical.
    val effectiveDeviceScaleFactor = resolvedViewport.deviceScaleFactor ?: if (isCI) 1.0 else 2.0
    options.setDeviceScaleFactor(effectiveDeviceScaleFactor)
    resolvedViewport.userAgent?.let { options.setUserAgent(it) }
    resolvedViewport.isMobile?.let { options.setIsMobile(it) }
    resolvedViewport.hasTouch?.let { options.setHasTouch(it) }
    if (recordVideoDir != null) {
      // Playwright finalizes the `.webm` only when the context closes — wire a finalizer
      // so PlaywrightVideoCapture.stop() can force-flush in the kept-alive case, where
      // the manager's own close() isn't called between sessions.
      options.setRecordVideoDir(recordVideoDir.toPath())
        .setRecordVideoSize(width, height)
      Console.log(
        "[PlaywrightBrowserManager] recording video to ${recordVideoDir.absolutePath} (deviceId=$deviceId)",
      )
    }
    browserContext = browser.newContext(options)
    currentRecordingDir = recordVideoDir
    currentPage = browserContext.newPage()
    setupPageForAutomation(currentPage)
    disableWebAuthn()
    if (deviceId != null && recordVideoDir != null) {
      PlaywrightVideoRecordDir.setFinalizer(deviceId) {
        // Close-and-recreate flushes the WebM. Must run on the Playwright dispatcher
        // thread for thread-affinity correctness.
        if (closed.get()) return@setFinalizer
        runBlocking(playwrightDispatcher) {
          try {
            browserContext.close()
          } catch (_: Exception) {}
          // After flushing, drop the registration so any subsequent fresh context for
          // this device id is built without recording (capture has already moved on).
          PlaywrightVideoRecordDir.clearRecordDir(deviceId)
          PlaywrightVideoRecordDir.clearFinalizer(deviceId)
          // Recreate so the manager remains usable in the kept-alive case.
          createFreshContextAndPage()
        }
      }
    }
  }

  /** Closes the browser and cleans up Playwright resources. */
  override fun close() {
    if (!closed.compareAndSet(false, true)) return
    inFlightRequests.clear()
    // Drop any video finalizer we registered so a later capture.stop() doesn't try
    // to drive this torn-down manager. The `browserContext.close()` below already
    // flushes the in-progress WebM.
    deviceId?.let { PlaywrightVideoRecordDir.clearFinalizer(it) }
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
    try {
      PlaywrightThreadBridge.runOnPlaywrightThread(
        currentThread = Thread.currentThread(),
        playwrightThread = if (::playwrightThread.isInitialized) playwrightThread else null,
        dispatcher = playwrightDispatcher,
        block = closeResources,
      )
    } catch (_: Exception) {}
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
