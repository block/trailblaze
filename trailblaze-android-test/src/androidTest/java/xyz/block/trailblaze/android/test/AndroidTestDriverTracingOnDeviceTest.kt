package xyz.block.trailblaze.android.test

import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.datetime.Clock
import org.json.JSONArray
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import xyz.block.trailblaze.android.test.tools.AndroidTestAssertVisibleTool
import xyz.block.trailblaze.api.DriverNodeMatch
import xyz.block.trailblaze.api.TrailblazeNodeSelector
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDeviceInfo
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.logs.client.TrailblazeLogger
import xyz.block.trailblaze.logs.client.TrailblazeSession
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.tracing.TraceLevel
import xyz.block.trailblaze.tracing.TrailblazeTracer
import xyz.block.trailblaze.utils.NoOpElementComparator

/**
 * On-device contract for the ANDROID_TEST driver's phase tracing.
 *
 * Five claims:
 * 1. **The phases are there.** A dispatch at [TraceLevel.VERBOSE] records the driver's three
 *    phases — memory interpolation, the native dispatch, and tool logging.
 * 2. **They nest under the tool.** The phases attach to the enclosing span rather than forming
 *    roots. This is the claim that catches the failure `:trailblaze-android` hit: parenting is
 *    thread-local, and work that crosses a thread silently reparents to the root.
 * 3. **Selector resolution is not counted as backend time.** A native tool resolves its own
 *    selector inside the dispatch, so `resolveSelector` nests inside `nativeDispatch`. Without
 *    that span, a profile reads a slow selector as slow Espresso — the one place it cannot be
 *    fixed. This is the same split the benchmark sink already makes by subtracting the resolve
 *    from `nativeExecutionMs`.
 * 4. **They are VERBOSE-only.** At [TraceLevel.NORMAL] not one of them is recorded, so the hot
 *    replay path this driver exists for pays nothing but a field read.
 * 5. **The published benchmark still works.** `AndroidTestMetricsSink` keeps reporting every phase
 *    while tracing is on. The sink is the numeric surface behind the in-process speedup figure;
 *    spans are additive and must not disturb it.
 *
 * Everything goes through `runTrailblazeTools`, the surface a trail actually has, so the spans are
 * exercised the way production reaches them.
 */
class AndroidTestDriverTracingOnDeviceTest {

  // createEmptyComposeRule is correct HERE because this fixture owns no other Compose harness;
  // a consumer app must instead pass its existing rule (see RuleBackedAndroidTestTarget kdoc).
  @get:Rule val composeRule = createEmptyComposeRule() as AndroidComposeTestRule<*, *>

  /**
   * Nullable rather than `lateinit`: `@After` runs even when `@Before` throws, and closing a
   * `lateinit` that was never assigned would add its own failure on top of the setup failure that
   * actually explains the run.
   */
  private var scenario: ActivityScenario<MixedUiFixtureActivity>? = null
  private lateinit var agent: AndroidTestTrailblazeAgent
  private val recordedTimings = mutableListOf<AndroidTestTiming>()

  /**
   * `TrailblazeTracer` is a process-wide singleton shared with every other test in this
   * instrumentation, so put back whatever level was in force and leave no events behind.
   *
   * Read at construction rather than in [launchFixture]: JUnit runs `@After` even when `@Before`
   * throws, and captured late a setup failure would write this field's default over whatever level
   * the run was actually configured with.
   */
  private val incomingLevel: TraceLevel = TrailblazeTracer.level

  @Before
  fun launchFixture() {
    val launched = ActivityScenario.launch(MixedUiFixtureActivity::class.java)
    scenario = launched
    var activity: MixedUiFixtureActivity? = null
    launched.onActivity { activity = it }
    val fixture = checkNotNull(activity)
    val target =
      RuleBackedAndroidTestTarget(activityProvider = { fixture }, composeTestRule = composeRule)
    val session =
      TrailblazeSession(
        sessionId = SessionId("android_test_driver_tracing_on_device"),
        startTime = Clock.System.now(),
      )
    val deviceInfo =
      TrailblazeDeviceInfo(
        trailblazeDeviceId =
          TrailblazeDeviceId(
            instanceId = "instrumentation",
            trailblazeDevicePlatform = TrailblazeDevicePlatform.ANDROID,
          ),
        trailblazeDriverType = TrailblazeDriverType.ANDROID_TEST,
        widthPixels = fixture.resources.displayMetrics.widthPixels,
        heightPixels = fixture.resources.displayMetrics.heightPixels,
      )
    agent =
      AndroidTestTrailblazeAgent(
        target = target,
        trailblazeLogger = TrailblazeLogger.createNoOp(),
        trailblazeDeviceInfoProvider = { deviceInfo },
        sessionProvider = { session },
        metricsSink = AndroidTestMetricsSink { recordedTimings += it },
        // On, because the logging phase is one of the three spans under test and it is off by
        // default. A consumer that leaves it off simply records two phases instead of three.
        logToolCalls = true,
      )
  }

  @After
  fun restoreTracerAndFixture() {
    TrailblazeTracer.level = incomingLevel
    TrailblazeTracer.clear()
    scenario?.close()
  }

  @Test
  fun verboseDispatchRecordsEveryPhaseOfTheTool() {
    val events = dispatchWithTracingAt(TraceLevel.VERBOSE)

    PHASE_SPAN_NAMES.forEach { name ->
      assertNotNull(
        events.firstNamed(name),
        "A verbose dispatch recorded no `$name` span, so that phase of the driver is invisible " +
          "in the timeline. Recorded: ${events.names()}",
      )
    }
  }

  @Test
  fun phaseSpansNestInsideTheEnclosingToolSpan() {
    val events = dispatchWithTracingAt(TraceLevel.VERBOSE)
    requireNotNull(events.firstNamed(ENCLOSING_SPAN_NAME)) {
      "The enclosing span itself is missing, so there is nothing to assert parenting against."
    }

    // Ancestry, not direct parentage: `BaseTrailblazeAgent` already opens a span per tool call
    // (named by `traceSpanName()`), so the phases sit one level below it. Asserting the enclosing
    // span is an ANCESTOR states the claim that matters — the phases are inside the tool call
    // rather than roots — without pinning the number of levels between them.
    PHASE_SPAN_NAMES.forEach { name ->
      val phase = requireNotNull(events.firstNamed(name)) { "No `$name` span to check parenting on." }
      assertTrue(
        ENCLOSING_SPAN_NAME in events.ancestorNamesOf(phase),
        "`$name` does not nest inside the enclosing tool span. Span parenting is thread-local, so " +
          "a phase that runs on another thread without adopting its caller's span id records as a " +
          "ROOT — which still profiles, but draws the phase beside the tool instead of inside it. " +
          "Ancestors found: ${events.ancestorNamesOf(phase)}",
      )
    }
  }

  @Test
  fun selectorResolutionIsAttributedToTrailblazeRatherThanTheBackend() {
    val events = dispatchWithTracingAt(TraceLevel.VERBOSE)

    val resolve =
      assertNotNull(
        events.firstNamed(RESOLVE_SPAN_NAME),
        "No `$RESOLVE_SPAN_NAME` span, so the hierarchy walk and resolve poll a native tool runs " +
          "inside its own dispatch are indistinguishable from Espresso/Compose time on the " +
          "timeline. Recorded: ${events.names()}",
      )
    assertTrue(
      NATIVE_DISPATCH_SPAN_NAME in events.ancestorNamesOf(resolve),
      "`$RESOLVE_SPAN_NAME` does not nest inside `$NATIVE_DISPATCH_SPAN_NAME`, so the timeline no " +
        "longer shows it as the part of the dispatch that is not backend work — which is the " +
        "same split the benchmark sink makes. Ancestors found: ${events.ancestorNamesOf(resolve)}",
    )
  }

  @Test
  fun phaseSpansAreVerboseOnly() {
    val events = dispatchWithTracingAt(TraceLevel.NORMAL)

    DETAIL_SPAN_NAMES.forEach { name ->
      assertNull(
        events.firstNamed(name),
        "`$name` was recorded at NORMAL. These are per-tool-call detail on the driver's hot replay " +
          "path and must cost nothing unless asked for. Recorded: ${events.names()}",
      )
    }
  }

  @Test
  fun theBenchmarkSinkStillReportsEveryPhaseWhileTracing() {
    dispatchWithTracingAt(TraceLevel.VERBOSE)

    val timing =
      recordedTimings.singleOrNull()
        ?: error("Expected exactly one timing for one dispatched tool, got: $recordedTimings")
    assertEquals(
      "AndroidTestAssertVisibleTool",
      timing.toolName,
      "The sink must keep attributing timings to the AUTHORED tool type.",
    )
    // One assertion per phase, and every clause able to fail. `loggingMs >= 0.0` held for any
    // duration, and `totalMs > 0.0` follows from any phase being non-zero, so a single combined
    // assertion was really one live check wearing three.
    assertTrue(
      timing.orchestrationMs > 0.0,
      "The sink reported no orchestration time. This is the phase that carries the selector " +
        "resolve `AndroidTestPhaseAttribution` subtracts out of the dispatch, so zero here means " +
        "that attribution stopped reaching the sink and the resolve is being sold as backend " +
        "cost: $timing",
    )
    assertTrue(
      timing.nativeExecutionMs > 0.0,
      "The sink reported no native execution time, which is the number the published in-process " +
        "speedup is computed from: $timing",
    )
    assertTrue(
      timing.loggingMs > 0.0,
      "The sink reported no logging time even though this agent runs with `logToolCalls = true`, " +
        "so the phase the tool log is written in has gone missing from the attribution: $timing",
    )
  }

  /**
   * Dispatches one real tool through the driver with the tracer at [level], and returns everything
   * it recorded.
   *
   * The dispatch is wrapped in a plain `trace { }` so there is a known enclosing span to assert
   * parenting against — the same shape production has, where the enclosing span is the tool call.
   */
  private fun dispatchWithTracingAt(level: TraceLevel): JSONArray {
    // Cleared before setting the level so nothing the fixture launch recorded is counted.
    TrailblazeTracer.clear()
    recordedTimings.clear()
    TrailblazeTracer.level = level

    val result =
      TrailblazeTracer.trace(ENCLOSING_SPAN_NAME) {
        execute(AndroidTestAssertVisibleTool(viewText(MixedUiFixtureActivity.VIEW_STATUS_INITIAL)))
      }
    // Not an assertion about tracing, but a dispatch that failed would make every span assertion
    // below measure nothing — and would report as a missing-span failure, which reads as a tracing
    // regression rather than the broken dispatch it actually is.
    assertIs<TrailblazeToolResult.Success>(
      result,
      "The dispatch under test failed, so the span assertions would prove nothing: $result",
    )
    return JSONArray(TrailblazeTracer.exportJson())
  }

  private fun viewText(text: String) =
    TrailblazeNodeSelector(androidView = DriverNodeMatch.AndroidView(textRegex = text))

  private fun execute(tool: TrailblazeTool): TrailblazeToolResult =
    agent.runTrailblazeTools(
      tools = listOf(tool),
      traceId = null,
      screenState = null,
      elementComparator = NoOpElementComparator,
      screenStateProvider = agent.screenStateProvider,
    ).result

  private fun JSONArray.firstNamed(name: String) =
    (0 until length())
      .map { getJSONObject(it) }
      .firstOrNull { it.optString("name") == name && it.optString("ph") == "X" }

  private fun JSONArray.names(): List<String> =
    (0 until length()).map { getJSONObject(it).optString("name") }

  /**
   * The names of [span]'s ancestors, innermost first, by following `psid` up.
   *
   * Guards against a cycle rather than trusting the data: this walks producer output, and a loop
   * would hang the test instead of failing it.
   */
  private fun JSONArray.ancestorNamesOf(span: org.json.JSONObject): List<String> {
    val bySid =
      (0 until length())
        .map { getJSONObject(it) }
        .filter { it.optString("ph") == "X" && it.optString("sid").isNotEmpty() }
        .associateBy { it.getString("sid") }
    val ancestors = mutableListOf<String>()
    val seen = mutableSetOf(span.optString("sid"))
    var parentId = span.optString("psid")
    while (parentId.isNotEmpty() && seen.add(parentId)) {
      val parent = bySid[parentId] ?: break
      ancestors += parent.optString("name")
      parentId = parent.optString("psid")
    }
    return ancestors
  }

  companion object {
    private const val ENCLOSING_SPAN_NAME = "enclosingToolCall"
    private const val NATIVE_DISPATCH_SPAN_NAME = "nativeDispatch"

    /** Trailblaze's own work inside the native dispatch — see `resolveNode`. */
    private const val RESOLVE_SPAN_NAME = "resolveSelector"

    /** The three phases `executeMeasured` attributes, in dispatch order. */
    private val PHASE_SPAN_NAMES =
      listOf("interpolateMemory", NATIVE_DISPATCH_SPAN_NAME, "logToolExecution")

    /** Every span this driver records only at [TraceLevel.VERBOSE]. */
    private val DETAIL_SPAN_NAMES = PHASE_SPAN_NAMES + RESOLVE_SPAN_NAME
  }
}
