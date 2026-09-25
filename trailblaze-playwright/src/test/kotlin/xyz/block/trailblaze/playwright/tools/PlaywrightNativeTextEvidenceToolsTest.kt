package xyz.block.trailblaze.playwright.tools

import com.microsoft.playwright.Browser
import com.microsoft.playwright.BrowserType
import com.microsoft.playwright.Page
import com.microsoft.playwright.Playwright
import com.microsoft.playwright.options.ViewportSize
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlinx.datetime.Clock
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
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
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

class PlaywrightNativeTextEvidenceToolsTest {
  /**
   * Hang containment, not a performance budget: every test here finishes in well under a second,
   * and this only fires if a browser call or the tool's poll loop wedges. It exists because this
   * class runs on a required merge gate, where an unbounded hang costs the whole step's 30-minute
   * budget and produces no JUnit result naming the method that stuck.
   */
  @get:Rule val perTestTimeout: Timeout = Timeout.seconds(60)

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

  /**
   * Runs [tool] and applies [changeThePage] once the browser-side absence observer is installed
   * and the tool is parked between status polls.
   *
   * Playwright is single-threaded, and so is this [runBlocking] event loop, so [changeThePage]
   * runs while the tool coroutine is suspended — the page change is ordered after the start of
   * the observation window by construction. Tests must not instead schedule the change with an
   * in-page timer and bet that it lands inside the window: on a loaded machine the timer can fire
   * before the observer exists, or the window can close first, and the test then passes or fails
   * for a reason it never meant to assert.
   *
   * The wait is for an observation that is still *watching*, not merely present, so the change
   * cannot land on a page whose observation has already latched its outcome.
   *
   * Two things this relies on. The caller must be a confined single-threaded [runBlocking]: the
   * probe below and the tool both drive the same thread-affine Playwright [page], so a
   * multi-threaded dispatcher would turn this into a hard Playwright error. And the tool must not
   * suspend before installing its observer. There is deliberately no wall-clock bound here — the
   * per-test [perTestTimeout] rule is the single hang bound, because a tighter inner bound would
   * fire first on a stalled agent and blame a missing observer for a slow machine.
   */
  private suspend fun CoroutineScope.observeAbsenceWhile(
    tool: PlaywrightNativeVerifyTextAbsentForDurationTool,
    changeThePage: suspend () -> Unit,
  ): TrailblazeToolResult {
    val verification = async { tool.executeWithPlaywright(page, context()) }
    while (verification.isActive && !anObservationIsOpen()) {
      yield()
    }
    if (!verification.isActive) {
      val outcome =
        runCatching { verification.await() }.fold({ "the tool returned $it" }, { "the tool threw $it" })
      fail(
        "No observation was ever open under window.$TEXT_ABSENCE_RUNTIME_KEY, so this test never " +
          "synchronized with the observation window and asserts nothing: $outcome.",
      )
    }
    changeThePage()
    return verification.await()
  }

  /**
   * Sits through [polls] of the tool's status polls before returning, re-checking after each one
   * that the observation is still open. Tests use this instead of a bare [delay] so that a stalled
   * agent produces this named failure rather than silently carrying the caller's page change past
   * the end of the observation window, where an expected failure would read as a pass.
   */
  private suspend fun waitOutPollsWhileObserving(polls: Int) {
    repeat(polls) { poll ->
      delay(TOOL_POLL_INTERVAL_MS)
      assertTrue(
        anObservationIsOpen(),
        "The observation window closed after ${poll + 1} of $polls polls, before this test could " +
          "change the page. Nothing was verified.",
      )
    }
  }

  private fun anObservationIsOpen(): Boolean = page.evaluate(OBSERVATION_IS_OPEN_SCRIPT) == true

  private fun anObservationExists(): Boolean = page.evaluate(OBSERVATION_EXISTS_SCRIPT) == true

  @Test
  fun `viewport requirement scrolls exact visible text into the viewport`() = runBlocking {
    page.setContent("<main style='height:1400px'></main><h2>Exact proof</h2>")
    val result = PlaywrightNativeRequireTextInViewportTool("Exact proof", timeoutMs = 1_000)
      .executeWithPlaywright(page, context())
    assertIs<TrailblazeToolResult.Success>(result, result.toString())
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

      assertIs<TrailblazeToolResult.Success>(result, result.toString())
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
    assertIs<TrailblazeToolResult.Error.ExceptionThrown>(result, result.toString())
    // The Error alone proves nothing: a tool that ignored the caller's 25ms and used its own 30s
    // default would return the same Error, 30 seconds later. Only the elapsed time separates them.
    assertTrue(
      elapsedMs < FAR_UNDER_THE_DEFAULT_TIMEOUT_MS,
      "The 25ms budget looks like it was replaced by the tool's own default: took ${elapsedMs}ms.",
    )
  }

  /**
   * The thrown type carries this on its own. A tool that stopped honouring cancellation has to stop
   * suspending to do it, and then it throws something other than [TimeoutCancellationException] —
   * verified by mutation: dropping the loop's `ensureActive` calls and blocking the thread instead
   * of suspending fails this on the exception type, with no clock involved. An elapsed-time bound
   * here used to stand in for "promptly" and pinned nothing that this does not.
   */
  @Test
  fun `viewport requirement propagates coroutine cancellation`() = runBlocking {
    page.setContent("<h1>Different text</h1>")

    assertFailsWith<TimeoutCancellationException> {
      withTimeout(50) {
        PlaywrightNativeRequireTextInViewportTool("Missing proof", timeoutMs = 30_000)
          .executeWithPlaywright(page, context())
      }
    }
    Unit
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
    assertIs<TrailblazeToolResult.Error.ExceptionThrown>(result, result.toString())
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

  /**
   * The tool installs its browser-side observer for exactly the span of one verification and
   * removes it again on the way out. Every [observeAbsenceWhile] test depends on the first half,
   * and nothing else covers the second.
   */
  @Test
  fun `the observer exists for the span of a verification and no longer`() = runBlocking {
    page.setContent("<h1>Home</h1>")
    assertFalse(anObservationExists(), "An observation was already installed before any tool ran.")

    var installedMidVerification = false
    val result = observeAbsenceWhile(
      PlaywrightNativeVerifyTextAbsentForDurationTool(
        text = "Forbidden",
        durationMs = 800,
        requiredVisibleNodeSelector = homeHeading,
      )
    ) {
      installedMidVerification = anObservationExists()
    }

    assertTrue(installedMidVerification, "No observer was installed while the tool was verifying.")
    assertIs<TrailblazeToolResult.Success>(result, result.toString())
    assertFalse(anObservationExists(), "The tool left its observer installed after returning.")
  }

  @Test
  fun `text absent across many polls succeeds`() = runBlocking {
    page.setContent("<h1>Home</h1><div hidden>Forbidden</div>")
    val result = PlaywrightNativeVerifyTextAbsentForDurationTool(
      text = "Forbidden",
      durationMs = 1_000,
      requiredVisibleNodeSelector = homeHeading,
    ).executeWithPlaywright(page, context())
    assertIs<TrailblazeToolResult.Success>(result, result.toString())
    Unit
  }

  @Test
  fun `forbidden text appearing several polls into the window fails`() = runBlocking {
    page.setContent("<h1>Home</h1><div id='target' hidden>Forbidden</div>")
    val result = observeAbsenceWhile(
      PlaywrightNativeVerifyTextAbsentForDurationTool(
        text = "Forbidden",
        durationMs = 2_000,
        requiredVisibleNodeSelector = homeHeading,
      )
    ) {
      // Exercises a steady observation window and a late change together: the wait confirms the
      // window is still open after each poll, so the reveal cannot drift past the end of it
      // unnoticed.
      waitOutPollsWhileObserving(polls = 5)
      page.evaluate("document.querySelector('#target').hidden = false")
    }
    assertIs<TrailblazeToolResult.Error.ExceptionThrown>(result, result.toString())
    assertTrue(result.errorMessage.contains("forbidden text became visible"), result.errorMessage)
    Unit
  }

  @Test
  fun `transient forbidden text fails before the terminal boundary`() = runBlocking {
    page.setContent("<h1>Home</h1><div id='target' hidden>Forbidden</div>")
    val result = observeAbsenceWhile(
      PlaywrightNativeVerifyTextAbsentForDurationTool(
        text = "Forbidden",
        durationMs = 800,
        requiredVisibleNodeSelector = homeHeading,
      )
    ) {
      page.evaluate("document.querySelector('#target').hidden = false")
    }
    assertIs<TrailblazeToolResult.Error.ExceptionThrown>(result, result.toString())
    assertTrue(result.errorMessage.contains("forbidden text became visible"), result.errorMessage)
    Unit
  }

  @Test
  fun `forbidden text appearing after the terminal boundary does not fail`() = runBlocking {
    page.setContent("<h1>Home</h1><div id='target' hidden>Forbidden</div>")
    var blockedForMs = 0.0
    val result = observeAbsenceWhile(
      PlaywrightNativeVerifyTextAbsentForDurationTool(
        text = "Forbidden",
        durationMs = 200,
        requiredVisibleNodeSelector = homeHeading,
      )
    ) {
      // Holding the event loop past the deadline is what puts the reveal after the terminal
      // boundary. The spin runs inline rather than on a timer so this returns only once the reveal
      // has happened, and it outlasts the whole window: the observation started no later than the
      // spin did, so a spin longer than durationMs lands the reveal provably past the deadline —
      // and the page cannot run the deadline timer or the animation-frame poll while it spins.
      blockedForMs = page.evaluate(
        """
        () => {
          const startedAt = performance.now();
          while (performance.now() - startedAt < 400) { /* hold the event loop */ }
          document.querySelector('#target').hidden = false;
          return performance.now() - startedAt;
        }
        """.trimIndent()
        // Playwright hands back an Integer for an integral JS number and a Double otherwise.
      ).let { (it as Number).toDouble() }
    }
    assertTrue(blockedForMs >= 400, "The event loop was only held for ${blockedForMs}ms.")
    assertTrue(
      page.evaluate("() => !document.querySelector('#target').hidden") == true,
      "The forbidden text was never revealed, so nothing appeared after the boundary.",
    )
    assertIs<TrailblazeToolResult.Success>(result, result.toString())
    Unit
  }

  @Test
  fun `forbidden text flash shorter than the Kotlin polling interval fails`() = runBlocking {
    page.setContent("<h1>Home</h1><div id='target' hidden>Forbidden</div>")
    val result = observeAbsenceWhile(
      PlaywrightNativeVerifyTextAbsentForDurationTool(
        text = "Forbidden",
        durationMs = 800,
        requiredVisibleNodeSelector = homeHeading,
      )
    ) {
      page.evaluate(flashTargetScript())
    }
    assertIs<TrailblazeToolResult.Error.ExceptionThrown>(result, result.toString())
    assertTrue(result.errorMessage.contains("forbidden text became visible"), result.errorMessage)
    Unit
  }

  @Test
  fun `forbidden text flash inside an open shadow root fails`() = runBlocking {
    page.setContent("<h1>Home</h1><div id='host'></div>")
    page.evaluate(
      """
      const root = document.querySelector('#host').attachShadow({ mode: 'open' });
      root.innerHTML = '<div id="target" hidden>Forbidden</div>';
      """.trimIndent()
    )
    val result = observeAbsenceWhile(
      PlaywrightNativeVerifyTextAbsentForDurationTool(
        text = "Forbidden",
        durationMs = 800,
        requiredVisibleNodeSelector = homeHeading,
      )
    ) {
      page.evaluate(flashTargetScript(hostSelectorWithShadowRoot = "#host"))
    }
    assertIs<TrailblazeToolResult.Error.ExceptionThrown>(result, result.toString())
    assertTrue(result.errorMessage.contains("forbidden text became visible"), result.errorMessage)
    Unit
  }

  @Test
  fun `forbidden text in a shadow root attached after observation starts fails`() = runBlocking {
    page.setContent("<h1>Home</h1><div id='host'></div>")
    val result = observeAbsenceWhile(
      PlaywrightNativeVerifyTextAbsentForDurationTool(
        text = "Forbidden",
        durationMs = 800,
        requiredVisibleNodeSelector = homeHeading,
      )
    ) {
      page.evaluate(
        """
        const root = document.querySelector('#host').attachShadow({ mode: 'open' });
        root.innerHTML = '<div>Forbidden</div>';
        """.trimIndent()
      )
    }
    assertIs<TrailblazeToolResult.Error.ExceptionThrown>(result, result.toString())
    assertTrue(result.errorMessage.contains("forbidden text became visible"), result.errorMessage)
    Unit
  }

  @Test
  fun `loss of readiness fails sustained absence`() = runBlocking {
    page.setContent("<h1 id='home'>Home</h1>")
    val result = observeAbsenceWhile(
      PlaywrightNativeVerifyTextAbsentForDurationTool(
        text = "Forbidden",
        durationMs = 800,
        requiredVisibleNodeSelector = homeHeading,
      )
    ) {
      page.evaluate("document.querySelector('#home').hidden = true")
    }
    assertIs<TrailblazeToolResult.Error.ExceptionThrown>(result, result.toString())
    assertTrue(result.errorMessage.contains("readiness anchor stopped being visible"), result.errorMessage)
    Unit
  }

  @Test
  fun `navigation that removes the browser observer fails sustained absence`() = runBlocking {
    page.setContent("<h1>Home</h1>")
    val result = observeAbsenceWhile(
      PlaywrightNativeVerifyTextAbsentForDurationTool(
        text = "Forbidden",
        durationMs = 800,
        requiredVisibleNodeSelector = homeHeading,
      )
    ) {
      // Returns once the new document has loaded, so the observer is already gone — and cannot
      // still be mid-teardown — before the tool takes its next status poll.
      page.reload()
    }
    assertIs<TrailblazeToolResult.Error.ExceptionThrown>(result, result.toString())
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
    assertIs<TrailblazeToolResult.Error.ExceptionThrown>(result, result.toString())
    Unit
  }
}

/** The tool's own Kotlin-side status poll interval, mirrored so tests can wait whole polls. */
private const val TOOL_POLL_INTERVAL_MS = 100L

/**
 * Ceiling proving a viewport requirement finished well before the tool's own 30 second default
 * timeout would have let it.
 *
 * This is the rare assertion that genuinely needs a clock: the tool returns the same error whether
 * it honoured the 25ms it was given or silently used its default, so only the duration separates
 * them. Sized to discriminate exactly that and nothing finer — the enclosed work is real CDP round
 * trips, so a tight bound would fail on a loaded agent for reasons unrelated to the budget being
 * respected, which is the flake shape this file exists to be free of. Mutation-checked: making the
 * tool use `MAX_DURATION_MS` instead of the caller's value takes the full 30s, against ~50ms here.
 */
private const val FAR_UNDER_THE_DEFAULT_TIMEOUT_MS = 10_000L

/** Whether an observation exists at all, whatever state it reached. */
private val OBSERVATION_EXISTS_SCRIPT =
  "() => (window.$TEXT_ABSENCE_RUNTIME_KEY?.observations.size ?? 0) > 0"

/**
 * Whether an observation is still *watching*. Presence alone is not enough to synchronize against:
 * an observation that has already latched its outcome stays in the map until the tool removes it on
 * the way out, so a test that mutated the page on presence could be mutating a page nothing is
 * watching any more, turning an expected failure into a pass.
 */
private val OBSERVATION_IS_OPEN_SCRIPT =
  """
  () => {
    const observations = window.$TEXT_ABSENCE_RUNTIME_KEY?.observations;
    return !!observations && [...observations.values()].some(it => it.status === 'observing');
  }
  """.trimIndent()

/**
 * Reveals `#target` and hides it again 20ms later — far shorter than the tool's 100ms Kotlin-side
 * status poll, so only the browser-side observer can catch it. The re-hide is a timer rather than a
 * second statement because the observer reads live DOM state from a microtask: a synchronous
 * reveal-and-hide would leave nothing for it to see.
 *
 * [hostSelectorWithShadowRoot] names the element whose open shadow root holds `#target`, or is null
 * when `#target` sits in the main document. Passing it explicitly rather than sniffing for a host
 * keeps the script from silently searching the wrong root.
 */
private fun flashTargetScript(hostSelectorWithShadowRoot: String? = null): String {
  val root = hostSelectorWithShadowRoot
    ?.let { "document.querySelector('$it').shadowRoot" }
    ?: "document"
  return """
    () => {
      const target = $root.querySelector('#target');
      if (!target) throw new Error("No #target to flash under $root");
      target.hidden = false;
      setTimeout(() => target.hidden = true, 20);
    }
  """.trimIndent()
}
