package xyz.block.trailblaze.playwright

import com.microsoft.playwright.Browser
import com.microsoft.playwright.BrowserContext
import com.microsoft.playwright.BrowserType
import com.microsoft.playwright.Page
import com.microsoft.playwright.Playwright
import com.microsoft.playwright.options.WaitUntilState
import com.sun.net.httpserver.HttpServer
import java.util.Base64
import java.util.concurrent.Executors
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
 * Each test covers one path:
 *   - no requests → just the post-action grace window
 *   - navigation request → waitForLoadState(LOAD) path, proven by the state the page is left in
 *   - XHR/fetch tracked → drain-loop path
 *   - action throws → listeners cleaned up cleanly (no leaked observers)
 *
 * There are two navigation tests because they answer different questions. One asserts settle
 * returns rather than parking on a ceiling, and cannot see which branch ran. The other proves
 * the branch, and pays a real page-load stall to do it.
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
   * Action triggers a navigation request, so settle runs the `waitForLoadState(LOAD)` path.
   *
   * Verifies the navigation completed and settle returned. It does NOT verify which branch ran
   * — see the comment on the elapsed-time assertion for the measurement that rules that out.
   */
  @Test
  fun `navigation request - settle returns instead of sitting on a ceiling`() = runBlocking {
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
    // This bound does NOT prove which branch ran — forcing settle down the drain branch instead
    // returns in 513ms against the load branch's ~600ms, because a data-URL document finishes
    // inside the 500ms grace and leaves the drain loop nothing to wait for. What it does prove
    // is that settle returns at all, so a `waitForLoadState` that never resolves fails as a 3s
    // assertion rather than as a 10s ceiling. Keep it as containment; the branch itself is
    // covered by `settle waits for the load event, not just the tracked requests` below.
    assertTrue(
      elapsed < 3000,
      "Navigation settle took ${elapsed}ms — it should have returned, not sat on a load-state ceiling.",
    )
  }

  /**
   * The navigation branch really is the branch that ran — the thing the test above cannot show.
   *
   * No clock. The two branches differ in the state they leave the page in, which is the whole
   * point of the navigation branch: it exists so the next tool is not handed a half-loaded page.
   * So the assertion is that `document.readyState` has reached `complete` by the time settle
   * returns, which is exactly the guarantee, rather than a duration standing in for it.
   *
   * Making the branches diverge needs a page whose LOAD event lags everything settle tracks.
   * An image does that: the browser withholds the load event until it finishes, while
   * `RESPONSE_DRAIN_RESOURCE_TYPES` deliberately does not track images. The image therefore has
   * to outlast the drain branch's own worst case, or the drain branch sits long enough for the
   * image to finish anyway and both branches look alike again — which is what
   * [SLOW_IMAGE_DELAY_MS] is derived from, and why this test is slow. The alternative is an upper
   * bound on elapsed time around real browser work, the shape this file's other assertions were
   * just audited to remove.
   */
  @Test
  fun `navigation request - settle waits for the load event, not just the tracked requests`() = runBlocking {
    // The image has to finish INSIDE the load-state ceiling as well as outlast the drain
    // ceiling, and those two pull in opposite directions. Overshoot the top and
    // `waitForLoadState` times out; settle swallows that in a `runCatching` and returns with
    // the page still loading, so the assertion below fails with a message blaming the drain
    // branch for something the drain branch did not do. Fail here instead, where the reason is
    // legible: whoever widened the drain ceiling needs a different lever, not a bigger number.
    require(SLOW_IMAGE_DELAY_MS <= NAVIGATION_LOAD_CEILING_LIMIT_MS) {
      "SLOW_IMAGE_DELAY_MS is ${SLOW_IMAGE_DELAY_MS}ms, which no longer fits inside settle's " +
        "${PlaywrightPageManager.NAVIGATION_LOAD_TIMEOUT_MS.toLong()}ms navigation load ceiling " +
        "(limit ${NAVIGATION_LOAD_CEILING_LIMIT_MS.toLong()}ms). It is derived from " +
        "RESPONSE_DRAIN_TIMEOUT_MS, so raising that has squeezed this test out of the gap it " +
        "needs between the two ceilings. This test cannot discriminate the branches any more — " +
        "rework it rather than retuning the constant."
    }

    val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext("/slow-image.html") { exchange ->
      val body = "<html><body><h1>landed</h1><img src=\"/slow.png\"></body></html>".toByteArray()
      exchange.responseHeaders.set("Content-Type", "text/html")
      exchange.sendResponseHeaders(200, body.size.toLong())
      exchange.responseBody.use { it.write(body) }
    }
    server.createContext("/slow.png") { exchange ->
      Thread.sleep(SLOW_IMAGE_DELAY_MS)
      val body = Base64.getDecoder().decode(ONE_PIXEL_PNG_BASE64)
      exchange.responseHeaders.set("Content-Type", "image/png")
      exchange.sendResponseHeaders(200, body.size.toLong())
      exchange.responseBody.use { it.write(body) }
    }
    // Pool the handlers: on the default single-threaded executor the image's sleep blocks the
    // document response from completing, which keeps a tracked "document" entry pending and
    // parks the drain branch on its ceiling for reasons unrelated to what is being tested.
    // Daemon threads, so a handler still mid-sleep when the test ends cannot hold the JVM.
    val serverExecutor = Executors.newFixedThreadPool(2) { runnable ->
      Thread(runnable, "settle-test-http").apply { isDaemon = true }
    }
    server.executor = serverExecutor
    server.start()

    val readyStateAtReturn: Any?
    try {
      manager.dispatchAndAwaitSettle {
        page.navigate(
          "http://127.0.0.1:${server.address.port}/slow-image.html",
          // Must return BEFORE the load event, or the action absorbs the wait under test and
          // both branches look alike — Page.navigate defaults to waitUntil=LOAD.
          Page.NavigateOptions().setWaitUntil(WaitUntilState.COMMIT),
        )
      }
      readyStateAtReturn = page.evaluate("() => document.readyState")
    } finally {
      server.stop(0)
      // `stop` does not touch an executor the caller supplied, and these are non-daemon threads —
      // left running they can hold the Gradle test worker open after the test returns.
      serverExecutor.shutdown()
    }

    assertEquals("landed", page.locator("h1").textContent())
    assertEquals(
      "complete",
      readyStateAtReturn,
      "Settle returned with document.readyState='$readyStateAtReturn', so it did not wait for " +
        "the load event — it took the drain branch, which does not track the image still in " +
        "flight, and handed back a page that is still loading.",
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

/** Headroom over the drain branch's worst case, so scheduling jitter cannot blur the two paths. */
private const val SLOW_IMAGE_SAFETY_MARGIN_MS = 2_000L

/**
 * Slack left under settle's load ceiling, covering only browser overhead between the load event
 * firing and the wait observing it.
 */
private const val NAVIGATION_LOAD_HEADROOM_MS = 1_000L

/**
 * Most the image may stall and still let settle's load wait see the load event.
 *
 * [SLOW_IMAGE_DELAY_MS] is squeezed between two of settle's own ceilings: it has to exceed the
 * drain ceiling to discriminate the branches, and stay under the navigation load ceiling or the
 * load wait expires. Settle swallows that expiry in a `runCatching`, so overshooting produces a
 * test failure that reads like a drain-branch bug. The gap between the two ceilings is narrow,
 * and that is a real constraint of the design rather than a margin to spend — so the test
 * asserts it holds instead of assuming it.
 */
private const val NAVIGATION_LOAD_CEILING_LIMIT_MS =
  PlaywrightPageManager.NAVIGATION_LOAD_TIMEOUT_MS - NAVIGATION_LOAD_HEADROOM_MS

/**
 * How long the test's image endpoint stalls before answering.
 *
 * Derived, not chosen: it has to outlast the drain branch's worst case, which is the drain
 * ceiling plus the grace window on either side of it. Fall below that and the drain branch waits
 * long enough for the image to arrive anyway — `readyState` reaches `complete` on both branches,
 * and the test goes green while proving nothing. Deriving it means raising the drain ceiling
 * moves this with it instead of silently disarming the test.
 *
 * It is bounded from above too, by [NAVIGATION_LOAD_CEILING_LIMIT_MS] — the test checks that
 * before it does anything, because the two bounds leave only a narrow gap.
 *
 * This is what makes the test slow, and it is not a margin that can be trimmed.
 */
private const val SLOW_IMAGE_DELAY_MS =
  PlaywrightPageManager.RESPONSE_DRAIN_TIMEOUT_MS +
    (2 * PlaywrightPageManager.POST_ACTION_GRACE_MS) +
    SLOW_IMAGE_SAFETY_MARGIN_MS

/** Smallest valid PNG, so the image endpoint answers with something a browser will decode. */
private const val ONE_PIXEL_PNG_BASE64 =
  "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg=="
