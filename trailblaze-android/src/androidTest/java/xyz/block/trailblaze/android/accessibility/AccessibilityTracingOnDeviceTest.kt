package xyz.block.trailblaze.android.accessibility

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import xyz.block.trailblaze.tracing.TraceLevel
import xyz.block.trailblaze.tracing.TrailblazeTracer
import xyz.block.trailblaze.util.Console

/**
 * On-device (connected) proof that the accessibility capture path records the spans
 * `trailblaze profile` reads — against a real accessibility tree on a real screen, which is the
 * only place this can be shown. The limb is on-device by construction: no JVM test can bind the
 * accessibility service, so without this the spans would be "it compiles" and nothing more.
 *
 * Five claims, each of which fails loudly if the instrumentation regresses:
 *
 * 1. **The phases are there.** A capture at [TraceLevel.VERBOSE] records the named phases of the
 *    screen-state build and of the framework work underneath it. A phase that stops being wrapped
 *    disappears from the profile silently — a 21-second block simply reads as one opaque bar again —
 *    so the names are asserted individually rather than by count.
 *
 * 2. **Each span carries its layer's category.** A profile groups by category, so a copy-pasted
 *    constant is invisible to claim 1 and wrong everywhere it matters.
 *
 * 3. **The parallel screenshot lands INSIDE the capture, on another thread.** The screenshot runs on
 *    `tb-screenshot-capture`, and a span's parent is per-thread, so the hand-off in
 *    [AccessibilityServiceScreenState] is the only thing keeping those spans from recording as roots
 *    beside the capture instead of children overlapping it. Dropping that hand-off still compiles
 *    and still profiles — it just draws the screenshot next to the work it actually overlaps.
 *
 * 4. **They are VERBOSE-only.** These fire hundreds of times per step; at [TraceLevel.NORMAL] not
 *    one of them may record. A `traceDetail` that became a `trace` would pass claim 1 and quietly
 *    change the shape of every ordinary run.
 *
 * 5. **No span records a text payload.** `trace.json` is uploaded to the host, packaged into report
 *    artifacts and exported as OpenTelemetry attributes, so a typed password or a
 *    `rememberSensitive` value must not reach it. Actions carry their text in
 *    `AccessibilityAction.description`, which makes "name the action in its span" a one-word change
 *    away from a leak — and one that no other test would notice.
 *
 * The capture runs through [AccessibilityDeviceManager], the way production reaches it, so the
 * driver-level spans are covered too and not just the screen state's own.
 *
 * What this does NOT prove: that a host-side `TRAILBLAZE_TRACE_LEVEL=verbose` turns these on. The
 * level reaches the host and its daemon but not this instrumentation process, so the tests below set
 * it on the device's own tracer — the only lever an instrumentation test has. Read a green run as
 * "the spans exist and are correctly shaped", not "a verbose run records them".
 *
 * Reuses [CoverageFixtureActivity]'s full-width layout as a real foreground screen, like the other
 * connected tests in this package.
 */
class AccessibilityTracingOnDeviceTest {

  /**
   * `TrailblazeTracer` is a process-wide singleton shared with everything else in this
   * instrumentation, so put back whatever level was in force and leave no events behind.
   *
   * Read at construction, not in [enableAccessibilityService]: JUnit runs `@After` even when
   * `@Before` throws, and the setup below can time out. Captured late, a setup failure would make
   * teardown write this field's default over whatever level the run was actually configured with.
   */
  private val incomingLevel: TraceLevel = TrailblazeTracer.level

  @Before
  fun enableAccessibilityService() {
    OnDeviceAccessibilityServiceSetup.ensureUiAutomationDoesNotSuppressAccessibility()
    OnDeviceAccessibilityServiceSetup.ensureAccessibilityServiceReady(timeoutMs = 15_000)
  }

  @After
  fun restoreTracer() {
    TrailblazeTracer.level = incomingLevel
    TrailblazeTracer.clear()
  }

  @Test
  fun verboseCapture_recordsTheScreenStateBuildPhases() {
    val events = captureWithTracingAt(TraceLevel.VERBOSE)

    VERBOSE_ONLY_SPANS.forEach { name ->
      assertNotNull(
        "Expected a \"$name\" span from the on-device capture. Recorded: ${events.names()}",
        events.firstNamed(name),
      )
    }
  }

  @Test
  fun eachSpanIsRecordedUnderItsOwnLayersCategory() {
    val events = captureWithTracingAt(TraceLevel.VERBOSE)

    // One span per production file. The category is what a profile groups by, so a copy-pasted
    // constant is invisible in the name assertions above but wrong in every profile.
    mapOf(
      "getScreenState" to "driver",
      "buildViewHierarchy" to "screenState",
      "awaitTreeStable" to "androidAccessibility",
    ).forEach { (name, expectedCat) ->
      val span = requireNotNull(events.firstNamed(name)) {
        "Missing the \"$name\" span. Recorded: ${events.names()}"
      }
      assertEquals(
        "\"$name\" must be recorded under the category its layer declares.",
        expectedCat,
        span.optString("cat"),
      )
    }
  }

  @Test
  fun parallelScreenshot_isRecordedOnItsOwnThreadInsideTheCapture() {
    val events = captureWithTracingAt(TraceLevel.VERBOSE)

    val enclosing = requireNotNull(events.firstNamed(ENCLOSING_SPAN_NAME)) {
      "Missing the test's own enclosing span. Recorded: ${events.names()}"
    }
    val screenshot = requireNotNull(events.firstNamed("captureScreenshot")) {
      "Missing the screenshot span. Recorded: ${events.names()}"
    }

    // Walked, not compared directly: what matters is that the screenshot ended up INSIDE the
    // capture, not how deep. Asserting `psid == enclosing.sid` would pin the exact nesting depth
    // and redden the moment any span is added between them — which is exactly what happened when
    // this capture started going through the driver.
    assertTrue(
      "The screenshot span must sit somewhere under the enclosing capture. Its psid " +
        "(\"${screenshot.optString("psid")}\") led to ${events.ancestorNamesOf(screenshot)} " +
        "instead. An absent or unresolvable psid means the cross-thread hand-off was lost, and the " +
        "profile will draw the screenshot beside the tree build rather than overlapping it.",
      events.ancestorNamesOf(screenshot).contains(ENCLOSING_SPAN_NAME),
    )
    // The parenting above is only interesting because the work really is on another thread; if it
    // ran inline, the hand-off would be unnecessary and the "parallel capture" comment a lie.
    assertTrue(
      "Expected the screenshot to be captured off the calling thread (tid " +
        "${enclosing.getLong("tid")}), but it recorded on the same one.",
      screenshot.getLong("tid") != enclosing.getLong("tid"),
    )
  }

  @Test
  fun normalLevel_recordsNoneOfTheseSpans() {
    val events = captureWithTracingAt(TraceLevel.NORMAL)

    // The enclosing span is a plain `trace { }`, so it MUST still record — otherwise this test
    // would pass just as well against a capture that never ran.
    assertNotNull(
      "The NORMAL-level control span is missing, so this run proves nothing about the capture.",
      events.firstNamed(ENCLOSING_SPAN_NAME),
    )
    // The SAME list the VERBOSE test asserts is present. Two lists let a `traceDetail` that became
    // a `trace` sit in the gap between them and pass both tests.
    VERBOSE_ONLY_SPANS.forEach { name ->
      assertNull(
        "\"$name\" is a traceDetail span and must not record at NORMAL. Recorded: ${events.names()}",
        events.firstNamed(name),
      )
    }
  }

  @Test
  fun actionSpanRecordsTheActionTypeAndNeverItsTextPayload() {
    TrailblazeTracer.clear()
    TrailblazeTracer.level = TraceLevel.VERBOSE

    // SetClipboard puts its text straight into `AccessibilityAction.description`, the same way
    // InputText does, and needs no focused field to dispatch — so it is the cheapest way to drive a
    // text-bearing action through `execute()` for real.
    AccessibilityDeviceManager(deviceClassifiers = emptyList())
      .execute(AccessibilityAction.SetClipboard(SECRET))

    val events = JSONArray(TrailblazeTracer.exportJson())
    val span = requireNotNull(events.firstNamed("executeAction")) {
      "Missing the \"executeAction\" span, so this test proves nothing. Recorded: ${events.names()}"
    }
    assertEquals(
      "The action span must name the action's TYPE. A description groups nothing in a profile " +
        "(it is unique per call) and carries the payload.",
      "SetClipboard",
      span.getJSONObject("args").optString("action"),
    )
    // The whole recording, not just that one arg: a payload that reappears under another key, or in
    // a nested span's args, is the same leak.
    assertFalse(
      "A trace recorded the action's text payload. `trace.json` is uploaded to the host, packaged " +
        "into report artifacts and exported as OpenTelemetry attributes, so a typed password or a " +
        "rememberSensitive value must never reach it. Recorded: ${TrailblazeTracer.exportJson()}",
      TrailblazeTracer.exportJson().contains(SECRET),
    )
  }

  /**
   * Runs one real capture through the production path with the tracer at [level], and returns
   * everything it recorded.
   *
   * The capture is wrapped in a plain `trace { }` so there is a known enclosing span to assert
   * parenting against — the same shape production has, where the enclosing span is the tool call.
   *
   * Captures through [AccessibilityDeviceManager] rather than constructing the screen state
   * directly, because that is how production reaches it — and it is the only way the driver-level
   * spans are exercised at all.
   */
  private fun captureWithTracingAt(level: TraceLevel): JSONArray {
    val context = InstrumentationRegistry.getInstrumentation().context
    val intent =
      Intent(context, CoverageFixtureActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        .putExtra(CoverageFixtureActivity.EXTRA_LAYOUT, CoverageFixtureActivity.LAYOUT_FULL_WIDTH)

    ActivityScenario.launch<CoverageFixtureActivity>(intent).use {
      TrailblazeAccessibilityService.waitForSettled(timeoutMs = 5_000)
      // Clear AFTER the settle, so nothing the fixture launch happened to record is counted.
      TrailblazeTracer.clear()
      TrailblazeTracer.level = level
      val screenState = TrailblazeTracer.trace(ENCLOSING_SPAN_NAME) {
        AccessibilityDeviceManager(deviceClassifiers = emptyList()).getScreenState()
      }
      // Not assertions about tracing, but a capture that came back empty would make the span
      // assertions measure nothing — and would report as a missing-span failure, which reads as a
      // tracing regression rather than the no-windows capture it actually is.
      assertTrue(
        "The capture produced no screenshot bytes, so it did not exercise the parallel path.",
        screenState.screenshotBytes?.isNotEmpty() == true,
      )
      assertNotNull(
        "The capture produced no accessibility tree, so the tree-build spans below would be " +
          "absent for reasons that have nothing to do with tracing.",
        screenState.trailblazeNodeTree,
      )
      val events = JSONArray(TrailblazeTracer.exportJson())
      Console.log("[tracing-test] level=$level recorded ${events.length()} spans: ${events.names()}")
      return events
    }
  }

  private fun JSONArray.firstNamed(name: String) =
    (0 until length())
      .map { getJSONObject(it) }
      .firstOrNull { it.optString("name") == name && it.optString("ph") == "X" }

  private fun JSONArray.names(): List<String> =
    (0 until length()).map { getJSONObject(it).optString("name") }

  /**
   * The names of [span]'s ancestors, innermost first, by following `psid` up.
   *
   * Guards against a cycle in the declared parentage rather than trusting it: this walks producer
   * data, and a loop here would hang the test instead of failing it.
   */
  private fun JSONArray.ancestorNamesOf(span: JSONObject): List<String> {
    val bySid = (0 until length())
      .map { getJSONObject(it) }
      .filter { it.optString("sid").isNotEmpty() }
      .associateBy { it.getString("sid") }
    val ancestors = mutableListOf<String>()
    val seen = mutableSetOf<String>()
    var cursor = span.optString("psid")
    while (cursor.isNotEmpty() && seen.add(cursor)) {
      val parent = bySid[cursor] ?: break
      ancestors += parent.optString("name")
      cursor = parent.optString("psid")
    }
    return ancestors
  }

  companion object {
    /** Stands in for the tool-call span that encloses a capture in production. */
    private const val ENCLOSING_SPAN_NAME = "tracing-test-capture"

    /** Distinctive enough that a substring search over the whole recording means something. */
    private const val SECRET = "tracing-test-secret-payload-8f2c"

    /**
     * Every span one capture records, asserted PRESENT at verbose and ABSENT at normal.
     *
     * One list serves both directions on purpose. With a list per test, a `traceDetail` that turned
     * into an ungated `trace` only has to be missing from the normal-level list to pass both.
     */
    private val VERBOSE_ONLY_SPANS = listOf(
      // The driver operations around the capture.
      "getScreenState",
      "waitForReady",
      // The phases of the screen-state build, in the order the constructor runs them.
      "getScreenDimensions",
      "getCurrentActivity",
      "captureMergedScreenTrees",
      "buildViewHierarchy",
      "buildTrailblazeNodeTree",
      "awaitScreenshotThread",
      // The framework work underneath the capture, which is where the time actually goes.
      "awaitTreeStable",
      "getCaptureWindowRoots",
      "refreshTree",
      "buildMaestroTree",
      "buildAccessibilityTree",
      "captureScreenshot",
      "scaleAndEncodeScreenshot",
    )
  }
}
