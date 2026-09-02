package xyz.block.trailblaze.android.test

import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.datetime.Clock
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import xyz.block.trailblaze.android.test.tools.AndroidTestAssertVisibleTool
import xyz.block.trailblaze.android.test.tools.AndroidTestTapTool
import xyz.block.trailblaze.android.test.tools.AndroidTestTypeTool
import xyz.block.trailblaze.api.DriverNodeDetail
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
import xyz.block.trailblaze.utils.NoOpElementComparator

/**
 * On-device behavioral contract for the ANDROID_TEST driver against a real mixed
 * View + Compose Activity.
 *
 * Everything here goes through the driver's three trail-facing tools, because that is the whole
 * surface a trail has. No test names Espresso or the Compose rule: which backend runs is decided by
 * what the selector resolved to, and these tests assert the app's own state changed as a result.
 */
class AndroidTestDriverOnDeviceTest {

  // createEmptyComposeRule is correct HERE because this fixture owns no other Compose harness;
  // a consumer app must instead pass its existing rule (see RuleBackedAndroidTestTarget kdoc).
  @get:Rule val composeRule = createEmptyComposeRule() as AndroidComposeTestRule<*, *>

  private lateinit var scenario: ActivityScenario<MixedUiFixtureActivity>
  private lateinit var agent: AndroidTestTrailblazeAgent

  @Before
  fun launchFixture() {
    scenario = ActivityScenario.launch(MixedUiFixtureActivity::class.java)
    var activity: MixedUiFixtureActivity? = null
    scenario.onActivity { activity = it }
    val fixture = checkNotNull(activity)
    val target =
      RuleBackedAndroidTestTarget(
        activityProvider = { fixture },
        composeTestRule = composeRule,
      )
    val session =
      TrailblazeSession(
        sessionId = SessionId("android_test_driver_on_device"),
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
      )
  }

  @After
  fun closeFixture() {
    scenario.close()
  }

  @Test
  fun androidViewSelectorsDriveClassicViews() {
    run(AndroidTestAssertVisibleTool(viewText(MixedUiFixtureActivity.VIEW_STATUS_INITIAL)))
    run(
      AndroidTestTypeTool(
        value = "typed into a view",
        // Hint, not id: the fixture's EditText uses View.generateViewId(), so it has no resource
        // entry name, and it has no text to match on until this call types into it.
        nodeSelector = TrailblazeNodeSelector(androidView = DriverNodeMatch.AndroidView(hintTextRegex = "View input")),
      )
    )
    run(AndroidTestAssertVisibleTool(viewText("typed into a view")))
    run(AndroidTestTapTool(viewText(MixedUiFixtureActivity.VIEW_BUTTON_LABEL)))
    run(AndroidTestAssertVisibleTool(viewText(MixedUiFixtureActivity.VIEW_STATUS_CLICKED)))
  }

  @Test
  fun composeSelectorsDriveComposeSemantics() {
    run(AndroidTestAssertVisibleTool(composeText(MixedUiFixtureActivity.COMPOSE_STATUS_INITIAL)))
    run(
      AndroidTestTypeTool(
        value = "typed into compose",
        nodeSelector =
          TrailblazeNodeSelector(
            compose = DriverNodeMatch.Compose(testTag = MixedUiFixtureActivity.COMPOSE_INPUT_TAG),
          ),
      )
    )
    run(AndroidTestAssertVisibleTool(composeText("typed into compose")))
    run(
      AndroidTestTapTool(
        TrailblazeNodeSelector(
          compose = DriverNodeMatch.Compose(testTag = MixedUiFixtureActivity.COMPOSE_BUTTON_TAG),
        )
      )
    )
    run(AndroidTestAssertVisibleTool(composeText(MixedUiFixtureActivity.COMPOSE_STATUS_CLICKED)))
  }

  @Test
  fun oneAgentCrossesBackendsInOneFlow() {
    run(AndroidTestTapTool(viewText(MixedUiFixtureActivity.VIEW_BUTTON_LABEL)))
    run(AndroidTestAssertVisibleTool(viewText(MixedUiFixtureActivity.VIEW_STATUS_CLICKED)))
    run(
      AndroidTestTapTool(
        TrailblazeNodeSelector(
          compose = DriverNodeMatch.Compose(testTag = MixedUiFixtureActivity.COMPOSE_BUTTON_TAG),
        )
      )
    )
    run(AndroidTestAssertVisibleTool(composeText(MixedUiFixtureActivity.COMPOSE_STATUS_CLICKED)))
    // Back to the View backend after Compose work, on the same agent.
    run(AndroidTestAssertVisibleTool(viewText(MixedUiFixtureActivity.VIEW_STATUS_CLICKED)))
  }

  /**
   * Two buttons carry the same text, and no property of either one tells them apart. The selector
   * picks one by the row it sits in, and the tap has to land on THAT button — which it can only do
   * by acting on the node that matched, since the buttons are indistinguishable to any property
   * matcher a native framework could be handed.
   */
  @Test
  fun identicalViewsAreToldApartByWhichNodeMatched() {
    run(
      AndroidTestTapTool(
        TrailblazeNodeSelector(
          androidView = DriverNodeMatch.AndroidView(textRegex = MixedUiFixtureActivity.VIEW_DUPLICATE_LABEL),
          childOf =
            TrailblazeNodeSelector(
              androidView = DriverNodeMatch.AndroidView(tagRegex = MixedUiFixtureActivity.VIEW_BETA_ROW_TAG),
            ),
        )
      )
    )
    run(AndroidTestAssertVisibleTool(viewText(MixedUiFixtureActivity.VIEW_STATUS_BETA)))

    run(
      AndroidTestTapTool(
        TrailblazeNodeSelector(
          androidView = DriverNodeMatch.AndroidView(textRegex = MixedUiFixtureActivity.VIEW_DUPLICATE_LABEL),
          childOf =
            TrailblazeNodeSelector(
              androidView = DriverNodeMatch.AndroidView(tagRegex = MixedUiFixtureActivity.VIEW_ALPHA_ROW_TAG),
            ),
        )
      )
    )
    run(AndroidTestAssertVisibleTool(viewText(MixedUiFixtureActivity.VIEW_STATUS_ALPHA)))
  }

  /**
   * `index` is applied once, by the resolver, and it counts down the screen. Applying it a second
   * time in the action layer would silently act on a different element than the one the selector
   * named — and with two identical buttons, nothing about the result would look wrong.
   */
  @Test
  fun indexCountsDownTheScreenAndIsAppliedOnce() {
    run(
      AndroidTestTapTool(
        viewText(MixedUiFixtureActivity.VIEW_DUPLICATE_LABEL).copy(index = 1),
      )
    )
    run(AndroidTestAssertVisibleTool(viewText(MixedUiFixtureActivity.VIEW_STATUS_BETA)))

    run(
      AndroidTestTapTool(
        viewText(MixedUiFixtureActivity.VIEW_DUPLICATE_LABEL).copy(index = 0),
      )
    )
    run(AndroidTestAssertVisibleTool(viewText(MixedUiFixtureActivity.VIEW_STATUS_ALPHA)))
  }

  /**
   * An ambiguous selector acts on the first PLACED match, which is what the Android accessibility
   * driver does (`AccessibilityDeviceManager.pickPreferredMatch`) and therefore the semantics every
   * recording in the estate was made against. Failing instead — which this driver used to do — is a
   * stricter contract than a recording can satisfy: case 5380720's `checkout_button_title` named
   * one element on the recording device and names two here the moment the merchant has open tickets
   * on, and a corpus that replays unmodified by design has no edit available to it.
   *
   * The first match is first down the screen, so this lands on the same button `index = 0` names
   * above — that is the assertion, since a rule that picked arbitrarily would pass half the time.
   */
  @Test
  fun ambiguousSelectorActsOnTheFirstPlacedMatch() {
    run(AndroidTestTapTool(viewText(MixedUiFixtureActivity.VIEW_DUPLICATE_LABEL)))
    run(AndroidTestAssertVisibleTool(viewText(MixedUiFixtureActivity.VIEW_STATUS_ALPHA)))
  }

  /**
   * A trail names the label, because that is what a person reads on the screen. The label handles
   * nothing; its parent row does. The tap has to find the handler and say that it did.
   */
  @Test
  fun tapOnALabelReachesItsClickableRowAndReportsIt() {
    val message = runReportingMessage(AndroidTestTapTool(viewText(MixedUiFixtureActivity.VIEW_ROW_LABEL)))
    run(AndroidTestAssertVisibleTool(viewText(MixedUiFixtureActivity.VIEW_STATUS_ROW)))
    assertTrue(
      message.contains("not clickable") && message.contains("ancestor"),
      "Relocation to the clickable row was not reported: $message",
    )
  }

  /**
   * Compose's unmerged tree splits a button into the button and its label, and the label is what a
   * trail can name by text. Tapping it has to reach the button that owns the click action.
   */
  @Test
  fun tapOnAComposeLabelReachesTheButtonThatOwnsTheClick() {
    val message =
      runReportingMessage(
        AndroidTestTapTool(composeText(MixedUiFixtureActivity.COMPOSE_BUTTON_LABEL)),
      )
    run(AndroidTestAssertVisibleTool(composeText(MixedUiFixtureActivity.COMPOSE_STATUS_CLICKED)))
    assertTrue(
      message.contains("not clickable") && message.contains("ancestor"),
      "Relocation to the clickable ancestor was not reported: $message",
    )
  }

  @Test
  fun hybridScreenStateContainsBothBackends() {
    val screenState = agent.screenStateProvider()
    val tree = checkNotNull(screenState.trailblazeNodeTree)

    val viewNodes = tree.findAll { it.driverDetail is DriverNodeDetail.AndroidView }
    val composeNodes = tree.findAll { it.driverDetail is DriverNodeDetail.Compose }
    assertTrue(viewNodes.isNotEmpty(), "Hybrid tree lost the classic View backend")
    assertTrue(composeNodes.isNotEmpty(), "Hybrid tree lost the Compose backend")

    assertTrue(
      viewNodes.any {
        (it.driverDetail as DriverNodeDetail.AndroidView).text == MixedUiFixtureActivity.VIEW_BUTTON_LABEL
      },
      "View button missing from hybrid tree",
    )
    // A view tag is invisible to the accessibility tree, so seeing one proves the View half is
    // collected from the live view objects rather than from an accessibility projection.
    assertTrue(
      viewNodes.any {
        (it.driverDetail as DriverNodeDetail.AndroidView).tag == MixedUiFixtureActivity.VIEW_BETA_ROW_TAG
      },
      "View tag missing: the View half is not collected in-process",
    )
    // testTag is only observable through NATIVE Compose semantics; its presence proves the
    // Compose subtree is not an accessibility mirror of the same control.
    assertTrue(
      composeNodes.any {
        (it.driverDetail as DriverNodeDetail.Compose).testTag == MixedUiFixtureActivity.COMPOSE_BUTTON_TAG
      },
      "Compose test tag missing: Compose subtree is not native semantics",
    )
    // The compact agent-facing text sees both halves of the screen.
    val text = checkNotNull(screenState.viewHierarchyTextRepresentation)
    assertTrue(
      text.contains(MixedUiFixtureActivity.VIEW_BUTTON_LABEL),
      "View button label missing from compact text:\n$text",
    )
    assertTrue(
      text.contains(MixedUiFixtureActivity.COMPOSE_BUTTON_LABEL),
      "Compose button label missing from compact text:\n$text",
    )
  }

  /**
   * A View embedded inside Compose is still part of the screen the driver can see and act on.
   *
   * Compose semantics stop at the interop boundary, so an `AndroidView`'s content exists only in
   * the View tree — beneath a Compose host. A collector that treats a Compose host as the end of
   * the View walk reports the screen as ending there, which in a large mixed app can silently drop
   * most of the body while every selector still fails with a plain "matched no element".
   *
   * Tapping flips COMPOSE state, so a pass means the driver reached the real embedded View rather
   * than something else with the same label.
   */
  @Test
  fun viewsEmbeddedInsideComposeAreVisibleAndActionable() {
    run(AndroidTestAssertVisibleTool(viewText(MixedUiFixtureActivity.EMBEDDED_VIEW_BUTTON_LABEL)))
    run(AndroidTestTapTool(viewText(MixedUiFixtureActivity.EMBEDDED_VIEW_BUTTON_LABEL)))
    run(AndroidTestAssertVisibleTool(composeText(MixedUiFixtureActivity.COMPOSE_STATUS_EMBEDDED)))
  }

  /** Both handles into the live UI are populated, or identity dispatch has nothing to act on. */
  @Test
  fun screenStateCarriesLiveHandlesForBothBackends() {
    val screenState = assertIs<AndroidTestScreenState>(agent.screenStateProvider())
    val tree = checkNotNull(screenState.trailblazeNodeTree)

    val viewNodeIds =
      tree.findAll { it.driverDetail is DriverNodeDetail.AndroidView }.map { it.nodeId }.toSet()
    val composeNodeIds =
      tree.findAll { it.driverDetail is DriverNodeDetail.Compose }.map { it.nodeId }.toSet()

    // The tree's root is a synthetic container with no view behind it only when the View half is
    // missing entirely; here every AndroidView node is a real view.
    assertTrue(
      viewNodeIds.all { it in screenState.viewByNodeId },
      "View nodes without a live View: ${viewNodeIds - screenState.viewByNodeId.keys}",
    )
    assertTrue(
      composeNodeIds.all { it in screenState.semanticsIdByNodeId },
      "Compose nodes without a semantics id: ${composeNodeIds - screenState.semanticsIdByNodeId.keys}",
    )
  }

  /** `androidView` is strict where `androidMaestro` is lenient, and the driver must not blur that. */
  @Test
  fun androidViewMatchingIsCaseSensitive() {
    val error =
      runExpectingError(
        AndroidTestTapTool(viewText(MixedUiFixtureActivity.VIEW_BUTTON_LABEL.lowercase())),
      )
    assertTrue(
      error.contains("matched no element"),
      "A lowercased pattern must not match mixed-case text, got: $error",
    )
  }

  @Test
  fun failedSelectorReportsErrorInsteadOfActingBlind() {
    val error =
      runExpectingError(
        AndroidTestTapTool(
          TrailblazeNodeSelector(compose = DriverNodeMatch.Compose(testTag = "does_not_exist_anywhere")),
        ),
      )
    assertTrue(error.contains("does_not_exist_anywhere"), "Error did not name the selector: $error")
  }

  /**
   * The resolver refuses an ambiguous selector rather than guessing. Relocation must not put the
   * guess back: a row that cannot take the tap itself, with two children that equally can, has no
   * right answer. Picking one and reporting success is the failure that never gets noticed — the
   * trail is green and half the time it exercised the wrong button.
   */
  @Test
  fun relocationRefusesToChooseBetweenTwoEquallyCloseTargets() {
    val error =
      runExpectingError(
        AndroidTestTapTool(
          TrailblazeNodeSelector(
            androidView = DriverNodeMatch.AndroidView(
              tagRegex = MixedUiFixtureActivity.VIEW_AMBIGUOUS_ROW_TAG,
            ),
          ),
        ),
      )
    assertTrue(
      error.contains("Refusing to pick one"),
      "Tapping a row with two equally close clickable children must refuse, got: $error",
    )
    // Neither button ran its listener, so the screen still reads as untouched.
    run(AndroidTestAssertVisibleTool(viewText(MixedUiFixtureActivity.VIEW_STATUS_INITIAL)))
  }

  /**
   * The single-candidate case still relocates, so the guard above rejects genuine ambiguity rather
   * than descending relocation as a whole.
   */
  @Test
  fun relocationStillDescendsWhenOnlyOneDescendantCanTakeTheAction() {
    val message =
      runReportingMessage(
        AndroidTestTapTool(
          TrailblazeNodeSelector(
            androidView = DriverNodeMatch.AndroidView(
              tagRegex = MixedUiFixtureActivity.VIEW_BETA_ROW_TAG,
            ),
          ),
        ),
      )
    assertTrue(message.contains("descendant"), "Expected a reported relocation, got: $message")
    run(AndroidTestAssertVisibleTool(viewText(MixedUiFixtureActivity.VIEW_STATUS_BETA)))
  }

  /**
   * Relocation walks the LIVE view tree rather than the captured snapshot, so it has to apply the
   * same visibility gate the collector does. A hidden clickable sibling was never a node any
   * selector could have named: counting it as a candidate either lands the tap on something
   * invisible or — as here — makes two candidates out of one and refuses a tap that had exactly one
   * real target.
   */
  @Test
  fun relocationIgnoresAHiddenSiblingOfTheOnlyRealTarget() {
    val message =
      runReportingMessage(
        AndroidTestTapTool(
          TrailblazeNodeSelector(
            androidView = DriverNodeMatch.AndroidView(
              tagRegex = MixedUiFixtureActivity.VIEW_HIDDEN_SIBLING_ROW_TAG,
            ),
          ),
        ),
      )
    assertTrue(message.contains("descendant"), "Expected a reported relocation, got: $message")
    run(AndroidTestAssertVisibleTool(viewText(MixedUiFixtureActivity.VIEW_STATUS_VISIBLE_ACTION)))
  }

  /**
   * A composable is in the semantics tree from the frame it is composed on, but reads as zero-area
   * until it is laid out. Every property a selector matches on is already correct by then, so
   * "matched" does not mean "ready" — acting would tap a point with nothing under it. The resolver
   * has to keep polling, and this is the wait that proves it does.
   *
   * The clock starts BEFORE the tap that schedules the reveal, so the floor is exact rather than a
   * guess about how long dispatch takes: the delay begins at the click, which is after [startMs],
   * so the assert cannot possibly return sooner. Nothing here bets on machine speed — a loaded CI
   * agent only makes the inequality more true.
   */
  @Test
  fun aComposeNodeLaidOutLateIsWaitedForRatherThanFailed() {
    val startMs = System.currentTimeMillis()
    run(AndroidTestTapTool(viewText(MixedUiFixtureActivity.VIEW_REVEAL_LABEL)))
    run(AndroidTestAssertVisibleTool(composeTag(MixedUiFixtureActivity.COMPOSE_LATE_TAG)))
    val elapsedMs = System.currentTimeMillis() - startMs
    assertTrue(
      elapsedMs >= MixedUiFixtureActivity.LATE_PLACEMENT_DELAY_MS,
      "The node was placed before the resolver ever had to wait, so this proved nothing. " +
        "elapsed=${elapsedMs}ms",
    )
  }

  /**
   * The other end of that wait. "Matched an element with no bounds" and "matched nothing" are
   * different problems with different fixes — a layout that never places its child versus a
   * selector that names the wrong thing — and the driver reports the wrong one at its own peril:
   * the author would go hunting for a typo in a selector that is exactly right.
   */
  @Test
  fun aComposeNodeThatIsNeverLaidOutFailsAsUnplacedNotAsUnmatched() {
    val error =
      runExpectingError(
        AndroidTestAssertVisibleTool(composeTag(MixedUiFixtureActivity.COMPOSE_NEVER_PLACED_TAG)),
      )
    assertTrue(
      error.contains("no on-screen bounds"),
      "A matched-but-unplaced node must not be reported as unmatched, got: $error",
    )
  }

  private fun viewText(text: String) =
    TrailblazeNodeSelector(androidView = DriverNodeMatch.AndroidView(textRegex = text))

  private fun composeTag(tag: String) =
    TrailblazeNodeSelector(compose = DriverNodeMatch.Compose(testTag = tag))

  private fun composeText(text: String) =
    TrailblazeNodeSelector(compose = DriverNodeMatch.Compose(textRegex = text))

  private fun run(tool: TrailblazeTool) {
    runReportingMessage(tool)
  }

  private fun runReportingMessage(tool: TrailblazeTool): String {
    val result = execute(tool)
    val success = assertIs<TrailblazeToolResult.Success>(result, "ANDROID_TEST tool failed: $result")
    return success.message.orEmpty()
  }

  private fun runExpectingError(tool: TrailblazeTool): String {
    val result = execute(tool)
    return assertIs<TrailblazeToolResult.Error>(result, "Expected a failure, got: $result").errorMessage
  }

  private fun execute(tool: TrailblazeTool): TrailblazeToolResult =
    agent.runTrailblazeTools(
      tools = listOf(tool),
      traceId = null,
      screenState = null,
      elementComparator = NoOpElementComparator,
      screenStateProvider = agent.screenStateProvider,
    ).result
}
