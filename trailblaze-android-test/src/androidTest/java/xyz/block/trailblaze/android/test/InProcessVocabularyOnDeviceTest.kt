package xyz.block.trailblaze.android.test

import android.content.Context
import android.net.wifi.WifiManager
import android.os.SystemClock
import android.provider.Settings
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.datetime.Clock
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import xyz.block.trailblaze.android.test.tools.AndroidTestAssertNotVisibleTool
import xyz.block.trailblaze.android.test.tools.AndroidTestAssertVisibleTool
import xyz.block.trailblaze.android.test.tools.AndroidTestTapTool
import xyz.block.trailblaze.api.DriverNodeMatch
import xyz.block.trailblaze.api.TrailblazeNodeSelector
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDeviceInfo
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.exception.TrailblazeException
import xyz.block.trailblaze.exception.TrailblazeToolExecutionException
import xyz.block.trailblaze.logs.client.TrailblazeLogger
import xyz.block.trailblaze.logs.client.TrailblazeSession
import xyz.block.trailblaze.logs.client.temp.OtherTrailblazeTool
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.toolcalls.commands.ClearTextTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.InputTextRandomTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.InputTextTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.NetworkConnectionTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.TapOnPointTrailblazeTool
import xyz.block.trailblaze.utils.NoOpElementComparator

/**
 * On-device contracts for the recorded-trail vocabulary the ANDROID_TEST driver interprets beyond
 * its own `androidTest_*` tools: the canonical tools whose recorded body is Maestro dispatch.
 *
 * Everything is asserted through what the screen or the device then does, never through which
 * adapter ran — a trail cannot see the difference and neither should these tests.
 */
class InProcessVocabularyOnDeviceTest {

  @get:Rule val composeRule = createEmptyComposeRule() as AndroidComposeTestRule<*, *>

  private lateinit var scenario: ActivityScenario<MixedUiFixtureActivity>
  private lateinit var agent: AndroidTestTrailblazeAgent

  @Before
  fun launchFixture() {
    scenario = ActivityScenario.launch(MixedUiFixtureActivity::class.java)
    var activity: MixedUiFixtureActivity? = null
    scenario.onActivity { activity = it }
    val fixture = checkNotNull(activity)
    agent = agentFor(
      RuleBackedAndroidTestTarget(activityProvider = { fixture }, composeTestRule = composeRule),
      fixture.resources.displayMetrics.widthPixels,
      fixture.resources.displayMetrics.heightPixels,
    )
  }

  @After
  fun closeFixture() {
    scenario.close()
  }

  /**
   * A recorded `tapOnPoint` touches the screen at its coordinates, as on every other driver. The
   * button's own click handler running is the proof that the touch reached it.
   */
  @Test
  fun aPointTapLandsOnWhatIsDrawnThere() {
    val origin = IntArray(2)
    var size = 0 to 0
    scenario.onActivity { activity ->
      val button = activity.findViewById<android.view.View>(MixedUiFixtureActivity.VIEW_BUTTON_ID)
      button.getLocationOnScreen(origin)
      size = button.width to button.height
    }
    run(TapOnPointTrailblazeTool(x = origin[0] + size.first / 2, y = origin[1] + size.second / 2))
    run(AndroidTestAssertVisibleTool(viewText(MixedUiFixtureActivity.VIEW_STATUS_CLICKED)))
  }

  /**
   * A generated value is typed into the focused field and remembered under the trail's variable,
   * so a later step can name it. Both halves are asserted from outside: the screen shows the value,
   * and memory hands back the same string.
   */
  @Test
  fun inputTextRandomTypesAFreshValueAndRemembersIt() {
    run(AndroidTestTapTool(composeTag(MixedUiFixtureActivity.COMPOSE_INPUT_TAG)))
    run(InputTextRandomTrailblazeTool(prefix = "TB-", digitCount = 8, variable = "generated"))

    val remembered = assertNotNull(
      agent.memory.variables["generated"],
      "inputTextRandom should remember its value under the variable the trail named",
    )
    assertTrue(
      remembered.matches(Regex("TB-[0-9]{8}")),
      "The generated value should follow the prefix/digit shape asked for, got: $remembered",
    )
    run(AndroidTestAssertVisibleTool(composeText(Regex.escape(remembered))))
  }

  /**
   * `inputText` with a `selector` types into the field it names, not the one holding focus: the
   * Compose input is focused first, and the text must land in the View input instead.
   */
  @Test
  fun inputTextWithASelectorTypesIntoThatFieldRatherThanTheFocusedOne() {
    run(AndroidTestTapTool(composeTag(MixedUiFixtureActivity.COMPOSE_INPUT_TAG)))

    run(InputTextTrailblazeTool(text = "typed-by-selector", selector = viewHint(MixedUiFixtureActivity.VIEW_INPUT_HINT)))

    run(AndroidTestAssertVisibleTool(viewText("typed-by-selector")))
    run(AndroidTestAssertNotVisibleTool(composeText("typed-by-selector")))
  }

  /**
   * A masked field reads as its mask while empty, so appending to what it reports offers it the
   * mask plus the input, which it refuses. The typing still has to land, as it does on the
   * accessibility driver, which reads the field back and types the keys when the edit did not take.
   */
  @Test
  fun inputTextFillsAMaskedFieldThatRefusesTheAppendedEdit() {
    run(InputTextTrailblazeTool(text = "03152013", selector = composeTag(MixedUiFixtureActivity.MASKED_DATE_TAG)))

    run(AndroidTestAssertVisibleTool(composeText("^${MixedUiFixtureActivity.MASKED_DATE_STATUS_PREFIX}03152013$")))
  }

  /**
   * A field that keeps only part of a pasted value still holds what it held before, and the keys
   * land after it, as a user's would. Emptying it first lost the prefilled text: "defgh" typed
   * into "abc" left "defgh", where a user's keys leave "abcde".
   */
  @Test
  fun inputTextKeepsWhatAFieldHeldWhenItTakesOnlyPartOfTheValue() {
    run(InputTextTrailblazeTool(text = "defgh", selector = composeTag(MixedUiFixtureActivity.CLIPPED_TAG)))

    run(AndroidTestAssertVisibleTool(composeText("^${MixedUiFixtureActivity.CLIPPED_STATUS_PREFIX}abcde$")))
  }

  /**
   * A recording names a described text field by that description as text: the platform publishes
   * it on a node of its own, apart from the field's (empty) text.
   */
  @Test
  fun inputTextFindsAFieldByTheDescriptionARecordingNamesItBy() {
    run(
      InputTextTrailblazeTool(
        text = "alex",
        selector = TrailblazeNodeSelector(
          androidAccessibility = DriverNodeMatch.AndroidAccessibility(textRegex = MixedUiFixtureActivity.SEARCH_DESCRIPTION),
        ),
      ),
    )

    run(AndroidTestAssertVisibleTool(composeText("^${MixedUiFixtureActivity.SEARCH_STATUS_PREFIX}alex$")))
  }

  /**
   * `clearFirst` replaces what the named field holds, on a View field and a Compose one. Selectors
   * match the whole text, so the field reading as just the new value proves nothing was left.
   */
  @Test
  fun inputTextWithClearFirstLeavesTheFieldHoldingOnlyTheNewText() {
    run(InputTextTrailblazeTool(text = "old-view-value", selector = viewHint(MixedUiFixtureActivity.VIEW_INPUT_HINT)))
    run(InputTextTrailblazeTool(text = "old-compose-value", selector = composeTag(MixedUiFixtureActivity.COMPOSE_INPUT_TAG)))

    run(
      InputTextTrailblazeTool(
        text = "new-view-value",
        selector = viewHint(MixedUiFixtureActivity.VIEW_INPUT_HINT),
        clearFirst = true,
      ),
    )
    run(
      InputTextTrailblazeTool(
        text = "new-compose-value",
        selector = composeTag(MixedUiFixtureActivity.COMPOSE_INPUT_TAG),
        clearFirst = true,
      ),
    )

    run(AndroidTestAssertVisibleTool(viewText("new-view-value")))
    run(AndroidTestAssertVisibleTool(composeText("new-compose-value")))
  }

  /** A `selector` that matches nothing fails the step, and nothing reaches the focused field. */
  @Test
  fun inputTextWithASelectorThatMatchesNothingTypesNothing() {
    run(AndroidTestTapTool(composeTag(MixedUiFixtureActivity.COMPOSE_INPUT_TAG)))

    runExpectingError(
      InputTextTrailblazeTool(
        text = "stray-text",
        selector = viewHint("No Such Field"),
      ),
    )

    run(AndroidTestAssertNotVisibleTool(composeText("stray-text")))
  }

  /**
   * `clearText` erases the focused Compose text input in one action, matching the canonical
   * tool's "erase what's focused" contract without a selector of its own.
   */
  @Test
  fun clearTextErasesTheFocusedComposeTextInput() {
    run(AndroidTestTapTool(composeTag(MixedUiFixtureActivity.COMPOSE_INPUT_TAG)))
    run(InputTextRandomTrailblazeTool(prefix = "TB-", digitCount = 8, variable = "toClear"))
    val typed = assertNotNull(agent.memory.variables["toClear"])
    run(AndroidTestAssertVisibleTool(composeText(Regex.escape(typed))))

    run(ClearTextTrailblazeTool)

    run(AndroidTestAssertNotVisibleTool(composeText(Regex.escape(typed))))
  }

  /**
   * The View half of the same contract: no focused Compose node, so the adapter falls back to the
   * focused [android.widget.EditText] and clears it through the same one-action replace.
   */
  @Test
  fun clearTextErasesTheFocusedEditText() {
    run(AndroidTestTapTool(viewHint(MixedUiFixtureActivity.VIEW_INPUT_HINT)))
    run(InputTextRandomTrailblazeTool(prefix = "TB-", digitCount = 8, variable = "toClearView"))
    val typed = assertNotNull(agent.memory.variables["toClearView"])
    run(AndroidTestAssertVisibleTool(viewText(typed)))

    run(ClearTextTrailblazeTool)

    run(AndroidTestAssertNotVisibleTool(viewText(typed)))
  }

  /**
   * A tool this driver has no executor for names ITSELF, not the wrapper it arrived in.
   *
   * Every tool the runtime cannot resolve arrives as one wrapper type, so a message built from the
   * wrapper reads the same whatever the trail called — leaving a failed run with no way to tell
   * which tool needs registering, which is the one fact needed to fix it.
   *
   * Asserted on the THROW, not on a returned error. A tool that RUNS and fails — however it fails,
   * including by throwing — comes back as a [TrailblazeToolResult.Error], because every executing
   * branch is wrapped in the measured dispatch that catches and converts. The unregistered case
   * never reaches that wrapper: there is no tool to run, so it throws straight past it.
   *
   * Both halves are pinned: it is NOT the tool-execution subclass — that would mean a tool ran and
   * failed, a different story carrying a different message — and it names the tool.
   */
  @Test
  fun aToolWithNoInProcessExecutorNamesTheToolTheTrailCalled() {
    val thrown = assertFailsWith<TrailblazeException> {
      execute(OtherTrailblazeTool(toolName = "someApp_seedAnAccount"))
    }
    assertFalse(
      thrown is TrailblazeToolExecutionException,
      "An unregistered tool never ran, so this must not arrive as a tool-EXECUTION failure: $thrown",
    )
    assertTrue(
      thrown.message?.contains("someApp_seedAnAccount") == true,
      "The failure must name the tool the trail called, got: ${thrown.message}",
    )
  }

  /** A digit count that cannot produce a value is refused rather than typing the prefix alone. */
  @Test
  fun inputTextRandomRefusesADigitCountThatGeneratesNothing() {
    val error = runExpectingError(InputTextRandomTrailblazeTool(digitCount = 0))
    assertTrue(error.contains("digitCount"), "The refusal must name the bad input, got: $error")
  }

  /**
   * Going offline and back online really switches the radios, rather than reporting a toggle
   * nothing performed. Wi-Fi is the one the emulator and a real device both report honestly, so it
   * is the one asserted; the tool switches mobile data and Bluetooth in the same breath.
   */
  @Test
  fun networkConnectionTakesTheDeviceOfflineAndBackOn() {
    val wifi = InstrumentationRegistry.getInstrumentation()
      .targetContext
      .applicationContext
      .getSystemService(Context.WIFI_SERVICE) as WifiManager
    try {
      run(allRadios(on = false))
      awaitWifi(false, wifi, "mobile_networkConnection with every radio false should turn Wi-Fi off")
      run(allRadios(on = true))
      awaitWifi(true, wifi, "mobile_networkConnection with every radio true should turn Wi-Fi back on")
    } finally {
      // Hand the device back on, and WAIT for it: a radio still on its way up is the state every
      // test after this one would start from. Nothing here asserts — a restore that fails must not
      // become the failure this test reports, which would hide whichever assertion above sent us
      // here.
      execute(allRadios(on = true))
      awaitWifiSettle(true, wifi)
    }
  }

  /**
   * Airplane mode is a real signal this tool sets, and it is NOT the radios.
   *
   * The only place that claim is checked against a device. `cmd connectivity airplane-mode`
   * genuinely moves `airplane_mode_on`, read back through the framework's own `Settings.Global`
   * rather than through the shell the tool wrote with, so a tool that only echoed its own write
   * cannot pass.
   *
   * The second half is the state the airplane-mode-first ordering exists for, and the reason these
   * are separate fields: the tool can end a step with airplane mode ON and wifi genuinely working.
   *
   * It asserts only that direction on purpose. Whether airplane mode takes wifi down on its way in
   * is NOT stable — the platform remembers whether wifi was last switched on or off during
   * airplane mode and repeats that on every later entry, so a test asserting "airplane mode
   * implies wifi down" passes or fails on leftover state from whatever ran before it. Asking for
   * both and asserting the result holds in either regime, and is the claim that actually matters.
   *
   * A device left in airplane mode is as persistent as a radio left down and strands every build
   * behind it, so the restore is unconditional and waited on.
   */
  @Test
  fun networkConnectionSetsRealAirplaneModeAndCanHoldWifiUpThroughIt() {
    val wifi = InstrumentationRegistry.getInstrumentation()
      .targetContext
      .applicationContext
      .getSystemService(Context.WIFI_SERVICE) as WifiManager
    try {
      run(NetworkConnectionTrailblazeTool(airplaneMode = true))
      awaitAirplaneMode(true)
      assertEquals(true, readAirplaneMode(), "airplaneMode: true must set the real flag")

      // Airplane mode and wifi in one step: the tool sets the flag, then brings wifi back up.
      run(NetworkConnectionTrailblazeTool(airplaneMode = true, wifi = true))
      awaitWifi(
        true,
        wifi,
        "wifi is on airplane_mode_toggleable_radios, so a step asking for airplane mode AND wifi " +
          "must end with wifi actually up",
      )
      assertEquals(
        true,
        readAirplaneMode(),
        "bringing wifi back up must NOT clear airplane mode — the two are separate signals",
      )

      // The modem is not on that list, so the platform would switch it straight back off.
      // Refused by name with the device untouched, rather than reported as a switch that landed.
      val error = runExpectingError(
        NetworkConnectionTrailblazeTool(airplaneMode = true, cellular = true),
      )
      assertTrue(
        error.contains("cellular"),
        "The refusal must name the radio airplane mode would take down, got: $error",
      )

      run(NetworkConnectionTrailblazeTool(airplaneMode = false))
      awaitAirplaneMode(false)
      assertEquals(false, readAirplaneMode(), "airplaneMode: false must clear the real flag")
    } finally {
      // Nothing here asserts: a restore that fails must not become the failure this test reports.
      execute(NetworkConnectionTrailblazeTool(airplaneMode = false))
      awaitAirplaneMode(false)
      execute(allRadios(on = true))
      awaitWifiSettle(true, wifi)
    }
  }

  /**
   * Every radio at once, which is what "offline" means now that the tool names them one by one.
   * Airplane mode is deliberately left alone: it is a separate signal, and it does not by itself
   * take wifi down.
   */
  private fun allRadios(on: Boolean) =
    NetworkConnectionTrailblazeTool(wifi = on, cellular = on, bluetooth = on)

  /** The platform's own view of the flag, read independently of the shell the tool wrote it with. */
  private fun readAirplaneMode(): Boolean = Settings.Global.getInt(
    InstrumentationRegistry.getInstrumentation().targetContext.contentResolver,
    Settings.Global.AIRPLANE_MODE_ON,
    0,
  ) == 1

  private fun awaitAirplaneMode(expected: Boolean) {
    val deadline = SystemClock.uptimeMillis() + WIFI_SETTLE_TIMEOUT_MS
    while (readAirplaneMode() != expected && SystemClock.uptimeMillis() < deadline) {
      SystemClock.sleep(250)
    }
  }

  /**
   * Waits for Wi-Fi to reach [expected], then asserts it got there.
   *
   * Switching a radio is asynchronous on every Android device — the framework accepts the request
   * and reports the new state once the supplicant has actually moved — so reading the state on the
   * line after the call tests the timing of the emulator, not the tool.
   */
  private fun awaitWifi(expected: Boolean, wifi: WifiManager, message: String) {
    awaitWifiSettle(expected, wifi)
    assertEquals(expected, wifi.isWifiEnabled, message)
  }

  private fun awaitWifiSettle(expected: Boolean, wifi: WifiManager) {
    val deadline = SystemClock.uptimeMillis() + WIFI_SETTLE_TIMEOUT_MS
    while (wifi.isWifiEnabled != expected && SystemClock.uptimeMillis() < deadline) {
      SystemClock.sleep(250)
    }
  }

  private fun viewText(text: String) =
    TrailblazeNodeSelector(androidView = DriverNodeMatch.AndroidView(textRegex = text))

  private fun viewHint(hint: String) =
    TrailblazeNodeSelector(androidView = DriverNodeMatch.AndroidView(hintTextRegex = hint))

  private fun composeTag(tag: String) =
    TrailblazeNodeSelector(compose = DriverNodeMatch.Compose(testTag = tag))

  private fun composeText(textRegex: String) =
    TrailblazeNodeSelector(compose = DriverNodeMatch.Compose(textRegex = textRegex))

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

  private fun agentFor(
    target: AndroidTestTarget,
    widthPixels: Int,
    heightPixels: Int,
  ): AndroidTestTrailblazeAgent =
    AndroidTestTrailblazeAgent(
      target = target,
      trailblazeLogger = TrailblazeLogger.createNoOp(),
      trailblazeDeviceInfoProvider = {
        TrailblazeDeviceInfo(
          trailblazeDeviceId = TrailblazeDeviceId(
            instanceId = "instrumentation",
            trailblazeDevicePlatform = TrailblazeDevicePlatform.ANDROID,
          ),
          trailblazeDriverType = TrailblazeDriverType.ANDROID_TEST,
          widthPixels = widthPixels,
          heightPixels = heightPixels,
        )
      },
      sessionProvider = {
        TrailblazeSession(
          sessionId = SessionId("in_process_vocabulary_on_device"),
          startTime = Clock.System.now(),
        )
      },
    )

  private companion object {
    /** Generous: an emulator brings its Wi-Fi back up in a second or two, a loaded one slower. */
    const val WIFI_SETTLE_TIMEOUT_MS = 20_000L
  }
}
