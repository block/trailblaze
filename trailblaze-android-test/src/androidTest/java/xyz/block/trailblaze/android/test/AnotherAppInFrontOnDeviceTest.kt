package xyz.block.trailblaze.android.test

import android.content.Intent
import android.os.SystemClock
import android.provider.Settings
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.datetime.Clock
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import xyz.block.trailblaze.android.test.hierarchy.ForeignWindow
import xyz.block.trailblaze.android.test.tools.AndroidTestAssertVisibleTool
import xyz.block.trailblaze.android.test.tools.AndroidTestTapTool
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
import xyz.block.trailblaze.toolcalls.commands.PressKeyTrailblazeTool.PressKeyCode
import xyz.block.trailblaze.toolcalls.commands.PressKeyTrailblazeTool
import xyz.block.trailblaze.utils.NoOpElementComparator

/**
 * Another app in front of the app under test — here the system Settings app, standing in for a
 * browser the app opened or a runtime permission prompt.
 *
 * The app's Activity is stopped behind it, so nothing in this process can see the screen. A
 * recorded accessibility selector still has to find and act on what is there, as it does on the
 * accessibility driver, and the in-process path has to take over again once the app is back.
 */
class AnotherAppInFrontOnDeviceTest {

  @get:Rule val composeRule = createEmptyComposeRule() as AndroidComposeTestRule<*, *>

  private lateinit var scenario: ActivityScenario<MixedUiFixtureActivity>
  private lateinit var agent: AndroidTestTrailblazeAgent

  @Before
  fun launchFixture() {
    scenario = ActivityScenario.launch(MixedUiFixtureActivity::class.java)
    var activity: MixedUiFixtureActivity? = null
    scenario.onActivity { activity = it }
    val fixture = checkNotNull(activity)
    agent = agentFor(RuleBackedAndroidTestTarget(activityProvider = { fixture }, composeTestRule = composeRule))
  }

  @After
  fun closeFixture() {
    scenario.close()
  }

  /**
   * A selector polled the moment a trail starts another app, before its window is in front: the
   * first read finds no other app, and by the time the app's own screen is read its Activity is
   * paused behind the new one. Read with the target a running trail has, whose Activity is
   * whichever one is resumed, this waited out the two-minute launch timeout and failed.
   */
  @Test
  fun aSelectorFindsAnotherAppsWindowThatWasStillOpeningWhenThePollBegan() {
    agent = agentFor(
      RuleBackedAndroidTestTarget(
        activityProvider = { AppUnderTestLauncher.awaitResumedActivity(yieldToAnotherApp = true) },
        composeTestRule = composeRule,
      ),
    )
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

    run(AndroidTestAssertVisibleTool(SETTINGS_CATEGORY, timeoutMs = FRONT_WINDOW_TIMEOUT_MS))
  }

  @Test
  fun aSelectorFindsAndTapsAnotherAppsWindowThenTheAppTakesOverAgain() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    val front = awaitFrontPackage { it != null }
    assertTrue(front != null, "Settings never came to the front")

    val category = SETTINGS_CATEGORY
    run(AndroidTestAssertVisibleTool(category))
    val tapped = run(AndroidTestTapTool(category))
    assertTrue(tapped.contains("another app's window"), "The tap should land through the other app's window, got: $tapped")

    // BACK until the fixture is in front again: the category tap opened a page, and Settings' home
    // sits under it.
    repeat(BACK_PRESSES_TO_LEAVE_SETTINGS) {
      if (ForeignWindow.frontPackage() != null) run(PressKeyTrailblazeTool(PressKeyCode.BACK))
    }
    assertEquals(null, awaitFrontPackage { it == null }, "the fixture never came back to the front")

    run(
      AndroidTestAssertVisibleTool(
        TrailblazeNodeSelector(androidView = DriverNodeMatch.AndroidView(textRegex = MixedUiFixtureActivity.VIEW_STATUS_INITIAL)),
      ),
    )
  }

  private fun awaitFrontPackage(until: (String?) -> Boolean): String? {
    val deadline = SystemClock.uptimeMillis() + FRONT_WINDOW_TIMEOUT_MS
    while (true) {
      val front = ForeignWindow.frontPackage()
      if (until(front) || SystemClock.uptimeMillis() >= deadline) return front
      SystemClock.sleep(100)
    }
  }

  private fun run(tool: TrailblazeTool): String {
    val result = agent.runTrailblazeTools(
      tools = listOf(tool),
      traceId = null,
      screenState = null,
      elementComparator = NoOpElementComparator,
      screenStateProvider = agent.screenStateProvider,
    ).result
    return assertIs<TrailblazeToolResult.Success>(result, "ANDROID_TEST tool failed: $result").message.orEmpty()
  }

  private fun agentFor(target: AndroidTestTarget): AndroidTestTrailblazeAgent {
    val metrics = InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics
    return AndroidTestTrailblazeAgent(
      target = target,
      trailblazeLogger = TrailblazeLogger.createNoOp(),
      trailblazeDeviceInfoProvider = {
        TrailblazeDeviceInfo(
          trailblazeDeviceId = TrailblazeDeviceId(
            instanceId = "instrumentation",
            trailblazeDevicePlatform = TrailblazeDevicePlatform.ANDROID,
          ),
          trailblazeDriverType = TrailblazeDriverType.ANDROID_TEST,
          widthPixels = metrics.widthPixels,
          heightPixels = metrics.heightPixels,
        )
      },
      sessionProvider = {
        TrailblazeSession(sessionId = SessionId("another_app_in_front_on_device"), startTime = Clock.System.now())
      },
    )
  }

  private companion object {
    // Settings' home lists its categories; which ones, and their wording, varies by release.
    val SETTINGS_CATEGORY = TrailblazeNodeSelector(
      androidAccessibility = DriverNodeMatch.AndroidAccessibility(
        textRegex = "(?i).*(network|internet|connections|wi.?fi).*",
      ),
    )
    const val FRONT_WINDOW_TIMEOUT_MS = 10_000L
    const val BACK_PRESSES_TO_LEAVE_SETTINGS = 4
  }
}
