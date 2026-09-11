package xyz.block.trailblaze.android.accessibility

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.datetime.Clock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import xyz.block.trailblaze.api.DriverNodeMatch
import xyz.block.trailblaze.api.TrailblazeNodeSelector
import xyz.block.trailblaze.logs.client.LogEmitter
import xyz.block.trailblaze.logs.client.ScreenStateLogger
import xyz.block.trailblaze.logs.client.TrailblazeLogger
import xyz.block.trailblaze.logs.client.TrailblazeSession
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult

/**
 * On-device proof that a capture which lost a node fetch cannot pass a "not visible" assertion.
 *
 * Every node in a capture is a live fetch from the app. When the app's main thread is blocked,
 * those fetches time out and return null, and the tree comes back with subtrees missing. Before
 * this test existed the walkers skipped such children silently and `assertNotVisible` released on
 * the first capture that lacked the element — so "wait until the loading screen is gone" released
 * while the loading screen was still up, and the next step ran against the wrong screen.
 *
 * A blocked main thread cannot be staged deterministically, so
 * [CoverageFixtureActivity.LAYOUT_UNFETCHABLE_CHILD] stands in for it: a view whose node
 * advertises a child that the app never hands over, which reaches the service as the same null
 * `getChild()` a timeout produces. Against that screen:
 * - the capture reports the dropped fetch instead of pretending the screen is smaller, and
 * - `assertNotVisible` for something genuinely absent must FAIL, naming the capture, not pass.
 *
 * Against the ordinary full-width screen the same assertion passes (complete captures are trusted)
 * and an assertion against a present label still fails as "still present" — the two controls that
 * keep the first test from being satisfiable by an `assertNotVisible` that always fails.
 */
class PartialCaptureOnDeviceTest {

  private val logger = TrailblazeLogger(
    logEmitter = LogEmitter { },
    screenStateLogger = ScreenStateLogger { it.fileName },
  )

  private val session = TrailblazeSession(
    sessionId = SessionId("partial-capture-on-device-test"),
    startTime = Clock.System.now(),
  )

  @Before
  fun enableAccessibilityService() {
    OnDeviceAccessibilityServiceSetup.ensureUiAutomationDoesNotSuppressAccessibility()
    OnDeviceAccessibilityServiceSetup.ensureAccessibilityServiceReady(timeoutMs = 15_000)
  }

  @Test
  fun completeScreen_reportsNoDroppedFetches() {
    onFixture(CoverageFixtureActivity.LAYOUT_FULL_WIDTH) {
      val capture = TrailblazeAccessibilityService.captureMergedScreenTrees()
      assertNotNull("The fixture must capture", capture.accessibilityNode)
      assertEquals(
        "An ordinary screen must read as complete, or every absence check would stall on it",
        0,
        capture.droppedFetchesForAccessibilityNode,
      )
      assertTrue(capture.isAccessibilityNodeComplete)
      assertTrue(capture.isTreeNodeComplete)
    }
  }

  @Test
  fun unfetchableChild_isCountedNotSwallowed() {
    onFixture(CoverageFixtureActivity.LAYOUT_UNFETCHABLE_CHILD) {
      val capture = TrailblazeAccessibilityService.captureMergedScreenTrees()
      val tree = capture.accessibilityNode
      assertNotNull("A partial capture still returns the nodes it did get", tree)
      assertTrue(
        "The rest of the screen must be in the capture (looked for ${CoverageFixtureActivity.FIRST_LABEL})",
        tree!!.containsText(CoverageFixtureActivity.FIRST_LABEL),
      )
      assertTrue(
        "The host whose child never fetched is itself present",
        tree.containsText(CoverageFixtureActivity.UNFETCHABLE_HOST_LABEL),
      )
      assertTrue(
        "A child the app did not hand over must be counted, got " +
          "droppedFetchesForAccessibilityNode=${capture.droppedFetchesForAccessibilityNode}",
        capture.droppedFetchesForAccessibilityNode >= 1,
      )
      assertTrue(!capture.isAccessibilityNodeComplete)
    }
  }

  @Test
  fun assertNotVisible_refusesToPassOnAPartialCapture() {
    val result = onFixture(CoverageFixtureActivity.LAYOUT_UNFETCHABLE_CHILD) {
      runAction(AccessibilityAction.AssertNotVisible(unmatchableSelector, timeoutMs = SHORT_TIMEOUT_MS))
    }
    assertTrue(
      "An element absent from a PARTIAL capture is not proven absent; the assertion must fail. Got: $result",
      result is TrailblazeToolResult.Error,
    )
    val message = (result as TrailblazeToolResult.Error).errorMessage
    assertTrue(
      "The failure must blame the capture, not the screen, so a reader does not go looking for the element. Got: $message",
      message.contains("no complete accessibility tree"),
    )
  }

  @Test
  fun assertNotVisible_passesOnACompleteCapture() {
    val result = onFixture(CoverageFixtureActivity.LAYOUT_FULL_WIDTH) {
      runAction(AccessibilityAction.AssertNotVisible(unmatchableSelector, timeoutMs = 10_000L))
    }
    assertTrue("A complete capture without the element proves absence. Got: $result", result is TrailblazeToolResult.Success)
  }

  @Test
  fun assertNotVisible_stillFailsWhenTheElementIsThere() {
    val result = onFixture(CoverageFixtureActivity.LAYOUT_FULL_WIDTH) {
      runAction(
        AccessibilityAction.AssertNotVisible(
          TrailblazeNodeSelector(
            androidAccessibility = DriverNodeMatch.AndroidAccessibility(textRegex = CoverageFixtureActivity.FIRST_LABEL),
          ),
          timeoutMs = SHORT_TIMEOUT_MS,
        ),
      )
    }
    assertTrue("Got: $result", result is TrailblazeToolResult.Error)
    val message = (result as TrailblazeToolResult.Error).errorMessage
    assertTrue("A present element reads as present, not as a capture problem. Got: $message", message.contains("still present"))
  }

  @Test
  fun screenState_carriesTheCaptureCompletenessOffTheDevice() {
    // The tally is only useful to the consumers that decide absence, and every one of them —
    // the waypoint matcher, `findMatches`, and anything reading a capture over the on-device RPC
    // — sees a ScreenState, never a MergedScreenTrees. This is the only link between the two, and
    // nothing off the device can exercise it.
    val partial = onFixture(CoverageFixtureActivity.LAYOUT_UNFETCHABLE_CHILD) {
      AccessibilityServiceScreenState(includeScreenshot = false)
    }
    assertTrue(
      "A capture that lost a child fetch must say so on the screen state, got " +
        "droppedNodeFetches=${partial.droppedNodeFetches}",
      (partial.droppedNodeFetches ?: 0) >= 1,
    )
    assertTrue(partial.isCaptureKnownPartial)

    val complete = onFixture(CoverageFixtureActivity.LAYOUT_FULL_WIDTH) {
      AccessibilityServiceScreenState(includeScreenshot = false)
    }
    assertEquals(
      "An ordinary screen must report zero, not null — null is 'unknown' and settles nothing",
      0,
      complete.droppedNodeFetches,
    )
    assertTrue(!complete.isCaptureKnownPartial)
  }

  // --- harness ---

  private val unmatchableSelector = TrailblazeNodeSelector(
    androidAccessibility = DriverNodeMatch.AndroidAccessibility(
      textRegex = "trailblaze-no-such-node-should-ever-match",
    ),
  )

  private fun runAction(action: AccessibilityAction): TrailblazeToolResult {
    val result = AccessibilityTrailRunner.runActions(
      actions = listOf(action),
      traceId = null,
      trailblazeLogger = logger,
      sessionProvider = { session },
    )
    AccessibilityTrailRunner.flushLogs()
    return result
  }

  private fun <T> onFixture(layout: String, block: () -> T): T {
    val context = InstrumentationRegistry.getInstrumentation().context
    val intent = Intent(context, CoverageFixtureActivity::class.java)
      .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      .putExtra(CoverageFixtureActivity.EXTRA_LAYOUT, layout)
    ActivityScenario.launch<CoverageFixtureActivity>(intent).use {
      TrailblazeAccessibilityService.waitForSettled(timeoutMs = 5_000)
      return block()
    }
  }

  private fun AccessibilityNode.containsText(needle: String): Boolean =
    text == needle || contentDescription == needle || children.any { it.containsText(needle) }

  private companion object {
    /** Long enough for several polls, short enough that the negative cases do not drag the suite. */
    const val SHORT_TIMEOUT_MS = 2_000L
  }
}
