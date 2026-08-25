package xyz.block.trailblaze.android.accessibility

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.datetime.Clock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import xyz.block.trailblaze.api.AgentDriverAction
import xyz.block.trailblaze.api.DriverNodeMatch
import xyz.block.trailblaze.api.TapDispatchRoute
import xyz.block.trailblaze.api.TrailblazeNodeSelector
import xyz.block.trailblaze.logs.client.LogEmitter
import xyz.block.trailblaze.logs.client.ScreenStateLogger
import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.logs.client.TrailblazeLogger
import xyz.block.trailblaze.logs.client.TrailblazeSession
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult

/**
 * On-device (connected) proof that a tap which resolved *nothing* is distinguishable from one that
 * resolved, in the log a reviewer actually reads.
 *
 * A tap delivered at the coordinates captured when a trail was recorded reports **success**, so
 * `dispatchRoute` is the only thing separating it from a tap whose selector matched. The JVM tests
 * cover the decision (`PlanUnresolvedTapOutcomeTest`) and the mapping
 * (`AccessibilityTrailRunnerTapRouteLoggingTest`) in isolation; this closes the two hops they
 * cannot reach — the driver dispatching against a real accessibility tree, and
 * [AccessibilityTrailRunner.logAsync] writing the result into an emitted [TrailblazeLog].
 *
 * It also pins the behavior every Android tap gets today: **no caller populates recorded
 * coordinates**, so a selector miss takes the failing exit rather than tapping blind. That is the
 * claim the docs make, and `selectorMissWithNoRecordedPoint_failsInsteadOfTappingBlind` is what
 * keeps it honest. The recorded-coordinate cases construct the action directly, since no production
 * caller can produce one.
 *
 * Reuses [CoverageFixtureActivity]'s full-width layout as a real foreground screen — its labels
 * ("Alpha" … "Golf") give both an unmatchable selector and a genuinely resolvable one.
 */
class RecordedCoordinateFallbackOnDeviceTest {

  private val emittedLogs = mutableListOf<TrailblazeLog>()

  /** Real collaborators — both are `fun interface`s, so no fake type is involved. */
  private val logger = TrailblazeLogger(
    logEmitter = LogEmitter { emittedLogs.add(it) },
    screenStateLogger = ScreenStateLogger { it.fileName },
  )

  private val session = TrailblazeSession(
    sessionId = SessionId("recorded-coordinate-fallback-on-device-test"),
    startTime = Clock.System.now(),
  )

  @Before
  fun enableAccessibilityService() {
    emittedLogs.clear()
    OnDeviceAccessibilityServiceSetup.ensureUiAutomationDoesNotSuppressAccessibility()
    OnDeviceAccessibilityServiceSetup.ensureAccessibilityServiceReady(timeoutMs = 15_000)
  }

  @Test
  fun selectorMissWithRecordedPoint_succeedsAndLogsTheMissAtThatPoint() {
    val point = screenCenter()

    val result = runOnFixture(
      AccessibilityAction.TapOnElement(
        nodeSelector = unmatchableSelector,
        fallbackX = point.first,
        fallbackY = point.second,
        timeoutMs = MISS_TIMEOUT_MS,
      ),
    )

    assertTrue(
      "A tap at recorded coordinates reports success — that is the whole problem. Got: $result",
      result is TrailblazeToolResult.Success,
    )
    val tap = singleLoggedAction() as? AgentDriverAction.TapPoint
    assertNotNull("Expected a TapPoint in the driver log, got ${singleLoggedAction()}", tap)
    assertEquals(
      "The miss must survive into the emitted log, not just logcat.",
      TapDispatchRoute.RECORDED_COORDINATES_AFTER_SELECTOR_MISS,
      tap!!.dispatchRoute,
    )
    assertEquals("Tap should be logged at the recorded point", point.first, tap.x)
    assertEquals(point.second, tap.y)
  }

  @Test
  fun longPressMissWithRecordedPoint_logsTheMissToo() {
    val point = screenCenter()

    val result = runOnFixture(
      AccessibilityAction.TapOnElement(
        nodeSelector = unmatchableSelector,
        longPress = true,
        fallbackX = point.first,
        fallbackY = point.second,
        timeoutMs = MISS_TIMEOUT_MS,
      ),
    )

    assertTrue("Got: $result", result is TrailblazeToolResult.Success)
    val longPress = singleLoggedAction() as? AgentDriverAction.LongPressPoint
    assertNotNull("Expected a LongPressPoint, got ${singleLoggedAction()}", longPress)
    assertEquals(
      "A long-press fallback is the same false evidence as a tap fallback.",
      TapDispatchRoute.RECORDED_COORDINATES_AFTER_SELECTOR_MISS,
      longPress!!.dispatchRoute,
    )
  }

  @Test
  fun selectorMissWithNoRecordedPoint_failsInsteadOfTappingBlind() {
    // The shape every Android accessibility tap has today: nothing populates recorded
    // coordinates, so an unresolved selector fails the step. If this ever starts passing, a
    // producer was added and the marker above stops being hypothetical.
    val result = runOnFixture(
      AccessibilityAction.TapOnElement(
        nodeSelector = unmatchableSelector,
        timeoutMs = MISS_TIMEOUT_MS,
      ),
    )

    assertTrue(
      "An unresolved selector with no recorded point must fail, not degrade to a coordinate. " +
        "Got: $result",
      result is TrailblazeToolResult.Error,
    )
    assertTrue(
      "A failed tap must not claim a dispatch route",
      loggedActions().none {
        it is AgentDriverAction.TapPoint &&
          it.dispatchRoute == TapDispatchRoute.RECORDED_COORDINATES_AFTER_SELECTOR_MISS
      },
    )
  }

  @Test
  fun resolvedSelector_logsARealRouteAndNeverTheMissMarker() {
    // The discriminator: a tap that genuinely resolved against the live tree records how it was
    // dispatched, and that is never the recorded-coordinate marker. Without this, the marker
    // could be set on every tap and the tests above would still pass.
    val result = runOnFixture(
      AccessibilityAction.TapOnElement(
        nodeSelector = TrailblazeNodeSelector(
          androidAccessibility = DriverNodeMatch.AndroidAccessibility(
            textRegex = CoverageFixtureActivity.FIRST_LABEL,
          ),
        ),
      ),
    )

    assertTrue("Got: $result", result is TrailblazeToolResult.Success)
    val tap = singleLoggedAction() as? AgentDriverAction.TapPoint
    assertNotNull("Expected a TapPoint, got ${singleLoggedAction()}", tap)
    assertNotNull("A selector-resolved tap must record the route it used", tap!!.dispatchRoute)
    assertNotEquals(
      TapDispatchRoute.RECORDED_COORDINATES_AFTER_SELECTOR_MISS,
      tap.dispatchRoute,
    )
  }

  // --- harness ---

  private val unmatchableSelector = TrailblazeNodeSelector(
    androidAccessibility = DriverNodeMatch.AndroidAccessibility(
      textRegex = "trailblaze-no-such-node-should-ever-match",
    ),
  )

  private fun runOnFixture(action: AccessibilityAction): TrailblazeToolResult {
    val context = InstrumentationRegistry.getInstrumentation().context
    val intent = Intent(context, CoverageFixtureActivity::class.java)
      .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      .putExtra(CoverageFixtureActivity.EXTRA_LAYOUT, CoverageFixtureActivity.LAYOUT_FULL_WIDTH)

    ActivityScenario.launch<CoverageFixtureActivity>(intent).use {
      val result = AccessibilityTrailRunner.runActions(
        actions = listOf(action),
        traceId = null,
        trailblazeLogger = logger,
        sessionProvider = { session },
      )
      // Driver logs are written on a background scope; join before reading them.
      AccessibilityTrailRunner.flushLogs()
      return result
    }
  }

  private fun loggedActions(): List<AgentDriverAction> =
    emittedLogs.filterIsInstance<TrailblazeLog.AgentDriverLog>().map { it.action }

  private fun singleLoggedAction(): AgentDriverAction? = loggedActions().singleOrNull()

  /** A point that is on-screen and harmless to tap on the fixture. */
  private fun screenCenter(): Pair<Int, Int> {
    val (width, height) = AccessibilityDeviceManager().getScreenDimensions()
    return width / 2 to height / 2
  }

  companion object {
    /**
     * Passed *into* the code under test as its own timeout, so this is not a wall-clock bet on the
     * device: three of these tests deliberately exhaust it, and the default 5s would triple the
     * class's runtime for no extra signal.
     */
    private const val MISS_TIMEOUT_MS = 1_500L
  }
}
