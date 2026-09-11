package xyz.block.trailblaze.playwright.tools

import com.microsoft.playwright.Browser
import com.microsoft.playwright.BrowserType
import com.microsoft.playwright.Page
import com.microsoft.playwright.Playwright
import com.microsoft.playwright.options.ViewportSize
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.Clock
import org.junit.After
import org.junit.Before
import org.junit.Test
import xyz.block.trailblaze.AgentMemory
import xyz.block.trailblaze.api.DriverNodeMatch
import xyz.block.trailblaze.api.TrailblazeNodeSelector
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
import kotlin.test.assertIs
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlaywrightNativeTextEvidenceToolsTest {
  private lateinit var playwright: Playwright
  private lateinit var browser: Browser
  private lateinit var page: Page

  @Before fun setUp() {
    playwright = Playwright.create()
    browser = playwright.chromium().launch(BrowserType.LaunchOptions().setHeadless(true))
    page = browser.newContext(Browser.NewContextOptions().setViewportSize(800, 600)).newPage()
  }

  @After fun tearDown() {
    browser.close()
    playwright.close()
  }

  private fun context() = TrailblazeToolExecutionContext(
    screenState = null,
    traceId = null,
    trailblazeDeviceInfo = TrailblazeDeviceInfo(
      trailblazeDeviceId = TrailblazeDeviceId("web-test", TrailblazeDevicePlatform.WEB),
      trailblazeDriverType = TrailblazeDriverType.PLAYWRIGHT_NATIVE,
      widthPixels = 800,
      heightPixels = 600,
    ),
    sessionProvider = TrailblazeSessionProvider {
      TrailblazeSession(SessionId("text-evidence-test"), Clock.System.now())
    },
    trailblazeLogger = TrailblazeLogger.createNoOp(),
    memory = AgentMemory(),
  )

  private val homeHeading = TrailblazeNodeSelector(
    web = DriverNodeMatch.Web(ariaRole = "heading", ariaNameRegex = "Home", headingLevel = 1),
  )

  @Test
  fun `viewport requirement scrolls exact visible text into the viewport`() = runBlocking {
    page.setContent("<main style='height:1400px'></main><h2>Exact proof</h2>")
    val result = PlaywrightNativeRequireTextInViewportTool("Exact proof", timeoutMs = 1_000)
      .executeWithPlaywright(page, context())
    assertIs<TrailblazeToolResult.Success>(result)
    assertTrue(page.evaluate("window.scrollY") as Int > 0)
  }

  @Test
  fun `viewport requirement works when the browser context has no fixed viewport`() = runBlocking {
    val nullViewportContext = browser.newContext(
      Browser.NewContextOptions().setViewportSize(null as ViewportSize?)
    )
    try {
      val nullViewportPage = nullViewportContext.newPage()
      assertNull(nullViewportPage.viewportSize())
      nullViewportPage.setContent("<main style='height:1400px'></main><h2>Exact proof</h2>")

      val result = PlaywrightNativeRequireTextInViewportTool("Exact proof", timeoutMs = 1_000)
        .executeWithPlaywright(nullViewportPage, context())

      assertIs<TrailblazeToolResult.Success>(result)
    } finally {
      nullViewportContext.close()
    }
    Unit
  }

  @Test
  fun `viewport requirement preserves a short caller timeout`() = runBlocking {
    page.setContent("<h1>Different text</h1>")
    val startedAt = System.nanoTime()

    val result = PlaywrightNativeRequireTextInViewportTool("Missing proof", timeoutMs = 25)
      .executeWithPlaywright(page, context())

    val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
    assertIs<TrailblazeToolResult.Error.ExceptionThrown>(result)
    assertTrue(elapsedMs < 500, "25ms timeout exceeded its bounded execution budget: ${elapsedMs}ms")
  }

  @Test
  fun `viewport requirement propagates coroutine cancellation promptly`() = runBlocking {
    page.setContent("<h1>Different text</h1>")
    val startedAt = System.nanoTime()

    assertFailsWith<TimeoutCancellationException> {
      withTimeout(50) {
        PlaywrightNativeRequireTextInViewportTool("Missing proof", timeoutMs = 30_000)
          .executeWithPlaywright(page, context())
      }
    }

    val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
    assertTrue(elapsedMs < 500, "Cancellation exceeded its bounded execution budget: ${elapsedMs}ms")
  }

  @Test
  fun `hidden matching text does not fail sustained absence`() = runBlocking {
    page.setContent("<h1>Home</h1><div hidden>Forbidden</div>")
    val result = PlaywrightNativeVerifyTextAbsentForDurationTool(
      text = "Forbidden",
      durationMs = 150,
      requiredVisibleNodeSelector = homeHeading,
    ).executeWithPlaywright(page, context())
    assertIs<TrailblazeToolResult.Success>(result, result.toString())
    Unit
  }

  @Test
  fun `visible forbidden text fails before the shortest observation starts`() = runBlocking {
    page.setContent("<h1>Home</h1><div>Forbidden</div>")
    val result = PlaywrightNativeVerifyTextAbsentForDurationTool(
      text = "Forbidden",
      durationMs = 1,
      requiredVisibleNodeSelector = homeHeading,
    ).executeWithPlaywright(page, context())
    assertIs<TrailblazeToolResult.Error.ExceptionThrown>(result)
    Unit
  }

  @Test
  fun `LLM-facing ref identifies the readiness element`() = runBlocking {
    page.setContent("<h1>Home</h1>")
    val result = PlaywrightNativeVerifyTextAbsentForDurationTool(
      text = "Forbidden",
      durationMs = 150,
      ref = "heading \"Home\"",
    ).executeWithPlaywright(page, context())
    assertIs<TrailblazeToolResult.Success>(result, result.toString())
    Unit
  }

  @Test
  fun `transient forbidden text fails before the terminal boundary`() = runBlocking {
    page.setContent("<h1>Home</h1><div id='target' hidden>Forbidden</div>")
    page.evaluate("setTimeout(() => document.querySelector('#target').hidden = false, 100)")
    val result = PlaywrightNativeVerifyTextAbsentForDurationTool(
      text = "Forbidden",
      durationMs = 800,
      requiredVisibleNodeSelector = homeHeading,
    ).executeWithPlaywright(page, context())
    assertIs<TrailblazeToolResult.Error.ExceptionThrown>(result)
    Unit
  }

  @Test
  fun `forbidden text appearing after the terminal boundary does not fail`() = runBlocking {
    page.setContent("<h1>Home</h1><div id='target' hidden>Forbidden</div>")
    page.evaluate(
      """
      setTimeout(() => {
        const stopBlockingAt = performance.now() + 250;
        while (performance.now() < stopBlockingAt) { /* hold the event loop across the deadline */ }
        document.querySelector('#target').hidden = false;
      }, 100)
      """.trimIndent()
    )
    val result = PlaywrightNativeVerifyTextAbsentForDurationTool(
      text = "Forbidden",
      durationMs = 200,
      requiredVisibleNodeSelector = homeHeading,
    ).executeWithPlaywright(page, context())
    assertIs<TrailblazeToolResult.Success>(result, result.toString())
    Unit
  }

  @Test
  fun `forbidden text flash shorter than the Kotlin polling interval fails`() = runBlocking {
    page.setContent("<h1>Home</h1><div id='target' hidden>Forbidden</div>")
    page.evaluate(
      """
      setTimeout(() => {
        const target = document.querySelector('#target');
        target.hidden = false;
        setTimeout(() => target.hidden = true, 20);
      }, 1_000)
      """.trimIndent()
    )
    val result = PlaywrightNativeVerifyTextAbsentForDurationTool(
      text = "Forbidden",
      durationMs = 1_500,
      requiredVisibleNodeSelector = homeHeading,
    ).executeWithPlaywright(page, context())
    assertIs<TrailblazeToolResult.Error.ExceptionThrown>(result)
    Unit
  }

  @Test
  fun `forbidden text flash inside an open shadow root fails`() = runBlocking {
    page.setContent("<h1>Home</h1><div id='host'></div>")
    page.evaluate(
      """
      const root = document.querySelector('#host').attachShadow({ mode: 'open' });
      root.innerHTML = '<div id="target" hidden>Forbidden</div>';
      setTimeout(() => {
        const target = root.querySelector('#target');
        target.hidden = false;
        setTimeout(() => target.hidden = true, 20);
      }, 5_000);
      """.trimIndent()
    )
    val result = PlaywrightNativeVerifyTextAbsentForDurationTool(
      text = "Forbidden",
      durationMs = 6_000,
      requiredVisibleNodeSelector = homeHeading,
    ).executeWithPlaywright(page, context())
    assertIs<TrailblazeToolResult.Error.ExceptionThrown>(result)
    Unit
  }

  @Test
  fun `forbidden text in a shadow root attached after observation starts fails`() = runBlocking {
    page.setContent("<h1>Home</h1><div id='host'></div>")
    page.evaluate(
      """
      setTimeout(() => {
        const root = document.querySelector('#host').attachShadow({ mode: 'open' });
        root.innerHTML = '<div>Forbidden</div>';
      }, 5_000);
      """.trimIndent()
    )
    val result = PlaywrightNativeVerifyTextAbsentForDurationTool(
      text = "Forbidden",
      durationMs = 6_000,
      requiredVisibleNodeSelector = homeHeading,
    ).executeWithPlaywright(page, context())
    assertIs<TrailblazeToolResult.Error.ExceptionThrown>(result)
    Unit
  }

  @Test
  fun `loss of readiness fails sustained absence`() = runBlocking {
    page.setContent("<h1 id='home'>Home</h1>")
    page.evaluate("setTimeout(() => document.querySelector('#home').hidden = true, 100)")
    val result = PlaywrightNativeVerifyTextAbsentForDurationTool(
      text = "Forbidden",
      durationMs = 800,
      requiredVisibleNodeSelector = homeHeading,
    ).executeWithPlaywright(page, context())
    assertIs<TrailblazeToolResult.Error.ExceptionThrown>(result)
    Unit
  }

  @Test
  fun `navigation that removes the browser observer fails sustained absence`() = runBlocking {
    page.setContent("<h1>Home</h1>")
    page.evaluate("setTimeout(() => location.reload(), 100)")
    val result = PlaywrightNativeVerifyTextAbsentForDurationTool(
      text = "Forbidden",
      durationMs = 800,
      requiredVisibleNodeSelector = homeHeading,
    ).executeWithPlaywright(page, context())
    assertIs<TrailblazeToolResult.Error.ExceptionThrown>(result)
    assertTrue(result.errorMessage.contains("observer was lost"), result.errorMessage)
    Unit
  }

  @Test
  fun `duration outside the bounded contract fails immediately`() = runBlocking {
    page.setContent("<h1>Home</h1>")
    val result = PlaywrightNativeVerifyTextAbsentForDurationTool(
      text = "Forbidden",
      durationMs = 30_001,
      requiredVisibleNodeSelector = homeHeading,
    ).executeWithPlaywright(page, context())
    assertIs<TrailblazeToolResult.Error.ExceptionThrown>(result)
    Unit
  }
}
