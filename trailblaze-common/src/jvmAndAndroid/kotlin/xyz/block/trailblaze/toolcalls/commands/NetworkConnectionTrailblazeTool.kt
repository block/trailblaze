package xyz.block.trailblaze.toolcalls.commands

import ai.koog.agents.core.tools.annotations.LLMDescription
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import maestro.orchestra.AirplaneValue
import maestro.orchestra.SetAirplaneModeCommand
import xyz.block.trailblaze.device.AndroidDeviceCommandExecutor
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.toolcalls.ExecutableTrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolClass
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult

/**
 * Sets the device's radios and airplane-mode signal, each independently.
 *
 * There is no `connected: Boolean` shorthand on purpose. "Connected" would have to decide for the
 * trail which radios it meant, and adding a radio to that set later would silently change what
 * every already-recorded step meant. Naming the radios keeps a recorded trail readable and fixes
 * its meaning for good.
 *
 * Airplane mode is deliberately NOT the same request as taking the radios down, because on Android
 * the two come apart in both directions.
 *
 * Switching the radios off never sets the airplane-mode signal, so an app coding against that
 * signal sees nothing.
 *
 * And airplane mode is not a synonym for offline. Whether it takes down a radio on
 * `airplane_mode_toggleable_radios` (default `bluetooth,wifi`) depends on a preference the platform
 * REMEMBERS, so the identical command gives opposite results on two devices that look the same.
 * Device-verified on API 36, both directions reproducible from a clean `airplane_mode_on=0`,
 * `wifi_on=1`:
 * - after wifi was last switched OFF during airplane mode, entering it moves `wifi_on` 1 → 3 and
 *   `cmd wifi status` reports "Wifi is disabled";
 * - after wifi was last switched ON during airplane mode, entering it moves `wifi_on` 1 → 2 and
 *   wifi stays enabled — and keeps doing so on every later entry, surviving airplane mode being
 *   turned off and on again.
 *
 * So a trail can never infer the radios from airplane mode, in either direction, which is the
 * whole reason these are separate fields rather than one "offline" flag. A trail that wants both
 * asks for both.
 *
 * On Android this executes directly against the device shell — no Maestro. On iOS it falls back to
 * Maestro's `SetAirplaneModeCommand`, the only part of this tool that platform can express. Web and
 * desktop have no network radios to set and refuse.
 */
@Serializable
@TrailblazeToolClass("networkConnection")
@LLMDescription(
  """
Sets the device's network radios and airplane mode. Every field is optional and a field left unset
is not touched, so this can change one radio without disturbing the others.

To take the device OFFLINE, set wifi, cellular and bluetooth all to false. To bring it back online,
set them all to true.

airplaneMode sets the real airplane-mode signal that apps read, which is NOT the same request as
taking the radios down. Setting the radios off does not set that signal, so an app that checks
airplane mode will not see one. Name whichever the trail is actually testing, or both.

Asking for airplaneMode true AND a radio true is allowed only for a radio this device lets a user
re-enable during airplane mode (wifi and bluetooth, normally; mobile data, normally not) — that is
a real state, and it is refused by name when the device would just undo it.
""",
)
data class NetworkConnectionTrailblazeTool(
  @param:LLMDescription("Whether wifi should be on. Android only. Omit to leave it alone.")
  val wifi: Boolean? = null,
  @param:LLMDescription("Whether mobile data should be on. Android only. Omit to leave it alone.")
  val cellular: Boolean? = null,
  @param:LLMDescription("Whether bluetooth should be on. Android only. Omit to leave it alone.")
  val bluetooth: Boolean? = null,
  @param:LLMDescription(
    "Whether airplane mode should be on. Does not by itself take the device offline. " +
      "Omit to leave it alone.",
  )
  val airplaneMode: Boolean? = null,
) : ExecutableTrailblazeTool {

  override suspend fun execute(
    toolExecutionContext: TrailblazeToolExecutionContext,
  ): TrailblazeToolResult {
    if (requestedRadios().isEmpty() && airplaneMode == null) {
      return invalidRequest(
        "networkConnection was asked to change nothing — set at least one of wifi, cellular, " +
          "bluetooth or airplaneMode. A call with every field omitted is far more likely to be a " +
          "mistake than a deliberate no-op, so it is refused rather than silently passing. If " +
          "this step used to say `connected: true|false`, that field was removed: name the " +
          "radios instead (offline is wifi, cellular and bluetooth all false), and note that " +
          "airplaneMode is now a separate signal that does not by itself take the device offline.",
      )
    }
    return when (val platform = toolExecutionContext.trailblazeDeviceInfo.platform) {
      TrailblazeDevicePlatform.ANDROID -> {
        val executor = toolExecutionContext.androidDeviceCommandExecutor
          ?: return invalidRequest(
            "networkConnection has no AndroidDeviceCommandExecutor, so it cannot reach the " +
              "device shell",
          )
        executeOnAndroid(executor)
      }

      TrailblazeDevicePlatform.IOS -> executeViaMaestro(toolExecutionContext)

      TrailblazeDevicePlatform.WEB, TrailblazeDevicePlatform.DESKTOP ->
        invalidRequest("networkConnection is not supported on ${platform.displayName}.")
    }
  }

  /**
   * The radios this call asks about, in the order they are switched.
   *
   * The order is [AndroidRadio]'s declaration order, and is fixed only so that a trail, a failure
   * message and the CI script that restores the radios between builds all describe the same
   * sequence. It is NOT a mitigation for anything: a driver reached over adb-over-wifi loses its
   * link the moment wifi goes down no matter where in the sequence that happens, and the radios
   * are independent otherwise.
   */
  private fun requestedRadios(): List<Pair<AndroidRadio, Boolean>> = listOfNotNull(
    wifi?.let { AndroidRadio.WIFI to it },
    cellular?.let { AndroidRadio.CELLULAR to it },
    bluetooth?.let { AndroidRadio.BLUETOOTH to it },
  )

  /**
   * Airplane mode first, then the radios.
   *
   * The order matters and only in this direction: entering or leaving airplane mode moves the
   * radios itself, so radios set first would be overwritten on the way through. Applying them last
   * lets a trail ask for a state the platform would not otherwise produce — airplane mode on with
   * wifi genuinely down, or airplane mode on with wifi deliberately up, which is a real state a
   * user can be in and an app can be tested against.
   *
   * Every shell call in here can throw — `executeShellCommand` surfaces a dead adb link or a
   * rejected command as an exception, not a return value — and a throw part-way leaves the device
   * part-changed. There is no rollback, so the throw is caught and re-reported with the list of
   * what already moved; letting it escape raw would lose exactly the half of the answer a trail
   * needs to be debugged from its log.
   */
  private suspend fun executeOnAndroid(
    executor: AndroidDeviceCommandExecutor,
  ): TrailblazeToolResult {
    val changed = mutableListOf<String>()
    return try {
      applyOnAndroid(executor, changed)
    } catch (e: CancellationException) {
      // A cancelled run is not a device failure. The settle poll suspends in `delay`, so an
      // enclosing timeout or an aborted session arrives here as a throw — and turning it into an
      // ordinary result both blames the device for a step nobody is waiting on any more and reports
      // the coroutine as having finished normally, which is what stops the cancellation propagating.
      throw e
    } catch (e: Exception) {
      deviceFailure(
        "networkConnection failed against the device shell: ${e.message ?: e::class.simpleName} " +
          "(${describeChanged(changed)})",
      )
    }
  }

  private suspend fun applyOnAndroid(
    executor: AndroidDeviceCommandExecutor,
    changed: MutableList<String>,
  ): TrailblazeToolResult {
    refuseAirplaneModeOnAnAndroidTooOldForIt(executor)?.let { return it }
    refuseRadiosAirplaneModeWillTakeDown(executor)?.let { return it }

    if (airplaneMode != null) {
      executor.executeShellCommand(androidAirplaneModeShellCommand(airplaneMode))
      val settled = awaitSetting(
        read = { readSetting(executor, ANDROID_AIRPLANE_MODE_SETTING) },
        reached = { androidAirplaneModeSettingMeansOn(it) == airplaneMode },
      )
      if (androidAirplaneModeSettingMeansOn(settled) != airplaneMode) {
        return deviceFailure(
          "networkConnection could not turn airplane mode ${onOrOff(airplaneMode)} — " +
            "`settings get global $ANDROID_AIRPLANE_MODE_SETTING` still reads '$settled' after " +
            "$SETTLE_MS ms (expected '${if (airplaneMode) "1" else "0"}'; ${describeChanged(changed)})",
        )
      }
      changed += "airplane mode ${onOrOff(airplaneMode)}"
    }

    for ((radio, wantOn) in requestedRadios()) {
      executor.executeShellCommand(androidRadioShellCommand(radio, wantOn))
      // What `svc` PRINTED cannot say whether it worked. It is silent on success for some radios
      // and chatty for others — on API 36 `svc bluetooth` prints "disable: Success" on the happy
      // path — and it exits 0 either way, so reading stdout calls a working switch a failure and a
      // failure that only wrote to stderr a success. Ask the DEVICE instead.
      //
      // The value the poll last saw is what the failure message prints. Re-reading the flag to
      // build the message would let it name a value the check never rejected, which is the shape
      // that sends someone debugging a device that has since settled correctly.
      val settled = awaitSetting(
        read = { readSetting(executor, radio.stateSetting) },
        reached = { radio.settingMeansOn(it) == wantOn },
      )
      if (radio.settingMeansOn(settled) != wantOn) {
        return deviceFailure(
          "networkConnection could not turn ${radio.fieldName} ${onOrOff(wantOn)} — " +
            "`settings get global ${radio.stateSetting}` still reads '$settled' after $SETTLE_MS " +
            "ms (expected any of " +
            radio.settingValuesMeaning(wantOn).joinToString(", ") { "'$it'" } +
            "; ${describeChanged(changed)})",
        )
      }
      changed += "${radio.fieldName} ${onOrOff(wantOn)}"
    }

    verifyRadiosHeld(executor, changed)?.let { return it }

    val state = readAndroidState(executor)
    return TrailblazeToolResult.Success(
      // The state is repeated in the message rather than left to structuredContent alone. The
      // payload does survive the boundaries that matter on the success path, but a report, a
      // session timeline and a human reading either see the message — and for a tool whose whole
      // job is that the device actually moved, "succeeded" should not have to be taken on faith.
      message = "Set ${changed.joinToString(", ")}. Device now reads ${describeState(state)}.",
      structuredContent = state,
    )
  }

  /**
   * Refuses, before the device is touched, an airplane-mode request the shell cannot carry out.
   *
   * `cmd connectivity airplane-mode` arrived in API 28 and this module still declares `minSdk 26`,
   * so an older device runs a command that does nothing, waits out the settle ceiling, and fails
   * naming a flag that was never going to move. Naming the OS version instead answers it in one
   * line, and it is an [invalidRequest] because the step as written cannot run here at all — the
   * trail has to say something different, not retry.
   *
   * A version this cannot read is not refused: the gate exists to explain a known limit, and
   * turning an unreadable `getprop` into a refusal would ground a device that would have worked.
   */
  private fun refuseAirplaneModeOnAnAndroidTooOldForIt(
    executor: AndroidDeviceCommandExecutor,
  ): TrailblazeToolResult? {
    if (airplaneMode == null) return null
    val reported = executor.executeShellCommand("getprop $ANDROID_SDK_INT_PROP").trim()
    if (androidSupportsRealAirplaneMode(reported) != false) return null
    return invalidRequest(
      "networkConnection cannot set airplane mode on this device — `cmd connectivity " +
        "airplane-mode` needs API $ANDROID_MIN_SDK_FOR_AIRPLANE_MODE and `$ANDROID_SDK_INT_PROP` " +
        "reports '$reported'. Trailblaze implements no older path. If the trail only needs the " +
        "device offline, set wifi, cellular and bluetooth false instead — that is a different " +
        "signal to the app, but it is one this device can produce.",
    )
  }

  /**
   * Refuses, before the device is touched, a radio that airplane mode is about to take down.
   *
   * Asking for `airplaneMode = true` alongside a radio ON is only coherent where the platform will
   * leave that radio up, and TWO device-configurable lists decide it, so both are read rather than
   * assumed: `airplane_mode_radios` is the set airplane mode touches at all, and
   * `airplane_mode_toggleable_radios` the subset a user may switch back on while it is on. A radio
   * is doomed only when it is in the first and not the second — by default that is the modem and
   * only the modem, which is why airplane mode with wifi back up is an ordinary state and airplane
   * mode with mobile data up is not.
   *
   * Whether airplane mode takes a doomed radio down on its way in is not fixed — it depends on a
   * remembered preference (see this class's KDoc), so this cannot be decided by predicting that.
   * What the lists decide reliably is whether switching one back on AFTERWARDS sticks, which is
   * what this tool's airplane-mode-first ordering relies on, and it is the only thing this refusal
   * reads them for.
   *
   * Refusing up front is the point. Run anyway and the tool switches a radio the platform then
   * switches back, and for the modem it does not even switch back visibly: `svc data enable`
   * writes the `mobile_data` PREFERENCE, which airplane mode never rewrites, so the flag this
   * tool verifies against reads `1` on a modem that is powered down. That combination used to
   * return Success and report `cellular` as unreadable in the same breath.
   */
  private fun refuseRadiosAirplaneModeWillTakeDown(
    executor: AndroidDeviceCommandExecutor,
  ): TrailblazeToolResult? {
    if (airplaneMode != true) return null
    val affected = readSetting(executor, ANDROID_AIRPLANE_MODE_RADIOS)
    val toggleable = readSetting(executor, ANDROID_AIRPLANE_MODE_TOGGLEABLE_RADIOS)
    val heldDown = androidRadiosAirplaneModeHoldsDown(affected, toggleable)
    val doomed = requestedRadios().filter { (radio, wantOn) -> wantOn && radio in heldDown }
    if (doomed.isEmpty()) return null
    val names = doomed.joinToString(", ") { (radio, _) -> radio.fieldName }
    return invalidRequest(
      "networkConnection cannot hold $names on while airplane mode is on — this device's " +
        "`$ANDROID_AIRPLANE_MODE_RADIOS` is '$affected' and its " +
        "`$ANDROID_AIRPLANE_MODE_TOGGLEABLE_RADIOS` is '$toggleable', so airplane mode switches " +
        "$names off and will not let $names back on, however they were set. Drop " +
        "airplaneMode from this step, or drop $names, depending on which the trail is testing.",
    )
  }

  /**
   * Re-reads every radio this call switched, once, after the whole sequence.
   *
   * Airplane mode is applied asynchronously in TWO stages: the platform writes `airplane_mode_on`
   * and only then broadcasts, and the receivers that actually move the radios run after that. The
   * airplane-mode wait can therefore return on the flag alone, before a receiver has had its turn
   * — so a radio switched after it can be moved back underneath us.
   *
   * This does not prevent the race; it stops the tool reporting success against a state it has
   * just seen contradicted. A radio the receiver moves after this read is still missed, which is
   * the trade for not paying a settle wait on every call that never had the race.
   */
  private fun verifyRadiosHeld(
    executor: AndroidDeviceCommandExecutor,
    changed: List<String>,
  ): TrailblazeToolResult? {
    for ((radio, wantOn) in requestedRadios()) {
      val raw = readSetting(executor, radio.stateSetting)
      // Three outcomes, not two. "Unreadable" is its own answer: `bluetooth_on = 2` is the value
      // the platform writes to remember bluetooth was on before airplane mode took it down, which
      // this framework deliberately does not map, and calling that "the device moved it back"
      // asserts a direction nothing here observed.
      when (radio.settingMeansOn(raw)) {
        wantOn -> continue
        null -> return deviceFailure(
          "networkConnection set ${radio.fieldName} ${onOrOff(wantOn)} and can no longer confirm " +
            "it — `settings get global ${radio.stateSetting}` now reads '$raw', which has no " +
            "verified meaning for this flag. ${AIRPLANE_MODE_RACE_HINT} (${describeChanged(changed)})",
        )

        else -> return deviceFailure(
          "networkConnection set ${radio.fieldName} ${onOrOff(wantOn)} and the device moved it " +
            "back before the step finished — `settings get global ${radio.stateSetting}` now " +
            "reads '$raw'. ${AIRPLANE_MODE_RACE_HINT} (${describeChanged(changed)})",
        )
      }
    }
    return null
  }

  /**
   * iOS, where only airplane mode exists as a concept this framework can drive.
   *
   * Maestro's vocabulary has exactly one knob here and no per-radio commands, so a call naming a
   * radio is refused by name rather than approximated: silently treating "wifi off" as airplane
   * mode would run a trail that reads as testing one thing and tests another.
   *
   * Unlike the Android path this returns no state — there is nothing to read the flag back from,
   * and inventing a payload out of what was requested would report success on a device that never
   * moved.
   */
  private suspend fun executeViaMaestro(
    toolExecutionContext: TrailblazeToolExecutionContext,
  ): TrailblazeToolResult {
    val radios = requestedRadios().map { (radio, _) -> radio.fieldName }
    if (radios.isNotEmpty()) {
      return invalidRequest(
        "networkConnection can only set airplaneMode on this device — " +
          "${radios.joinToString(", ")} ${if (radios.size == 1) "is" else "are"} Android-only, " +
          "because switching individual radios has no equivalent off Android. Ask for " +
          "airplaneMode instead, or run this step on an Android device.",
      )
    }
    // Unreachable given the all-fields-unset guard in execute(), and left as a refusal rather
    // than a `!!` so a future caller reaching this directly gets an answer instead of a crash.
    val enabled = airplaneMode
      ?: return invalidRequest(
        "networkConnection needs airplaneMode set to do anything on this device",
      )
    val agent = toolExecutionContext.maestroTrailblazeAgent
      ?: return invalidRequest(
        "networkConnection has no device shell and no Maestro agent, so it cannot reach a device",
      )
    return agent.runMaestroCommands(
      maestroCommands = listOf(
        SetAirplaneModeCommand(
          value = if (enabled) AirplaneValue.Enable else AirplaneValue.Disable,
        ),
      ),
      traceId = toolExecutionContext.traceId,
    )
  }

  /**
   * Every flag this tool understands, whether or not this call touched it.
   *
   * Reporting the untouched radios too is the point: airplane mode moves radios on its own, so the
   * fields a caller did NOT name are exactly the ones whose state they cannot predict. A flag the
   * device reports in a state this framework has no verified meaning for is `null` here rather
   * than guessed — the same refusal [AndroidRadio.settingMeansOn] makes. That includes airplane
   * mode itself, which is why it is not a plain boolean.
   */
  private fun readAndroidState(executor: AndroidDeviceCommandExecutor): JsonObject {
    val airplaneModeOn = readAirplaneMode(executor)
    val heldDown = if (airplaneModeOn == true) {
      androidRadiosAirplaneModeHoldsDown(
        affectedRadiosSetting = readSetting(executor, ANDROID_AIRPLANE_MODE_RADIOS),
        toggleableRadiosSetting = readSetting(executor, ANDROID_AIRPLANE_MODE_TOGGLEABLE_RADIOS),
      )
    } else {
      emptySet()
    }
    return buildJsonObject {
      AndroidRadio.entries.forEach { radio ->
        val on = radio.settingMeansOn(readSetting(executor, radio.stateSetting))
        // A flag that does not track airplane mode cannot be trusted while airplane mode is on and
        // the radio is not exempt, and `mobile_data` says the wrong thing loudly rather than
        // saying nothing: it is a PREFERENCE that airplane mode never rewrites, so it reads `1`
        // for "the user would like mobile data" on a modem the platform has powered down.
        // Reporting that as `cellular=true` is the same species of lie this tool was rewritten to
        // stop telling. There is no shell read for modem power here, so it is reported as
        // unreadable rather than guessed either way.
        //
        // Only flags that need it are suppressed. `wifi_on` writes `3` for "off because airplane
        // mode", which is a verified answer this tool can read straight off the device — blanking
        // it would throw away the very thing that makes the wifi case legible.
        val suppressed = !radio.stateSettingTracksAirplaneMode && radio in heldDown
        put(radio.fieldName, if (on == null || suppressed) JsonNull else JsonPrimitive(on))
      }
      put("airplaneMode", airplaneModeOn?.let { JsonPrimitive(it) } ?: JsonNull)
    }
  }

  private fun describeState(state: JsonObject): String =
    state.entries.joinToString(", ") { (field, value) ->
      "$field=${if (value is JsonNull) "unreadable" else value.toString()}"
    }

  /**
   * Names what already moved. There is no rollback, so a refusal part-way leaves the device
   * part-changed, and a trail debugged from the message alone needs to know which half.
   */
  private fun describeChanged(changed: List<String>): String =
    if (changed.isEmpty()) "nothing was changed" else "already set ${changed.joinToString(", ")}"

  /**
   * Whether airplane mode is on, or null when the device did not answer readably.
   *
   * Read through a mapping rather than `== "1"` for the same reason the radios are. `settings get`
   * prints the literal `null` for a flag that was never written, and a failed read can surface as
   * an error string — a bare equality folds both into "off", so `airplaneMode = false` would
   * report success without proving the device moved, including where the command did nothing at
   * all. Unreadable is refused, not rounded down.
   */
  private fun readAirplaneMode(executor: AndroidDeviceCommandExecutor): Boolean? =
    androidAirplaneModeSettingMeansOn(readSetting(executor, ANDROID_AIRPLANE_MODE_SETTING))

  /**
   * Polls [read] until [reached] accepts what it returned, or the settle ceiling passes, and
   * returns the last value seen either way.
   *
   * Returning the value rather than a verdict is what lets a caller's failure message quote the
   * exact read its check rejected, instead of reading the flag a second time and quoting a value
   * that may have settled in between.
   *
   * The switch is asynchronous — the shell command returns before the flag flips — but only just:
   * every radio settles inside a single poll on the images measured. The ceiling is generous
   * against a slower device rather than tuned, because it is only ever waited out when the switch
   * genuinely failed.
   */
  private suspend fun awaitSetting(read: () -> String, reached: (String) -> Boolean): String {
    val deadline = TimeSource.Monotonic.markNow() + SETTLE_MS.milliseconds
    while (true) {
      val value = read()
      if (reached(value)) return value
      if (deadline.hasPassedNow()) return value
      delay(POLL_MS.milliseconds)
    }
  }

  private fun readSetting(executor: AndroidDeviceCommandExecutor, setting: String): String =
    executor.executeShellCommand(androidSettingReadCommand(setting)).trim()

  private fun onOrOff(on: Boolean): String = if (on) "on" else "off"

  /**
   * The caller asked for something this tool will not do — refused with the device untouched.
   *
   * [TrailblazeToolResult.Error.InvalidToolCall] is the argument-level error, and an LLM reading
   * one is expected to rewrite the step. That is the right instruction here and the wrong one for
   * [deviceFailure], which is why the two are separate.
   */
  private fun invalidRequest(message: String): TrailblazeToolResult =
    TrailblazeToolResult.Error.InvalidToolCall(errorMessage = message, command = this)

  /**
   * The request was fine and the device did not do it.
   *
   * Reported as [TrailblazeToolResult.Error.ExceptionThrown] rather than `InvalidToolCall`
   * because the arguments are not the problem: an LLM told its call was invalid rewrites the step,
   * and rewriting `wifi: false` gets it nowhere against a device that ignored `svc wifi disable`.
   */
  private fun deviceFailure(message: String): TrailblazeToolResult =
    TrailblazeToolResult.Error.ExceptionThrown(errorMessage = message, command = this)

  /**
   * An Android radio this tool can switch, and everything it takes to switch one and read it back.
   *
   * Every radio carries FOUR names, and they genuinely differ: `svc` calls mobile data `data`, the
   * tool calls it `cellular` because `data` reads as "the network" to anyone who has not used
   * `svc`, `settings global` records it as `mobile_data`, and
   * [ANDROID_AIRPLANE_MODE_TOGGLEABLE_RADIOS] lists it as `cell`. Holding them together on one
   * entry is what stops a lookup falling through to a plausible wrong answer — and it makes a
   * radio's identity a type, so there is no "unmapped radio" branch left to write a test for.
   */
  enum class AndroidRadio(
    /** What `svc` calls this radio. */
    val svcName: String,
    /** What this tool's own schema calls it, and the only spelling any message uses. */
    val fieldName: String,
    /**
     * The `settings global` flag this radio flips, which is the only way to tell whether a switch
     * actually happened. Read it through [settingMeansOn] — the values are a four-way state, not a
     * boolean, and not the same four states on every flag.
     */
    val stateSetting: String,
    /**
     * What BOTH airplane-mode lists call it — [ANDROID_AIRPLANE_MODE_RADIOS] and
     * [ANDROID_AIRPLANE_MODE_TOGGLEABLE_RADIOS] share one spelling, which is neither the `svc` name
     * nor the field name.
     */
    val airplaneModeRadioName: String,
    /**
     * Whether [stateSetting] is rewritten when airplane mode moves this radio.
     *
     * When it is, the flag stays honest under airplane mode and can be reported as read. When it
     * is not, the flag keeps describing a radio the platform has already switched off, and this
     * tool reports the radio as unreadable instead of repeating it.
     */
    val stateSettingTracksAirplaneMode: Boolean,
    /**
     * What each value of [stateSetting] means, as an on/off answer, and `null` for every value
     * left out.
     *
     * These flags are NOT booleans, and — the second trap — they do not share one vocabulary. The
     * value `2` means opposite things depending on which flag holds it, so a single shared
     * value-to-state map would report a radio as on precisely when airplane mode had turned it
     * off. That is why the mapping hangs off the radio rather than sitting beside it.
     */
    private val settingMeanings: Map<String, Boolean>,
  ) {
    /**
     * A genuine four-state flag: `0` off, `1` on, `2` on again during airplane mode, `3` off
     * because of airplane mode.
     *
     * The `2`/`3` pair is device-verified on API 36 and both reproduce on demand: `svc wifi
     * disable` during airplane mode leaves the flag such that the next entry reads `3` ("Wifi is
     * disabled"), and `svc wifi enable` during airplane mode leaves it reading `2` ("Wifi is
     * enabled") on that entry and every later one, with `airplane_mode_on` still 1.
     */
    WIFI(
      svcName = "wifi",
      fieldName = "wifi",
      stateSetting = "wifi_on",
      airplaneModeRadioName = "wifi",
      stateSettingTracksAirplaneMode = true,
      settingMeanings = mapOf("0" to false, "1" to true, "2" to true, "3" to false),
    ),

    /**
     * 0/1, and airplane mode does not rewrite it — `mobile_data` is the user's PREFERENCE, not
     * modem power, so it survives airplane mode saying the user would like mobile data on a modem
     * the platform has switched off.
     *
     * This is the SINGLE-SIM key. On a multi-SIM device the per-subscription state is written as
     * `mobile_data<subId>`, so the bare key can lag what `svc data` actually changed. Every device
     * these drivers run on today is single-SIM, and resolving the default data subscription is not
     * worth writing blind, so it stays keyless until there is a multi-SIM device to verify it on.
     */
    CELLULAR(
      svcName = "data",
      fieldName = "cellular",
      stateSetting = "mobile_data",
      airplaneModeRadioName = "cell",
      stateSettingTracksAirplaneMode = false,
      settingMeanings = mapOf("0" to false, "1" to true),
    ),

    /**
     * 0/1 here. The platform also writes `2` to remember that bluetooth was on before airplane
     * mode took it down, which means the radio is OFF — the reverse of wifi's `2`. That is
     * deliberately left unmapped rather than mapped to `false`: this framework has no device
     * evidence for it, and refusing loudly beats a guess that would report a switch that never
     * happened.
     */
    BLUETOOTH(
      svcName = "bluetooth",
      fieldName = "bluetooth",
      stateSetting = "bluetooth_on",
      airplaneModeRadioName = "bluetooth",
      // It IS rewritten — to the `2` above, which is unmapped, so airplane mode already makes this
      // radio report as unreadable without any suppression on top.
      stateSettingTracksAirplaneMode = true,
      settingMeanings = mapOf("0" to false, "1" to true),
    ),
    ;

    /**
     * Whether [settingValue] — the raw text of `settings get global [stateSetting]` — says this
     * radio is on, or `null` when it says nothing this framework can read for this flag.
     *
     * `null` covers the unset flag (`settings get` prints the literal string `null`) and a value
     * this flag has no verified meaning for. Callers refuse loudly on `null` rather than guessing
     * a direction: guessing wrong reports a device as offline when a trail's whole point was to
     * take it there, or as switched when the radio never moved.
     */
    fun settingMeansOn(settingValue: String): Boolean? = settingMeanings[settingValue.trim()]

    /**
     * Every value [settingMeansOn] reads as on (or off), so a failure message can name what it was
     * looking for. Derived from the same map, so a value added there cannot go missing from the
     * message that explains a refusal.
     */
    fun settingValuesMeaning(on: Boolean): List<String> =
      settingMeanings.filterValues { it == on }.keys.sorted()
  }

  companion object {
    /**
     * The Android radios this tool can switch, in the order it switches them, as their `svc` names.
     *
     * One CI script outside this file encodes the same set and does NOT read it, so a change here
     * has to be made there too: the script that re-enables the radios out of band between builds.
     * These settings persist across the app, the run and a reboot, so a radio this list can
     * disable and that script does not restore strands the device for every later build.
     *
     * `AndroidRadioShellCommandsStayInOneSourceTest` fails on a new hardcoded `svc` radio literal
     * in Kotlin, which is the drift this list exists to prevent. It cannot see that script.
     */
    val ANDROID_RADIOS: List<String> = AndroidRadio.entries.map { it.svcName }

    /** The `svc` command that turns [radio] on or off. */
    fun androidRadioShellCommand(radio: AndroidRadio, on: Boolean): String =
      "svc ${radio.svcName} ${if (on) "enable" else "disable"}"

    /**
     * Every radio at once, as the radios-off stand-in for Maestro's `setAirplaneMode`.
     *
     * This is NOT what this tool does. `networkConnection` sets the real airplane-mode flag with
     * [androidAirplaneModeShellCommand] and each radio separately. This exists for the Maestro
     * `Driver.setAirplaneMode` override the Android drivers implement, which is reached only by a
     * raw `mobile_maestro: setAirplaneMode` command — a surface with one knob and no per-radio
     * vocabulary, so the radios are the closest thing it can mean.
     *
     * Named for the surface rather than for airplane mode because it does not set airplane mode,
     * and it sits one letter away from [androidAirplaneModeShellCommand], which does.
     */
    fun androidMaestroAirplaneModeRadioCommands(airplaneModeEnabled: Boolean): Map<String, String> =
      AndroidRadio.entries.associate { radio ->
        radio.svcName to androidRadioShellCommand(radio, on = !airplaneModeEnabled)
      }

    /**
     * The command that sets real airplane mode.
     *
     * `cmd connectivity airplane-mode` needs API 28+ and works from the shell uid every Android
     * driver already has — including `UiAutomation.executeShellCommand` in an instrumentation test,
     * verified on device. The older fallback (`settings put` plus an ACTION_AIRPLANE_MODE
     * broadcast) needs a permission the shell no longer holds on modern Android, so there is no
     * second path to fall back to and none is pretended.
     *
     * The CI script that clears a stranded airplane mode between builds writes this same command
     * and cannot read it from here; `AndroidRadioShellCommandsStayInOneSourceTest` guards the
     * Kotlin side only.
     */
    fun androidAirplaneModeShellCommand(enabled: Boolean): String =
      "cmd connectivity airplane-mode ${if (enabled) "enable" else "disable"}"

    /**
     * The `settings global` flag holding real airplane mode. Read it through
     * [androidAirplaneModeSettingMeansOn] — an unwritten flag prints the literal `null`, which is
     * not the same answer as "off".
     */
    const val ANDROID_AIRPLANE_MODE_SETTING: String = "airplane_mode_on"

    /**
     * The platform's list of radios that are ALLOWED to be on while airplane mode is on,
     * comma-separated. Defaults to `bluetooth,wifi`, so the modem normally is not on it.
     *
     * This is not a list of radios airplane mode leaves alone — whether a listed radio survives
     * entry depends on a remembered preference (see this class's KDoc). What the list decides
     * reliably is whether switching one back on AFTERWARDS sticks, which is why this tool applies
     * airplane mode first and the radios second. Entries are spelled as
     * [AndroidRadio.airplaneModeRadioName], which is not the `svc` spelling.
     */
    const val ANDROID_AIRPLANE_MODE_TOGGLEABLE_RADIOS: String = "airplane_mode_toggleable_radios"

    /**
     * The platform's list of radios airplane mode touches AT ALL, comma-separated. Defaults to
     * `cell,bluetooth,wifi,nfc,wimax` and is device-configurable, so a radio can be left out and
     * then airplane mode simply does not apply to it.
     *
     * This is the list [ANDROID_AIRPLANE_MODE_TOGGLEABLE_RADIOS] is a subset OF, and the two answer
     * different questions. Reading only the second treats "airplane mode never touches this radio"
     * as "airplane mode takes this radio down for good", which refuses a request the device would
     * have honoured. Spelled with [AndroidRadio.airplaneModeRadioName], same as the other list.
     */
    const val ANDROID_AIRPLANE_MODE_RADIOS: String = "airplane_mode_radios"

    /**
     * The radios that, on a device configured like this, airplane mode takes down and will not let
     * back up — the ones a request for `airplaneMode = true` plus that radio ON cannot have.
     *
     * Pure, and takes the two raw setting values rather than a device, because the interesting part
     * is the set arithmetic between two lists that are easy to conflate: affected MINUS toggleable.
     *
     * A list the device did not answer readably is resolved in the refusing direction, separately
     * for each: an unreadable affected list is treated as "airplane mode touches everything" and an
     * unreadable toggleable list as "it lets nothing back on". Both are the conservative reading —
     * the alternative silently drops the guard on exactly the device whose configuration could not
     * be established, and this returns a refusal, not a device change.
     *
     * "Unreadable" and "empty" are NOT the same answer, which is what makes that safe. A list this
     * tool can parse and that simply names no radio it knows — `nfc,wimax`, or genuinely nothing —
     * is a real answer and is honoured, so a device airplane mode does not apply to gets its
     * request run. See [androidRadioListOrNull] for where the line is drawn.
     */
    fun androidRadiosAirplaneModeHoldsDown(
      affectedRadiosSetting: String,
      toggleableRadiosSetting: String,
    ): Set<AndroidRadio> {
      val affected = androidRadioListOrNull(affectedRadiosSetting) ?: AndroidRadio.entries.toSet()
      val toggleable = androidRadioListOrNull(toggleableRadiosSetting) ?: emptySet()
      return affected - toggleable
    }

    /**
     * The radios named in one of the airplane-mode lists, or null when this is not a radio list at
     * all.
     *
     * The distinction is the whole job, because "no radios" and "no answer" pull the guard in
     * opposite directions and both arrive as text off a shell. An unwritten setting prints the
     * literal `null`; a rejected read prints whatever the shell had to say. Deciding by "named no
     * radio I recognise" cannot tell `Error: unknown` from `nfc,wimax` and reads BOTH as an empty
     * list, which drops the guard on exactly the read that failed.
     *
     * So it is decided on shape instead: a radio list is comma-separated lowercase identifiers, and
     * anything holding a space, punctuation or capital is not one. Empty is then free to mean what
     * it says — no radios — rather than being lumped in with the failures.
     */
    private fun androidRadioListOrNull(settingValue: String): Set<AndroidRadio>? {
      val raw = settingValue.trim()
      if (raw == "null") return null
      val names = raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }
      if (names.any { !ANDROID_RADIO_LIST_ENTRY.matches(it) }) return null
      return AndroidRadio.entries.filter { it.airplaneModeRadioName in names }.toSet()
    }

    /**
     * What one entry of an airplane-mode radio list looks like. Every real value is a lowercase
     * identifier (`cell`, `bluetooth`, `wifi`, `nfc`, `wimax`), and the lookup against
     * [AndroidRadio.airplaneModeRadioName] is case-sensitive anyway — so a value outside this shape
     * could never have matched, and reading it as "unreadable" rather than "matched nothing" is
     * strictly the better of the two answers available.
     */
    private val ANDROID_RADIO_LIST_ENTRY = Regex("[a-z0-9_]+")

    /** The system property holding the device's API level. */
    const val ANDROID_SDK_INT_PROP: String = "ro.build.version.sdk"

    /**
     * The API level `cmd connectivity airplane-mode` arrived in. Below it the command is not there
     * and nothing this tool can run sets real airplane mode.
     */
    const val ANDROID_MIN_SDK_FOR_AIRPLANE_MODE: Int = 28

    /**
     * Whether the API level in [ANDROID_SDK_INT_PROP]'s raw value can set real airplane mode, or
     * null when the value says nothing readable.
     *
     * Null is its own answer for the same reason the flag reads are: a `getprop` that came back
     * empty or garbled is not evidence of an old device, and rounding it to "too old" would refuse
     * an airplane-mode step on a device that would have run it.
     */
    fun androidSupportsRealAirplaneMode(sdkIntValue: String): Boolean? =
      sdkIntValue.trim().toIntOrNull()?.let { it >= ANDROID_MIN_SDK_FOR_AIRPLANE_MODE }

    /**
     * Whether `airplane_mode_on`'s raw value says airplane mode is on, or null when it says
     * nothing this framework can read. Mirrors [AndroidRadio.settingMeansOn]: unreadable is
     * refused rather than rounded to "off".
     */
    fun androidAirplaneModeSettingMeansOn(settingValue: String): Boolean? =
      when (settingValue.trim()) {
        "0" -> false
        "1" -> true
        else -> null
      }

    /**
     * The command that reads a `settings global` flag. The one composition of that read, so a
     * driver reading [ANDROID_AIRPLANE_MODE_SETTING] and this tool verifying its own write cannot
     * end up asking the device two different questions.
     */
    fun androidSettingReadCommand(setting: String): String = "settings get global $setting"

    /**
     * What a driver's `Boolean` airplane-mode read answers for a raw flag value — the same mapping
     * as [androidAirplaneModeSettingMeansOn], with unreadable rounded DOWN to off.
     *
     * Separate from that function, and deliberately not a default on it, because the two callers
     * want opposite things from an unreadable flag and both are right. This tool refuses, because
     * it is verifying a write it just made and "can't tell" is not evidence the device moved.
     * `maestro.Driver.isAirplaneModeEnabled(): Boolean` cannot refuse — it has no third answer to
     * return — so it needs a direction, and `false` is the honest one: `"1"` is the only value that
     * says airplane mode is on and the platform writes it whenever airplane mode goes on, so an
     * unreadable value is never evidence FOR airplane mode. It is also what the common source of
     * one actually means, a flag never written on a device whose airplane mode has never been on.
     *
     * Pure so the direction is pinned by a JVM test rather than only by a device.
     */
    fun androidAirplaneModeSettingMeansOnOrOff(settingValue: String): Boolean =
      androidAirplaneModeSettingMeansOn(settingValue) ?: false

    private const val AIRPLANE_MODE_RACE_HINT =
      "Airplane mode broadcasts to the radios after its own flag is set, so asking for airplane " +
        "mode and a radio in the same step can race; split them into two steps if this recurs."

    private const val SETTLE_MS = 5_000L
    private const val POLL_MS = 100L
  }
}
