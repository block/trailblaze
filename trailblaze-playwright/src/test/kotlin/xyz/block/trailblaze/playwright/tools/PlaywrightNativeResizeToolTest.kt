package xyz.block.trailblaze.playwright.tools

import com.microsoft.playwright.Browser
import com.microsoft.playwright.BrowserType
import com.microsoft.playwright.Page
import com.microsoft.playwright.Playwright
import kotlinx.coroutines.runBlocking
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
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Behavior tests for [PlaywrightNativeResizeTool]. Drives a real headless
 * Chromium so the tool's contract — "after Success, [Page.viewportSize] reports
 * the new dimensions" — is exercised end-to-end, mirroring the
 * `PlaywrightToolRefResolutionTest` pattern (no Page mock, which would re-encode
 * Playwright's own internals).
 *
 * The validation-branch tests do NOT start a browser; they construct a context
 * with a non-null screenState and assert the tool short-circuits before touching
 * the page. We need only the page-touching subset of the context for the happy
 * path, so the input branch can use a context with a no-op `screenState`.
 */
class PlaywrightNativeResizeToolTest {

  private lateinit var playwright: Playwright
  private lateinit var browser: Browser
  private lateinit var page: Page

  @Before
  fun setUp() {
    playwright = Playwright.create()
    browser = playwright.chromium().launch(BrowserType.LaunchOptions().setHeadless(true))
    page = browser.newContext().newPage()
    page.setContent("<html><body><h1>hello</h1></body></html>")
  }

  @After
  fun tearDown() {
    try {
      browser.close()
    } finally {
      playwright.close()
    }
  }

  @Test
  fun `non-positive width returns Error without touching the page`() = runBlocking {
    val before = page.viewportSize()
    val tool = PlaywrightNativeResizeTool(width = 0, height = 600)
    val result = tool.executeWithPlaywright(page, buildContext())
    assertIs<TrailblazeToolResult.Error.ExceptionThrown>(result)
    assertTrue(
      result.errorMessage.contains("positive") && result.errorMessage.contains("0x600"),
      "error message should call out positive-width violation and include the bad input, got: ${result.errorMessage}",
    )
    // The page's viewport must remain unchanged because the tool short-circuits.
    assertEquals(before.width, page.viewportSize().width)
    assertEquals(before.height, page.viewportSize().height)
  }

  @Test
  fun `non-positive height returns Error without touching the page`() = runBlocking {
    val before = page.viewportSize()
    val tool = PlaywrightNativeResizeTool(width = 800, height = -1)
    val result = tool.executeWithPlaywright(page, buildContext())
    assertIs<TrailblazeToolResult.Error.ExceptionThrown>(result)
    assertTrue(
      result.errorMessage.contains("positive") && result.errorMessage.contains("800x-1"),
      "error message should call out positive-height violation: ${result.errorMessage}",
    )
    assertEquals(before.width, page.viewportSize().width)
    assertEquals(before.height, page.viewportSize().height)
  }

  @Test
  fun `happy path applies the new viewport and reports applied dimensions`() = runBlocking {
    val tool = PlaywrightNativeResizeTool(width = 414, height = 896)
    val result = tool.executeWithPlaywright(page, buildContext())
    val success = assertIs<TrailblazeToolResult.Success>(result)
    assertNotNull(success.message, "Success result should carry a human-readable message")
    assertTrue(
      success.message!!.contains("414x896"),
      "Success message should include the applied dimensions, got: ${success.message}",
    )
    val applied = page.viewportSize()
    assertEquals(414, applied.width, "page.viewportSize() must reflect the applied width")
    assertEquals(896, applied.height, "page.viewportSize() must reflect the applied height")
  }

  private fun buildContext(): TrailblazeToolExecutionContext {
    return TrailblazeToolExecutionContext(
      screenState = null,
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
}
