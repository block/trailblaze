package xyz.block.trailblaze.playwright.tools

import com.microsoft.playwright.Browser
import com.microsoft.playwright.BrowserType
import com.microsoft.playwright.Page
import com.microsoft.playwright.Playwright
import com.microsoft.playwright.assertions.PlaywrightAssertions
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
import kotlin.test.assertTrue

/**
 * Behavioral tests for `web_verifyListVisible`'s item matching.
 *
 * The load-bearing behavior: item checks must AUTO-WAIT. Server-backed lists routinely
 * render their container first and hydrate rows a beat later; a fail-fast snapshot
 * check at call time flags those rows missing and fails real trails on real pages.
 */
class PlaywrightNativeVerifyListVisibleToolTest {

  private lateinit var playwright: Playwright
  private lateinit var browser: Browser
  private lateinit var page: Page

  @Before
  fun setUp() {
    playwright = Playwright.create()
    browser = playwright.chromium().launch(BrowserType.LaunchOptions().setHeadless(true))
    page = browser.newContext(
      Browser.NewContextOptions().setViewportSize(1280, 800),
    ).newPage()
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
  fun `items that hydrate after the container renders still verify`() {
    // min-height keeps the empty container VISIBLE from t=0 — otherwise the container
    // visibility assert does the waiting and the per-item behavior goes untested.
    page.setContent(
      """<!DOCTYPE html><html><body><ul id="orders" style="min-height:50px"></ul></body></html>""",
    )
    // Rows land ~300ms after the tool starts checking — the server-backed-list shape.
    page.evaluate(
      """() => setTimeout(() => {
        document.getElementById('orders').innerHTML =
          '<li>Latte</li><li>Espresso</li><li>Cold Brew</li>';
      }, 300)""",
    )

    val tool = PlaywrightNativeVerifyListVisibleTool(
      ref = "css=#orders",
      items = listOf("Latte", "Espresso", "Cold Brew"),
    )
    val result = runBlocking { tool.executeWithPlaywright(page, buildContext()) }

    assertIs<TrailblazeToolResult.Success>(
      result,
      "Late-hydrating rows must be absorbed by the item auto-wait, not flagged missing.",
    )
  }

  @Test
  fun `a hidden duplicate ahead of the visible row does not report the item missing`() {
    // The assertion's contract is "some visible row shows this text". Narrowing the text
    // match to `.first()` pins it to DOM order instead, so a hidden node carrying the same
    // text — a collapsed template, an aria-hidden mirror, a filtered-out row — makes the
    // real visible row unreachable and the item reads as missing.
    page.setContent(
      """
      <!DOCTYPE html>
      <html><body><ul id="orders">
        <li hidden>Latte</li>
        <li>Latte</li>
      </ul></body></html>
      """.trimIndent(),
    )

    val tool = PlaywrightNativeVerifyListVisibleTool(
      ref = "css=#orders",
      items = listOf("Latte"),
    )
    val result = runBlocking { tool.executeWithPlaywright(page, buildContext()) }

    assertIs<TrailblazeToolResult.Success>(
      result,
      "A visible row satisfies the assertion even when a hidden duplicate precedes it.",
    )
  }

  @Test
  fun `genuinely missing items are reported by name within a bounded wait`() {
    page.setContent(
      """<!DOCTYPE html><html><body><ul id="orders"><li>Latte</li></ul></body></html>""",
    )

    val elapsedMs = withAmbientAssertionTimeout(UNBOUNDED_ASSERTION_TIMEOUT_MS) {
      val startMs = System.currentTimeMillis()
      val tool = PlaywrightNativeVerifyListVisibleTool(
        ref = "css=#orders",
        items = listOf("Latte", "Espresso"),
      )
      val result = runBlocking { tool.executeWithPlaywright(page, buildContext()) }
      val elapsedMs = System.currentTimeMillis() - startMs

      val error = assertIs<TrailblazeToolResult.Error.ExceptionThrown>(result)
      assertContains(error.errorMessage, "Espresso")
      elapsedMs
    }

    assertTrue(
      elapsedMs < WELL_UNDER_THE_AMBIENT_TIMEOUT_MS,
      "One missing item should report in about " +
        "${PlaywrightNativeVerifyListVisibleTool.ITEM_VISIBILITY_TIMEOUT_MS.toLong()}ms, " +
        "took ${elapsedMs}ms — the per-item budget looks like it was dropped.",
    )
  }

  /**
   * Runs [block] with Playwright's ambient assertion timeout raised, then restores it.
   *
   * The ambient default is what a dropped per-item `setTimeout` falls back to, so raising it is
   * what separates the two outcomes. Global state, so it is always restored — a leaked 30s
   * default would silently slow every later assertion in this JVM.
   */
  private fun <T> withAmbientAssertionTimeout(timeoutMs: Double, block: () -> T): T = try {
    PlaywrightAssertions.setDefaultAssertionTimeout(timeoutMs)
    block()
  } finally {
    PlaywrightAssertions.setDefaultAssertionTimeout(PLAYWRIGHT_DEFAULT_ASSERTION_TIMEOUT_MS)
  }
}

/** Playwright's own out-of-the-box assertion timeout, restored after each override. */
private const val PLAYWRIGHT_DEFAULT_ASSERTION_TIMEOUT_MS = 5_000.0

/**
 * The ambient assertion timeout the test installs while verifying a missing item.
 *
 * Deliberately far above `ITEM_VISIBILITY_TIMEOUT_MS`: the regression this test guards is the
 * per-item `setTimeout` being dropped, and a dropped timeout falls back to whatever the ambient
 * default is. Against Playwright's real 5s default the two outcomes are only 3s apart, which
 * leaves no room for a bound that is also safe on a loaded agent. Raising the fallback makes the
 * gap 28s.
 */
private const val UNBOUNDED_ASSERTION_TIMEOUT_MS = 30_000.0

/**
 * Ceiling proving one missing item costs one per-item wait rather than the ambient default.
 *
 * Duration is the only discriminator — the tool names "Espresso" either way — so this one has to
 * be a clock. Correct code takes about `ITEM_VISIBILITY_TIMEOUT_MS` (2s, which genuinely elapses
 * because the item is absent); the regression takes [UNBOUNDED_ASSERTION_TIMEOUT_MS]. 10s sits 8s
 * above the correct path and 20s below the broken one.
 */
private const val WELL_UNDER_THE_AMBIENT_TIMEOUT_MS = 10_000L
