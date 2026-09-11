package xyz.block.trailblaze.toolcalls.commands

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import maestro.orchestra.AirplaneValue
import maestro.orchestra.Command
import maestro.orchestra.SetAirplaneModeCommand
import xyz.block.trailblaze.AgentMemory
import xyz.block.trailblaze.MaestroTrailblazeAgent
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDeviceInfo
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.logs.client.TrailblazeLogger
import xyz.block.trailblaze.logs.client.TrailblazeSession
import xyz.block.trailblaze.logs.client.TrailblazeSessionProvider
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.toolcalls.commands.NetworkConnectionTrailblazeTool.AndroidRadio

/**
 * Pins what a `networkConnection` step MEANS, on both halves of the tool.
 *
 * The Android half runs against a device shell, so what can be proven here is the vocabulary it
 * reads the device with — the flag values, and the refusals a device cannot be involved in.
 * `InProcessVocabularyOnDeviceTest` proves the radios and the airplane-mode flag actually move,
 * including the combination this tool refuses because the platform would undo it.
 *
 * `AndroidRadioShellCommandsStayInOneSourceTest` guards the other half: that no driver goes back
 * to writing its own copy of the radio set or of the airplane-mode command.
 */
class NetworkConnectionTrailblazeToolTest {

  @Test
  fun `a radio switch is an svc enable or disable for that radio`() {
    assertEquals(
      "svc wifi disable",
      NetworkConnectionTrailblazeTool.androidRadioShellCommand(AndroidRadio.WIFI, on = false),
    )
    assertEquals(
      "svc bluetooth enable",
      NetworkConnectionTrailblazeTool.androidRadioShellCommand(AndroidRadio.BLUETOOTH, on = true),
    )
  }

  /**
   * Airplane mode is set as airplane mode, not stood in for by the radios.
   *
   * This is the whole reason the tool exists in this shape: the radios and the airplane-mode
   * signal are separate things an app can read separately, and the old tool claimed the second
   * while only doing the first.
   */
  @Test
  fun `airplane mode is set through the connectivity service, not through the radios`() {
    assertEquals(
      "cmd connectivity airplane-mode enable",
      NetworkConnectionTrailblazeTool.androidAirplaneModeShellCommand(enabled = true),
    )
    assertEquals(
      "cmd connectivity airplane-mode disable",
      NetworkConnectionTrailblazeTool.androidAirplaneModeShellCommand(enabled = false),
    )
  }

  /**
   * The inversion the two Maestro `setAirplaneMode` driver overrides depend on, which has no other
   * guard. Maestro's `setAirplaneMode(enabled = true)` means the device goes OFFLINE, so it lowers
   * to the radios coming DOWN. A dropped negation here disconnects a device a trail asked to be
   * online, and every other test still passes.
   */
  @Test
  fun `the maestro stand-in takes the radios down for airplane mode on, and up for off`() {
    assertEquals(
      listOf("svc wifi disable", "svc data disable", "svc bluetooth disable"),
      NetworkConnectionTrailblazeTool
        .androidMaestroAirplaneModeRadioCommands(airplaneModeEnabled = true).values.toList(),
    )
    assertEquals(
      listOf("svc wifi enable", "svc data enable", "svc bluetooth enable"),
      NetworkConnectionTrailblazeTool
        .androidMaestroAirplaneModeRadioCommands(airplaneModeEnabled = false).values.toList(),
    )
  }

  /**
   * Every name each radio answers to, written out.
   *
   * A radio carries four of them and they genuinely differ, so every one of these is a place a
   * plausible-looking wrong value fails somewhere else entirely: the wrong `svc` name switches
   * nothing, the wrong `settings` key verifies a flag the switch never touched, the wrong field
   * name sends a trail author looking for a field their tool does not have, and the wrong
   * exemption name silently mis-answers whether airplane mode is about to take that radio down.
   *
   * Spelled out as literals rather than derived, because a derivation would read the same table
   * the production code does and pass whatever it said.
   */
  @Test
  fun `each radio's four names are the ones the device and the schema actually use`() {
    assertEquals(
      mapOf(
        AndroidRadio.WIFI to listOf("wifi", "wifi", "wifi_on", "wifi"),
        AndroidRadio.CELLULAR to listOf("data", "cellular", "mobile_data", "cell"),
        AndroidRadio.BLUETOOTH to listOf("bluetooth", "bluetooth", "bluetooth_on", "bluetooth"),
      ),
      AndroidRadio.entries.associateWith {
        listOf(it.svcName, it.fieldName, it.stateSetting, it.airplaneModeRadioName)
      },
    )
  }

  /**
   * What every value of every flag means, written out.
   *
   * The polarity is the load-bearing part: reading these the wrong way round reports a device that
   * stayed online as having gone offline, which is the failure `networkConnection` exists to
   * prevent. Every value NOT listed here has to read as unreadable, which is what stops a future
   * platform value being guessed into a direction.
   */
  @Test
  fun `each radio flag's values mean exactly this and nothing else`() {
    assertEquals(EXPECTED_SETTING_MEANINGS, actualSettingMeanings())
  }

  /**
   * `wifi_on`'s two airplane-mode states, and the reason this is a mapping rather than a
   * comparison against "1"/"0".
   *
   * Under airplane mode wifi does not write `1` or `0`: left on it reads `2`, taken down it reads
   * `3`. Matching literals reads both as unreadable, so `networkConnection` waits out its whole
   * settle ceiling and reports a refusal on a device whose radio is sitting in exactly the state
   * the trail asked for. `2` meaning on is device-verified — a device reading `wifi_on=2` answers
   * "Wifi is enabled" to `cmd wifi status`.
   */
  @Test
  fun `the wifi airplane mode states read as on and off, not as unreadable`() {
    assertEquals(true, AndroidRadio.WIFI.settingMeansOn("2"))
    assertEquals(false, AndroidRadio.WIFI.settingMeansOn("3"))
  }

  /**
   * The flags do not share a vocabulary, which is the failure a single value-to-state map would
   * cause rather than prevent.
   *
   * `bluetooth_on` also holds `2`, but it means the reverse of wifi's: the platform writes it to
   * remember that bluetooth was ON before airplane mode took it down, so the radio is OFF. Reading
   * it as "on" would let `networkConnection` report a bluetooth switch as landed while the radio
   * had not moved — a silent wrong success, the one outcome worse than a loud refusal. It stays
   * unmapped until there is device evidence for it, and unmapped means refuse.
   */
  @Test
  fun `bluetooth does not inherit wifi's meaning for the same flag value`() {
    assertNull(
      AndroidRadio.BLUETOOTH.settingMeansOn("2"),
      "bluetooth_on=2 means the radio is OFF, not on — it must never read as connected",
    )
    assertNull(
      AndroidRadio.CELLULAR.settingMeansOn("2"),
      "mobile_data has no airplane-mode state; 2 has no verified meaning to report",
    )
  }

  /**
   * Anything else is refused rather than guessed. `settings get` prints the literal `null` for a
   * flag that was never written, and a future platform value has no direction this framework can
   * infer — guessing "on" there reports a radio as up that a trail just took down.
   */
  @Test
  fun `an unset or unrecognized radio flag reads as neither`() {
    listOf("null", "", "4", "on", "-1").forEach { unreadable ->
      assertNull(
        AndroidRadio.WIFI.settingMeansOn(unreadable),
        "'$unreadable' is not a state this framework can read, so it must not answer on or off",
      )
    }
  }

  /**
   * `settings get global <flag>` comes back with a trailing newline, so the raw shell output has
   * to read the same as the trimmed value — otherwise every read is unreadable on a real device
   * while every test here passes.
   */
  @Test
  fun `a radio flag surrounded by shell whitespace reads the same as a bare one`() {
    assertEquals(true, AndroidRadio.WIFI.settingMeansOn("1\n"))
    assertEquals(false, AndroidRadio.WIFI.settingMeansOn(" 3 \r\n"))
  }

  /**
   * A radio the tool switches but has no meanings for would refuse every switch on the device,
   * after burning the full settle ceiling — so the mapping has to cover every radio the tool can
   * move in both directions, not just the ones a test happens to name.
   */
  @Test
  fun `every radio the tool switches can be verified in both directions`() {
    AndroidRadio.entries.forEach { radio ->
      listOf(true, false).forEach { on ->
        assertTrue(
          radio.settingValuesMeaning(on).isNotEmpty(),
          "${radio.fieldName} (${radio.stateSetting}) has no value meaning on=$on, so a switch " +
            "in that direction can never be confirmed",
        )
      }
    }
  }

  /**
   * The values a refusal message names have to be the values that would actually have satisfied
   * it, and all of them.
   *
   * Checked against the same literals as the meanings above rather than against the production
   * mapping: derived from the mapping it explains, this assertion would hold for any contents at
   * all, including an empty one.
   */
  @Test
  fun `the values a failure message names are exactly the ones that would have passed`() {
    assertEquals(
      EXPECTED_SETTING_MEANINGS.mapValues { (_, meanings) ->
        listOf(true, false).associateWith { on ->
          meanings.filterValues { it == on }.keys.sorted()
        }
      },
      AndroidRadio.entries.associateWith { radio ->
        listOf(true, false).associateWith { on -> radio.settingValuesMeaning(on) }
      },
    )
  }

  /**
   * Airplane mode's flag is read the same way the radios' are: an answer this framework cannot
   * read is `null`, not "off".
   *
   * `settings get` prints the literal `null` for a flag that was never written, and a failed read
   * can surface as an error string. A bare `== "1"` folds both into "off" — so a step asking for
   * `airplaneMode = false` would report success without proving the device moved, including where
   * the command did nothing at all. That is the silent wrong success this tool exists to prevent,
   * one flag over.
   */
  @Test
  fun `an unreadable airplane mode flag is neither on nor off`() {
    assertEquals(
      true,
      NetworkConnectionTrailblazeTool.androidAirplaneModeSettingMeansOn("1"),
    )
    assertEquals(
      false,
      NetworkConnectionTrailblazeTool.androidAirplaneModeSettingMeansOn("0\n"),
    )
    listOf("null", "", "2", "true", "Error: unknown").forEach { unreadable ->
      assertNull(
        NetworkConnectionTrailblazeTool.androidAirplaneModeSettingMeansOn(unreadable),
        "'$unreadable' must not read as airplane mode being off",
      )
    }
  }

  /**
   * A driver's `Boolean` read rounds an unreadable flag DOWN to off, where this tool refuses it.
   *
   * Both are right, which is why they are separate functions. `maestro.Driver
   * .isAirplaneModeEnabled(): Boolean` has no third answer to return, and `"1"` is the only value
   * that says airplane mode is on — so an unreadable flag is never evidence FOR airplane mode, and
   * answering `true` would claim a state nothing observed. The direction is pinned here rather than
   * only on a device, because the device test needs an emulator and this is the whole of the new
   * decision.
   */
  @Test
  fun `a driver's boolean airplane mode read rounds an unreadable flag down to off`() {
    assertEquals(
      true,
      NetworkConnectionTrailblazeTool.androidAirplaneModeSettingMeansOnOrOff("1"),
    )
    assertEquals(
      false,
      NetworkConnectionTrailblazeTool.androidAirplaneModeSettingMeansOnOrOff("0\n"),
    )
    listOf("null", "", "2", "true", "Error: unknown").forEach { unreadable ->
      assertEquals(
        false,
        NetworkConnectionTrailblazeTool.androidAirplaneModeSettingMeansOnOrOff(unreadable),
        "'$unreadable' must not read as airplane mode being ON",
      )
    }
  }

  /**
   * The flag read is composed in one place, so the driver's read and this tool's own write
   * verification cannot end up asking the device two different questions.
   */
  @Test
  fun `the setting read command names the flag it was given`() {
    assertEquals(
      "settings get global airplane_mode_on",
      NetworkConnectionTrailblazeTool.androidSettingReadCommand(
        NetworkConnectionTrailblazeTool.ANDROID_AIRPLANE_MODE_SETTING,
      ),
    )
  }

  /**
   * The two airplane-mode lists answer different questions, and only their difference is doomed.
   *
   * `airplane_mode_radios` is what airplane mode touches; `airplane_mode_toggleable_radios` is what
   * it lets back on. Reading only the second — which this tool used to do — refuses a radio that
   * airplane mode was never going to touch, on any device whose vendor trimmed the first list. The
   * default configuration is the row that has to keep working: the modem, and only the modem, is
   * the one a trail cannot hold up.
   */
  @Test
  fun `only a radio airplane mode both touches and will not restore is doomed`() {
    assertEquals(
      setOf(AndroidRadio.CELLULAR),
      NetworkConnectionTrailblazeTool.androidRadiosAirplaneModeHoldsDown(
        affectedRadiosSetting = "cell,bluetooth,wifi,nfc,wimax",
        toggleableRadiosSetting = "bluetooth,wifi",
      ),
      "the default Android configuration must doom the modem and nothing else",
    )
    assertEquals(
      emptySet<AndroidRadio>(),
      NetworkConnectionTrailblazeTool.androidRadiosAirplaneModeHoldsDown(
        affectedRadiosSetting = "nfc,wimax",
        toggleableRadiosSetting = "",
      ),
      "a device whose airplane mode touches none of these radios must doom none of them",
    )
    assertEquals(
      setOf(AndroidRadio.WIFI, AndroidRadio.CELLULAR, AndroidRadio.BLUETOOTH),
      NetworkConnectionTrailblazeTool.androidRadiosAirplaneModeHoldsDown(
        affectedRadiosSetting = "cell,bluetooth,wifi",
        toggleableRadiosSetting = "",
      ),
      "a device that restores nothing must doom every radio it touches",
    )
  }

  /**
   * A list the device did not answer resolves toward refusing, and each list does it on its own.
   *
   * `settings get` prints the literal `null` for a setting that was never written, and a rejected
   * read prints whatever the shell had to say. Reading either as an empty affected list would
   * silently drop the whole guard — the one outcome worth biasing against, since the guard's job is
   * to stop the tool reporting a switch the platform is about to undo, and the cost of being wrong
   * the other way is a refusal the author can read.
   *
   * The shell error is the case a "named no radio I recognise" rule cannot get right: it looks
   * exactly like a list of radios this tool does not know.
   */
  @Test
  fun `an unreadable airplane mode radio list is resolved toward refusing`() {
    assertEquals(
      setOf(AndroidRadio.WIFI, AndroidRadio.CELLULAR, AndroidRadio.BLUETOOTH),
      NetworkConnectionTrailblazeTool.androidRadiosAirplaneModeHoldsDown(
        affectedRadiosSetting = "null",
        toggleableRadiosSetting = "null",
      ),
      "neither list readable must mean every radio is doomed, not none",
    )
    assertEquals(
      setOf(AndroidRadio.CELLULAR),
      NetworkConnectionTrailblazeTool.androidRadiosAirplaneModeHoldsDown(
        affectedRadiosSetting = "Error: unknown",
        toggleableRadiosSetting = "bluetooth,wifi",
      ),
      "a shell error on the affected list must not read as an empty list, and must not throw away " +
        "a readable toggleable list",
    )
    assertEquals(
      setOf(AndroidRadio.WIFI, AndroidRadio.CELLULAR, AndroidRadio.BLUETOOTH),
      NetworkConnectionTrailblazeTool.androidRadiosAirplaneModeHoldsDown(
        affectedRadiosSetting = "cell,bluetooth,wifi",
        toggleableRadiosSetting = "cmd: Failure calling service",
      ),
      "a shell error on the toggleable list must restore nothing, not everything",
    )
  }

  /**
   * An empty list means no radios, which is not the same answer as no list.
   *
   * A device whose airplane mode is configured to touch nothing is a device where `airplaneMode`
   * plus any radio ON is a perfectly legal request, so lumping empty in with the unreadable values
   * refuses a step the platform would have honoured. It is only safe to read empty literally
   * because a failed read is classified on its shape rather than on whether it happened to name a
   * radio — which is what stops `Error: unknown` sliding in here as "no radios".
   */
  @Test
  fun `an empty airplane mode radio list means no radios, not an unreadable one`() {
    assertEquals(
      emptySet<AndroidRadio>(),
      NetworkConnectionTrailblazeTool.androidRadiosAirplaneModeHoldsDown(
        affectedRadiosSetting = "",
        toggleableRadiosSetting = "bluetooth,wifi",
      ),
      "airplane mode that affects no radio must doom no radio",
    )
  }

  /**
   * Airplane mode is refused on the Android versions whose shell has no command for it.
   *
   * `cmd connectivity airplane-mode` arrived in API 28 and this module declares `minSdk 26`, so the
   * gap is real rather than theoretical. Without the gate an older device runs a command that does
   * nothing, waits out the settle ceiling, and then blames the flag — the version is the answer, so
   * the version is what gets said. An unreadable `getprop` is NOT refused: it is not evidence of an
   * old device, and rounding it down would ground a device that works.
   */
  @Test
  fun `airplane mode is refused only on an android whose api level is readably too old`() {
    assertEquals(
      false,
      NetworkConnectionTrailblazeTool.androidSupportsRealAirplaneMode(
        (NetworkConnectionTrailblazeTool.ANDROID_MIN_SDK_FOR_AIRPLANE_MODE - 1).toString(),
      ),
      "the API level below the command's own minimum must be refused",
    )
    assertEquals(
      true,
      NetworkConnectionTrailblazeTool.androidSupportsRealAirplaneMode(
        NetworkConnectionTrailblazeTool.ANDROID_MIN_SDK_FOR_AIRPLANE_MODE.toString(),
      ),
      "the command's own minimum must be allowed, not refused",
    )
    assertEquals(
      true,
      NetworkConnectionTrailblazeTool.androidSupportsRealAirplaneMode("36\n"),
      "a value with the shell's trailing newline must read the same as a bare one",
    )
    listOf("", "null", "Error: unknown", "P").forEach { unreadable ->
      assertNull(
        NetworkConnectionTrailblazeTool.androidSupportsRealAirplaneMode(unreadable),
        "'$unreadable' must not read as an Android too old to set airplane mode",
      )
    }
  }

  /**
   * A step that names nothing is refused, not passed.
   *
   * Every field is optional so a trail can move one radio without disturbing the others, and the
   * cost of that is that the empty call is syntactically valid. It is far likelier to be a typo or
   * a half-written step than a deliberate no-op, and a no-op that reports success is a step that
   * silently stops testing what it was written to test.
   */
  @Test
  fun `a call that asks for nothing is refused rather than passing`() {
    val error = errorFrom(NetworkConnectionTrailblazeTool(), platform = TrailblazeDevicePlatform.IOS)
    listOf("wifi", "cellular", "bluetooth", "airplaneMode").forEach { field ->
      assertTrue(field in error, "the refusal must name '$field' as a thing to set, got: $error")
    }
    // A trail still written against the old `connected: Boolean` deserializes to all-unset and
    // lands exactly here. Without naming the removed field, the message reads as "you set
    // nothing" to an author who plainly set something, and says nothing about what to write
    // instead.
    assertTrue(
      "connected" in error,
      "the refusal must say `connected` was removed, since an old step lands here: $error",
    )
  }

  /**
   * iOS gets the one knob it has, in the right direction. Maestro's
   * `setAirplaneMode(enabled = true)` means airplane mode ON.
   */
  @Test
  fun `on iOS, airplane mode is sent to maestro in the direction it was asked for`() {
    assertEquals(
      listOf(SetAirplaneModeCommand(AirplaneValue.Enable)),
      maestroCommandsFrom(NetworkConnectionTrailblazeTool(airplaneMode = true)),
    )
    assertEquals(
      listOf(SetAirplaneModeCommand(AirplaneValue.Disable)),
      maestroCommandsFrom(NetworkConnectionTrailblazeTool(airplaneMode = false)),
    )
  }

  /**
   * A radio asked for off Android is refused BY NAME, and nothing is sent.
   *
   * Maestro has one knob here and no per-radio vocabulary, so the tempting lowering is to treat
   * "wifi off" as airplane mode. That would run a trail that reads as testing one thing and tests
   * another — and on iOS it would not even take the device offline. Asserting the emitted commands
   * are empty is the half that matters: a refusal that still sent the command would look identical
   * from the error alone.
   */
  @Test
  fun `on iOS, a radio is refused by name instead of being lowered onto airplane mode`() {
    val agent = RecordingAgent()
    val result = runBlocking {
      NetworkConnectionTrailblazeTool(wifi = false, cellular = false)
        .execute(context(TrailblazeDevicePlatform.IOS, agent))
    }
    val error = (result as TrailblazeToolResult.Error).errorMessage
    assertTrue("wifi" in error, "the refusal must name wifi, got: $error")
    assertTrue("cellular" in error, "the refusal must name cellular, got: $error")
    assertEquals(
      emptyList(),
      agent.commands,
      "a refused radio must not still reach the device as an airplane-mode toggle",
    )
  }

  /**
   * Both platforms with no radios and no airplane mode say so by name, rather than failing deeper
   * down where the message names a missing agent instead of an unsupported platform.
   */
  @Test
  fun `a platform with no network radios refuses the tool by name`() {
    listOf(TrailblazeDevicePlatform.WEB, TrailblazeDevicePlatform.DESKTOP).forEach { platform ->
      val error = errorFrom(NetworkConnectionTrailblazeTool(airplaneMode = true), platform)
      assertTrue(
        platform.displayName in error,
        "the refusal must name the platform it cannot run on, got: $error",
      )
    }
  }

  /**
   * A missing device handle is reported as a missing device handle.
   *
   * Both of these are reachable — an Android context assembled without a shell executor, and an
   * iOS one assembled without a Maestro agent — and neither can be diagnosed from a
   * `NullPointerException` thrown several frames down. The refusal names the handle that was
   * absent so the answer is in the trail's own log.
   */
  @Test
  fun `a device this tool cannot reach is refused by naming the missing handle`() {
    val androidError = runBlocking {
      NetworkConnectionTrailblazeTool(wifi = false)
        .execute(context(TrailblazeDevicePlatform.ANDROID, agent = RecordingAgent()))
    }
    assertTrue(androidError is TrailblazeToolResult.Error, "expected a refusal, got: $androidError")
    assertTrue(
      "AndroidDeviceCommandExecutor" in androidError.errorMessage,
      "the refusal must name the missing shell executor, got: ${androidError.errorMessage}",
    )

    val iosError = runBlocking {
      NetworkConnectionTrailblazeTool(airplaneMode = true)
        .execute(context(TrailblazeDevicePlatform.IOS, agent = null))
    }
    assertTrue(iosError is TrailblazeToolResult.Error, "expected a refusal, got: $iosError")
    assertTrue(
      "Maestro agent" in iosError.errorMessage,
      "the refusal must name the missing Maestro agent, got: ${iosError.errorMessage}",
    )
  }

  private fun errorFrom(
    tool: NetworkConnectionTrailblazeTool,
    platform: TrailblazeDevicePlatform,
  ): String {
    val result = runBlocking { tool.execute(context(platform, RecordingAgent())) }
    assertTrue(result is TrailblazeToolResult.Error, "expected a refusal, got: $result")
    return result.errorMessage
  }

  private fun maestroCommandsFrom(tool: NetworkConnectionTrailblazeTool): List<Command> {
    val agent = RecordingAgent()
    val result = runBlocking { tool.execute(context(TrailblazeDevicePlatform.IOS, agent)) }
    assertTrue(result is TrailblazeToolResult.Success, "expected a success, got: $result")
    return agent.commands
  }

  private fun actualSettingMeanings(): Map<AndroidRadio, Map<String, Boolean>> =
    AndroidRadio.entries.associateWith { radio ->
      CANDIDATE_SETTING_VALUES.mapNotNull { value ->
        radio.settingMeansOn(value)?.let { value to it }
      }.toMap()
    }

  private fun context(
    platform: TrailblazeDevicePlatform,
    agent: MaestroTrailblazeAgent?,
  ) = TrailblazeToolExecutionContext(
    screenState = null,
    traceId = null,
    trailblazeDeviceInfo = deviceInfo(platform),
    sessionProvider = SESSION_PROVIDER,
    trailblazeLogger = TrailblazeLogger.createNoOp(),
    memory = AgentMemory(),
    maestroTrailblazeAgent = agent,
  )

  /** Records what reached Maestro instead of executing it, so a refusal's silence is provable. */
  private class RecordingAgent : MaestroTrailblazeAgent(
    trailblazeLogger = TrailblazeLogger.createNoOp(),
    trailblazeDeviceInfoProvider = { deviceInfo(TrailblazeDevicePlatform.IOS) },
    sessionProvider = SESSION_PROVIDER,
  ) {
    val commands = mutableListOf<Command>()

    override suspend fun executeMaestroCommands(
      commands: List<Command>,
      traceId: xyz.block.trailblaze.logs.model.TraceId?,
    ): TrailblazeToolResult {
      this.commands += commands
      return TrailblazeToolResult.Success()
    }
  }

  companion object {
    private val SESSION_PROVIDER = TrailblazeSessionProvider {
      TrailblazeSession(sessionId = SessionId("fixture-session"), startTime = Clock.System.now())
    }

    /**
     * Every value a flag could plausibly hold, so the expectations below can assert what each flag
     * does NOT read as. Without the values nobody maps, an expectation only ever says which values
     * are understood and never that the rest are refused.
     */
    private val CANDIDATE_SETTING_VALUES =
      listOf("0", "1", "2", "3", "4", "-1", "null", "", "on", "true")

    /** Written out, not derived: the production mapping is the thing under test. */
    private val EXPECTED_SETTING_MEANINGS = mapOf(
      AndroidRadio.WIFI to mapOf("0" to false, "1" to true, "2" to true, "3" to false),
      AndroidRadio.CELLULAR to mapOf("0" to false, "1" to true),
      AndroidRadio.BLUETOOTH to mapOf("0" to false, "1" to true),
    )

    private fun deviceInfo(platform: TrailblazeDevicePlatform) = TrailblazeDeviceInfo(
      trailblazeDeviceId = TrailblazeDeviceId(
        instanceId = "fixture-device",
        trailblazeDevicePlatform = platform,
      ),
      trailblazeDriverType = when (platform) {
        TrailblazeDevicePlatform.IOS -> TrailblazeDriverType.IOS_HOST
        TrailblazeDevicePlatform.WEB -> TrailblazeDriverType.PLAYWRIGHT_NATIVE
        TrailblazeDevicePlatform.DESKTOP -> TrailblazeDriverType.COMPOSE
        TrailblazeDevicePlatform.ANDROID -> TrailblazeDriverType.ANDROID_ONDEVICE_ACCESSIBILITY
      },
      widthPixels = 1080,
      heightPixels = 1920,
    )
  }
}
