package xyz.block.trailblaze.playwright

import com.microsoft.playwright.Browser
import com.microsoft.playwright.BrowserType
import com.microsoft.playwright.Page
import com.microsoft.playwright.Playwright
import kotlinx.datetime.Clock
import org.junit.After
import org.junit.Before
import org.junit.Test
import xyz.block.trailblaze.AgentMemory
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDeviceInfo
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.logs.client.TrailblazeLogger
import xyz.block.trailblaze.logs.client.TrailblazeSession
import xyz.block.trailblaze.logs.client.TrailblazeSessionProvider
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.playwright.tools.PlaywrightExecutableTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Integration tests for [PlaywrightExecutableTool.Companion.validateAndResolveRef] and
 * [PlaywrightExecutableTool.Companion.resolveRef].
 *
 * Uses a real Chromium browser with inline HTML to verify element resolution
 * strategies: element IDs, CSS selectors, and ARIA descriptors.
 */
class PlaywrightToolRefResolutionTest {

  private lateinit var playwright: Playwright
  private lateinit var browser: Browser
  private lateinit var page: Page

  @Before
  fun setUp() {
    // Shrink the element-attached auto-wait so negative-path tests (e.g. "non-existent ref
    // returns error") don't pay the production 10s timeout — they're intentionally
    // resolving missing selectors and should error promptly.
    PlaywrightExecutableTool.elementResolutionTimeoutMs = 100.0

    playwright = Playwright.create()
    browser = playwright.chromium().launch(
      BrowserType.LaunchOptions().setHeadless(true),
    )
    val context = browser.newContext(
      Browser.NewContextOptions().setViewportSize(1280, 800),
    )
    page = context.newPage()
  }

  @After
  fun tearDown() {
    browser.close()
    playwright.close()
    PlaywrightExecutableTool.elementResolutionTimeoutMs = 10_000.0
  }

  private val testHtml = """
    <!DOCTYPE html>
    <html>
    <body>
      <nav aria-label="Main">
        <a href="#home">Home</a>
        <a href="#about">About</a>
      </nav>
      <main>
        <h1>Welcome</h1>
        <form>
          <label for="email">Email</label>
          <input id="email" type="text" aria-label="Email" />
          <button type="submit">Submit</button>
        </form>
      </main>
    </body>
    </html>
  """.trimIndent()

  private fun buildContext(screenState: PlaywrightScreenState): TrailblazeToolExecutionContext {
    return TrailblazeToolExecutionContext(
      screenState = screenState,
      traceId = null,
      trailblazeDeviceInfo = TrailblazeDeviceInfo(
        trailblazeDeviceId = TrailblazeDeviceId(
          instanceId = "test-browser",
          trailblazeDevicePlatform = TrailblazeDevicePlatform.WEB,
        ),
        trailblazeDriverType = TrailblazeDriverType.PLAYWRIGHT_NATIVE,
        widthPixels = 1280,
        heightPixels = 800,
      ),
      sessionProvider = TrailblazeSessionProvider {
        TrailblazeSession(
          sessionId = SessionId("test-session"),
          startTime = Clock.System.now(),
        )
      },
      trailblazeLogger = TrailblazeLogger.createNoOp(),
      memory = AgentMemory(),
    )
  }

  @Test
  fun `element ID ref resolves to correct element`() {
    page.setContent(testHtml)

    val screenState = PlaywrightScreenState(
      page = page,
      viewportWidth = 1280,
      viewportHeight = 800,
    )
    val context = buildContext(screenState)

    // Find the element ID for "Submit" button
    val submitEntry = screenState.elementIdMapping.entries.find {
      it.value.descriptor.contains("Submit")
    }
    assertNotNull(submitEntry, "Should find Submit in element mapping")

    val (locator, error) = PlaywrightExecutableTool.validateAndResolveRef(
      page, submitEntry.key, "Submit button", context,
    )
    assertNull(error)
    assertNotNull(locator)
    assertTrue(locator.count() > 0)
    assertContains(locator.first().textContent(), "Submit")
  }

  @Test
  fun `element ID ref with brackets resolves correctly`() {
    page.setContent(testHtml)

    val screenState = PlaywrightScreenState(
      page = page,
      viewportWidth = 1280,
      viewportHeight = 800,
    )
    val context = buildContext(screenState)

    val submitEntry = screenState.elementIdMapping.entries.find {
      it.value.descriptor.contains("Submit")
    }
    assertNotNull(submitEntry)

    // Use [eN] format with brackets
    val (locator, error) = PlaywrightExecutableTool.validateAndResolveRef(
      page, "[${submitEntry.key}]", "Submit button", context,
    )
    assertNull(error)
    assertNotNull(locator)
    assertTrue(locator.count() > 0)
  }

  @Test
  fun `CSS selector ref resolves via CSS`() {
    page.setContent(testHtml)

    val screenState = PlaywrightScreenState(
      page = page,
      viewportWidth = 1280,
      viewportHeight = 800,
    )
    val context = buildContext(screenState)

    val (locator, error) = PlaywrightExecutableTool.validateAndResolveRef(
      page, "css=#email", "Email input", context,
    )
    assertNull(error)
    assertNotNull(locator)
    assertTrue(locator.count() > 0)
  }

  @Test
  fun `ARIA descriptor ref resolves via getByRole`() {
    page.setContent(testHtml)

    val screenState = PlaywrightScreenState(
      page = page,
      viewportWidth = 1280,
      viewportHeight = 800,
    )
    val context = buildContext(screenState)

    val (locator, error) = PlaywrightExecutableTool.validateAndResolveRef(
      page, "textbox \"Email\"", "Email textbox", context,
    )
    assertNull(error)
    assertNotNull(locator)
    assertTrue(locator.count() > 0)
  }

  @Test
  fun `blank ref returns error result`() {
    page.setContent(testHtml)

    val screenState = PlaywrightScreenState(
      page = page,
      viewportWidth = 1280,
      viewportHeight = 800,
    )
    val context = buildContext(screenState)

    val (locator, error) = PlaywrightExecutableTool.validateAndResolveRef(
      page, "", "test element", context,
    )
    assertNull(locator)
    assertNotNull(error)
    assertIs<TrailblazeToolResult.Error.ExceptionThrown>(error)
    assertContains(error.errorMessage, "blank")
  }

  @Test
  fun `non-existent ref returns error mentioning playwright_snapshot`() {
    page.setContent(testHtml)

    val screenState = PlaywrightScreenState(
      page = page,
      viewportWidth = 1280,
      viewportHeight = 800,
    )
    val context = buildContext(screenState)

    val (locator, error) = PlaywrightExecutableTool.validateAndResolveRef(
      page, "css=#does-not-exist", "missing element", context,
    )
    assertNull(locator)
    assertNotNull(error)
    assertIs<TrailblazeToolResult.Error.ExceptionThrown>(error)
    assertContains(error.errorMessage, "web_snapshot")
  }

  @Test
  fun `data-testid selector resolves correctly`() {
    val html = """
      <!DOCTYPE html>
      <html>
      <body>
        <div data-testid="card-container">
          <span>Card Content</span>
        </div>
      </body>
      </html>
    """.trimIndent()
    page.setContent(html)

    val screenState = PlaywrightScreenState(
      page = page,
      viewportWidth = 1280,
      viewportHeight = 800,
    )
    val context = buildContext(screenState)

    val (locator, error) = PlaywrightExecutableTool.validateAndResolveRef(
      page, "css=[data-testid=\"card-container\"]", "card", context,
    )
    assertNull(error)
    assertNotNull(locator)
    assertEquals(1, locator.count())
  }

  @Test
  fun `data-test-id selector resolves correctly`() {
    val html = """
      <!DOCTYPE html>
      <html>
      <body>
        <div data-test-id="payment-form">
          <span>Payment Form</span>
        </div>
      </body>
      </html>
    """.trimIndent()
    page.setContent(html)

    val screenState = PlaywrightScreenState(
      page = page,
      viewportWidth = 1280,
      viewportHeight = 800,
    )
    val context = buildContext(screenState)

    val (locator, error) = PlaywrightExecutableTool.validateAndResolveRef(
      page, "css=[data-test-id=\"payment-form\"]", "payment form", context,
    )
    assertNull(error)
    assertNotNull(locator)
    assertEquals(1, locator.count())
  }

  /**
   * Positive-path coverage for the SPA-render race this PR was built to fix.
   *
   * Setup: initial HTML has NO matching element. A `setTimeout` scheduled via
   * `page.evaluate` inserts the target ~200ms after we begin resolution — mirroring
   * the real-world pattern where a navigation's `load` event fires, the next tool
   * issues a locator query, and the SPA's React/Vue/Svelte hydration finishes a
   * beat later.
   *
   * Without the element-attached auto-wait in `validateAndResolveRef`, this case
   * fails fast (locator.count() == 0 at call time → error). With auto-wait, the
   * locator's internal `waitFor(ATTACHED)` polls until the element attaches and
   * the call returns a usable locator. This test guards both halves: the call
   * succeeds AND demonstrably waited (didn't bail in <100ms).
   */
  @Test
  fun `auto-wait resolves element that appears mid-wait (SPA-render race)`() {
    // Override the 100ms @Before default — we need a budget large enough to absorb
    // the 200ms scheduled DOM insert plus locator-poll overhead.
    PlaywrightExecutableTool.elementResolutionTimeoutMs = 2000.0

    page.setContent(
      """<!DOCTYPE html><html><body><div id="empty"></div></body></html>""",
    )

    // Schedule the DOM mutation. `page.evaluate` returns once the script is dispatched;
    // the setTimeout callback fires ~200ms later, after we've already started waiting.
    page.evaluate(
      """() => setTimeout(() => {
        document.body.insertAdjacentHTML('beforeend', '<button id="late-button">Click me</button>');
      }, 200)""",
    )

    val screenState = PlaywrightScreenState(
      page = page,
      viewportWidth = 1280,
      viewportHeight = 800,
    )
    val context = buildContext(screenState)

    val startMs = System.currentTimeMillis()
    val (locator, error) = PlaywrightExecutableTool.validateAndResolveRef(
      page,
      "css=#late-button",
      "late-appearing button",
      context,
    )
    val elapsedMs = System.currentTimeMillis() - startMs

    // Resolution succeeded — auto-wait waited for the element to attach.
    assertNull(error, "Expected no error; element should have attached during the wait")
    assertNotNull(locator)
    assertEquals(1, locator.count())

    // We actually waited. Without the auto-wait, the call would have returned in
    // <50ms because at invocation time `#late-button` didn't exist in the DOM.
    assertTrue(
      elapsedMs >= 150,
      "Expected wait of at least ~150ms (element scheduled at +200ms); " +
        "got ${elapsedMs}ms — auto-wait may not be firing.",
    )

    // But we didn't sit on the full 2000ms ceiling — once the element attached,
    // the call returned promptly. A regression that no-ops the waitFor's "early
    // resolve on match" behavior would burn the full budget.
    assertTrue(
      elapsedMs < 1500,
      "Wait took ${elapsedMs}ms (budget was 2000ms) — auto-wait isn't resolving " +
        "promptly after the element attaches.",
    )
  }

  @Test
  fun `CSS escape - ID with special characters does not break selector`() {
    val html = """
      <!DOCTYPE html>
      <html>
      <body>
        <div id="item.price:total" class="value">$19.99</div>
      </body>
      </html>
    """.trimIndent()
    page.setContent(html)

    val screenState = PlaywrightScreenState(
      page = page,
      viewportWidth = 1280,
      viewportHeight = 800,
    )
    val context = buildContext(screenState)

    // Use CSS.escape-style selector to handle dots and colons in ID
    val (locator, error) = PlaywrightExecutableTool.validateAndResolveRef(
      page, "css=#item\\.price\\:total", "price element", context,
    )
    assertNull(error)
    assertNotNull(locator)
    assertEquals(1, locator.count())
  }
}
