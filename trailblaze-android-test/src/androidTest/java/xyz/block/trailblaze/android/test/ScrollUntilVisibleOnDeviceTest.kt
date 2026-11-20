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
import xyz.block.trailblaze.android.test.tools.AndroidTestAssertNotVisibleTool
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
import xyz.block.trailblaze.maestro.TrailblazeScrollStartPosition
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.toolcalls.commands.ScrollUntilTextIsVisibleTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.SwipeWithRelativeCoordinatesTool
import xyz.block.trailblaze.utils.NoOpElementComparator

/**
 * On-device contracts for the recorded `scrollUntilVisible`, against content taller than the
 * screen.
 *
 * The subject is what the recording asks for — "keep scrolling until this is on screen" — so the
 * assertions are that the element ends up visible, and that an option this driver cannot carry out
 * says so instead of scrolling anyway.
 */
class ScrollUntilVisibleOnDeviceTest {

  @get:Rule val composeRule = createEmptyComposeRule() as AndroidComposeTestRule<*, *>

  private lateinit var scenario: ActivityScenario<ScrollFixtureActivity>
  private lateinit var agent: AndroidTestTrailblazeAgent

  @Before
  fun launchFixture() {
    scenario = ActivityScenario.launch(ScrollFixtureActivity::class.java)
    var activity: ScrollFixtureActivity? = null
    scenario.onActivity { activity = it }
    val fixture = checkNotNull(activity)
    agent = AndroidTestTrailblazeAgent(
      target = RuleBackedAndroidTestTarget(
        activityProvider = { fixture },
        composeTestRule = composeRule,
      ),
      trailblazeLogger = TrailblazeLogger.createNoOp(),
      trailblazeDeviceInfoProvider = {
        TrailblazeDeviceInfo(
          trailblazeDeviceId = TrailblazeDeviceId(
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
          sessionId = SessionId("scroll_until_visible_on_device"),
          startTime = Clock.System.now(),
        )
      },
    )
  }

  @After
  fun closeFixture() {
    scenario.close()
  }

  /**
   * A recorded `swipeWithRelativeCoordinates` drags a finger across the screen, so a long list
   * moves under it: the first row leaves the screen.
   */
  @Test
  fun aRecordedRelativeSwipeDragsTheListUnderTheFinger() {
    run(AndroidTestAssertVisibleTool(viewText(ScrollFixtureActivity.TOP_LABEL)))
    @Suppress("DEPRECATION")
    run(SwipeWithRelativeCoordinatesTool(startRelative = "50%,85%", endRelative = "50%,20%"))
    run(AndroidTestAssertNotVisibleTool(viewText(ScrollFixtureActivity.TOP_LABEL)))
  }

  /** `centerElement: true` scrolls to the target and leaves it on screen, from both entry points. */
  @Test
  fun canonicalCenterElementScrollsToTheTarget() {
    run(ScrollUntilTextIsVisibleTrailblazeTool(text = ScrollFixtureActivity.TARGET_LABEL, centerElement = true))
    run(AndroidTestAssertVisibleTool(viewText(ScrollFixtureActivity.TARGET_LABEL)))
  }

  /**
   * An option this driver cannot carry out is refused, naming the option, rather than dropped and
   * scrolled anyway.
   */
  @Test
  fun anOptionTheScrollLoopCannotCarryOutIsRefused() {
    val refusals = listOf(
      "canonical visibilityPercentage" to ScrollUntilTextIsVisibleTrailblazeTool(
        text = ScrollFixtureActivity.TARGET_LABEL,
        visibilityPercentage = 50,
      ),
      "canonical scrollStartPosition" to ScrollUntilTextIsVisibleTrailblazeTool(
        text = ScrollFixtureActivity.TARGET_LABEL,
        scrollStartPosition = TrailblazeScrollStartPosition.TOP,
      ),
    )
    for ((what, tool) in refusals) {
      val error = runExpectingError(tool)
      assertTrue(
        error.contains("visibilityPercentage") || error.contains("scrollStartPosition"),
        "The $what refusal must name the option it refused, got: $error",
      )
    }
    // Refused rather than scrolled: the target is still where it started.
    run(AndroidTestAssertNotVisibleTool(viewText(ScrollFixtureActivity.TARGET_LABEL)))
  }

  /** The same canonical tool at the defaults it was recorded with still scrolls. */
  @Test
  fun aCanonicalScrollAtItsDefaultOptionsBringsTheElementIntoView() {
    run(ScrollUntilTextIsVisibleTrailblazeTool(text = ScrollFixtureActivity.TARGET_LABEL))
    run(AndroidTestAssertVisibleTool(viewText(ScrollFixtureActivity.TARGET_LABEL)))
  }

  /**
   * A blank `textRegex` next to a real `text` targets the text, as it does on every other driver.
   *
   * The canonical tool treats a blank regex as unwritten and scrolls for the `text` substring. A
   * driver that read the blank as "a regex was given" would search for empty text instead, and
   * the divergence is silent: no refusal, just a scroll that never stops on the right row. So the
   * assertion is not only that the step succeeds but that the row it named is what came into view.
   */
  @Test
  fun aBlankTextRegexBesideARealTextTargetsTheTextLikeEveryOtherDriver() {
    run(AndroidTestAssertNotVisibleTool(viewText(ScrollFixtureActivity.TARGET_LABEL)))
    run(
      ScrollUntilTextIsVisibleTrailblazeTool(
        text = ScrollFixtureActivity.TARGET_LABEL,
        textRegex = "",
        id = "",
      ),
    )
    run(AndroidTestAssertVisibleTool(viewText(ScrollFixtureActivity.TARGET_LABEL)))
  }

  /**
   * Blank in every target slot is no target at all, and is refused as the canonical tool refuses
   * it — not read as "a regex for empty text" and scrolled on.
   */
  @Test
  fun aScrollWhoseEveryTargetIsBlankIsRefusedRatherThanMatchedOnNothing() {
    val error = runExpectingError(
      ScrollUntilTextIsVisibleTrailblazeTool(text = "", textRegex = "", id = ""),
    )
    assertTrue(
      error.contains("text/textRegex/id"),
      "The refusal must say what a target could have been, got: $error",
    )
    run(AndroidTestAssertNotVisibleTool(viewText(ScrollFixtureActivity.TARGET_LABEL)))
  }

  private fun viewText(text: String) =
    TrailblazeNodeSelector(androidView = DriverNodeMatch.AndroidView(textRegex = text))

  private fun run(tool: TrailblazeTool) {
    runReportingMessage(tool)
  }

  private fun runReportingMessage(tool: TrailblazeTool): String {
    val result = execute(tool)
    return assertIs<TrailblazeToolResult.Success>(result, "ANDROID_TEST tool failed: $result")
      .message
      .orEmpty()
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
