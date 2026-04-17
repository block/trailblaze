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
