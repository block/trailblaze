package xyz.block.trailblaze.playwright

import com.microsoft.playwright.Frame
import com.microsoft.playwright.Page
import com.microsoft.playwright.Request
import com.microsoft.playwright.options.LoadState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
import xyz.block.trailblaze.api.DriverDispatch
import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.util.Console
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.function.Consumer

/**
 * Abstraction over Playwright page lifecycle used by [PlaywrightTrailblazeAgent] and test
 * infrastructure classes.
 *
 * Two implementations exist:
 * - [PlaywrightBrowserManager]: launches a fresh Chromium browser.
 * - [PlaywrightElectronBrowserManager]: connects to an Electron app via CDP.
 */
interface PlaywrightPageManager : AutoCloseable, DriverDispatch {
  val currentPage: Page
  val playwrightDispatcher: CoroutineDispatcher
  val idlingConfig: PlaywrightNativeIdlingConfig
  fun requestDetails(details: Set<ViewHierarchyDetail>)
  fun getScreenState(): ScreenState
  fun captureScreenStateForLogging(): ScreenState
  fun waitForPageReady(
    domStabilityTimeoutMs: Double = DEFAULT_DOM_STABILITY_TIMEOUT_MS,
  )
  fun resetSession()

  /**
   * Implements the [DriverDispatch] contract for Playwright: runs [action] and does not return
   * until pending HTTP requests have drained (or a navigation triggered by the action has
   * reached `load`).
   *
   * Request-tracking post-action settle, modeled on Microsoft's playwright-mcp
   * (`packages/playwright-core/src/tools/backend/utils.ts` → `waitForCompletion` — that's the
   * upstream function name; the in-repo method is `dispatchAndAwaitSettle`).
   *
   * 1. Run [action]. Playwright's actionability checks already gate per-tool readiness
   *    (visible / stable / enabled / editable / receives events) inside `locator.click`,
   *    `locator.fill`, `locator.hover`, `locator.selectOption`, etc.
   * 2. Wait 500ms after the action — gives any follow-up requests (validation XHR,
   *    analytics, lazy chunk loads) time to start firing.
   * 3. If the action triggered a navigation request, wait for the new page's `load`
   *    state (10s cap) and return.
   * 4. Otherwise, poll for tracked document/stylesheet/script/xhr/fetch requests to
   *    complete (5s overall cap), then another 500ms grace.
   *
   * Replaces MutationObserver-based DOM-stability settling, which times out on SPAs
   * with continuous analytics/animation mutations even when the page is functionally
   * ready, and was the root cause of recordings needing `web_wait` filler steps
   * between every action.
   */
  override suspend fun <R> dispatchAndAwaitSettle(action: suspend () -> R): R {
    val requests = CopyOnWriteArrayList<Request>()
    val pending = ConcurrentHashMap<Request, Boolean>()
    val onRequest = Consumer<Request> { req ->
      requests.add(req)
      pending[req] = true
    }
    val onFinished = Consumer<Request> { req -> pending.remove(req) }
    val onFailed = Consumer<Request> { req -> pending.remove(req) }

    val page = currentPage
    page.onRequest(onRequest)
    page.onRequestFinished(onFinished)
    page.onRequestFailed(onFailed)

    // Listener lifecycle: onRequestFinished / onRequestFailed must remain registered
    // for the *entire* settle, including the response-drain loop. They are the only thing
    // that removes entries from `pending` — tearing them down after the 500ms grace would
    // leave any still-in-flight request marked pending forever, making the drain loop
    // hit its 5s ceiling on every action with slow XHR/fetch. The Microsoft reference
    // (backend/utils.ts) avoids the issue by calling `request.response()` per request in
    // a Promise.race; Playwright Java's sync API forces us to use listener-driven
    // completion tracking, so we just have to keep them alive longer.
    val settleStartMs = System.currentTimeMillis()
    try {
      // Nested try/finally: settle phase runs whether [action] returns normally or throws.
      // If action threw partway through a gesture (e.g. a follow-up assertion in the lambda),
      // the page may have already dispatched requests or started navigating — we still want
      // those to drain before propagating the exception so the next caller doesn't read a
      // half-loaded state. See [DriverDispatch] kdoc for the cross-driver exception contract.
      return try {
        action()
      } finally {
        delay(POST_ACTION_GRACE_MS)

        if (requests.any { it.isNavigationRequest }) {
          runCatching {
            page.mainFrame().waitForLoadState(
              LoadState.LOAD,
              Frame.WaitForLoadStateOptions().setTimeout(NAVIGATION_LOAD_TIMEOUT_MS),
            )
          }
          Console.log(
            "  [dispatchAndAwaitSettle] navigation settle (${System.currentTimeMillis() - settleStartMs}ms, " +
              "${requests.size} req)",
          )
        } else {
          val deadline = System.currentTimeMillis() + RESPONSE_DRAIN_TIMEOUT_MS
          var drainTimedOut = false
          while (System.currentTimeMillis() < deadline) {
            val anyRelevantPending = pending.keys.any { req ->
              RESPONSE_DRAIN_RESOURCE_TYPES.contains(req.resourceType())
            }
            if (!anyRelevantPending) break
            delay(POLL_INTERVAL_MS)
            if (System.currentTimeMillis() >= deadline) drainTimedOut = true
          }

          if (requests.isNotEmpty()) {
            delay(POST_ACTION_GRACE_MS)
          }

          val pendingTypes = pending.keys
            .map { it.resourceType() }
            .filter { RESPONSE_DRAIN_RESOURCE_TYPES.contains(it) }
          if (drainTimedOut) {
            Console.log(
              "  [dispatchAndAwaitSettle] drain TIMEOUT after ${RESPONSE_DRAIN_TIMEOUT_MS}ms — " +
                "${pendingTypes.size} requests still pending (types: ${pendingTypes.distinct()})",
            )
          } else {
            Console.log(
              "  [dispatchAndAwaitSettle] drained (${System.currentTimeMillis() - settleStartMs}ms, " +
                "${requests.size} req)",
            )
          }
        }
      }
    } finally {
      page.offRequest(onRequest)
      page.offRequestFinished(onFinished)
      page.offRequestFailed(onFailed)
    }
  }

  companion object {
    // ─── dispatchAndAwaitSettle (request-tracking settle, modeled on playwright-mcp) ───

    /** Grace period after an action, matching playwright-mcp's `tab.waitForTimeout(500)`. */
    internal const val POST_ACTION_GRACE_MS = 500L

    /** Upper bound on waiting for an action-triggered navigation to reach `load`. */
    internal const val NAVIGATION_LOAD_TIMEOUT_MS = 10_000.0

    /** Overall cap on draining action-triggered XHR/fetch/document requests. */
    internal const val RESPONSE_DRAIN_TIMEOUT_MS = 5_000L

    /** Polling interval while waiting for tracked requests to complete. */
    internal const val POLL_INTERVAL_MS = 50L

    /** Resource types we treat as load-blocking for the response-drain phase. */
    internal val RESPONSE_DRAIN_RESOURCE_TYPES =
      setOf("document", "stylesheet", "script", "xhr", "fetch")

    // ─── waitForPageReady (MutationObserver-based DOM-stability settle) ───
    // Used by `getScreenState()` to gate ARIA-snapshot capture on a quiet DOM. Public
    // so callers can override the default timeout per call.

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
}
