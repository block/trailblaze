package xyz.block.trailblaze.playwright

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import com.microsoft.playwright.Browser
import com.microsoft.playwright.BrowserType
import com.microsoft.playwright.Page
import com.microsoft.playwright.Playwright
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import org.junit.After
import org.junit.Before
import org.junit.Test
import xyz.block.trailblaze.api.DriverNodeMatch
import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.api.TrailblazeNodeSelector
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDeviceInfo
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.logs.client.TrailblazeLogger
import xyz.block.trailblaze.logs.client.TrailblazeSession
import xyz.block.trailblaze.logs.client.TrailblazeSessionProvider
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.playwright.tools.PlaywrightExecutableTool
import xyz.block.trailblaze.playwright.tools.PlaywrightNativeClickTool
import xyz.block.trailblaze.playwright.tools.PlaywrightNativeEvaluateTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.toolcalls.commands.BooleanAssertionTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.StringEvaluationTrailblazeTool
import xyz.block.trailblaze.utils.ElementComparator

/**
 * [PlaywrightExecutableTool.awaitsSettle] decides whether [PlaywrightTrailblazeAgent] runs a tool
 * inside the post-action settle and takes a pre-action screenshot for it. `web_evaluate` opts out,
 * because scripted tools poll the page with it and the settle costs at least 500ms per call.
 */
class PlaywrightToolSettleOptOutTest {

  private lateinit var playwright: Playwright
  private lateinit var browser: Browser
  private lateinit var page: Page
  private lateinit var manager: CountingPageManager

  @Before
  fun setUp() {
    playwright = Playwright.create()
    browser = playwright.chromium().launch(BrowserType.LaunchOptions().setHeadless(true))
    page = browser.newPage()
    page.setContent("<html><head><title>fixture</title></head><body></body></html>")
    manager = CountingPageManager(page)
  }

  @After
  fun tearDown() {
    browser.close()
    playwright.close()
  }

  @Test
  fun `web_evaluate runs its script without the settle or the pre-action screenshot`() = runBlocking {
    val result = buildAgent().runTrailblazeTools(
      tools = listOf(PlaywrightNativeEvaluateTool(script = "document.title")),
      elementComparator = noOpComparator,
    ).result

    assertThat(result).isInstanceOf(TrailblazeToolResult.Success::class)
    assertThat((result as TrailblazeToolResult.Success).message).isEqualTo("fixture")
    assertThat(manager.settles).isEqualTo(0)
    assertThat(manager.screenshots).isEqualTo(0)
  }

  /** The control: without it, the test above would pass against an agent that never settles. */
  @Test
  fun `a tool that keeps the default still settles and is screenshotted`() = runBlocking {
    val result = buildAgent().runTrailblazeTools(
      tools = listOf(DefaultSettlingTool),
      elementComparator = noOpComparator,
    ).result

    assertThat(result).isInstanceOf(TrailblazeToolResult.Success::class)
    assertThat(manager.settles).isEqualTo(1)
    assertThat(manager.screenshots).isEqualTo(1)
  }

  @Test
  fun `a scripted run skips the settle and the screenshot even for a tool that settles`() = runBlocking {
    val agent = buildAgent()
    val result = agent.runScripted {
      agent.runTrailblazeTools(tools = listOf(DefaultSettlingTool), elementComparator = noOpComparator).result
    }

    assertThat(result).isInstanceOf(TrailblazeToolResult.Success::class)
    assertThat(manager.settles).isEqualTo(0)
    assertThat(manager.screenshots).isEqualTo(0)
  }

  /** Recorded tools carry a selector, not a ref; the post-action capture only turns a ref into one. */
  @Test
  fun `a click by selector is captured before the action and not again after it`() = runBlocking {
    page.setContent("<html><body><button>Save</button></body></html>")
    val click = PlaywrightNativeClickTool(
      nodeSelector = TrailblazeNodeSelector(web = DriverNodeMatch.Web(ariaRole = "button", ariaNameRegex = "Save")),
    )

    val result = buildAgent().runTrailblazeTools(tools = listOf(click), elementComparator = noOpComparator).result

    assertThat(result).isInstanceOf(TrailblazeToolResult.Success::class)
    assertThat(manager.settles).isEqualTo(1)
    assertThat(manager.screenshots).isEqualTo(1)
  }

  private object DefaultSettlingTool : PlaywrightExecutableTool {
    override suspend fun executeWithPlaywright(
      page: Page,
      context: TrailblazeToolExecutionContext,
    ): TrailblazeToolResult = TrailblazeToolResult.Success()
  }

  /** Counts the two per-tool costs under test; the screenshot it hands back is the real page's. */
  private class CountingPageManager(override val currentPage: Page) : PlaywrightPageManager {
    var settles = 0
    var screenshots = 0
    override val playwrightDispatcher: CoroutineDispatcher = Dispatchers.Default
    override val idlingConfig: PlaywrightNativeIdlingConfig = PlaywrightNativeIdlingConfig()

    override suspend fun <R> dispatchAndAwaitSettle(action: suspend () -> R): R {
      settles++
      return action()
    }

    override fun captureScreenStateForLogging(): ScreenState {
      screenshots++
      error("the agent must tolerate a failed capture; the count is what this test reads")
    }

    override fun requestDetails(details: Set<ViewHierarchyDetail>) = Unit
    override fun getScreenState(): ScreenState = error("getScreenState is not under test")
    override fun waitForPageReady(domStabilityTimeoutMs: Double) = Unit
    override fun resetSession() = Unit
    override fun close() = Unit
  }

  private fun buildAgent(): PlaywrightTrailblazeAgent = PlaywrightTrailblazeAgent(
    browserManager = manager,
    trailblazeLogger = TrailblazeLogger.createNoOp(),
    trailblazeDeviceInfoProvider = {
      TrailblazeDeviceInfo(
        trailblazeDeviceId = TrailblazeDeviceId(
          instanceId = "fixture-browser",
          trailblazeDevicePlatform = TrailblazeDevicePlatform.WEB,
        ),
        trailblazeDriverType = TrailblazeDriverType.PLAYWRIGHT_NATIVE,
        widthPixels = 1280,
        heightPixels = 800,
      )
    },
    sessionProvider = TrailblazeSessionProvider {
      TrailblazeSession(sessionId = SessionId("fixture-session"), startTime = Clock.System.now())
    },
  )

  private val noOpComparator = object : ElementComparator {
    override fun getElementValue(prompt: String): String? = null
    override fun evaluateBoolean(statement: String) =
      BooleanAssertionTrailblazeTool(reason = statement, result = true)
    override fun evaluateString(query: String) =
      StringEvaluationTrailblazeTool(reason = query, result = "")
    override fun extractNumberFromString(input: String): Double? = null
  }
}
