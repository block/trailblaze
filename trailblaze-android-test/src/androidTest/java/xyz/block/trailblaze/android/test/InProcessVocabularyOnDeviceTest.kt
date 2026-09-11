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
import xyz.block.trailblaze.toolcalls.commands.InputTextRandomTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.MaestroTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.NetworkConnectionTrailblazeTool
import xyz.block.trailblaze.utils.NoOpElementComparator

/**
 * On-device contracts for the recorded-trail vocabulary the ANDROID_TEST driver interprets beyond
 * its own `androidTest_*` tools: Maestro's `optional`, and the canonical tools whose recorded body
 * is Maestro dispatch.
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
   * The contract Maestro's `optional` carries on every driver: the step the screen refused is
   * skipped, and the flow keeps going. The second tap landing is the proof — an `optional` that
   * merely reported success while aborting the rest of the command list would look identical from
   * the result alone.
   */
  @Test
  fun anOptionalStepThatFindsNothingIsSkippedAndTheFlowContinues() {
    val message = runReportingMessage(
      maestro(
        """
        - tapOn:
            text: "No Such Button"
            optional: true
        - tapOn:
            text: "${MixedUiFixtureActivity.VIEW_BUTTON_LABEL}"
        """.trimIndent(),
      ),
    )
    run(AndroidTestAssertVisibleTool(viewText(MixedUiFixtureActivity.VIEW_STATUS_CLICKED)))
    assertTrue(
      message.contains("Optional 'tapOn'"),
      "The skip should be reported, not silent, got: $message",
    )
  }

  /** The same step without `optional` is still fatal — that is what makes the flag mean anything. */
  @Test
  fun aStepThatFindsNothingStillFailsWhenItIsNotOptional() {
    val error = runExpectingError(
      maestro(
        """
        - tapOn:
            text: "No Such Button"
        - tapOn:
            text: "${MixedUiFixtureActivity.VIEW_BUTTON_LABEL}"
        """.trimIndent(),
      ),
    )
    assertTrue(error.isNotBlank(), "A failed non-optional step must report why")
    run(AndroidTestAssertVisibleTool(viewText(MixedUiFixtureActivity.VIEW_STATUS_INITIAL)))
  }

  /** `optional` is read where Maestro puts it on a wait — on the nested element selector. */
  @Test
  fun anOptionalNestedSelectorSkipsItsWaitAndTheFlowContinues() {
    run(
      maestro(
        """
        - extendedWaitUntil:
            visible:
              text: "No Such Thing"
              optional: true
            timeout: 1000
        - tapOn:
            text: "${MixedUiFixtureActivity.VIEW_BUTTON_LABEL}"
        """.trimIndent(),
      ),
    )
    run(AndroidTestAssertVisibleTool(viewText(MixedUiFixtureActivity.VIEW_STATUS_CLICKED)))
  }

  /**
   * `optional` says what to do when the SCREEN refuses a step. A step this driver cannot interpret
   * was never attempted, so skipping it would silently drop whatever the recording meant — the
   * failure this driver exists to make loud.
   */
  @Test
  fun anOptionalStepOutsideTheVocabularyStillFails() {
    val error = runExpectingError(
      maestro(
        """
        - tapOn:
            point: "50%,50%"
            optional: true
        """.trimIndent(),
      ),
    )
    assertTrue(
      error.contains("not supported") && error.contains("point"),
      "A refusal must name what it refused, got: $error",
    )
  }

  /**
   * The recorded way to prove something is GONE. The positive assert cannot say it, so a trail that
   * removes a thing and checks it left has no other step to use.
   */
  @Test
  fun aRecordedAssertNotVisiblePassesForSomethingAbsentAndFailsForSomethingPresent() {
    run(
      maestro(
        """
        - assertNotVisible:
            text: "No Such Label"
            timeout: 1000
        """.trimIndent(),
      ),
    )
    val error = runExpectingError(
      maestro(
        """
        - assertNotVisible:
            text: "${MixedUiFixtureActivity.VIEW_BUTTON_LABEL}"
            timeout: 1000
        """.trimIndent(),
      ),
    )
    assertTrue(
      error.contains(MixedUiFixtureActivity.VIEW_BUTTON_LABEL),
      "The failure must name what was still on screen, got: $error",
    )
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

  /**
   * A recorded `optional` this driver cannot read is refused, not quietly read as "not optional".
   *
   * Reading an unrecognised spelling as absent would flip the flag's whole meaning — a step the
   * recording marked skippable would fail the trail — without saying anything.
   */
  @Test
  fun anUnreadableOptionalIsRefusedRatherThanReadAsAbsent() {
    val error = runExpectingError(
      maestro(
        """
        - tapOn:
            text: "No Such Button"
            optional: "yes"
        """.trimIndent(),
      ),
    )
    assertTrue(error.contains("optional"), "The refusal must name the flag it could not read, got: $error")
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
      awaitWifi(false, wifi, "networkConnection with every radio false should turn Wi-Fi off")
      run(allRadios(on = true))
      awaitWifi(true, wifi, "networkConnection with every radio true should turn Wi-Fi back on")
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

  /**
   * Maestro's step-level metadata is not part of what `launchApp` is asking for.
   *
   * `label` and `optional` can be written on ANY recorded command — they name the step in a report
   * and say whether it may be skipped — and every other command here reads them as such. Turning a
   * recorded relaunch away over its own prose would refuse a trail for something that says nothing
   * about the launch.
   *
   * Asserted through WHICH refusal comes back for a launch this driver genuinely cannot perform:
   * one naming the app it was asked to launch, rather than one naming the label. A foreign app id
   * is used so the assertion is about reading the command, and no app is actually relaunched out
   * from under the fixture.
   */
  @Test
  fun aRecordedLaunchAppKeepsItsStepMetadata() {
    val error = runExpectingError(
      maestro(
        """
        - launchApp:
            appId: "com.example.not.the.app.under.test"
            label: "Restart the app"
            optional: false
        """.trimIndent(),
      ),
    )
    assertTrue(
      error.contains("com.example.not.the.app.under.test"),
      "Expected the refusal to name the app it cannot launch, not the step's own metadata: $error",
    )
  }

  private fun maestro(yaml: String) = MaestroTrailblazeTool(yaml = yaml)

  private fun viewText(text: String) =
    TrailblazeNodeSelector(androidView = DriverNodeMatch.AndroidView(textRegex = text))

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
