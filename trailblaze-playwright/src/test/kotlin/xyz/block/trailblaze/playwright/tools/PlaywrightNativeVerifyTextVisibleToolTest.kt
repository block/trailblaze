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
import xyz.block.trailblaze.playwright.PlaywrightScreenState
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import kotlin.test.assertContains
import kotlin.test.assertIs

/**
 * `web_verifyTextVisible` on a page whose main thread is blocked. Playwright's `assertThat` makes
 * one check with no time limit before its 5s of retries, so without a bounded probe first the
 * verify waits out the whole block: 1,000s on one real page, after which it passed.
 */
class PlaywrightNativeVerifyTextVisibleToolTest {

  private lateinit var playwright: Playwright
  private lateinit var browser: Browser
  private lateinit var page: Page

  @Before
  fun setUp() {
    playwright = Playwright.create()
    browser = playwright.chromium().launch(BrowserType.LaunchOptions().setHeadless(true))
    page = browser.newContext(Browser.NewContextOptions().setViewportSize(1280, 800)).newPage()
  }

  @After
  fun tearDown() {
    browser.close()
    playwright.close()
  }

  private fun buildContext(): TrailblazeToolExecutionContext = TrailblazeToolExecutionContext(
    screenState = PlaywrightScreenState(page = page, viewportWidth = 1280, viewportHeight = 800),
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
      TrailblazeSession(sessionId = SessionId("test-session"), startTime = Clock.System.now())
    },
    trailblazeLogger = TrailblazeLogger.createNoOp(),
    memory = AgentMemory(),
  )

  @Test
  fun `a page whose main thread is blocked fails within the probe timeout instead of waiting it out`() {
    page.setContent("<p>Sign your debit card</p>")
    // Longer than the probe allows. The text is already visible, so the only way to fail is to not
    // wait for the block.
    blockMainThread(FREEZE_MS)

    val result = runBlocking {
      PlaywrightNativeVerifyTextVisibleTool(text = "Sign your debit card").executeWithPlaywright(page, buildContext())
    }

    // A verify that waited out the block would have found the text and passed.
    assertIs<TrailblazeToolResult.Error>(result)
    assertContains(result.errorMessage, "stopped responding")
  }

  @Test
  fun `a page that stalls briefly still passes once the stall ends`() {
    page.setContent("<p>Sign your debit card</p>")
    // Longer than the assertion's 5s, shorter than the probe: this passed before the probe existed.
    blockMainThread(STALL_MS)

    val result = runBlocking {
      PlaywrightNativeVerifyTextVisibleTool(text = "Sign your debit card").executeWithPlaywright(page, buildContext())
    }

    assertIs<TrailblazeToolResult.Success>(result)
  }

  @Test
  fun `a responsive page still verifies visible text`() {
    page.setContent("<p>Sign your debit card</p>")

    val result = runBlocking {
      PlaywrightNativeVerifyTextVisibleTool(text = "Sign your debit card").executeWithPlaywright(page, buildContext())
    }

    assertIs<TrailblazeToolResult.Success>(result)
  }

  /** Busy-loops the page's main thread for [ms], starting just after this call returns. */
  private fun blockMainThread(ms: Long) {
    page.evaluate("() => { setTimeout(() => { const end = Date.now() + $ms; while (Date.now() < end) {} }, 50) }")
    Thread.sleep(300)
  }

  private companion object {
    val FREEZE_MS = PlaywrightPageResponsiveness.PROBE_TIMEOUT_MS.toLong() + 15_000L
    const val STALL_MS = 7_000L
  }
}
