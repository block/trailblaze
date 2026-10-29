package xyz.block.trailblaze.playwright

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
import xyz.block.trailblaze.api.AgentDriverAction
import xyz.block.trailblaze.api.ScreenState
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
import xyz.block.trailblaze.playwright.tools.PlaywrightNativeDragTool
import xyz.block.trailblaze.toolcalls.commands.BooleanAssertionTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.StringEvaluationTrailblazeTool
import xyz.block.trailblaze.utils.ElementComparator
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * What a `web_drag` leaves behind for the report and the recording, against a real headless
 * Chromium.
 *
 * The drag overlay needs both endpoints. A drag onto an ELEMENT carries no x/y at all, so unless
 * the drop target's position is resolved for the log, the swipe is logged ending nowhere and the
 * report drops the overlay — the one step whose whole point is showing where something went.
 *
 * The last test covers the other half: which screen state the recorded selector is resolved from.
 */
class PlaywrightDragDriverLogTest {

  private lateinit var playwright: Playwright
  private lateinit var browser: Browser
  private lateinit var page: Page
  private val logs = mutableListOf<TrailblazeLog>()

  /** The real page, wrapped in the manager surface the agent drives it through. */
  private inner class RealPageManager : PlaywrightPageManager {
    /** How many times the agent captured a state FOR LOGGING — one pre-action, one post-action. */
    var loggingCaptures = 0
      private set

    override val currentPage: Page get() = page
    override val playwrightDispatcher: CoroutineDispatcher = Dispatchers.Default
    override val idlingConfig: PlaywrightNativeIdlingConfig = PlaywrightNativeIdlingConfig()
    override fun requestDetails(details: Set<ViewHierarchyDetail>) = Unit
    override fun getScreenState(): ScreenState = screenState()
    override fun captureScreenStateForLogging(): ScreenState {
      loggingCaptures++
      return screenState()
    }
    override fun waitForPageReady(domStabilityTimeoutMs: Double) = Unit
    override fun resetSession() = Unit
    override fun close() = Unit

    private fun screenState(): ScreenState =
      PlaywrightScreenState(page, viewportWidth = 1280, viewportHeight = 800)
  }

  private val noOpComparator = object : ElementComparator {
    override fun getElementValue(prompt: String): String? = null
    override fun evaluateBoolean(statement: String) =
      BooleanAssertionTrailblazeTool(reason = statement, result = true)

    override fun evaluateString(query: String) =
      StringEvaluationTrailblazeTool(reason = query, result = "")

    override fun extractNumberFromString(input: String): Double? = null
  }

  @Before
  fun setUp() {
    playwright = Playwright.create()
    browser = playwright.chromium().launch(BrowserType.LaunchOptions().setHeadless(true))
    page = browser.newContext().newPage()
    // Absolute positions so the expected drop point is arithmetic, not a guess.
    page.setContent(
      """
      <html><body style="margin:0">
        <div id="source" draggable="true"
             style="position:absolute;left:0;top:0;width:40px;height:40px;background:#c00"></div>
        <div id="zone"
             style="position:absolute;left:400px;top:200px;width:200px;height:100px;background:#eee"></div>
        <script>
          const s = document.getElementById('source'), z = document.getElementById('zone');
          s.addEventListener('dragstart', e => e.dataTransfer.setData('text/plain', 'source'));
          z.addEventListener('dragover', e => e.preventDefault());
          z.addEventListener('drop', e => e.preventDefault());
        </script>
      </body></html>
      """.trimIndent(),
    )
    logs.clear()
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
  fun `a drag onto an element logs where it was dropped`() {
    runDrag(PlaywrightNativeDragTool(ref = "css=#source", dropRef = "css=#zone"))

    val swipe = loggedSwipe()
    assertNotNull(swipe.endX, "a drag onto an element must log where it ended")
    assertNotNull(swipe.endY, "a drag onto an element must log where it ended")
    // The zone's center: left 400 + half of 200, top 200 + half of 100.
    assertEquals(500, swipe.endX, "the logged end point should be the drop target's center")
    assertEquals(250, swipe.endY, "the logged end point should be the drop target's center")
  }

  @Test
  fun `drop offsets move the logged end point with the drop`() {
    runDrag(
      PlaywrightNativeDragTool(ref = "css=#source", dropRef = "css=#zone", dropOffsetX = 10, dropOffsetY = 20),
    )

    val swipe = loggedSwipe()
    assertEquals(410, swipe.endX, "an offset drop should be logged where the release happens")
    assertEquals(220, swipe.endY, "an offset drop should be logged where the release happens")
  }

  @Test
  fun `a drag onto a viewport point still logs the point it was given`() {
    runDrag(PlaywrightNativeDragTool(ref = "css=#source", x = 640, y = 480))

    val swipe = loggedSwipe()
    assertEquals(640, swipe.endX)
    assertEquals(480, swipe.endY)
  }

  @Test
  fun `a drop target and a point together log the drop target, which is where the drag goes`() {
    runDrag(PlaywrightNativeDragTool(ref = "css=#source", dropRef = "css=#zone", x = 640, y = 480))

    val swipe = loggedSwipe()
    assertEquals(500, swipe.endX, "dropRef wins over x/y in the drag, so it must win in the log too")
    assertEquals(250, swipe.endY, "dropRef wins over x/y in the drag, so it must win in the log too")
  }

  @Test
  fun `a drag resolves its recorded selector from the page as it was before the drop`() {
    // A drag is how a list gets reordered, and refs are positional: resolving the source ref
    // against the page AFTER the drop names whatever slid into that slot, and the recording then
    // replays against a different element. So the drag must never take the post-action capture —
    // one capture, the pre-action one, is the whole assertion.
    val dragged = RealPageManager()
    runTool(PlaywrightNativeDragTool(ref = "css=#source", dropRef = "css=#zone"), dragged)
    assertEquals(
      1,
      dragged.loggingCaptures,
      "a drag must be enriched from the pre-action state only, so no second capture is taken",
    )

    // The control: without reordering, the post-action state is still preferred, because an
    // element may only exist once the tool completes.
    val clicked = RealPageManager()
    runTool(PlaywrightNativeClickTool(ref = "css=#zone"), clicked)
    assertEquals(
      2,
      clicked.loggingCaptures,
      "a tool that cannot renumber refs should still capture again after it ran",
    )
  }

  private fun runDrag(tool: PlaywrightNativeDragTool) = runTool(tool, RealPageManager())

  private fun runTool(tool: PlaywrightExecutableTool, manager: RealPageManager) {
    val agent = PlaywrightTrailblazeAgent(
      browserManager = manager,
      trailblazeLogger = TrailblazeLogger({ logs.add(it) }, { "screenshot.png" }),
      trailblazeDeviceInfoProvider = {
        TrailblazeDeviceInfo(
          trailblazeDeviceId = TrailblazeDeviceId(
            instanceId = "test-browser",
            trailblazeDevicePlatform = TrailblazeDevicePlatform.WEB,
          ),
          trailblazeDriverType = TrailblazeDriverType.PLAYWRIGHT_NATIVE,
          widthPixels = 1280,
          heightPixels = 800,
        )
      },
      sessionProvider = TrailblazeSessionProvider {
        TrailblazeSession(sessionId = SessionId("test-session"), startTime = Clock.System.now())
      },
      trailblazeToolRepo = null,
    )
    val outcome = runBlocking { agent.runTrailblazeTools(tools = listOf(tool), elementComparator = noOpComparator) }
    assertTrue(
      outcome.result is xyz.block.trailblaze.toolcalls.TrailblazeToolResult.Success,
      "the tool itself must succeed, otherwise the assertions prove nothing: ${outcome.result}",
    )
  }

  private fun loggedSwipe(): AgentDriverAction.Swipe {
    val actions = logs.filterIsInstance<TrailblazeLog.AgentDriverLog>().map { it.action }
    val swipe = actions.filterIsInstance<AgentDriverAction.Swipe>().firstOrNull()
    assertNotNull(swipe, "the drag should have logged a driver action, got: $actions")
    return swipe
  }
}
