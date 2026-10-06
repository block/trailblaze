package xyz.block.trailblaze.playwright

import com.microsoft.playwright.Browser
import com.microsoft.playwright.BrowserType
import com.microsoft.playwright.Page
import com.microsoft.playwright.Playwright
import com.microsoft.playwright.Route
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
import xyz.block.trailblaze.playwright.tools.PlaywrightNativeClickTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * An element a dialog covers must never be offered to the model as clickable: not in the
 * element list, not as a set-of-mark label, and a click on it must say what covers it.
 * A model shown a covered element's label on top of the dialog's field clicks it, times
 * out, and clicks it again until the run is killed.
 */
class PlaywrightCoveredElementTest {

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

  /**
   * A row checkbox and button under a dialog, with the dialog's text field on top of them. The
   * button's name is too long for either snapshot to print, which the page walk that
   * shortcuts the visibility pass can't match, so its verdict needs the button's own ref.
   */
  private val dialogOverRowHtml = """
    <!DOCTYPE html>
    <html>
    <body style="margin:0;">
      <input type="checkbox" id="row" style="position:absolute; left:100px; top:220px; width:30px; height:30px;">
      <button style="position:absolute; left:300px; top:220px; width:200px; height:30px;">${"Save ".repeat(200)}</button>
      <div role="dialog" aria-label="Create item"
           style="position:fixed; left:0; top:150px; width:800px; height:300px; background:#fff; z-index:10;">
        <input type="text" aria-label="Name" style="position:absolute; left:60px; top:60px; width:400px; height:40px;">
      </div>
    </body>
    </html>
  """.trimIndent()

  private fun refFor(state: PlaywrightScreenState, descriptor: String): String? =
    state.elementIdMapping.entries.firstOrNull { it.value.descriptor == descriptor }?.key

  @Test
  fun `a covered element stays hidden after a cross-site navigation`() {
    // After the main frame changes site, Playwright prefixes its refs (`f4e1`), which the
    // visibility pass must still read.
    page.route("**/*") { route ->
      val body = if (route.request().url().startsWith("https://b.test")) dialogOverRowHtml else "<button>Start</button>"
      route.fulfill(Route.FulfillOptions().setContentType("text/html").setBody(body))
    }
    page.navigate("https://a.test/")
    page.navigate("https://b.test/")

    val state = PlaywrightScreenState(page, 1280, 800)

    val text = state.viewHierarchyTextRepresentation!!
    assertFalse(text.lines().any { it.trim().endsWith("checkbox") }, "the covered checkbox is listed:\n$text")
    assertFalse(text.lines().any { it.trim().endsWith("button") }, "the covered button is listed:\n$text")
    assertContains(text, "occluded elements hidden")
    val labels = state.annotationElements.orEmpty().map { it.refLabel }
    val name = assertNotNull(refFor(state, "textbox \"Name\""), "the dialog's field is listed:\n$text")
    assertEquals(listOf(name), labels.filter { it == name }, "the dialog's field is labelled once")
    assertEquals(state.elementIdMapping.keys.filter { it in labels }.toSet(), labels.toSet())
  }

  @Test
  fun `a set-of-mark label is not painted on a covered element the visibility pass cannot place`() {
    // Neither snapshot prints a name this long, and AI mode gives no ref to an element that
    // takes no pointer events, so nothing ties this button to a ref and its label box comes
    // from the fallback lookup instead.
    val longName = "Save ".repeat(200)
    page.setContent(
      """
      <body style="margin:0;">
        <button style="position:absolute; left:100px; top:220px; width:200px; height:30px; pointer-events:none;">$longName</button>
        <div style="position:fixed; left:0; top:150px; width:800px; height:300px; background:#fff; z-index:10;"></div>
        <button style="position:absolute; left:100px; top:20px; width:200px; height:30px;">Open</button>
      </body>
      """.trimIndent(),
    )

    val state = PlaywrightScreenState(page, 1280, 800)

    val covered = assertNotNull(refFor(state, "button"), "${state.elementIdMapping}")
    val open = assertNotNull(refFor(state, "button \"Open\""), "${state.elementIdMapping}")
    val labels = state.annotationElements.orEmpty().map { it.refLabel }
    assertFalse(covered in labels, "the covered button is labelled: $labels")
    assertTrue(open in labels, "the uncovered button is labelled: $labels")
  }

  @Test
  fun `a nameless element ref resolves to the nameless element, not a named one of the same role`() {
    page.setContent(
      """
      <input type="checkbox" id="all" aria-label="Select all">
      <input type="checkbox" id="first">
      <input type="checkbox" id="second">
      """.trimIndent(),
    )
    val state = PlaywrightScreenState(page, 1280, 800)

    val nameless = state.elementIdMapping.filterValues { it.descriptor == "checkbox" }
    assertEquals(2, nameless.size, "${state.elementIdMapping}")
    assertEquals(
      listOf("first", "second"),
      nameless.values.map { PlaywrightAriaSnapshot.resolveElementRef(page, it).getAttribute("id") },
    )
  }

  @Test
  fun `a click on a covered element fails naming the dialog that covers it`() = kotlinx.coroutines.runBlocking {
    page.setContent(dialogOverRowHtml)
    page.setDefaultTimeout(1_000.0)
    val state = PlaywrightScreenState(page, 1280, 800, requestedDetails = setOf(ViewHierarchyDetail.OCCLUDED_ELEMENTS))
    val row = assertNotNull(refFor(state, "checkbox"), "${state.elementIdMapping}")

    val result = PlaywrightNativeClickTool(ref = row).executeWithPlaywright(page, buildContext(state))

    assertIs<TrailblazeToolResult.Error.ExceptionThrown>(result)
    assertContains(result.errorMessage, "timed out because it is covered")
    assertContains(result.errorMessage, "inside <div role=\"dialog\" aria-label=\"Create item\">")
    assertTrue(result.errorMessage.length < 400, "the reason is not buried in Playwright's retry log: ${result.errorMessage}")
  }

  @Test
  fun `a click on a disabled element fails saying it is disabled`() = kotlinx.coroutines.runBlocking {
    page.setContent("<button disabled>Save</button>")
    page.setDefaultTimeout(1_000.0)
    val state = PlaywrightScreenState(page, 1280, 800)
    val save = assertNotNull(refFor(state, "button \"Save\" [disabled]") ?: refFor(state, "button \"Save\""), "${state.elementIdMapping}")

    val result = PlaywrightNativeClickTool(ref = save).executeWithPlaywright(page, buildContext(state))

    assertIs<TrailblazeToolResult.Error.ExceptionThrown>(result)
    assertContains(result.errorMessage, "timed out because it is disabled")
  }

  @Test
  fun `a click whose page never finishes loading names the page it was loading`() = kotlinx.coroutines.runBlocking {
    // Playwright waits for a navigation the click starts, so it times out after the click.
    page.route("**/*") { route ->
      if (route.request().url().endsWith("/next")) return@route // never answered
      route.fulfill(Route.FulfillOptions().setContentType("text/html").setBody("<a href='https://a.test/next'>Next</a>"))
    }
    page.navigate("https://a.test/")
    page.setDefaultTimeout(1_000.0)
    val state = PlaywrightScreenState(page, 1280, 800)
    val next = assertNotNull(refFor(state, "link \"Next\""), "${state.elementIdMapping}")

    val result = PlaywrightNativeClickTool(ref = next).executeWithPlaywright(page, buildContext(state))

    assertIs<TrailblazeToolResult.Error.ExceptionThrown>(result)
    assertContains(result.errorMessage, "while the page was loading https://a.test/next")
    assertFalse("timed out because" in result.errorMessage, result.errorMessage)
  }

  @Test
  fun `an iframe reloading itself during a blocked click does not hide what covers the target`() =
    kotlinx.coroutines.runBlocking {
      page.route("**/*") { route ->
        val body = if (route.request().url().contains("ticker")) {
          "<meta http-equiv='refresh' content='0.1'>tick"
        } else {
          dialogOverRowHtml.replace("</body>", "<iframe src='https://a.test/ticker'></iframe></body>")
        }
        route.fulfill(Route.FulfillOptions().setContentType("text/html").setBody(body))
      }
      page.navigate("https://a.test/")
      page.setDefaultTimeout(1_000.0)
      val state = PlaywrightScreenState(page, 1280, 800, requestedDetails = setOf(ViewHierarchyDetail.OCCLUDED_ELEMENTS))
      val row = assertNotNull(refFor(state, "checkbox"), "${state.elementIdMapping}")

      val result = PlaywrightNativeClickTool(ref = row).executeWithPlaywright(page, buildContext(state))

      assertIs<TrailblazeToolResult.Error.ExceptionThrown>(result)
      assertContains(result.errorMessage, "timed out because it is covered")
    }

  private fun buildContext(screenState: PlaywrightScreenState) = TrailblazeToolExecutionContext(
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
