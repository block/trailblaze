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
    PlaywrightExecutableTool.elementResolutionTimeoutMs = 10_000.0
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
    assertContains(locator.first().textContent(), "Submit")
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
  fun `non-existent ref returns error mentioning playwright_snapshot`() {
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
    assertContains(error.errorMessage, "web_snapshot")
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
    // Override the 100ms @Before default — we need a budget large enough to absorb
    // the 200ms scheduled DOM insert plus locator-poll overhead.
    PlaywrightExecutableTool.elementResolutionTimeoutMs = 2000.0

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

    // But we didn't sit on the full 2000ms ceiling — once the element attached,
    // the call returned promptly. A regression that no-ops the waitFor's "early
    // resolve on match" behavior would burn the full budget.
    assertTrue(
      elapsedMs < 1500,
      "Wait took ${elapsedMs}ms (budget was 2000ms) — auto-wait isn't resolving " +
        "promptly after the element attaches.",
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
}
