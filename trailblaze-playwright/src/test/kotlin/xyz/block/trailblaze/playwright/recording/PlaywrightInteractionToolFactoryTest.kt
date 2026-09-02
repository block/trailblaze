package xyz.block.trailblaze.playwright.recording

import com.microsoft.playwright.Browser
import com.microsoft.playwright.BrowserType
import com.microsoft.playwright.Page
import com.microsoft.playwright.Playwright
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.playwright.PlaywrightNativeIdlingConfig
import xyz.block.trailblaze.playwright.PlaywrightPageManager
import xyz.block.trailblaze.playwright.ViewHierarchyDetail
import xyz.block.trailblaze.playwright.tools.PlaywrightExecutableTool
import xyz.block.trailblaze.playwright.tools.PlaywrightNativeClickTool
import xyz.block.trailblaze.playwright.tools.PlaywrightNativeTypeTool
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Behavioral tests for what the recorder writes into a trail when the user clicks or
 * types: the selector on the recorded tool IS the replay contract, so these assert on
 * the emitted tool, not on any intermediate resolution step.
 *
 * Uses a real Chromium page behind a minimal [PlaywrightPageManager] fake. Playwright
 * Java objects are thread-affine, so the fake pins one executor thread and creates the
 * browser on it — mirroring how the production managers pin their dispatcher.
 */
class PlaywrightInteractionToolFactoryTest {

  private lateinit var playwright: Playwright
  private lateinit var browser: Browser
  private lateinit var page: Page
  private val executor = Executors.newSingleThreadExecutor()
  private val dispatcher = executor.asCoroutineDispatcher()

  private val pageManager = object : PlaywrightPageManager {
    override val currentPage: Page get() = page
    override val playwrightDispatcher: CoroutineDispatcher get() = dispatcher
    override val idlingConfig: PlaywrightNativeIdlingConfig = PlaywrightNativeIdlingConfig()
    override fun requestDetails(details: Set<ViewHierarchyDetail>) = Unit
    override fun getScreenState(): ScreenState = error("unused in this test")
    override fun captureScreenStateForLogging(): ScreenState = error("unused in this test")
    override fun waitForPageReady(domStabilityTimeoutMs: Double) = Unit
    override fun resetSession() = Unit
    override fun close() = Unit
  }

  private lateinit var factory: PlaywrightInteractionToolFactory

  @Before
  fun setUp() {
    runBlocking(dispatcher) {
      playwright = Playwright.create()
      browser = playwright.chromium().launch(BrowserType.LaunchOptions().setHeadless(true))
      page = browser.newContext(
        Browser.NewContextOptions().setViewportSize(1280, 800),
      ).newPage()
    }
    factory = PlaywrightInteractionToolFactory(PlaywrightDeviceScreenStream(pageManager))
  }

  @After
  fun tearDown() {
    runBlocking(dispatcher) {
      browser.close()
      playwright.close()
    }
    executor.shutdown()
  }

  private fun setContent(html: String) = runBlocking(dispatcher) { page.setContent(html) }

  private fun focus(selector: String) = runBlocking(dispatcher) { page.focus(selector) }

  @Test
  fun `typed text is recorded against the focused field's durable selector`() {
    setContent(
      """
      <!DOCTYPE html>
      <html><body>
        <input data-testid="email-input" type="text" />
      </body></html>
      """.trimIndent(),
    )
    focus("[data-testid=email-input]")

    val (tool, name) = factory.createInputTextTool("user@example.com")

    assertEquals("web_type", name)
    val typeTool = tool as PlaywrightNativeTypeTool
    assertNull(
      typeTool.ref,
      "A durably-identified field must not be recorded via css=:focus.",
    )
    assertEquals(
      "email-input",
      typeTool.nodeSelector?.web?.dataTestId,
      "The recording must pin typing to the field that held focus at record time.",
    )
  }

  @Test
  fun `an auto-advancing field records the box the text went into, not the next one`() {
    // The OTP shape: each box hands focus to the next on input. Typed characters are
    // buffered and debounced, so by the time a tool is built the browser's activeElement
    // is the FOLLOWING box — replaying that selector types into the wrong field.
    setContent(
      """
      <!DOCTYPE html>
      <html><body>
        <input data-testid="otp-1" maxlength="1" />
        <input data-testid="otp-2" maxlength="1" />
      </body></html>
      """.trimIndent(),
    )
    focus("[data-testid=otp-1]")

    val target = factory.captureInputTarget()
    // The field's own input handler advances focus while the burst is still buffering.
    focus("[data-testid=otp-2]")

    val (tool, _) = factory.createInputTextTool("4", target)

    assertEquals(
      "otp-1",
      (tool as PlaywrightNativeTypeTool).nodeSelector?.web?.dataTestId,
      "The snapshot from the start of the burst decides the field, not live focus.",
    )
  }

  @Test
  fun `duplicate OTP boxes record the ordinal of the one that took the text`() {
    // Every identifier the focus snapshot reads is shared across these three boxes, and
    // replay narrows a multi-match locator with `.first()`. Without the ordinal, text typed
    // into box 3 replays into box 1 — a passing recording that fills the wrong field.
    setContent(
      """
      <!DOCTYPE html>
      <html><body>
        <input placeholder="Digit" maxlength="1" />
        <input placeholder="Digit" maxlength="1" />
        <input placeholder="Digit" maxlength="1" />
      </body></html>
      """.trimIndent(),
    )
    focus("input:nth-of-type(3)")

    val (tool, _) = factory.createInputTextTool("7", factory.captureInputTarget())

    val web = (tool as PlaywrightNativeTypeTool).nodeSelector?.web
    assertEquals("textbox", web?.ariaRole)
    assertEquals(2, web?.nthIndex, "The recorded selector must name WHICH duplicate got the text.")
  }

  @Test
  fun `a field named only by its wrapping label still records a durable selector`() {
    // The commonest sign-in markup in the wild: no id, no name, no placeholder, no
    // aria-label — the label element IS the name. Reading only name-bearing attributes left
    // this field unidentified and fell back to `css=:focus`, which replays into whatever
    // autofocus grabbed instead.
    setContent(
      """
      <!DOCTYPE html>
      <html><body>
        <label>Email <input type="text" /></label>
      </body></html>
      """.trimIndent(),
    )
    focus("input")

    val (tool, _) = factory.createInputTextTool("user@example.com", factory.captureInputTarget())

    val typeTool = tool as PlaywrightNativeTypeTool
    assertNull(typeTool.ref, "A label-named field must not degrade to css=:focus.")
    assertEquals("textbox", typeTool.nodeSelector?.web?.ariaRole)
    assertEquals("Email", typeTool.nodeSelector?.web?.ariaNameRegex)
  }

  @Test
  fun `a long label is recorded whole, because the name replays as an exact match`() {
    // Replay resolves a recorded ARIA name exactly, so a clipped name cannot match the control
    // it came from — the recording is unresolvable the moment it is written. Consent copy and
    // disclosure text routinely run past 80 characters, which is where the cap used to sit.
    val longLabel = "I agree to the terms of service, the privacy policy, " +
      "and to receive occasional product updates by email"
    setContent(
      """
      <!DOCTYPE html>
      <html><body>
        <label>$longLabel <input type="text" /></label>
      </body></html>
      """.trimIndent(),
    )
    focus("input")

    val (tool, _) = factory.createInputTextTool("yes", factory.captureInputTarget())

    val web = (tool as PlaywrightNativeTypeTool).nodeSelector?.web
    assertEquals(longLabel, web?.ariaNameRegex, "The recorded name must not be clipped.")
    // The real contract, not just the string: the selector has to resolve back to the field.
    val locator = runBlocking(dispatcher) {
      PlaywrightExecutableTool.nodeSelectorToReadinessLocator(
        page,
        tool.nodeSelector!!,
      )?.count()
    }
    assertEquals(1, locator, "A whole-name selector must resolve back to the field it named.")
  }

  @Test
  fun `a field named by aria-labelledby records that name`() {
    setContent(
      """
      <!DOCTYPE html>
      <html><body>
        <span id="pw-label">Password</span>
        <input type="text" aria-labelledby="pw-label" />
      </body></html>
      """.trimIndent(),
    )
    focus("input")

    val (tool, _) = factory.createInputTextTool("hunter2", factory.captureInputTarget())

    assertEquals("Password", (tool as PlaywrightNativeTypeTool).nodeSelector?.web?.ariaNameRegex)
  }

  @Test
  fun `a recorded field is never named by the text being typed into it`() {
    // The snapshot is taken mid-burst, so consulting `value` would bake the typed text into
    // the selector — a recording that only ever replays against its own first run.
    setContent(
      """
      <!DOCTYPE html>
      <html><body>
        <label>Email <input type="text" value="already-typed@example.com" /></label>
      </body></html>
      """.trimIndent(),
    )
    focus("input")

    val (tool, _) = factory.createInputTextTool("second@example.com", factory.captureInputTarget())

    assertEquals("Email", (tool as PlaywrightNativeTypeTool).nodeSelector?.web?.ariaNameRegex)
  }

  @Test
  fun `a lone field records no ordinal`() {
    // The disambiguator is noise when there's nothing to disambiguate, and `nth=0` in a
    // recorded selector reads as a deliberate pick rather than an absent one.
    setContent(
      """
      <!DOCTYPE html>
      <html><body><input placeholder="Digit" maxlength="1" /></body></html>
      """.trimIndent(),
    )
    focus("input")

    val (tool, _) = factory.createInputTextTool("7", factory.captureInputTarget())

    assertNull((tool as PlaywrightNativeTypeTool).nodeSelector?.web?.nthIndex)
  }

  @Test
  fun `a focused search field records its implicit role, not textbox`() {
    // Replay resolves a recorded ariaRole through `getByRole`, whose role match is EXACT.
    // `<input type="search">` is a `searchbox`, so recording it as `textbox` yields a
    // locator that times out on a page where the field is plainly present. Only
    // `placeholder` identifies this field, so the role+name branch is the one that fires.
    setContent(
      """
      <!DOCTYPE html>
      <html><body><input type="search" placeholder="Search" /></body></html>
      """.trimIndent(),
    )
    focus("input[type=search]")

    val (tool, _) = factory.createInputTextTool("coffee", factory.captureInputTarget())

    val web = (tool as PlaywrightNativeTypeTool).nodeSelector?.web
    assertEquals("searchbox", web?.ariaRole)
    assertEquals("Search", web?.ariaNameRegex)
  }

  @Test
  fun `a clicked number field records spinbutton, not textbox`() {
    // Same role table, the tap path. The recorder round-trip-verifies an ARIA selector
    // against the live page, so a wrong role also costs the click its verification.
    setContent(
      """
      <!DOCTYPE html>
      <html><body><input type="number" placeholder="Quantity" /></body></html>
      """.trimIndent(),
    )
    val box = runBlocking(dispatcher) { page.locator("input[type=number]").boundingBox() }

    val (tool, _) = factory.createTapTool(
      node = null,
      x = (box.x + box.width / 2).toInt(),
      y = (box.y + box.height / 2).toInt(),
    )

    assertEquals(
      "spinbutton",
      (tool as PlaywrightNativeClickTool).nodeSelector?.web?.ariaRole,
    )
  }

  @Test
  fun `typed text falls back to css=focus when the focused element has no identifiers`() {
    // Body-focused page: activeElement is <body>, which resolveFocusedElement rejects.
    setContent("""<!DOCTYPE html><html><body><p>Nothing focusable</p></body></html>""")

    val (tool, _) = factory.createInputTextTool("stray keystrokes")

    val typeTool = tool as PlaywrightNativeTypeTool
    assertEquals("css=:focus", typeTool.ref)
    assertNull(typeTool.nodeSelector)
  }

  @Test
  fun `tap with no usable identifier records no selector instead of html`() {
    // A bare div: no id, testid, role, label, or text — nothing in the ancestor chain
    // (capped before <body>) yields an identifier, so the recorded click must carry NO
    // selector. The old `cssSelector="html"` stand-in made replay silently click the
    // document element.
    setContent(
      """
      <!DOCTYPE html>
      <html><body>
        <div style="width:400px;height:400px;"></div>
      </body></html>
      """.trimIndent(),
    )

    val (tool, name) = factory.createTapTool(node = null, x = 200, y = 200, trailblazeNodeTree = null)

    assertEquals("web_click", name)
    val clickTool = tool as PlaywrightNativeClickTool
    assertNull(clickTool.ref)
    assertNull(
      clickTool.nodeSelector,
      "An unidentifiable click target must record no selector (loud replay failure), " +
        "not a document-element stand-in (silent wrong click).",
    )
  }

  @Test
  fun `tap on an identified element still records its selector`() {
    setContent(
      """
      <!DOCTYPE html>
      <html><body>
        <button data-testid="submit-order" style="position:absolute;left:100px;top:100px;width:200px;height:50px;">Submit</button>
      </body></html>
      """.trimIndent(),
    )

    val (tool, _) = factory.createTapTool(node = null, x = 200, y = 125, trailblazeNodeTree = null)

    val clickTool = tool as PlaywrightNativeClickTool
    assertNotNull(clickTool.nodeSelector, "An identified target must keep recording a selector.")
    assertEquals("submit-order", clickTool.nodeSelector?.web?.dataTestId)
  }
}
