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
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import xyz.block.trailblaze.api.DriverNodeMatch
import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.api.TrailblazeNodeSelector
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDeviceInfo
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.logs.client.TrailblazeLogger
import xyz.block.trailblaze.logs.client.TrailblazeSession
import xyz.block.trailblaze.logs.client.TrailblazeSessionProvider
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.playwright.tools.PlaywrightExecutableTool
import xyz.block.trailblaze.playwright.tools.PlaywrightNativeClickTool
import xyz.block.trailblaze.playwright.tools.PlaywrightNativeEvaluateTool
import xyz.block.trailblaze.playwright.tools.PlaywrightNativeFillSecretTool
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

  /** A recorded replay keeps the boxed tree per acting tool, and nothing for a poll. */
  @Test
  fun `a scripted run captures the tree before an acting tool and not before web_evaluate`() = runBlocking {
    val agent = buildAgent().apply { replayCapture = WebReplayCapture.TREE }
    agent.runScripted {
      agent.runTrailblazeTools(
        tools = listOf(DefaultSettlingTool, PlaywrightNativeEvaluateTool(script = "document.title")),
        elementComparator = noOpComparator,
      ).result
    }

    assertThat(manager.treeCaptures).isEqualTo(listOf(false))
    assertThat(manager.screenshots).isEqualTo(0)
  }

  @Test
  fun `a scripted run captures nothing when replay capture is off`() = runBlocking {
    val agent = buildAgent().apply { replayCapture = WebReplayCapture.OFF }
    agent.runScripted {
      agent.runTrailblazeTools(tools = listOf(DefaultSettlingTool), elementComparator = noOpComparator).result
    }

    assertThat(manager.treeCaptures).isEqualTo(emptyList())
  }

  /** A host reuses one agent across MCP sessions; a secret typed in one is not the next one's. */
  @Test
  fun `a filled secret is kept out of later captures in its own session only`() = runBlocking {
    page.setContent("<html><body><input aria-label=\"Code\"></body></html>")
    var session = "first"
    val agent = buildAgent { session }.apply { replayCapture = WebReplayCapture.TREE }
    val fill = PlaywrightNativeFillSecretTool(ref = "css=input", value = "otp-83716")
    agent.runScripted {
      agent.runTrailblazeTools(tools = listOf(fill, DefaultSettlingTool), elementComparator = noOpComparator).result
    }
    session = "second"
    agent.runScripted {
      agent.runTrailblazeTools(tools = listOf(DefaultSettlingTool), elementComparator = noOpComparator).result
    }

    assertThat(manager.treeCaptureSecrets).isEqualTo(listOf(emptySet(), setOf("otp-83716"), emptySet()))
  }

  /** The report takes a tree-only capture's picture from the video at its log's time. */
  @Test
  fun `a replay capture is logged at the moment its tree was read`() = runBlocking {
    val logs = mutableListOf<TrailblazeLog>()
    val states = mutableListOf<PlaywrightTreeScreenState>()
    manager.replayState = { withScreenshot, secrets ->
      PlaywrightTreeScreenState(page, 1280, 800, BrowserEngine.CHROMIUM, withScreenshot, secrets).also {
        states += it
        Thread.sleep(50) // a slow capture: the tool starts well after the tree was read
      }
    }
    val agent = buildAgent(logger = TrailblazeLogger(logEmitter = { logs += it }, screenStateLogger = { "" }))
      .apply { replayCapture = WebReplayCapture.TREE }
    agent.runScripted {
      agent.runTrailblazeTools(tools = listOf(DefaultSettlingTool), elementComparator = noOpComparator).result
    }

    val driverLog = logs.filterIsInstance<TrailblazeLog.AgentDriverLog>().single()
    assertThat(driverLog.timestamp).isEqualTo(states.single().capturedAt)
  }

  /**
   * The report shows a logged screenshot beside the session's video at the log's time. Playwright
   * waits for a busy page before it captures, so a screenshot asked for while the page is busy
   * shows the screen after the wait, not the one on screen when it was asked for.
   */
  @Test
  fun `a pre-action screenshot is logged at a moment the page showed it`() = runBlocking {
    page.setContent("<html><body style=\"background: rgb(255, 0, 0)\"></body></html>")
    val logs = mutableListOf<TrailblazeLog>()
    val states = mutableListOf<PlaywrightScreenState>()
    manager.loggingState = {
      page.evaluate(
        """() => {
          window.__shown = [[Date.now(), 'red']];
          setTimeout(() => {
            const end = Date.now() + 300;
            while (Date.now() < end) {}
            document.body.style.background = 'rgb(0, 255, 0)';
            window.__shown.push([Date.now(), 'green']);
          }, 0);
        }""",
      )
      Thread.sleep(50) // the page is now inside its busy task
      PlaywrightScreenState(page, 1280, 800, screenshotScalingConfig = null).also { states += it }
    }
    val agent = buildAgent(logger = TrailblazeLogger(logEmitter = { logs += it }, screenStateLogger = { "" }))
    agent.runTrailblazeTools(tools = listOf(DefaultSettlingTool), elementComparator = noOpComparator).result

    val loggedAtMs = logs.filterIsInstance<TrailblazeLog.AgentDriverLog>().single().timestamp.toEpochMilliseconds()
    @Suppress("UNCHECKED_CAST")
    val shown = page.evaluate("() => window.__shown") as List<List<Any>>
    val onScreenAtLog = shown.last { (it[0] as Number).toLong() <= loggedAtMs }[1]
    val pixel = ImageIO.read(ByteArrayInputStream(states.single().screenshotBytes)).getRGB(10, 10)
    val inScreenshot = if ((pixel shr 8 and 0xFF) > (pixel shr 16 and 0xFF)) "green" else "red"
    assertThat(onScreenAtLog).isEqualTo(inScreenshot)
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

    /** One entry per replay capture: whether it asked for a JPEG. */
    val treeCaptures = mutableListOf<Boolean>()

    /** The secrets each replay capture was asked to keep out. */
    val treeCaptureSecrets = mutableListOf<Set<String>>()
    override val playwrightDispatcher: CoroutineDispatcher = Dispatchers.Default
    override val idlingConfig: PlaywrightNativeIdlingConfig = PlaywrightNativeIdlingConfig()

    override suspend fun <R> dispatchAndAwaitSettle(action: suspend () -> R): R {
      settles++
      return action()
    }

    override fun captureScreenStateForLogging(): ScreenState {
      screenshots++
      return loggingState?.invoke()
        ?: error("the agent must tolerate a failed capture; the count is what this test reads")
    }

    /** What a logging capture hands back; a failed capture unless a test supplies one. */
    var loggingState: (() -> ScreenState)? = null

    override fun captureTreeForReplay(withScreenshot: Boolean, secrets: Set<String>): ScreenState? {
      treeCaptures += withScreenshot
      treeCaptureSecrets += secrets
      return replayState?.invoke(withScreenshot, secrets)
    }

    /** What a replay capture hands back; none unless a test supplies one. */
    var replayState: ((Boolean, Set<String>) -> ScreenState?)? = null

    override fun requestDetails(details: Set<ViewHierarchyDetail>) = Unit
    override fun getScreenState(): ScreenState = error("getScreenState is not under test")
    override fun waitForPageReady(domStabilityTimeoutMs: Double) = Unit
    override fun resetSession() = Unit
    override fun close() = Unit
  }

  private fun buildAgent(
    logger: TrailblazeLogger = TrailblazeLogger.createNoOp(),
    sessionId: () -> String = { "fixture-session" },
  ): PlaywrightTrailblazeAgent = PlaywrightTrailblazeAgent(
    browserManager = manager,
    trailblazeLogger = logger,
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
      TrailblazeSession(sessionId = SessionId(sessionId()), startTime = Clock.System.now())
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
