package xyz.block.trailblaze.android.test

import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import java.util.Locale
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
import xyz.block.trailblaze.toolcalls.commands.MaestroTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.ScrollUntilTextIsVisibleTrailblazeTool
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
  private val defaultLocale: Locale = Locale.getDefault()

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
    Locale.setDefault(defaultLocale)
    scenario.close()
  }

  @Test
  fun aRecordedScrollUntilVisibleBringsAnOffScreenElementIntoView() {
    run(AndroidTestAssertNotVisibleTool(viewText(ScrollFixtureActivity.TARGET_LABEL)))
    run(
      maestro(
        """
        - scrollUntilVisible:
            element:
              text: "${ScrollFixtureActivity.TARGET_LABEL}"
            direction: DOWN
        """.trimIndent(),
      ),
    )
    run(AndroidTestAssertVisibleTool(viewText(ScrollFixtureActivity.TARGET_LABEL)))
  }

  /**
   * The whole option set a recorded scroll actually carries, together.
   *
   * Estate recordings do not write the bare command — they write the direction, a scroll speed, the
   * default full-visibility ask, an explicit `centerElement: false`, a timeout and an `optional`
   * flag, all at once. Accepting each option in isolation would not say this list is accepted, and
   * this driver refuses a command the moment ONE of its options is unreadable.
   */
  @Test
  fun aFullyWrittenOutRecordedScrollIsAccepted() {
    run(
      maestro(
        """
        - scrollUntilVisible:
            element:
              text: "${ScrollFixtureActivity.TARGET_LABEL}"
            direction: DOWN
            speed: "400"
            visibilityPercentage: 100
            centerElement: false
            timeout: "10000"
            optional: false
        """.trimIndent(),
      ),
    )
    run(AndroidTestAssertVisibleTool(viewText(ScrollFixtureActivity.TARGET_LABEL)))
  }

  /** Maestro's default direction, left unwritten, is the one this driver can scroll. */
  @Test
  fun aScrollUntilVisibleWithNoDirectionScrollsForward() {
    run(
      maestro(
        """
        - scrollUntilVisible:
            element: "${ScrollFixtureActivity.TARGET_LABEL}"
        """.trimIndent(),
      ),
    )
    run(AndroidTestAssertVisibleTool(viewText(ScrollFixtureActivity.TARGET_LABEL)))
  }

  /**
   * In-process scrolling drives the container forward, so there is no honest reading of UP. It
   * refuses rather than scrolling the other way and reporting the element was never found.
   */
  @Test
  fun aDirectionThisDriverCannotScrollIsRefusedRatherThanScrolledAnyway() {
    val error = runExpectingError(
      maestro(
        """
        - scrollUntilVisible:
            element:
              text: "${ScrollFixtureActivity.TOP_LABEL}"
            direction: UP
        """.trimIndent(),
      ),
    )
    assertTrue(error.contains("UP"), "The refusal must name the direction it refused, got: $error")
  }

  /**
   * A recorded timeout bounds the search, so a wrong selector stops there instead of scrolling on.
   *
   * The bound is short on purpose, and is the tool's OWN timeout parameter rather than a wall-clock
   * bet on the machine. The loop is bounded by a scroll count as well; a bound well under the time
   * that many scrolls take is what makes the deadline the one this search reaches.
   */
  @Test
  fun aRecordedTimeoutEndsTheSearchAndSaysSo() {
    val error = runExpectingError(
      maestro(
        """
        - scrollUntilVisible:
            element:
              text: "Nothing In This List Says This"
            timeout: 500
        """.trimIndent(),
      ),
    )
    assertTrue(error.contains("500ms"), "The failure must name the bound it hit, got: $error")
  }

  /**
   * The options this driver cannot carry out refuse from EITHER entry point.
   *
   * A recorded scroll reaches this driver two ways — as the canonical `scrollUntilTextIsVisible`
   * tool and as a raw Maestro command — onto one scroll loop. A refusal that only one of them
   * makes is the same silent degradation as no refusal at all, so both are asserted, and both are
   * asserted to still run the same scroll when the options ARE ones this driver can carry out.
   */
  @Test
  fun anOptionTheScrollLoopCannotCarryOutIsRefusedFromEitherEntryPoint() {
    val refusals = listOf(
      "canonical centerElement" to ScrollUntilTextIsVisibleTrailblazeTool(
        text = ScrollFixtureActivity.TARGET_LABEL,
        centerElement = true,
      ),
      "canonical visibilityPercentage" to ScrollUntilTextIsVisibleTrailblazeTool(
        text = ScrollFixtureActivity.TARGET_LABEL,
        visibilityPercentage = 50,
      ),
      "canonical scrollStartPosition" to ScrollUntilTextIsVisibleTrailblazeTool(
        text = ScrollFixtureActivity.TARGET_LABEL,
        scrollStartPosition = TrailblazeScrollStartPosition.TOP,
      ),
      "maestro centerElement" to maestro(
        """
        - scrollUntilVisible:
            element:
              text: "${ScrollFixtureActivity.TARGET_LABEL}"
            centerElement: true
        """.trimIndent(),
      ),
      "maestro visibilityPercentage" to maestro(
        """
        - scrollUntilVisible:
            element:
              text: "${ScrollFixtureActivity.TARGET_LABEL}"
            visibilityPercentage: 50
        """.trimIndent(),
      ),
    )
    for ((what, tool) in refusals) {
      val error = runExpectingError(tool)
      assertTrue(
        error.contains("centerElement") || error.contains("visibilityPercentage") ||
          error.contains("scrollStartPosition"),
        "The $what refusal must name the option it refused, got: $error",
      )
    }
    // Refused rather than scrolled: the target is still where it started.
    run(AndroidTestAssertNotVisibleTool(viewText(ScrollFixtureActivity.TARGET_LABEL)))
  }

  /**
   * A recorded direction reads the same whatever locale the DEVICE is in.
   *
   * Trails set device locale, and the direction is a machine token, so a case fold that followed
   * the device locale would be a bug with a real device behind it: under Turkish rules `right`
   * uppercases to `RİGHT`, which is no direction at all, and the recording would be turned away
   * for its SPELLING on that device while running on every other one. Kotlin's no-argument
   * `uppercase()` folds by invariant rules and does not have that problem — this test is what
   * keeps it that way, and fails if the parse is ever switched to the device's locale.
   *
   * `RIGHT` is a direction this driver refuses either way, so the subject is WHICH refusal comes
   * back: the one naming the direction it cannot scroll, not the one saying the word was not a
   * direction. Turkish is the case that has a dotted capital I; `DOWN` has no I and so could not
   * tell the two readings apart.
   */
  @Test
  fun aRecordedDirectionIsReadTheSameWhateverLocaleTheDeviceIsIn() {
    Locale.setDefault(Locale.forLanguageTag("tr"))
    val error = runExpectingError(
      maestro(
        """
        - scrollUntilVisible:
            element:
              text: "${ScrollFixtureActivity.TARGET_LABEL}"
            direction: right
        """.trimIndent(),
      ),
    )
    assertTrue(
      error.contains("direction=RIGHT"),
      "Expected the refusal that names the direction this driver cannot scroll, got: $error",
    )
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

  /**
   * A `label` is a name for the step in a report, not a thing to match on, so a labelled recording
   * replays rather than being refused over its own prose.
   */
  @Test
  fun aLabelledRecordedStepStillRuns() {
    run(
      maestro(
        """
        - scrollUntilVisible:
            element:
              text: "${ScrollFixtureActivity.TARGET_LABEL}"
            label: "Scroll to the target row"
        - assertVisible:
            text: "${ScrollFixtureActivity.TARGET_LABEL}"
            label: "The target row is on screen"
        """.trimIndent(),
      ),
    )
  }

  /**
   * A recorded value this driver cannot read is refused, not treated as unwritten.
   *
   * Reading `timeout: "20s"` as "no timeout given" would drop the bound the recording set and let
   * the search run to the scroll cap — a silent change to what the trail waits for.
   */
  @Test
  fun anUnreadableOptionIsRefusedRatherThanReadAsAbsent() {
    val error = runExpectingError(
      maestro(
        """
        - scrollUntilVisible:
            element:
              text: "${ScrollFixtureActivity.TARGET_LABEL}"
            timeout: "20s"
        """.trimIndent(),
      ),
    )
    assertTrue(error.contains("20s"), "The refusal must name the value it could not read, got: $error")
    run(AndroidTestAssertNotVisibleTool(viewText(ScrollFixtureActivity.TARGET_LABEL)))
  }

  /** Under `optional`, a target that never appears is skipped like any other unmet step. */
  @Test
  fun anOptionalScrollForSomethingAbsentIsSkipped() {
    val message = runReportingMessage(
      maestro(
        """
        - scrollUntilVisible:
            element:
              text: "Nothing In This List Says This"
            timeout: 500
            optional: true
        """.trimIndent(),
      ),
    )
    assertTrue(
      message.contains("Optional 'scrollUntilVisible'"),
      "The skip should be reported, not silent, got: $message",
    )
  }

  private fun maestro(yaml: String) = MaestroTrailblazeTool(yaml = yaml)

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
