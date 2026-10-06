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
import xyz.block.trailblaze.api.ScreenState
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
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Behavior tests for [PlaywrightNativeDragTool] against a real headless Chromium, following
 * [PlaywrightNativeResizeToolTest]: the drop actually happens on the happy path, and the
 * refusals are observed by the page NOT changing.
 *
 * The drop-target rule is the interesting one. A snapshot element ID (`e5`) is positional, and
 * unlike the drag source it is never rewritten into a durable selector on the way into a
 * recording — so it is refused, with the durable form named in the message.
 */
class PlaywrightNativeDragToolTest {

  private lateinit var playwright: Playwright
  private lateinit var browser: Browser
  private lateinit var page: Page

  @Before
  fun setUp() {
    playwright = Playwright.create()
    browser = playwright.chromium().launch(BrowserType.LaunchOptions().setHeadless(true))
    page = browser.newContext().newPage()
    page.setContent(
      """
      <html><body style="margin:0">
        <div id="source" draggable="true" style="width:60px;height:60px;background:#c00"></div>
        <button id="table1">Table 1</button>
        <div id="zone" style="width:200px;height:200px;background:#eee"></div>
        <script>
          const s = document.getElementById('source'), z = document.getElementById('zone');
          s.addEventListener('dragstart', e => e.dataTransfer.setData('text/plain', 'source'));
          z.addEventListener('dragover', e => e.preventDefault());
          z.addEventListener('drop', e => { e.preventDefault(); z.setAttribute('data-dropped', 'yes'); });
        </script>
      </body></html>
      """.trimIndent(),
    )
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
  fun `a css drop target drags and the page receives the drop`() = runBlocking {
    val tool = PlaywrightNativeDragTool(ref = "css=#source", dropRef = "css=#zone")
    val result = tool.executeWithPlaywright(page, buildContext(screenState = null))
    val success = assertIs<TrailblazeToolResult.Success>(result)
    assertNotNull(success.message, "Success should carry a human-readable message")
    assertEquals(
      "yes",
      page.locator("#zone").getAttribute("data-dropped"),
      "the drop handler on the target must have run",
    )
  }

  @Test
  fun `a snapshot element ID as the drop target is refused, and nothing is dropped`() = runBlocking {
    val screenState = PlaywrightScreenState(page, viewportWidth = 1280, viewportHeight = 800)
    // Whichever ID the snapshot gave the button, the refusal has to hand back that element's own
    // descriptor — reading it here rather than hard-coding one keeps the test honest if the
    // snapshot's numbering changes.
    val elementId = generateSequence(1) { it + 1 }.take(30)
      .map { "e$it" }
      .first { screenState.resolveElementId(it)?.descriptor?.contains("Table 1") == true }
    val descriptor = screenState.resolveElementId(elementId)!!.descriptor

    val tool = PlaywrightNativeDragTool(ref = "css=#source", dropRef = elementId)
    val result = tool.executeWithPlaywright(page, buildContext(screenState))
    val error = assertIs<TrailblazeToolResult.Error.ExceptionThrown>(result)
    assertTrue(
      error.errorMessage.contains("positional") && error.errorMessage.contains(descriptor),
      "the refusal should say why and name the durable form to use, got: ${error.errorMessage}",
    )
    assertNull(
      page.locator("#zone").getAttribute("data-dropped"),
      "nothing may be dragged when the drop target is refused",
    )
  }

  @Test
  fun `an element ID is refused even when no snapshot is available to name a replacement`() = runBlocking {
    val tool = PlaywrightNativeDragTool(ref = "css=#source", dropRef = "e5")
    val result = tool.executeWithPlaywright(page, buildContext(screenState = null))
    val error = assertIs<TrailblazeToolResult.Error.ExceptionThrown>(result)
    assertTrue(
      error.errorMessage.contains("css=") && error.errorMessage.contains("ARIA descriptor"),
      "with no snapshot to read, the refusal should still name the durable forms: ${error.errorMessage}",
    )
  }

  @Test
  fun `an ARIA descriptor drop target is not mistaken for an element ID`() = runBlocking {
    val tool = PlaywrightNativeDragTool(ref = "css=#source", dropRef = "button \"Table 1\"")
    val result = tool.executeWithPlaywright(page, buildContext(screenState = null))
    val success = assertIs<TrailblazeToolResult.Success>(
      result,
      "a descriptor that merely contains a letter and digits must still drag",
    )
    assertTrue(
      success.message!!.contains("Table 1"),
      "the drag should report the descriptor it dropped on, got: ${success.message}",
    )
  }

  @Test
  fun `a point drop lands on a page whose body has no height`() = runBlocking {
    // Every element absolutely positioned, as canvas-style editors lay out: <body> has zero
    // height, so nothing that waits for <body> to be visible can ever drop here. The doctype
    // matters — in quirks mode <body> stretches to the viewport and hides the problem.
    page.setContent(
      """
      <!DOCTYPE html>
      <html><body style="margin:0">
        <div id="source" draggable="true"
          style="position:absolute;left:0;top:0;width:60px;height:60px;background:#c00"></div>
        <div id="zone"
          style="position:absolute;left:300px;top:300px;width:200px;height:200px;background:#eee"></div>
        <script>
          // Scoped: this page already ran setUp's script, whose top-level consts are still declared.
          (() => {
            const s = document.getElementById('source'), z = document.getElementById('zone');
            s.addEventListener('dragstart', e => e.dataTransfer.setData('text/plain', 'source'));
            z.addEventListener('dragover', e => e.preventDefault());
            z.addEventListener('drop', e => { e.preventDefault(); z.setAttribute('data-dropped', 'yes'); });
          })();
        </script>
      </body></html>
      """.trimIndent(),
    )
    page.setDefaultTimeout(5_000.0)

    val tool = PlaywrightNativeDragTool(ref = "css=#source", x = 400, y = 400)
    val result = tool.executeWithPlaywright(page, buildContext(screenState = null))
    assertIs<TrailblazeToolResult.Success>(result, "the point drop should succeed, got: $result")
    assertEquals(
      "yes",
      page.locator("#zone").getAttribute("data-dropped"),
      "the drop zone under the point must have received the drop",
    )
  }

  @Test
  fun `a point drop refuses a covered source instead of pressing whatever covers it`() = runBlocking {
    page.evaluate(
      """
      () => {
        const cover = document.createElement('div');
        cover.id = 'cover';
        cover.style.cssText = 'position:absolute;left:0;top:0;width:100px;height:100px;z-index:10';
        document.body.appendChild(cover);
      }
      """.trimIndent(),
    )
    page.setDefaultTimeout(2_000.0)
    val zoneBox = page.locator("#zone").boundingBox()!!

    val tool = PlaywrightNativeDragTool(
      ref = "css=#source",
      x = (zoneBox.x + zoneBox.width / 2).toInt(),
      y = (zoneBox.y + zoneBox.height / 2).toInt(),
    )
    val result = tool.executeWithPlaywright(page, buildContext(screenState = null))
    assertIs<TrailblazeToolResult.Error.ExceptionThrown>(
      result,
      "a drag whose source is covered must fail, not report success, got: $result",
    )
    assertNull(
      page.locator("#zone").getAttribute("data-dropped"),
      "nothing may be dropped when the source could not be pressed",
    )
  }

  @Test
  fun `no drop target at all is an error naming both forms`() = runBlocking {
    val tool = PlaywrightNativeDragTool(ref = "css=#source")
    val result = tool.executeWithPlaywright(page, buildContext(screenState = null))
    val error = assertIs<TrailblazeToolResult.Error.ExceptionThrown>(result)
    assertTrue(
      error.errorMessage.contains("dropRef") && error.errorMessage.contains("x and y"),
      "the message should name both ways to give a drop target, got: ${error.errorMessage}",
    )
  }

  private fun buildContext(screenState: ScreenState?): TrailblazeToolExecutionContext =
    TrailblazeToolExecutionContext(
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
        TrailblazeSession(sessionId = SessionId("test-session"), startTime = Clock.System.now())
      },
      trailblazeLogger = TrailblazeLogger.createNoOp(),
      memory = AgentMemory(),
    )
}
