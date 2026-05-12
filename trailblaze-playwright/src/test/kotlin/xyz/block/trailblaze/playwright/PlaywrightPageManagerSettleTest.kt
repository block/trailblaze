package xyz.block.trailblaze.playwright

import com.microsoft.playwright.Browser
import com.microsoft.playwright.BrowserContext
import com.microsoft.playwright.BrowserType
import com.microsoft.playwright.Page
import com.microsoft.playwright.Playwright
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import org.junit.After
import org.junit.Before
import org.junit.Test
import xyz.block.trailblaze.api.ScreenState
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Branch coverage for [PlaywrightPageManager.dispatchAndAwaitSettle] — the request-tracking
 * settle ported from microsoft/playwright `backend/utils.ts`.
 *
 * Each test exercises one path:
 *   - no requests → just the post-action grace window
 *   - navigation request → waitForLoadState(LOAD) path
 *   - XHR/fetch tracked → drain-loop path
 *   - action throws → listeners cleaned up cleanly (no leaked observers)
 *
 * Uses a live Chromium page with `page.route()` stubs to drive each scenario
 * deterministically. The minimal `StubPageManager` only implements what the
 * default-method `dispatchAndAwaitSettle` reads from the interface (`currentPage`);
 * everything else fails on use, so a regression that starts calling another
 * interface method would surface immediately.
 */
class PlaywrightPageManagerSettleTest {

  private lateinit var playwright: Playwright
  private lateinit var browser: Browser
  private lateinit var context: BrowserContext
  private lateinit var page: Page
  private lateinit var manager: PlaywrightPageManager

  @Before
  fun setUp() {
    playwright = Playwright.create()
    browser = playwright.chromium().launch(BrowserType.LaunchOptions().setHeadless(true))
    context = browser.newContext(Browser.NewContextOptions().setViewportSize(1280, 800))
    page = context.newPage()
    manager = StubPageManager(page)
  }

  @After
  fun tearDown() {
    context.close()
    browser.close()
    playwright.close()
  }

  /** No requests fired during the action → only the fixed post-action grace is paid. */
  @Test
  fun `no requests - exits after only the post-action grace window`() = runBlocking {
    page.setContent("<html><body><h1>static</h1></body></html>")

    val start = System.currentTimeMillis()
    val result = manager.dispatchAndAwaitSettle { "ok" }
    val elapsed = System.currentTimeMillis() - start

    assertEquals("ok", result)
    // POST_ACTION_GRACE_MS = 500. Allow generous lower bound for scheduling jitter
    // and tight upper bound — anything over ~1s means we're hitting an unrelated wait.
    assertTrue(
      elapsed in 400..1100,
      "Expected ~500ms grace, got ${elapsed}ms — settle is doing more than the grace.",
    )
  }

  /**
   * Action triggers a navigation request → settle takes the `waitForLoadState(LOAD)` path.
   * Verifies the navigation completes successfully and we return promptly (not at the
   * drain-loop ceiling or the navigation-load timeout ceiling).
   */
  @Test
  fun `navigation request - takes the load-state path, returns on load not drain`() = runBlocking {
    page.setContent("<html><body><h1>start</h1></body></html>")

    val start = System.currentTimeMillis()
    manager.dispatchAndAwaitSettle {
      // Direct `page.navigate(...)` to a data URL is the cleanest way to fire a real
      // navigation request that Playwright will both see (so settle picks the
      // navigation branch) and resolve quickly (so we don't depend on external network).
      page.navigate("data:text/html,<html><body><h1>landed</h1></body></html>")
    }
    val elapsed = System.currentTimeMillis() - start

    assertEquals("landed", page.locator("h1").textContent())
    // NAVIGATION_LOAD_TIMEOUT_MS = 10000; RESPONSE_DRAIN_TIMEOUT_MS = 5000. A data-URL
    // nav + one grace window should be well under either.
    assertTrue(
      elapsed < 3000,
      "Navigation settle took ${elapsed}ms — should land + return well under 3s.",
    )
  }

  /**
   * Action triggers an XHR → settle takes the drain path, not just the grace.
   *
   * We assert the LOWER bound (elapsed > grace + responseDelayMs) to prove the
   * drain loop engaged and held while the request was in flight. We do NOT assert
   * a tight upper bound because `requestFinished` semantics in a unit-test embedded
   * server differ from production XHR behavior — production XHRs cleanly fire
   * `requestFinished` and the drain exits promptly (this is what makes the actual
   * Square Web trail complete in ~30s, not 5+ minutes). The drain-ceiling path is
   * itself well-defined (`RESPONSE_DRAIN_TIMEOUT_MS = 5000`), so worst case is a
   * known bounded ceiling; "drain returns promptly when response finishes" is a
   * production property covered end-to-end by the trail runs.
   */
  @Test
  fun `xhr request - takes the drain path beyond the grace window`() = runBlocking {
    // Response delay must be longer than POST_ACTION_GRACE_MS (500ms) so the XHR is
    // still pending when the drain loop starts. Otherwise the XHR completes during
    // grace, `pending` is empty by the time drain runs, and we never exercise the
    // drain-wait code path we're trying to test.
    val responseDelayMs = 800L
    // Use a real embedded HTTP server rather than `page.route(...)` — Playwright's
    // route-handler runs on the dispatcher thread, so any synchronous delay blocks
    // request-event dispatch; an off-thread `route.fulfill()` doesn't fire
    // `requestFinished` reliably. A real server with a slow endpoint sidesteps both.
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext("/slow.json") { exchange ->
      Thread.sleep(responseDelayMs)
      val body = """{"ok":true}""".toByteArray()
      exchange.responseHeaders.set("Content-Type", "application/json")
      // about:blank → http://127.0.0.1 is cross-origin; without the CORS header the
      // browser blocks the response and `requestFinished` never fires.
      exchange.responseHeaders.set("Access-Control-Allow-Origin", "*")
      exchange.sendResponseHeaders(200, body.size.toLong())
      exchange.responseBody.use { it.write(body) }
    }
    server.start()
    val slowUrl = "http://127.0.0.1:${server.address.port}/slow.json"
    page.setContent(
      """<html><body><button id='go'
        onclick="fetch('$slowUrl').then(r=>r.json())">Go</button></body></html>""".trimIndent(),
    )

    val start = System.currentTimeMillis()
    try {
      manager.dispatchAndAwaitSettle {
        page.click("#go")
      }
    } finally {
      server.stop(0)
    }
    val elapsed = System.currentTimeMillis() - start

    // Lower bound: the drain loop engaged — elapsed exceeded just the grace window.
    // If drain had been skipped, we'd return at ~500ms (grace only). If drain engaged,
    // we waited at least past the response delay (the request was in-flight that long).
    assertTrue(
      elapsed > responseDelayMs,
      "XHR settle returned in ${elapsed}ms — drain loop didn't engage past the " +
        "${responseDelayMs}ms in-flight response.",
    )
  }

  /**
   * The action throws →
   * (a) the settle phase still runs before the exception propagates (DriverDispatch contract);
   * (b) listeners get cleaned up in `finally` so a subsequent call sees a clean manager.
   *
   * Regression guard for two related concerns:
   * - if someone moves the settle phase back into the inner try (pre-PR shape), the throw path
   *   would skip the post-action grace and the timing assertion fails;
   * - if the listener-teardown finally is ever made conditional on success, a second settle
   *   would observe stale `pending` entries from the prior failed action.
   */
  @Test
  fun `action throws - settle still runs and listeners are cleaned up`() = runBlocking {
    page.setContent("<html><body><h1>static</h1></body></html>")

    val firstStart = System.currentTimeMillis()
    try {
      manager.dispatchAndAwaitSettle<Unit> {
        error("boom")
      }
      fail("Expected the action's exception to propagate")
    } catch (e: IllegalStateException) {
      assertEquals("boom", e.message)
    }
    val firstElapsed = System.currentTimeMillis() - firstStart

    // (a) Settle-on-throw: the post-action grace delay must have run even though the action
    // threw, so the elapsed time on the throw path is at least POST_ACTION_GRACE_MS. If the
    // settle phase had been skipped (pre-PR shape), the throw would propagate ~immediately.
    assertTrue(
      firstElapsed >= PlaywrightPageManager.POST_ACTION_GRACE_MS - 50L,
      "First settle returned in ${firstElapsed}ms — settle phase appears to have been " +
        "skipped on the throw path (expected at least ${PlaywrightPageManager.POST_ACTION_GRACE_MS}ms).",
    )

    // (b) Listeners were cleaned up by the outer finally: a second settle on the same manager
    // runs cleanly without double-registered listeners or stale pending entries.
    val secondStart = System.currentTimeMillis()
    val result = manager.dispatchAndAwaitSettle { "second" }
    val secondElapsed = System.currentTimeMillis() - secondStart

    assertEquals("second", result)
    assertNotNull(result)
    assertTrue(secondElapsed < 1100, "Second settle took ${secondElapsed}ms — possible listener leak.")
  }

  /**
   * Minimal interface impl for testing the default-method `dispatchAndAwaitSettle` in
   * isolation. Only `currentPage` is read by the default method; everything else
   * throws so a regression that pulls another interface method into the settle path
   * surfaces immediately.
   */
  private class StubPageManager(override val currentPage: Page) : PlaywrightPageManager {
    override val playwrightDispatcher: CoroutineDispatcher = Dispatchers.Default
    override val idlingConfig: PlaywrightNativeIdlingConfig = PlaywrightNativeIdlingConfig()

    override fun requestDetails(details: Set<ViewHierarchyDetail>) {
      error("requestDetails should not be invoked from dispatchAndAwaitSettle")
    }

    override fun getScreenState(): ScreenState {
      error("getScreenState should not be invoked from dispatchAndAwaitSettle")
    }

    override fun captureScreenStateForLogging(): ScreenState {
      error("captureScreenStateForLogging should not be invoked from dispatchAndAwaitSettle")
    }

    override fun waitForPageReady(domStabilityTimeoutMs: Double) {
      error("waitForPageReady should not be invoked from dispatchAndAwaitSettle")
    }

    override fun resetSession() {
      error("resetSession should not be invoked from dispatchAndAwaitSettle")
    }

    override fun close() = Unit
  }
}
