package xyz.block.trailblaze.android.test

import android.accessibilityservice.AccessibilityServiceInfo
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.datetime.Clock
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import xyz.block.trailblaze.android.accessibility.toAccessibilityNode
import xyz.block.trailblaze.android.accessibility.toTrailblazeNode
import xyz.block.trailblaze.android.test.tools.AndroidTestTapTool
import xyz.block.trailblaze.api.DriverNodeDetail
import xyz.block.trailblaze.api.DriverNodeMatch
import xyz.block.trailblaze.api.TrailblazeNode
import xyz.block.trailblaze.api.TrailblazeNodeSelector
import xyz.block.trailblaze.api.TrailblazeNodeSelectorResolver
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDeviceInfo
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.logs.client.TrailblazeLogger
import xyz.block.trailblaze.logs.client.TrailblazeSession
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.utils.NoOpElementComparator

/**
 * Can the in-process driver act by tapping accessibility-resolved BOUNDS without losing the speed
 * or the synchronization it gets from acting on a live `View`?
 *
 * The switch settles on bounds tapping — it is what
 * `ANDROID_ONDEVICE_ACCESSIBILITY` already does, and the parity measurement showed the
 * accessibility tree puts a control's label and its clickability on DIFFERENT nodes, so acting on
 * the node a text selector matched is not an option. The two open objections to it are speed and
 * synchronization, and both are measurable before any driver code changes.
 *
 * **Speed** is a comparison, not a threshold, over three arms on the same targets in the same run:
 * arm A is the tap the driver performs today, arm B is a bounds gesture for every target, and
 * arm C is the actual proposal — the same route choice `planActionClickRoute` already makes, so
 * `ACTION_CLICK` where the matched node advertises it and a bounds gesture everywhere else. Arm B
 * exists to price the gesture on its own; arm C is the number that decides the switch.
 *
 * **Synchronization** is the assertion, and it is the reason every tap in the run — warm-up taps
 * included — takes the next value of one monotonic counter rather than an index each arm picks for
 * itself. The fixture writes both statuses to one field, so consecutive taps landing on different
 * targets means every tap must produce a status string DIFFERENT from the one standing before it,
 * and each arm reads that status back out of its own hierarchy immediately after its own settle —
 * no polling, no sleep. A tap that had not landed yet, or an accessibility projection lagging the
 * View state it describes, both fail here rather than being smoothed over by a retry. Let any two
 * consecutive taps share a target and the second one's assertion passes on a status already
 * standing, so a tap that did nothing at all is both accepted and timed without its UI transition.
 * A single counter makes that impossible at every seam; per-arm offsets did not, because the
 * warm-up ended on the target measurement began on. The read is the strict test of the two: a
 * coordinate gesture is injected synchronously but the framework still has to route it, and the
 * accessibility tree is an event-driven projection.
 *
 * **What this does NOT isolate.** Arm A runs through the driver's full tool dispatch
 * (`runTrailblazeTools`) while arms B and C start below that boundary, so the arm-to-arm difference
 * carries Trailblaze's outer orchestration as well as the route itself. Two biases in opposite
 * directions, neither measured here: that boundary flatters arm C, and arm C's stand-in for the
 * node side map (re-finding the node by label) penalizes it. Read these as "the proposed route is
 * not slower", not as a decomposed cost model.
 *
 * The alternating pair is chosen to cover the split the parity run found: "View Button" is a
 * `Button` that carries its own label, while "Row label" is a `TextView` whose clickability lives
 * on the `LinearLayout` above it. So the pair exercises both of arm C's routes, and it proves the
 * gesture reaches a target whose own node cannot be clicked — a coordinate hits the row underneath.
 */
class BoundsTapViabilityOnDeviceTest {

  @get:Rule val composeRule = createEmptyComposeRule() as AndroidComposeTestRule<*, *>

  private var scenario: ActivityScenario<MixedUiFixtureActivity>? = null
  private lateinit var fixture: MixedUiFixtureActivity
  private lateinit var target: RuleBackedAndroidTestTarget
  private lateinit var agent: AndroidTestTrailblazeAgent
  private val routeNotes = mutableListOf<String>()

  /** Samples keyed by (arm, target label) — see the per-target reporting note in the test. */
  private val timings = linkedMapOf<Pair<String, String>, MutableList<Double>>()

  /**
   * Monotonic tap counter, the single source of every arm's target.
   *
   * Every arm invocation takes the NEXT value, so consecutive taps land on opposite targets by
   * construction — within an iteration, between iterations, AND across the warm-up/measurement
   * seam. Hand-picked per-arm offsets got the first two of those and missed the third: the warm-up
   * ended on the same target measurement began on, so the first measured sample did not have to
   * flip the status and its timing omitted the transition.
   */
  private var step = 0

  /**
   * Time one arm on the next tap and file the sample under the target that tap selects. The index
   * arithmetic matches what the arms themselves do, so an arm and its bucket can never disagree
   * about which target was tapped.
   */
  private fun runArm(arm: String, body: (Index) -> Unit) {
    val index = step++
    val case = TARGETS[index % TARGETS.size]
    val elapsed = measure { body(index) }
    timings.getOrPut(arm to case.label) { mutableListOf() } += elapsed
  }

  @Before
  fun launchFixture() {
    val launched = ActivityScenario.launch(MixedUiFixtureActivity::class.java)
    scenario = launched
    var activity: MixedUiFixtureActivity? = null
    launched.onActivity { activity = it }
    fixture = checkNotNull(activity)
    composeRule.waitForIdle()
    target =
      RuleBackedAndroidTestTarget(activityProvider = { fixture }, composeTestRule = composeRule)
    agent =
      AndroidTestTrailblazeAgent(
        target = target,
        trailblazeLogger = TrailblazeLogger.createNoOp(),
        trailblazeDeviceInfoProvider = {
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
        },
        sessionProvider = {
          TrailblazeSession(
            sessionId = SessionId("bounds_tap_viability"),
            startTime = Clock.System.now(),
          )
        },
      )

    val uiAutomation = InstrumentationRegistry.getInstrumentation().uiAutomation
    uiAutomation.serviceInfo = uiAutomation.serviceInfo.apply {
      flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
    }
  }

  @After
  fun releaseFixture() {
    scenario?.close()
  }

  @Test
  fun boundsTapMatchesTheLiveViewTapOnSpeedAndStaysSynchronized() {
    // Warm every path: the first post-`serviceInfo` hierarchy read pays a reconnect, and the first
    // tap of a run pays one-time tool and Espresso setup.
    repeat(2) { runArm(ARM_A, ::armA); runArm(ARM_B, ::armB); runArm(ARM_C, ::armC) }
    // Discard the warm-up SAMPLES only. `step` deliberately keeps counting, so the first measured
    // tap lands on the opposite target from the last warm-up tap and still has to flip the status.
    timings.clear()

    // No arm ever asserts a status the previous tap already established — with two targets writing
    // one status field, consecutive taps land on opposite targets and every tap has to flip it.
    // See the synchronization paragraph on the class.
    repeat(ITERATIONS) {
      runArm(ARM_A, ::armA)
      runArm(ARM_B, ::armB)
      runArm(ARM_C, ::armC)
    }

    // Reported per TARGET, not per arm. The two targets are deliberately different shapes — one
    // grants ACTION_CLICK, one only has a clickable ancestor — so a single median over the
    // interleaved series would average two populations and describe neither.
    Log.i(TAG, "=== ONE TAP, THREE WAYS ($ITERATIONS iterations per arm) ===")
    TARGETS.forEach { case ->
      Log.i(TAG, "--- ${case.label}")
      listOf(ARM_A to "live-View tap (today)", ARM_B to "a11y bounds gesture", ARM_C to "proposal")
        .forEach { (arm, description) ->
          val samples = timings[arm to case.label].orEmpty()
          Log.i(TAG, "  arm $arm ${description.padEnd(22)}: median ${samples.median()} ms " +
            "of ${samples.render()}")
        }
    }
    Log.i(TAG, "arm C routes taken: ${routeNotes.distinct()}")

    // Arm C is the arm whose number decides the switch, so its sample count is asserted too — a
    // short series would mean the table above was rendered from an incomplete run. Counted per
    // arm across both targets, since each arm visits each target ITERATIONS / TARGETS.size times.
    listOf(ARM_A, ARM_B, ARM_C).forEach { arm ->
      val total = TARGETS.sumOf { timings[arm to it.label].orEmpty().size }
      assertTrue(
        total == ITERATIONS,
        "arm $arm produced $total samples, expected $ITERATIONS — the reported medians would be " +
          "rendered from an incomplete run",
      )
    }
  }

  /** The tap the driver performs today: real tool dispatch, resolved onto the live View tree. */
  private fun armA(index: Index) {
    val expected = TARGETS[index % TARGETS.size].expectedStatus
    val result =
      agent.runTrailblazeTools(
        tools = listOf(AndroidTestTapTool(TARGETS[index % TARGETS.size].viewSelector)),
        traceId = null,
        screenState = null,
        elementComparator = NoOpElementComparator,
        screenStateProvider = agent.screenStateProvider,
      ).result
    assertIs<TrailblazeToolResult.Success>(result, "arm A tap failed: $result")
    // Read back out of the driver's OWN hierarchy, with no poll: the tool already settled.
    assertTrue(
      hybridHierarchy().hasText(expected),
      "arm A: after tapping ${TARGETS[index % TARGETS.size].label}, the in-process tree did not " +
        "show \"$expected\" — the tap or the settle did not complete before the read",
    )
  }

  /** The proposed tap: resolve on the accessibility tree, inject a gesture at the bounds center. */
  private fun armB(index: Index) {
    val case = TARGETS[index % TARGETS.size]
    val node = resolveUniquely(arm = "B", case = case)
    val bounds = checkNotNull(node.bounds) { "arm B resolved ${case.label} with no bounds" }
    tapAt(
      x = (bounds.left + bounds.right) / 2f,
      y = (bounds.top + bounds.bottom) / 2f,
    )
    // The SAME settle the driver already runs after every native action, so this arm is not being
    // credited with skipping synchronization that arm A pays for.
    target.waitForIdle()
    assertTrue(
      accessibilityHierarchy().hasText(case.expectedStatus),
      "arm B: after tapping ${case.label} at ${bounds}, the accessibility tree did not show " +
        "\"${case.expectedStatus}\" — either the injected gesture had not been routed when the " +
        "settle returned, or the accessibility projection lagged the View state",
    )
  }

  /**
   * The actual proposal: arm B's resolution, plus the route choice
   * `AccessibilityDeviceManager.planActionClickRoute` already makes on this same data — fire
   * `ACTION_CLICK` when the matched node itself advertises it and carries a label, otherwise
   * gesture at its bounds.
   *
   * Deliberately does NOT relocate to a clickable ancestor, even though a text node whose row
   * above it owns the click is exactly the shape the parity run found. Walking up retargets the
   * action at a node the selector did not match — for a clickable list row containing a button,
   * that silently activates the row instead of the button. The gesture is the answer for that
   * shape, and it lands because a coordinate hits whatever is actually under it.
   */
  private fun armC(index: Index) {
    val case = TARGETS[index % TARGETS.size]
    val node = resolveUniquely(arm = "C", case = case)
    val bounds = checkNotNull(node.bounds) { "arm C resolved ${case.label} with no bounds" }
    val detail = node.driverDetail as? DriverNodeDetail.AndroidAccessibility
    val actionClickEligible =
      detail != null &&
        ACTION_CLICK_NAME in detail.actions &&
        detail.isEnabled &&
        !detail.isEditable &&
        detail.isVisibleToUser &&
        !(detail.text.isNullOrBlank() && detail.contentDescription.isNullOrBlank())
    // The one thing the driver still needs that this test fakes: a nodeId -> AccessibilityNodeInfo
    // side map recorded during the tree read. Re-finding by text here is strictly SLOWER than that
    // map would be, so this arm's timing is an upper bound on the real route.
    val performed =
      actionClickEligible &&
        run {
          val root = InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow
          checkNotNull(root) { "rootInActiveWindow was null" }
          // Match on whichever label the eligibility gate accepted. Comparing `text` alone would
          // let a node labelled only by contentDescription degrade to `null == null`, which
          // matches the first text-less node in the tree — normally the root — and fires
          // ACTION_CLICK at the wrong element while still reporting the ACTION_CLICK route.
          val label = detail!!.text?.takeIf { it.isNotBlank() }
          val description = detail.contentDescription?.takeIf { it.isNotBlank() }
          val info =
            findNode(root) { candidate ->
              (label != null && candidate.text?.toString() == label) ||
                (description != null && candidate.contentDescription?.toString() == description)
            } ?: error("arm C matched ${case.label} on the tree but not on the live node info")
          info.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }
    if (!performed) {
      tapAt(x = (bounds.left + bounds.right) / 2f, y = (bounds.top + bounds.bottom) / 2f)
    }
    routeNotes += "${case.label} -> ${if (performed) "ACTION_CLICK" else "bounds gesture"}"
    target.waitForIdle()
    assertTrue(
      accessibilityHierarchy().hasText(case.expectedStatus),
      "arm C: after ${if (performed) "ACTION_CLICK" else "a bounds gesture"} on ${case.label}, " +
        "the accessibility tree did not show \"${case.expectedStatus}\"",
    )
  }

  /**
   * Resolve a target on the accessibility tree, refusing anything but a single match.
   *
   * An ambiguous selector is a broken measurement rather than a route to benchmark: which node
   * `nodes.first()` yields depends on traversal order, so the timings and the ACTION_CLICK/gesture
   * split would silently become a property of tree ordering on that OS version. Both targets are
   * chosen to be unique on this fixture, so a failure here means the fixture or the projection
   * changed and the numbers need re-reading, not that the arm should pick one.
   */
  private fun resolveUniquely(arm: String, case: TapCase): TrailblazeNode {
    val tree = accessibilityHierarchy()
    return when (
      val resolved = TrailblazeNodeSelectorResolver.resolve(tree, case.accessibilitySelector)
    ) {
      is TrailblazeNodeSelectorResolver.ResolveResult.SingleMatch -> resolved.node
      is TrailblazeNodeSelectorResolver.ResolveResult.MultipleMatches ->
        error(
          "arm $arm resolved ${case.label} to ${resolved.nodes.size} nodes on the accessibility " +
            "tree (${resolved.nodes.map { it.bounds }}) — an ambiguous target makes the timing " +
            "and the route split depend on traversal order",
        )
      is TrailblazeNodeSelectorResolver.ResolveResult.NoMatch ->
        error("arm $arm could not resolve ${case.label} on the accessibility tree")
    }
  }

  private fun findNode(
    node: AccessibilityNodeInfo,
    predicate: (AccessibilityNodeInfo) -> Boolean,
  ): AccessibilityNodeInfo? {
    if (predicate(node)) return node
    for (index in 0 until node.childCount) {
      val child = runCatching { node.getChild(index) }.getOrNull() ?: continue
      findNode(child, predicate)?.let { return it }
    }
    return null
  }

  /**
   * A synchronous two-event tap. `injectInputEvent(event, sync = true)` returns only once the
   * event has been dispatched, which is what makes this comparable to a `View`-level action rather
   * than to fire-and-forget injection.
   */
  private fun tapAt(x: Float, y: Float) {
    val uiAutomation = InstrumentationRegistry.getInstrumentation().uiAutomation
    val downAt = SystemClock.uptimeMillis()
    val down = MotionEvent.obtain(downAt, downAt, MotionEvent.ACTION_DOWN, x, y, 0)
    val up = MotionEvent.obtain(downAt, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x, y, 0)
    try {
      down.source = InputDevice.SOURCE_TOUCHSCREEN
      up.source = InputDevice.SOURCE_TOUCHSCREEN
      uiAutomation.injectInputEvent(down, true)
      uiAutomation.injectInputEvent(up, true)
    } finally {
      down.recycle()
      up.recycle()
    }
  }

  private fun accessibilityHierarchy(): TrailblazeNode {
    val root = InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow
    checkNotNull(root) { "rootInActiveWindow was null" }
    return root.toAccessibilityNode().toTrailblazeNode()
  }

  private fun hybridHierarchy(): TrailblazeNode =
    AndroidTestScreenState(target, includeScreenshot = false).requiredNodeTree

  private fun TrailblazeNode.hasText(text: String): Boolean = aggregate().any { node ->
    when (val detail = node.driverDetail) {
      is DriverNodeDetail.AndroidAccessibility -> detail.text == text
      is DriverNodeDetail.AndroidView -> detail.text == text
      is DriverNodeDetail.Compose -> detail.text == text
      else -> false
    }
  }

  private fun measure(block: () -> Unit): Double {
    val start = System.nanoTime()
    block()
    return (System.nanoTime() - start) / 1_000_000.0
  }

  private fun List<Double>.render(): String = joinToString { "%.1f".format(it) }

  private fun List<Double>.median(): String = "%.1f".format(sorted()[size / 2])

  private data class TapCase(
    val label: String,
    /** Native-dialect selector, the shape today's in-process tools resolve. */
    val viewSelector: TrailblazeNodeSelector,
    /**
     * Canonical selector. Button labels are UPPERCASED because the accessibility projection
     * publishes a `Button`'s transformed text, which is the divergence the parity run found.
     */
    val accessibilitySelector: TrailblazeNodeSelector,
    val expectedStatus: String,
  )

  private companion object {
    const val TAG = "BoundsTapViability"

    /**
     * The name the accessibility mapper publishes for `AccessibilityNodeInfo.ACTION_CLICK`.
     * Duplicated as a literal because the mapper's own constant is `internal` to
     * `trailblaze-android`; when the driver switch lands, the route gate lives beside that
     * constant rather than here.
     */
    const val ACTION_CLICK_NAME = "ACTION_CLICK"
    const val ITERATIONS = 10

    const val ARM_A = "A"
    const val ARM_B = "B"
    const val ARM_C = "C"

    val TARGETS = listOf(
      TapCase(
        label = "View Button (Button carrying its own label)",
        viewSelector =
          TrailblazeNodeSelector(
            androidView =
              DriverNodeMatch.AndroidView(textRegex = MixedUiFixtureActivity.VIEW_BUTTON_LABEL),
          ),
        accessibilitySelector =
          TrailblazeNodeSelector(
            androidAccessibility =
              DriverNodeMatch.AndroidAccessibility(
                textRegex = MixedUiFixtureActivity.VIEW_BUTTON_LABEL.uppercase(),
              ),
          ),
        expectedStatus = MixedUiFixtureActivity.VIEW_STATUS_CLICKED,
      ),
      TapCase(
        label = "Row label (TextView whose clickability is on the row above it)",
        viewSelector =
          TrailblazeNodeSelector(
            androidView =
              DriverNodeMatch.AndroidView(textRegex = MixedUiFixtureActivity.VIEW_ROW_LABEL),
          ),
        accessibilitySelector =
          TrailblazeNodeSelector(
            androidAccessibility =
              DriverNodeMatch.AndroidAccessibility(
                textRegex = MixedUiFixtureActivity.VIEW_ROW_LABEL,
              ),
          ),
        expectedStatus = MixedUiFixtureActivity.VIEW_STATUS_ROW,
      ),
    )
  }
}

private typealias Index = Int
