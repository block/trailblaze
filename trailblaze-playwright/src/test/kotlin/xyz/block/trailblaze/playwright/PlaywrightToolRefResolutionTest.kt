package xyz.block.trailblaze.playwright

import com.microsoft.playwright.Browser
import com.microsoft.playwright.BrowserType
import com.microsoft.playwright.Locator
import com.microsoft.playwright.Page
import com.microsoft.playwright.Playwright
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
import xyz.block.trailblaze.playwright.tools.PlaywrightExecutableTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.util.escapeForSelector
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
    // Shrink the element-attached auto-wait so negative-path tests (e.g. "non-existent ref
    // returns error") don't pay the production 10s timeout — they're intentionally
    // resolving missing selectors and should error promptly.
    PlaywrightExecutableTool.elementResolutionTimeoutMs = 100.0

    playwright = Playwright.create()
    PlaywrightTextRuns.register(playwright)
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
    PlaywrightExecutableTool.elementResolutionTimeoutMs = PRODUCTION_RESOLUTION_BUDGET_MS
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
    assertContains(assertNotNull(locator.first().textContent()), "Submit")
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
  fun `non-existent ref returns error pointing at the current element list`() {
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
    assertContains(error.errorMessage, "current element list")
  }

  @Test
  fun `a ref whose element changed role names the element it became`() {
    page.setContent(
      """<html><body><input type="search" aria-label="Search Wikipedia" /></body></html>""",
    )
    val screenState = PlaywrightScreenState(page = page, viewportWidth = 1280, viewportHeight = 800)
    val context = buildContext(screenState)
    val ref = assertNotNull(
      screenState.elementIdMapping.entries.find { it.value.descriptor == "searchbox \"Search Wikipedia\"" },
      "snapshot should list the searchbox: ${screenState.elementIdMapping}",
    ).key

    // Focusing a search field often turns it into a combobox (autocomplete).
    page.evaluate("document.querySelector('input').setAttribute('role', 'combobox')")

    val (locator, error) = PlaywrightExecutableTool.validateAndResolveRef(page, ref, "search field", context)
    assertNull(locator)
    assertIs<TrailblazeToolResult.Error.ExceptionThrown>(error)
    assertContains(error.errorMessage, "combobox \"Search Wikipedia\"")
    assertContains(error.errorMessage, "current element list")
  }

  @Test
  fun `a descriptor ref whose element changed role names the element it became`() {
    page.setContent(
      """<html><body><input type="search" aria-label="Search Wikipedia" /></body></html>""",
    )
    val screenState = PlaywrightScreenState(page = page, viewportWidth = 1280, viewportHeight = 800)
    page.evaluate("document.querySelector('input').setAttribute('role', 'combobox')")

    // The snapshot is taken after the change, as when a recorded ref replays on a newer page.
    val (_, error) = PlaywrightExecutableTool.validateAndResolveRef(
      page, "searchbox \"Search Wikipedia\"", "search field", buildContext(screenState),
    )
    assertIs<TrailblazeToolResult.Error.ExceptionThrown>(error)
    assertContains(error.errorMessage, "combobox \"Search Wikipedia\"")
    // A descriptor ref has no snapshot of its own to compare against, so no causal claim.
    assertFalse(error.errorMessage.contains("role changed"), error.errorMessage)
  }

  @Test
  fun `a same-named element that was already on the page is not called a role change`() {
    page.setContent(
      """<html><body><a href="#">Sign in</a><button>Sign in</button></body></html>""",
    )
    val screenState = PlaywrightScreenState(page = page, viewportWidth = 1280, viewportHeight = 800)
    val context = buildContext(screenState)
    val ref = assertNotNull(
      screenState.elementIdMapping.entries.find { it.value.descriptor == "button \"Sign in\"" },
      "snapshot should list the button: ${screenState.elementIdMapping}",
    ).key

    page.evaluate("document.querySelector('button').remove()")

    val (locator, error) = PlaywrightExecutableTool.validateAndResolveRef(page, ref, "sign in", context)
    assertNull(locator)
    assertIs<TrailblazeToolResult.Error.ExceptionThrown>(error)
    assertFalse(error.errorMessage.contains("role changed"), error.errorMessage)
  }

  @Test
  fun `a miss the caller will discard skips the role-change diagnosis`() {
    page.setContent(
      """<html><body><input type="search" aria-label="Search Wikipedia" /></body></html>""",
    )
    val screenState = PlaywrightScreenState(page = page, viewportWidth = 1280, viewportHeight = 800)
    val context = buildContext(screenState)
    val ref = screenState.elementIdMapping.entries.first { it.value.descriptor.startsWith("searchbox") }.key
    page.evaluate("document.querySelector('input').setAttribute('role', 'combobox')")

    val (_, error) =
      PlaywrightExecutableTool.validateAndResolveRef(page, ref, "search field", context, explainMiss = false)
    assertIs<TrailblazeToolResult.Error.ExceptionThrown>(error)
    assertFalse(error.errorMessage.contains("combobox"), error.errorMessage)
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

  /**
   * Positive-path coverage for the SPA-render race this PR was built to fix.
   *
   * Setup: initial HTML has NO matching element. A `setTimeout` scheduled via
   * `page.evaluate` inserts the target ~200ms after we begin resolution — mirroring
   * the real-world pattern where a navigation's `load` event fires, the next tool
   * issues a locator query, and the SPA's React/Vue/Svelte hydration finishes a
   * beat later.
   *
   * Without the element-attached auto-wait in `validateAndResolveRef`, this case
   * fails fast (locator.count() == 0 at call time → error). With auto-wait, the
   * locator's internal `waitFor(ATTACHED)` polls until the element attaches and
   * the call returns a usable locator. This test guards both halves: the call
   * succeeds AND demonstrably waited (didn't bail in <100ms).
   */
  @Test
  fun `auto-wait resolves element that appears mid-wait (SPA-render race)`() {
    // Override the 100ms @Before default and run on the production budget. The budget is
    // also what a broken early-resolve would burn, so making it large is what puts the two
    // outcomes seconds apart instead of a few hundred milliseconds — see
    // [WELL_UNDER_THE_RESOLUTION_BUDGET_MS]. Restored by @After.
    PlaywrightExecutableTool.elementResolutionTimeoutMs = PRODUCTION_RESOLUTION_BUDGET_MS

    page.setContent(
      """<!DOCTYPE html><html><body><div id="empty"></div></body></html>""",
    )

    // Schedule the DOM mutation. `page.evaluate` returns once the script is dispatched;
    // the setTimeout callback fires ~200ms later, after we've already started waiting.
    page.evaluate(
      """() => setTimeout(() => {
        document.body.insertAdjacentHTML('beforeend', '<button id="late-button">Click me</button>');
      }, 200)""",
    )

    val screenState = PlaywrightScreenState(
      page = page,
      viewportWidth = 1280,
      viewportHeight = 800,
    )
    val context = buildContext(screenState)

    val startMs = System.currentTimeMillis()
    val (locator, error) = PlaywrightExecutableTool.validateAndResolveRef(
      page,
      "css=#late-button",
      "late-appearing button",
      context,
    )
    val elapsedMs = System.currentTimeMillis() - startMs

    // Resolution succeeded — auto-wait waited for the element to attach.
    assertNull(error, "Expected no error; element should have attached during the wait")
    assertNotNull(locator)
    assertEquals(1, locator.count())

    // We actually waited. Without the auto-wait, the call would have returned in
    // <50ms because at invocation time `#late-button` didn't exist in the DOM.
    assertTrue(
      elapsedMs >= 150,
      "Expected wait of at least ~150ms (element scheduled at +200ms); " +
        "got ${elapsedMs}ms — auto-wait may not be firing.",
    )

    // But we didn't sit on the full ceiling — once the element attached, the call returned.
    assertTrue(
      elapsedMs < WELL_UNDER_THE_RESOLUTION_BUDGET_MS,
      "Wait took ${elapsedMs}ms of a ${PRODUCTION_RESOLUTION_BUDGET_MS.toLong()}ms budget — " +
        "auto-wait isn't resolving promptly after the element attaches.",
    )
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

  /**
   * A ref and a nodeSelector that resolve to *different* elements are OR-ed into one
   * locator, so the union matches both. Acting on a plural locator (`fill`, `hover`,
   * `selectOption`) throws Playwright's strict-mode error, which is what `web_type` and
   * friends used to do whenever a recording carried both signals and the page had
   * drifted. Resolution must hand back a single element.
   */
  @Test
  fun `resolved locator is narrowed to one element when ref and nodeSelector diverge`() {
    page.setContent(
      """
      <!DOCTYPE html>
      <html><body>
        <input id="first" data-testid="search-box" type="text" />
        <input id="second" type="text" />
      </body></html>
      """.trimIndent(),
    )

    val screenState = PlaywrightScreenState(
      page = page,
      viewportWidth = 1280,
      viewportHeight = 800,
    )
    val context = buildContext(screenState)

    val (locator, error) = PlaywrightExecutableTool.validateAndResolveRef(
      page,
      "css=#second",
      "search input",
      context,
      TrailblazeNodeSelector(web = DriverNodeMatch.Web(dataTestId = "search-box")),
    )

    assertNull(error)
    assertNotNull(locator)
    assertEquals(
      1,
      locator.count(),
      "Union of two different elements must be narrowed to one before it reaches a tool.",
    )

    // The real payoff: an acting call must not throw strict mode. `fill` is what
    // web_type does, and it is the call that used to blow up here.
    locator.fill("hello")
  }

  /**
   * A single nodeSelector matching several nodes is the other half of the same
   * contract — no ref involved, so this fails even without a union.
   */
  @Test
  fun `resolved locator is narrowed when the nodeSelector alone matches many nodes`() {
    page.setContent(
      """
      <!DOCTYPE html>
      <html><body>
        <button data-testid="row-action">One</button>
        <button data-testid="row-action">Two</button>
        <button data-testid="row-action">Three</button>
      </body></html>
      """.trimIndent(),
    )

    val screenState = PlaywrightScreenState(
      page = page,
      viewportWidth = 1280,
      viewportHeight = 800,
    )
    val context = buildContext(screenState)

    val (locator, error) = PlaywrightExecutableTool.validateAndResolveRef(
      page,
      null,
      "row action",
      context,
      TrailblazeNodeSelector(web = DriverNodeMatch.Web(dataTestId = "row-action")),
    )

    assertNull(error)
    assertNotNull(locator)
    assertEquals(1, locator.count())
    assertEquals("One", locator.textContent())
  }

  /**
   * Durability ordering: a recording that captured both a `data-testid` and a CSS
   * selector must replay against the testid. The CSS selector here still matches a
   * real (wrong) element, so a regression that re-prefers CSS resolves successfully
   * against the wrong node rather than failing loudly — exactly the silent-retarget
   * class of replay bug this ordering exists to prevent.
   */
  @Test
  fun `nodeSelector prefers data-testid over a stale css selector`() {
    page.setContent(
      """
      <!DOCTYPE html>
      <html><body>
        <button class="btn-primary">Stale CSS Match</button>
        <button data-testid="submit-order">Submit Order</button>
      </body></html>
      """.trimIndent(),
    )

    val locator = PlaywrightExecutableTool.nodeSelectorToReadinessLocator(
      page,
      TrailblazeNodeSelector(
        web = DriverNodeMatch.Web(
          cssSelector = ".btn-primary",
          dataTestId = "submit-order",
        ),
      ),
    )

    assertNotNull(locator)
    assertEquals(
      "Submit Order",
      locator.textContent(),
      "data-testid is the durable signal and must win over cssSelector.",
    )
  }

  /**
   * Second rung of the same ladder: ARIA role+name beats a raw CSS selector.
   */
  @Test
  fun `nodeSelector prefers aria role and name over a stale css selector`() {
    page.setContent(
      """
      <!DOCTYPE html>
      <html><body>
        <button class="btn-primary">Stale CSS Match</button>
        <button>Place Order</button>
      </body></html>
      """.trimIndent(),
    )

    val locator = PlaywrightExecutableTool.nodeSelectorToReadinessLocator(
      page,
      TrailblazeNodeSelector(
        web = DriverNodeMatch.Web(
          ariaRole = "button",
          ariaNameRegex = "Place Order",
          cssSelector = ".btn-primary",
        ),
      ),
    )

    assertNotNull(locator)
    assertEquals("Place Order", locator.first().textContent())
  }

  /**
   * `ariaNameRegex` is matched as a PATTERN everywhere else — `matchesWeb` routes it through
   * `requirePattern` — so resolving it as an exact literal name breaks the field's contract.
   * With ARIA now ranked above CSS, that also means the CSS selector recorded alongside is
   * never reached, turning a resolvable selector into a timeout.
   */
  @Test
  fun `nodeSelector matches an aria name pattern as a regex, not a literal`() {
    page.setContent(
      """
      <!DOCTYPE html>
      <html><body><button id="save">Save changes</button></body></html>
      """.trimIndent(),
    )

    val locator = PlaywrightExecutableTool.nodeSelectorToReadinessLocator(
      page,
      TrailblazeNodeSelector(
        web = DriverNodeMatch.Web(
          ariaRole = "button",
          ariaNameRegex = "Save.*",
          cssSelector = "#save",
        ),
      ),
    )

    assertNotNull(locator)
    assertEquals(
      "Save changes",
      locator.first().textContent(),
      "A real pattern must match by regex rather than searching for a control named 'Save.*'.",
    )
  }

  /**
   * Playwright matches a regex NAME as a substring search, where every other Trailblaze driver
   * full-matches it — `TrailblazeNodeSelectorResolver.matchesPattern` documents "full-string
   * matching (not substring) to prevent false positives". Unanchored, `Save.*` also matches
   * "AutoSave draft", and since replay narrows with `.first()` it would pick that one by DOM order.
   */
  @Test
  fun `an aria name pattern full-matches, so it cannot resolve a control it merely appears inside`() {
    page.setContent(
      """
      <!DOCTYPE html>
      <html><body>
        <button id="autosave">AutoSave draft</button>
        <button id="save">Save changes</button>
      </body></html>
      """.trimIndent(),
    )

    val locator = PlaywrightExecutableTool.nodeSelectorToReadinessLocator(
      page,
      TrailblazeNodeSelector(
        web = DriverNodeMatch.Web(ariaRole = "button", ariaNameRegex = "Save.*", cssSelector = "#save"),
      ),
    )

    assertNotNull(locator)
    assertEquals(1, locator.count(), "Only the control whose whole name the pattern matches.")
    assertEquals("Save changes", locator.first().textContent())
  }

  /**
   * The shared matcher's literal fallback, which exists because a bare `$` is an end-of-input
   * anchor: `$5.00` compiles as a regex and can never match anything, so `matchesPattern` falls
   * back to string equality. Replay has to offer the same alternative or every price-named control
   * becomes unresolvable.
   */
  @Test
  fun `an aria name that cannot match as a regex still resolves as a literal`() {
    page.setContent(
      """
      <!DOCTYPE html>
      <html><body><button id="total">${'$'}5.00</button></body></html>
      """.trimIndent(),
    )

    val locator = PlaywrightExecutableTool.nodeSelectorToReadinessLocator(
      page,
      TrailblazeNodeSelector(
        web = DriverNodeMatch.Web(ariaRole = "button", ariaNameRegex = "${'$'}5.00", cssSelector = "#total"),
      ),
    )

    assertNotNull(locator)
    assertEquals(1, locator.count(), "The literal fallback must resolve what the regex leg cannot.")
  }

  /**
   * The case-insensitivity escape hatch the shared matcher documents for the NATIVE dialect: a
   * leading `(?i)`. Java honors it inline, but Playwright forwards the pattern SOURCE to a JS
   * `RegExp` and translates only `Pattern.flags()`, and `(?i)` is a syntax error there — so an
   * untranslated leading flag group makes the selector throw on evaluation, and the ARIA tier
   * returns before the CSS fallback could rescue it.
   */
  @Test
  fun `a leading inline case-insensitive flag resolves instead of failing the selector`() {
    page.setContent(
      """
      <!DOCTYPE html>
      <html><body><button id="save">SAVE</button></body></html>
      """.trimIndent(),
    )

    val locator = PlaywrightExecutableTool.nodeSelectorToReadinessLocator(
      page,
      TrailblazeNodeSelector(
        web = DriverNodeMatch.Web(ariaRole = "button", ariaNameRegex = "(?i)save", cssSelector = "#save"),
      ),
    )

    assertNotNull(locator)
    assertEquals(1, locator.count(), "A leading (?i) must survive the hand-off to Playwright.")
  }

  /** `(?i)` must not become a blanket widening: the pattern still has to full-match. */
  @Test
  fun `a case-insensitive aria name still full-matches`() {
    page.setContent(
      """
      <!DOCTYPE html>
      <html><body>
        <button id="save">SAVE</button>
        <button id="autosave">AutoSave draft</button>
      </body></html>
      """.trimIndent(),
    )

    val locator = PlaywrightExecutableTool.nodeSelectorToReadinessLocator(
      page,
      TrailblazeNodeSelector(web = DriverNodeMatch.Web(ariaRole = "button", ariaNameRegex = "(?i)save")),
    )

    assertNotNull(locator)
    assertEquals(1, locator.count(), "Case-insensitive, not unanchored.")
    assertEquals("SAVE", locator.first().textContent())
  }

  /**
   * The other half of the same contract: a recorded literal must keep its EXACT match. The
   * recorder emits literals, so treating them as patterns would let `Save` match `Save changes`
   * and quietly widen every recording.
   */
  @Test
  fun `nodeSelector keeps an exact match for a literal aria name`() {
    page.setContent(
      """
      <!DOCTYPE html>
      <html><body>
        <button>Save changes</button>
        <button>Save</button>
      </body></html>
      """.trimIndent(),
    )

    val locator = PlaywrightExecutableTool.nodeSelectorToReadinessLocator(
      page,
      TrailblazeNodeSelector(web = DriverNodeMatch.Web(ariaRole = "button", ariaNameRegex = "Save")),
    )

    assertNotNull(locator)
    assertEquals(1, locator.count(), "A literal name must not widen into a prefix match.")
    assertEquals("Save", locator.first().textContent())
  }

  /**
   * A metacharacter-bearing literal arrives `\Q...\E`-quoted from `escapeForSelector`. It must
   * resolve as the literal it stands for — and must NOT reach the pattern path, because
   * Playwright hands patterns to a JS RegExp, which has no `\Q` support.
   */
  @Test
  fun `nodeSelector resolves a quoted literal aria name containing metacharacters`() {
    page.setContent(
      """
      <!DOCTYPE html>
      <html><body><button>Total (incl. tax)</button></body></html>
      """.trimIndent(),
    )

    val locator = PlaywrightExecutableTool.nodeSelectorToReadinessLocator(
      page,
      TrailblazeNodeSelector(
        web = DriverNodeMatch.Web(
          ariaRole = "button",
          ariaNameRegex = escapeForSelector("Total (incl. tax)"),
        ),
      ),
    )

    assertNotNull(locator)
    assertEquals("Total (incl. tax)", locator.first().textContent())
  }

  /**
   * The exception to the ladder: a role with no name is a CATEGORY, not an element, so
   * `getByRole("button")` plus the caller's `.first()` acts on the first button on the page
   * — strictly worse than the specific CSS selector recorded alongside it. The structural
   * selector generator emits exactly this `cssSelector` + bare `ariaRole` shape, so ranking
   * ARIA above CSS unconditionally silently retargets those recordings.
   */
  @Test
  fun `nodeSelector prefers css over a role carrying no name`() {
    page.setContent(
      """
      <!DOCTYPE html>
      <html><body>
        <button>First Button</button>
        <button class="btn-primary">Recorded Target</button>
      </body></html>
      """.trimIndent(),
    )

    val locator = PlaywrightExecutableTool.nodeSelectorToReadinessLocator(
      page,
      TrailblazeNodeSelector(
        web = DriverNodeMatch.Web(ariaRole = "button", cssSelector = ".btn-primary"),
      ),
    )

    assertNotNull(locator)
    assertEquals(
      "Recorded Target",
      locator.first().textContent(),
      "A bare role must not outrank the CSS selector recorded with it.",
    )
  }

  /**
   * A bare role is demoted, not discarded: with no CSS to fall back on it must still
   * resolve, or a landmark-only recording stops replaying at all.
   */
  @Test
  fun `nodeSelector still resolves a role carrying no name when it is the only signal`() {
    page.setContent(
      """
      <!DOCTYPE html>
      <html><body><nav>Primary Nav</nav></body></html>
      """.trimIndent(),
    )

    val locator = PlaywrightExecutableTool.nodeSelectorToReadinessLocator(
      page,
      TrailblazeNodeSelector(web = DriverNodeMatch.Web(ariaRole = "navigation")),
    )

    assertNotNull(locator)
    assertEquals("Primary Nav", locator.first().textContent())
  }

  /**
   * CSS is still the last resort rather than dead — a selector carrying only a
   * cssSelector must keep resolving.
   */
  @Test
  fun `nodeSelector still falls back to css selector when it is the only signal`() {
    page.setContent(
      """<!DOCTYPE html><html><body><div class="only-signal">Found</div></body></html>""",
    )

    val locator = PlaywrightExecutableTool.nodeSelectorToReadinessLocator(
      page,
      TrailblazeNodeSelector(web = DriverNodeMatch.Web(cssSelector = ".only-signal")),
    )

    assertNotNull(locator)
    assertEquals("Found", locator.textContent())
  }

  /**
   * The recorded nodeSelector outranks the LLM-supplied ref when both match, regardless
   * of which one the document happens to list first. `Locator.or()` plus `.first()` would
   * answer in DOM order, which silently retargets `fill`/`click` at a stale ref.
   */
  @Test
  fun `a durable nodeSelector wins over a ref that appears earlier in the DOM`() {
    page.setContent(
      """
      <!DOCTYPE html>
      <html><body>
        <button id="stale-first">Stale</button>
        <button data-testid="durable-target">Durable</button>
      </body></html>
      """.trimIndent(),
    )
    val context = buildContext(
      PlaywrightScreenState(page = page, viewportWidth = 1280, viewportHeight = 800),
    )

    val (locator, error) = PlaywrightExecutableTool.validateAndResolveRef(
      page,
      ref = "css=#stale-first",
      description = "durability preference",
      context = context,
      nodeSelector = TrailblazeNodeSelector(
        web = DriverNodeMatch.Web(dataTestId = "durable-target"),
      ),
    )

    assertNull(error)
    assertNotNull(locator)
    assertEquals(
      "Durable",
      locator.textContent(),
      "The recorded nodeSelector must win; DOM order picked the stale ref instead.",
    )
  }

  /**
   * `nthIndex` is the disambiguator for a selector that matches several nodes. Ignoring it
   * on the CSS branch would resolve element 0, and the single-element narrowing downstream
   * makes that indistinguishable from a deliberate pick.
   */
  @Test
  fun `a css nodeSelector honors its nthIndex`() {
    page.setContent(
      """
      <!DOCTYPE html>
      <html><body>
        <button class="row">First</button>
        <button class="row">Second</button>
        <button class="row">Third</button>
      </body></html>
      """.trimIndent(),
    )
    val context = buildContext(
      PlaywrightScreenState(page = page, viewportWidth = 1280, viewportHeight = 800),
    )

    val (locator, error) = PlaywrightExecutableTool.validateAndResolveRef(
      page,
      ref = null,
      description = "nth css match",
      context = context,
      nodeSelector = TrailblazeNodeSelector(
        web = DriverNodeMatch.Web(cssSelector = ".row", nthIndex = 2),
      ),
    )

    assertNull(error)
    assertNotNull(locator)
    assertEquals("Third", locator.textContent())
  }

  /**
   * Candidate capture reads `data-testid || data-test-id` into one field, so replay has to
   * match both spellings or a page using the hyphenated form resolves nothing at all.
   */
  @Test
  fun `a nodeSelector testid matches the hyphenated data-test-id spelling too`() {
    page.setContent(
      """
      <!DOCTYPE html>
      <html><body><button data-test-id="hyphenated">Hyphenated</button></body></html>
      """.trimIndent(),
    )

    val locator = PlaywrightExecutableTool.nodeSelectorToReadinessLocator(
      page,
      TrailblazeNodeSelector(web = DriverNodeMatch.Web(dataTestId = "hyphenated")),
    )

    assertNotNull(locator)
    assertEquals("Hyphenated", locator.textContent())
  }

  @Test
  fun `readiness selector enforces heading level`() {
    page.setContent("<h2>Home</h2><h1>Home</h1>")

    val locator = PlaywrightExecutableTool.nodeSelectorToReadinessLocator(
      page,
      TrailblazeNodeSelector(
        web = DriverNodeMatch.Web(
          ariaRole = "heading",
          ariaNameRegex = "Home",
          headingLevel = 1,
        ),
      ),
    )

    assertNotNull(locator)
    assertEquals(1, locator.count())
    assertEquals("H1", locator.evaluate("element => element.tagName"))
  }

  /**
   * A caller-supplied timeout must beat the global [PlaywrightExecutableTool.elementResolutionTimeoutMs].
   * The overlay-center resolution in the agent depends on this: it resolves elements only
   * to place a screenshot dot, and a missing element must not cost it the full budget.
   */
  @Test
  fun `explicit timeout override beats the global resolution timeout`() {
    page.setContent(testHtml)
    val context = buildContext(
      PlaywrightScreenState(page = page, viewportWidth = 1280, viewportHeight = 800),
    )

    // Global timeout is deliberately long; the override must be what bounds the wait.
    // It's a mutable companion field, so restore it — otherwise 5000ms leaks into every
    // later test in this JVM and silently changes their resolution budgets.
    val previousGlobalTimeoutMs = PlaywrightExecutableTool.elementResolutionTimeoutMs
    val startMs: Long
    val elapsedMs: Long
    val locator: Locator?
    val error: TrailblazeToolResult?
    try {
      PlaywrightExecutableTool.elementResolutionTimeoutMs = 5_000.0
      startMs = System.currentTimeMillis()
      val resolved = PlaywrightExecutableTool.validateAndResolveRef(
        page, "css=#does-not-exist", "overlay center", context,
        timeoutMs = 300.0,
      )
      elapsedMs = System.currentTimeMillis() - startMs
      locator = resolved.first
      error = resolved.second
    } finally {
      PlaywrightExecutableTool.elementResolutionTimeoutMs = previousGlobalTimeoutMs
    }

    assertNull(locator)
    assertNotNull(error)
    assertTrue(
      elapsedMs < 2_000,
      "A 300ms override must bound the wait even with a 5000ms global timeout; took ${elapsedMs}ms.",
    )
  }

  /**
   * A selector recorded from a paragraph must replay. A paragraph has no accessible name, so the
   * name the snapshot gives it is its text, and `getByRole(paragraph, name)` alone finds nothing.
   * The text here also contains `: `, which Playwright's snapshot quotes as YAML — the recorded
   * name must be the text on the page, not the quoted scalar.
   */
  @Test
  fun `a selector recorded from a paragraph replays against its text`() {
    page.setContent(
      """
      <!DOCTYPE html>
      <html><body>
        <p>Current value: <span>0</span></p>
        <p>Total: <span>10</span></p>
      </body></html>
      """.trimIndent(),
    )
    val selector = recordSelector { it.ariaRole == "paragraph" && it.ariaName?.contains("Current") == true }
    assertEquals("Current value: 0", selector.web?.ariaNameRegex?.let { PlaywrightAriaSnapshot.literalAriaName(it) })

    val locator = assertNotNull(PlaywrightExecutableTool.nodeSelectorToReadinessLocator(page, selector))
    assertEquals(1, locator.count())
    assertEquals("Current value: 0", locator.first().textContent())

    page.locator("span").first().evaluate("e => e.textContent = '9'")
    assertEquals(0, locator.count(), "A paragraph with different text must not match.")
  }

  /** The snapshot's bare `text:` node has no role at all; its selector replays by visible text. */
  @Test
  fun `a selector recorded from bare text replays against that text`() {
    page.setContent(
      """
      <!DOCTYPE html>
      <html><body>
        <div>Sale attributed to: ${"$"}alex <a href="#">Details</a></div>
        <div>Other</div>
      </body></html>
      """.trimIndent(),
    )
    val selector = recordSelector { it.ariaRole == "text" && it.ariaName?.contains("attributed") == true }

    val locator = assertNotNull(PlaywrightExecutableTool.nodeSelectorToReadinessLocator(page, selector))
    assertEquals(1, locator.count())

    val pattern = PlaywrightExecutableTool.nodeSelectorToReadinessLocator(
      page,
      TrailblazeNodeSelector(web = DriverNodeMatch.Web(ariaRole = "text", ariaNameRegex = ".*[$]alex")),
    )
    assertEquals(1, assertNotNull(pattern).count(), "A pattern matches the run of text.")

    page.locator("div").first().evaluate("e => e.firstChild.textContent = 'Sale attributed to: ${"$"}sam '")
    assertEquals(0, locator.count(), "Different text must not match.")
    assertEquals(0, pattern.count(), "Different text must not match.")
  }

  /**
   * A list of plain divs snapshots as one run of text, its cells joined by spaces the page does
   * not have. The selector recorded from it still has to find the row.
   */
  @Test
  fun `a selector recorded from a flattened row replays against that row`() {
    page.setContent(
      """
      <!DOCTYPE html>
      <html><body>
        <h1>Transactions</h1>
        <div><div>Custom Amount</div><div>Main Location</div><div>${"$"}25.00</div></div>
      </body></html>
      """.trimIndent(),
    )
    val selector = recordSelector { it.ariaRole == "text" && it.ariaName?.contains("Main Location") == true }
    assertEquals("Custom Amount Main Location \$25.00", selector.web?.ariaNameRegex?.let { PlaywrightAriaSnapshot.literalAriaName(it) })

    val locator = assertNotNull(PlaywrightExecutableTool.nodeSelectorToReadinessLocator(page, selector))
    assertEquals(1, locator.count())

    val pattern = PlaywrightExecutableTool.nodeSelectorToReadinessLocator(
      page,
      TrailblazeNodeSelector(web = DriverNodeMatch.Web(ariaRole = "text", ariaNameRegex = ".*Main Location [$]25\\.00.*")),
    )
    assertEquals(1, assertNotNull(pattern).count(), "A pattern matches across the joined cells.")

    page.locator("div div").nth(2).evaluate("e => e.textContent = '${"$"}30.00'")
    assertEquals(0, locator.count(), "Different text must not match.")
    assertEquals(0, pattern.count(), "Different text must not match.")
  }

  /**
   * Text matching is for elements without an accessible name. A button labelled "Close" that
   * shows "Save" must not answer to a selector for a button named "Save".
   */
  @Test
  fun `a named control is not matched by the text inside it`() {
    page.setContent(
      """
      <!DOCTYPE html>
      <html><body><button aria-label="Close">Save</button></body></html>
      """.trimIndent(),
    )

    val locator = PlaywrightExecutableTool.nodeSelectorToReadinessLocator(
      page,
      TrailblazeNodeSelector(web = DriverNodeMatch.Web(ariaRole = "button", ariaNameRegex = "Save")),
    )

    assertEquals(0, assertNotNull(locator).count())
  }

  /** The snapshot collapses the page's whitespace; a selector recorded from it still replays. */
  @Test
  fun `a selector recorded from text the page spaces out replays against it`() {
    page.setContent(
      """
      <!DOCTYPE html>
      <html><body>
        <p>
          Current   value:
          <span>0</span>
        </p>
      </body></html>
      """.trimIndent(),
    )
    val selector = recordSelector { it.ariaRole == "paragraph" }
    assertEquals("Current value: 0", selector.web?.ariaNameRegex?.let { PlaywrightAriaSnapshot.literalAriaName(it) })

    val locator = assertNotNull(PlaywrightExecutableTool.nodeSelectorToReadinessLocator(page, selector))
    assertEquals(1, locator.count())

    page.locator("span").evaluate("e => e.textContent = '9'")
    assertEquals(0, locator.count(), "A paragraph with different text must not match.")
  }

  /** Hidden text is not in the snapshot, so it neither breaks a match nor makes one. */
  @Test
  fun `hidden text neither breaks nor makes a text match`() {
    page.setContent(
      """
      <!DOCTYPE html>
      <html><body>
        <p id="shown">Visible <span style="display:none">hidden</span>text</p>
        <p id="count">Count: <span style="display:none">0</span></p>
      </body></html>
      """.trimIndent(),
    )
    val selector = recordSelector { it.ariaRole == "paragraph" && it.ariaName?.startsWith("Visible") == true }
    assertEquals("Visible text", selector.web?.ariaNameRegex?.let { PlaywrightAriaSnapshot.literalAriaName(it) })
    val shown = assertNotNull(PlaywrightExecutableTool.nodeSelectorToReadinessLocator(page, selector))
    assertEquals("shown", shown.first().getAttribute("id"))
    assertEquals(1, shown.count())

    val hiddenValue = PlaywrightExecutableTool.nodeSelectorToReadinessLocator(
      page,
      TrailblazeNodeSelector(web = DriverNodeMatch.Web(ariaRole = "paragraph", ariaNameRegex = "Count: 0")),
    )
    assertEquals(0, assertNotNull(hiddenValue).count(), "A value the page hides must not match.")
  }

  /** A bare text run matches in full: a longer run that begins with it is different text. */
  @Test
  fun `a bare text selector does not match a longer run`() {
    page.setContent(
      """
      <!DOCTYPE html>
      <html><body><div>Sale attributed to: ${"$"}alex <a href="#">Details</a></div></body></html>
      """.trimIndent(),
    )
    val selector = recordSelector { it.ariaRole == "text" && it.ariaName?.contains("attributed") == true }
    val locator = assertNotNull(PlaywrightExecutableTool.nodeSelectorToReadinessLocator(page, selector))
    assertEquals(1, locator.count())

    page.locator("div").evaluate("e => e.firstChild.textContent = 'Sale attributed to: ${"$"}alexander '")
    assertEquals(0, locator.count(), "A run that only starts with the recorded text must not match.")
  }

  /** A hidden copy of a run is not a match, so it cannot be the element replay acts on. */
  @Test
  fun `a hidden copy of a text run is skipped`() {
    page.setContent(
      """
      <!DOCTYPE html>
      <html><body>
        <div id="hidden" style="display:none">Terms apply <a href="#">a</a></div>
        <div id="shown">Terms apply <a href="#">b</a></div>
      </body></html>
      """.trimIndent(),
    )
    val locator = PlaywrightExecutableTool.nodeSelectorToReadinessLocator(
      page,
      TrailblazeNodeSelector(web = DriverNodeMatch.Web(ariaRole = "text", ariaNameRegex = "Terms apply")),
    )

    assertEquals(1, assertNotNull(locator).count())
    assertEquals("shown", locator.first().getAttribute("id"))
  }

  /** A text selector matches text runs only; a control showing the same words is not one. */
  @Test
  fun `a text selector does not resolve to a control with the same words`() {
    page.setContent(
      """
      <!DOCTYPE html>
      <html><body><button>Terms apply</button></body></html>
      """.trimIndent(),
    )
    val locator = PlaywrightExecutableTool.nodeSelectorToReadinessLocator(
      page,
      TrailblazeNodeSelector(web = DriverNodeMatch.Web(ariaRole = "text", ariaNameRegex = "Terms apply")),
    )

    assertEquals(0, assertNotNull(locator).count())
  }

  /**
   * `$5.00` is a valid regex that can never match, because its bare `$` ends the input. Like
   * every other name match, text matching falls back to equality with the recorded value.
   */
  @Test
  fun `a recorded price replays as text without escaping`() {
    page.setContent(
      """
      <!DOCTYPE html>
      <html><body>
        <p>${"$"}5.00</p>
        <div>${"$"}7.00 <a href="#">Edit</a></div>
      </body></html>
      """.trimIndent(),
    )
    fun count(role: String, name: String) = assertNotNull(
      PlaywrightExecutableTool.nodeSelectorToReadinessLocator(
        page,
        TrailblazeNodeSelector(web = DriverNodeMatch.Web(ariaRole = role, ariaNameRegex = name)),
      ),
    ).count()

    assertEquals(1, count("paragraph", "${"$"}5.00"))
    assertEquals(1, count("text", "${"$"}7.00"))
    assertEquals(0, count("paragraph", "${"$"}6.00"))
  }

  /** The recorded pattern is used as written; a quantified space stays a valid pattern. */
  @Test
  fun `a pattern quantifying a space resolves`() {
    page.setContent("<!DOCTYPE html><html><body><p>Sign in</p></body></html>")
    val locator = PlaywrightExecutableTool.nodeSelectorToReadinessLocator(
      page,
      TrailblazeNodeSelector(web = DriverNodeMatch.Web(ariaRole = "paragraph", ariaNameRegex = "Sign *in")),
    )

    assertEquals(1, assertNotNull(locator).count())
  }

  /** The spaces the snapshot prints are part of the text: removing or moving one is a change. */
  @Test
  fun `a selector does not match text whose word or cell boundaries moved`() {
    page.setContent(
      """
      <!DOCTYPE html>
      <html><body>
        <p>Log in</p>
        <h1>Totals</h1>
        <div><div id="a">1</div><div id="b">23</div></div>
      </body></html>
      """.trimIndent(),
    )
    val words = assertNotNull(PlaywrightExecutableTool.nodeSelectorToReadinessLocator(page, recordSelector { it.ariaRole == "paragraph" }))
    val cells = assertNotNull(PlaywrightExecutableTool.nodeSelectorToReadinessLocator(page, recordSelector { it.ariaRole == "text" }))
    assertEquals(1, words.count())
    assertEquals(1, cells.count())

    page.locator("p").evaluate("e => e.textContent = 'Login'")
    page.evaluate("() => { document.getElementById('a').textContent = '12'; document.getElementById('b').textContent = '3' }")
    assertEquals(0, words.count(), "Text that lost a space must not match.")
    assertEquals(0, cells.count(), "Cells whose boundary moved must not match.")
  }

  /**
   * An occurrence index counts text runs as the snapshot printed them. Two runs in one element
   * both resolve to that element, so the third run is still index 2.
   */
  @Test
  fun `each occurrence of a text run is counted`() {
    page.setContent(
      """
      <!DOCTYPE html>
      <html><body>
        <div id="first">Terms apply <a href="#">a</a> Terms apply <a href="#">b</a></div>
        <div id="second">Terms apply <a href="#">c</a></div>
      </body></html>
      """.trimIndent(),
    )
    fun nth(index: Int) = assertNotNull(
      PlaywrightExecutableTool.nodeSelectorToReadinessLocator(
        page,
        TrailblazeNodeSelector(web = DriverNodeMatch.Web(ariaRole = "text", ariaNameRegex = "Terms apply", nthIndex = index)),
      ),
    )

    assertEquals("first", nth(0).getAttribute("id"))
    assertEquals("first", nth(1).getAttribute("id"))
    assertEquals("second", nth(2).getAttribute("id"))
    assertEquals(0, nth(3).count())
  }

  /** A text run that renders after the wait starts is found: the match is re-run as it polls. */
  @Test
  fun `a text selector waits for text that renders late`() {
    PlaywrightExecutableTool.elementResolutionTimeoutMs = PRODUCTION_RESOLUTION_BUDGET_MS
    page.setContent(
      """
      <!DOCTYPE html>
      <html><body><h1>Loading</h1><div id="slot"></div>
      <script>setTimeout(() => { document.getElementById('slot').innerHTML = 'Ready now <a href="#">Go</a>' }, 200)</script>
      </body></html>
      """.trimIndent(),
    )
    val locator = assertNotNull(
      PlaywrightExecutableTool.nodeSelectorToReadinessLocator(
        page,
        TrailblazeNodeSelector(web = DriverNodeMatch.Web(ariaRole = "text", ariaNameRegex = "Ready now")),
      ),
    )

    locator.first().waitFor(Locator.WaitForOptions().setTimeout(PRODUCTION_RESOLUTION_BUDGET_MS))
    assertEquals("slot", locator.first().getAttribute("id"))
  }

  /** The snapshot prints a frame as a node of its own, so text on either side is two runs. */
  @Test
  fun `a frame separates the text runs around it`() {
    page.setContent(
      """
      <!DOCTYPE html>
      <html><body>
        <div id="row">Before <iframe srcdoc="<p>inside</p>"></iframe> After <a href="#">Go</a></div>
      </body></html>
      """.trimIndent(),
    )
    val selector = recordSelector { it.ariaRole == "text" && it.ariaName == "Before" }
    val before = assertNotNull(PlaywrightExecutableTool.nodeSelectorToReadinessLocator(page, selector))
    assertEquals("row", before.first().getAttribute("id"))
    assertEquals(1, before.count())

    val joined = PlaywrightExecutableTool.nodeSelectorToReadinessLocator(
      page,
      TrailblazeNodeSelector(web = DriverNodeMatch.Web(ariaRole = "text", ariaNameRegex = "Before After")),
    )
    assertEquals(0, assertNotNull(joined).count(), "Runs a frame separates must not join into one.")
  }

  /** Text a component renders straight into its shadow root resolves to that component. */
  @Test
  fun `a text run in a shadow root resolves to its host`() {
    page.setContent(
      """
      <!DOCTYPE html>
      <html><body>
        <div id="host"></div><a href="#">Go</a>
        <script>document.getElementById('host').attachShadow({ mode: 'open' }).textContent = 'Ready now'</script>
      </body></html>
      """.trimIndent(),
    )
    val selector = recordSelector { it.ariaRole == "text" && it.ariaName == "Ready now" }
    val locator = assertNotNull(PlaywrightExecutableTool.nodeSelectorToReadinessLocator(page, selector))

    assertEquals("host", locator.first().getAttribute("id"))
  }

  /** A run made only of CSS-generated text resolves to the element that generates it. */
  @Test
  fun `a text run of generated content resolves to the element generating it`() {
    page.setContent(
      """
      <!DOCTYPE html>
      <html><head><style>#badge::before { content: "Fresh" }</style></head><body>
        <span id="badge"></span><a href="#">Go</a>
      </body></html>
      """.trimIndent(),
    )
    val selector = recordSelector { it.ariaRole == "text" && it.ariaName == "Fresh" }
    val locator = assertNotNull(PlaywrightExecutableTool.nodeSelectorToReadinessLocator(page, selector))

    assertEquals("badge", locator.first().getAttribute("id"))
  }

  /**
   * The snapshot has no node for an inline generic around a single text node, nor for an empty
   * generic: their text joins the text around them.
   */
  @Test
  fun `text joins across a generic the snapshot leaves out`() {
    page.setContent(
      """
      <!DOCTYPE html>
      <html><body>
        <p>Total: <span role="generic">10</span></p>
        <div id="row">New prefix <span role="generic"></span>Total <a href="#">Go</a></div>
      </body></html>
      """.trimIndent(),
    )
    val paragraph = recordSelector { it.ariaRole == "paragraph" }
    assertEquals("Total: 10", paragraph.web?.ariaNameRegex?.let { PlaywrightAriaSnapshot.literalAriaName(it) })
    assertEquals(1, assertNotNull(PlaywrightExecutableTool.nodeSelectorToReadinessLocator(page, paragraph)).count())

    val run = recordSelector { it.ariaRole == "text" && it.ariaName?.contains("Total") == true }
    assertEquals("New prefix Total", run.web?.ariaNameRegex?.let { PlaywrightAriaSnapshot.literalAriaName(it) })
    val locator = assertNotNull(PlaywrightExecutableTool.nodeSelectorToReadinessLocator(page, run))
    assertEquals("row", locator.first().getAttribute("id"))
    assertEquals(1, locator.count())
  }

  /** A run of a component's own text and the text slotted into it resolves inside the component. */
  @Test
  fun `a text run across a slot resolves to the element showing it`() {
    page.setContent(
      """
      <!DOCTYPE html>
      <html><body>
        <div id="host">world</div>
        <script>document.getElementById('host').attachShadow({ mode: 'open' }).innerHTML = '<div id="inner">Hello <slot></slot> <a href="#">Go</a></div>'</script>
      </body></html>
      """.trimIndent(),
    )
    val selector = recordSelector { it.ariaRole == "text" && it.ariaName == "Hello world" }
    val locator = assertNotNull(PlaywrightExecutableTool.nodeSelectorToReadinessLocator(page, selector))

    assertEquals("inner", locator.first().getAttribute("id"))
  }

  /**
   * Generated content prints only when it is all strings: an image or a counter voids the whole
   * value, and an image with alternative text prints that text.
   */
  @Test
  fun `generated images print only their alternative text`() {
    page.setContent(
      """
      <!DOCTYPE html>
      <html><head><style>
        #icon::before { content: url("icon.svg") }
        #alt::before { content: url("icon.svg") / "New: " }
        #step::before { content: "Step " counter(list-item) }
      </style></head><body>
        <p id="icon">Ready</p>
        <p id="step">One</p>
        <p id="alt">Later</p>
      </body></html>
      """.trimIndent(),
    )
    val icon = recordSelector { it.ariaRole == "paragraph" && it.ariaName?.contains("Ready") == true }
    assertEquals("Ready", icon.web?.ariaNameRegex?.let { PlaywrightAriaSnapshot.literalAriaName(it) })
    assertEquals("icon", assertNotNull(PlaywrightExecutableTool.nodeSelectorToReadinessLocator(page, icon)).first().getAttribute("id"))

    val alt = recordSelector { it.ariaRole == "paragraph" && it.ariaName?.contains("Later") == true }
    assertEquals("New: Later", alt.web?.ariaNameRegex?.let { PlaywrightAriaSnapshot.literalAriaName(it) })
    assertEquals("alt", assertNotNull(PlaywrightExecutableTool.nodeSelectorToReadinessLocator(page, alt)).first().getAttribute("id"))

    val step = recordSelector { it.ariaRole == "paragraph" && it.ariaName?.contains("One") == true }
    assertEquals("One", step.web?.ariaNameRegex?.let { PlaywrightAriaSnapshot.literalAriaName(it) })
    assertEquals("step", assertNotNull(PlaywrightExecutableTool.nodeSelectorToReadinessLocator(page, step)).first().getAttribute("id"))
  }

  /** An aria-hidden wrapper hides what it holds even when it draws no box of its own. */
  @Test
  fun `text inside an aria-hidden display-contents wrapper is hidden`() {
    page.setContent(
      """
      <!DOCTYPE html>
      <html><body><p>Count: <span style="display:contents" aria-hidden="true"><b>0</b></span></p></body></html>
      """.trimIndent(),
    )
    val selector = recordSelector { it.ariaRole == "paragraph" }
    assertEquals("Count:", selector.web?.ariaNameRegex?.let { PlaywrightAriaSnapshot.literalAriaName(it) })
    assertEquals(1, assertNotNull(PlaywrightExecutableTool.nodeSelectorToReadinessLocator(page, selector)).count())

    val hiddenValue = PlaywrightExecutableTool.nodeSelectorToReadinessLocator(
      page,
      TrailblazeNodeSelector(web = DriverNodeMatch.Web(ariaRole = "paragraph", ariaNameRegex = "Count: 0")),
    )
    assertEquals(0, assertNotNull(hiddenValue).count(), "A value the page hides must not match.")
  }

  /**
   * A link's `/url` and a text field's `/placeholder` print beside its text, so text that differs
   * from the name prints as a run of its own. Text that repeats the name prints nothing.
   */
  @Test
  fun `text printed beside a link or field property is a run`() {
    page.setContent(
      """
      <!DOCTYPE html>
      <html><body>
        <a id="close" href="#" aria-label="Close dialog">X</a>
        <a href="#">Y</a>
        <input id="search" aria-label="Search" placeholder="Type here" value="shoes">
      </body></html>
      """.trimIndent(),
    )
    val link = assertNotNull(PlaywrightExecutableTool.nodeSelectorToReadinessLocator(page, recordSelector { it.ariaRole == "text" && it.ariaName == "X" }))
    assertEquals("close", link.first().getAttribute("id"))
    assertEquals(1, link.count())

    val field = assertNotNull(PlaywrightExecutableTool.nodeSelectorToReadinessLocator(page, recordSelector { it.ariaRole == "text" && it.ariaName == "shoes" }))
    assertEquals("search", field.first().getAttribute("id"))

    val named = PlaywrightExecutableTool.nodeSelectorToReadinessLocator(
      page,
      TrailblazeNodeSelector(web = DriverNodeMatch.Web(ariaRole = "text", ariaNameRegex = "Y")),
    )
    assertEquals(0, assertNotNull(named).count(), "Text that is the link's name is not printed as a run.")
  }

  /**
   * A label's hidden descendants are not part of the name, so a link or field whose shown label
   * repeats its text prints no separate run.
   */
  @Test
  fun `hidden label text does not make a run of a link or field`() {
    page.setContent(
      """
      <!DOCTYPE html>
      <html><body>
        <a href="#" aria-labelledby="close-label">Close</a>
        <span id="close-label">Close<span style="display:none"> dialog</span></span>
        <label>Search<span style="display:none"> products</span> <input placeholder="Search" value="shoes"></label>
      </body></html>
      """.trimIndent(),
    )
    val yaml = PlaywrightAriaSnapshot.captureAriaSnapshot(page).yaml
    val printed = yaml.lines().map { it.trim() }
    assertFalse("- text: Close" in printed || "- text: shoes" in printed, "Playwright prints no such run:\n$yaml")
    fun count(name: String) = assertNotNull(
      PlaywrightExecutableTool.nodeSelectorToReadinessLocator(
        page,
        TrailblazeNodeSelector(web = DriverNodeMatch.Web(ariaRole = "text", ariaNameRegex = name)),
      ),
    ).count()

    assertEquals(0, count("Close"))
    assertEquals(0, count("shoes"))
  }

  /**
   * A label names a link the way Playwright reads it: line breaks and alt text as shown, text
   * from shadow roots and slots, an aria-label in place of the content it labels, and a dropdown
   * as its chosen option. Each link here is named exactly its text, so none of them prints a
   * text run.
   */
  @Test
  fun `label text is read as Playwright names from it`() {
    page.setContent(
      """
      <!DOCTYPE html>
      <html><body>
        <a href="#" aria-labelledby="br">Close dialog</a>
        <a href="#" aria-labelledby="alt">Next page</a>
        <a href="#" aria-labelledby="shadow">Save draft</a>
        <a href="#" aria-labelledby="slot">Send message</a>
        <a href="#" aria-labelledby="named">Delete</a>
        <a href="#" aria-labelledby="inner">Open menu</a>
        <a href="#" aria-labelledby="dropdown">Size Large</a>
        <p>Labels: <span id="br">Close<br>dialog</span></p>
        <p>Labels: <span id="alt">Next <img alt="page" src="data:,"></span></p>
        <p>Labels: <span id="shadow">Save <span id="shadow-host"></span></span></p>
        <p>Labels: <span id="slot"><span id="slot-host">message</span></span></p>
        <p>Labels: <span id="named" aria-label="Delete">Remove item</span></p>
        <p>Labels: <span id="inner">Open <span aria-label="menu">=</span></span></p>
        <div>Labels: <span id="dropdown">Size <span role="listbox"><span role="option" aria-selected="true">Large</span> <span role="option">Small</span></span></span></div>
        <script>
          document.getElementById('shadow-host').attachShadow({ mode: 'open' }).innerHTML = 'draft';
          document.getElementById('slot-host').attachShadow({ mode: 'open' }).innerHTML = 'Send <slot></slot>';
        </script>
      </body></html>
      """.trimIndent(),
    )
    val names = listOf("Close dialog", "Next page", "Save draft", "Send message", "Delete", "Open menu", "Size Large")
    val yaml = PlaywrightAriaSnapshot.captureAriaSnapshot(page).yaml
    val printed = yaml.lines().map { it.trim() }
    for (name in names) {
      assertTrue("- link \"$name\":" in printed, "Playwright names the link \"$name\":\n$yaml")
      assertFalse("- text: $name" in printed, "Playwright prints no run \"$name\":\n$yaml")
    }
    fun count(name: String) = assertNotNull(
      PlaywrightExecutableTool.nodeSelectorToReadinessLocator(
        page,
        TrailblazeNodeSelector(web = DriverNodeMatch.Web(ariaRole = "text", ariaNameRegex = name)),
      ),
    ).count()

    assertEquals(names.map { it to 0 }, names.map { it to count(it) })
  }

  /**
   * Fields take Playwright's input roles: a search field is a searchbox, which prints no
   * placeholder, so its value is its content rather than a run; a password field is a textbox,
   * which splits the text around it. An SVG in a label names the field by its title.
   */
  @Test
  fun `fields take the roles and label names Playwright gives them`() {
    page.setContent(
      """
      <!DOCTYPE html>
      <html><body>
        <input type="search" aria-label="Search" placeholder="Type here" value="shoes">
        <label><svg width="10" height="10"><title>Find</title></svg> <input placeholder="Find" value="boots"></label>
        <p>Password <input type="password"> required</p>
      </body></html>
      """.trimIndent(),
    )
    val yaml = PlaywrightAriaSnapshot.captureAriaSnapshot(page).yaml
    val printed = yaml.lines().map { it.trim() }
    assertTrue("- searchbox \"Search\": shoes" in printed, yaml)
    assertTrue("- textbox \"Find\": boots" in printed, yaml)
    assertTrue("- text: Password" in printed && "- text: required" in printed, yaml)
    fun count(name: String) = assertNotNull(
      PlaywrightExecutableTool.nodeSelectorToReadinessLocator(
        page,
        TrailblazeNodeSelector(web = DriverNodeMatch.Web(ariaRole = "text", ariaNameRegex = name)),
      ),
    ).count()

    assertEquals(
      listOf("shoes" to 0, "boots" to 0, "Password" to 1, "required" to 1, "Password required" to 0),
      listOf("shoes", "boots", "Password", "required", "Password required").map { it to count(it) },
    )
  }

  /**
   * A link named from its content reads that content as Playwright does: an aria-label inside
   * stands in for the text it labels, so the text itself prints as a run.
   */
  @Test
  fun `text inside a link named from an inner aria-label is a run`() {
    page.setContent(
      """
      <!DOCTYPE html>
      <html><body>
        <a id="close" href="#"><span aria-label="Close dialog">X</span></a>
        <a href="#"><span>Plain</span></a>
      </body></html>
      """.trimIndent(),
    )
    val printed = PlaywrightAriaSnapshot.captureAriaSnapshot(page).yaml.lines().map { it.trim() }
    assertTrue("- link \"Close dialog\":" in printed && "- text: X" in printed, printed.joinToString("\n"))
    assertFalse("- text: Plain" in printed, printed.joinToString("\n"))
    fun locate(name: String) = assertNotNull(
      PlaywrightExecutableTool.nodeSelectorToReadinessLocator(
        page,
        TrailblazeNodeSelector(web = DriverNodeMatch.Web(ariaRole = "text", ariaNameRegex = name)),
      ),
    )

    assertEquals(1, locate("X").count())
    assertEquals("close", locate("X").first().evaluate("e => e.closest('a').id"))
    assertEquals(0, locate("Plain").count())
  }

  /** aria-labelledby inside a shadow root names its link from that shadow root. */
  @Test
  fun `a link labelled inside a shadow root keeps its text run`() {
    page.setContent(
      """
      <!DOCTYPE html>
      <html><body>
        <div id="host"></div>
        <script>document.getElementById('host').attachShadow({ mode: 'open' }).innerHTML = '<span id="l">Close dialog</span><a id="x" href="#" aria-labelledby="l">X</a>'</script>
      </body></html>
      """.trimIndent(),
    )
    val selector = recordSelector { it.ariaRole == "text" && it.ariaName == "X" }
    val locator = assertNotNull(PlaywrightExecutableTool.nodeSelectorToReadinessLocator(page, selector))

    assertEquals("x", locator.first().getAttribute("id"))
  }

  /** The engine runs in a world of its own: page scripts replacing globals cannot fake a match. */
  @Test
  fun `a page replacing globals cannot make absent text match`() {
    page.setContent("<!DOCTYPE html><html><body><p>Present</p><a href=\"#\">Go</a></body></html>")
    page.evaluate("() => { JSON.parse = () => ({ probe: true }) }")
    val locator = PlaywrightExecutableTool.nodeSelectorToReadinessLocator(
      page,
      TrailblazeNodeSelector(web = DriverNodeMatch.Web(ariaRole = "text", ariaNameRegex = "Absent")),
    )

    assertEquals(0, assertNotNull(locator).count())
  }

  /**
   * A Playwright that never registered the engine still resolves selectors, by accessible name
   * alone, rather than failing every lookup on an unknown engine.
   */
  @Test
  fun `a Playwright without the engine resolves by accessible name`() {
    val bare = Playwright.create()
    try {
      val bareBrowser = bare.chromium().launch(BrowserType.LaunchOptions().setHeadless(true))
      val barePage = bareBrowser.newPage()
      barePage.setContent("<!DOCTYPE html><html><body><p>Current value: 0</p><button>Save</button></body></html>")

      assertFalse(PlaywrightTextRuns.isRegistered(barePage))
      fun count(role: String, name: String) = assertNotNull(
        PlaywrightExecutableTool.nodeSelectorToReadinessLocator(
          barePage,
          TrailblazeNodeSelector(web = DriverNodeMatch.Web(ariaRole = role, ariaNameRegex = name)),
        ),
      ).count()
      assertEquals(1, count("button", "Save"))
      assertEquals(0, count("paragraph", "Current value: 0"))
    } finally {
      bare.close()
    }
    assertTrue(PlaywrightTextRuns.isRegistered(page))
  }

  /** A context looked up before the engine was registered gets text matching once it is. */
  @Test
  fun `registering the engine after a lookup turns text matching on`() {
    val late = Playwright.create()
    try {
      val latePage = late.chromium().launch(BrowserType.LaunchOptions().setHeadless(true)).newPage()
      latePage.setContent("<!DOCTYPE html><html><body><p>Current value: 0</p></body></html>")
      assertFalse(PlaywrightTextRuns.isRegistered(latePage))

      PlaywrightTextRuns.register(late)

      assertTrue(PlaywrightTextRuns.isRegistered(latePage))
      assertEquals(
        1,
        assertNotNull(
          PlaywrightExecutableTool.nodeSelectorToReadinessLocator(
            latePage,
            TrailblazeNodeSelector(web = DriverNodeMatch.Web(ariaRole = "paragraph", ariaNameRegex = "Current value: 0")),
          ),
        ).count(),
      )
    } finally {
      late.close()
    }
  }

  /**
   * Captures [page] as the recorder does and returns the role + name selector the generator
   * records for the first node [predicate] accepts.
   */
  private fun recordSelector(
    predicate: (xyz.block.trailblaze.api.DriverNodeDetail.Web) -> Boolean,
  ): TrailblazeNodeSelector {
    val yaml = PlaywrightAriaSnapshot.captureAriaSnapshot(page).yaml
    val root = assertNotNull(PlaywrightTrailblazeNodeMapper.mapAriaSnapshotToTrailblazeNode(yaml))
    fun find(node: xyz.block.trailblaze.api.TrailblazeNode): xyz.block.trailblaze.api.DriverNodeDetail.Web? {
      val detail = node.driverDetail as? xyz.block.trailblaze.api.DriverNodeDetail.Web
      if (detail != null && predicate(detail)) return detail
      return node.children.firstNotNullOfOrNull(::find)
    }
    val detail = assertNotNull(find(root), "No captured node matched in:\n$yaml")
    return TrailblazeNodeSelector(
      web = DriverNodeMatch.Web(
        ariaRole = detail.ariaRole,
        ariaNameRegex = escapeForSelector(assertNotNull(detail.ariaName)),
      ),
    )
  }
}

/**
 * The auto-wait budget these tests run on, and what `@After` restores. Chosen to match
 * [PlaywrightExecutableTool.elementResolutionTimeoutMs]'s default rather than to track it: the
 * tests need a budget far above the ~200ms path they measure, which any plausible production
 * value satisfies, so reading the live value would only couple the bound below to a tuning knob.
 */
private const val PRODUCTION_RESOLUTION_BUDGET_MS = 10_000.0

/**
 * Ceiling proving the auto-wait returns when the element attaches rather than sitting out its
 * whole budget.
 *
 * Duration is the only discriminator here — both outcomes hand back the same resolved locator —
 * so the bound has to be a clock, and the way to keep it honest is to put the two outcomes far
 * apart rather than to tighten it. Correct code returns in ~200ms (when the scheduled insert
 * lands); a regression that no-ops the waitFor's early resolve burns the full
 * [PRODUCTION_RESOLUTION_BUDGET_MS]. Half the budget sits ~20x above the correct path and 5s
 * below the broken one. A bound near the correct path would instead fail on a loaded agent for
 * reasons that have nothing to do with early resolve, since the wait encloses real CDP round
 * trips.
 */
private const val WELL_UNDER_THE_RESOLUTION_BUDGET_MS = 5_000L
