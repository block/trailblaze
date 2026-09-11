package xyz.block.trailblaze.android.test.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import androidx.test.platform.app.InstrumentationRegistry
import maestro.ScrollDirection
import xyz.block.trailblaze.android.test.AndroidTestTarget
import xyz.block.trailblaze.android.test.AppUnderTestLauncher
import xyz.block.trailblaze.api.DriverNodeMatch
import xyz.block.trailblaze.api.TrailblazeNodeSelector
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.toolcalls.commands.MaestroTrailblazeTool

/**
 * One entry of a `mobile_maestro` commands list: the Maestro command name and its raw argument
 * node (`JsonObject` for `tapOn: {text: …}`, `JsonPrimitive` for the shorthand `tapOn: "…"`,
 * empty object for a bare `- back`).
 *
 * Scalar leaves inside [args] are ALWAYS string [JsonPrimitive]s regardless of how the author
 * spelled them — the round-trip through [MaestroTrailblazeTool]'s serializer deliberately never
 * coerces numbers or booleans (see `coerceNumbers = false` there) — so consumers parse
 * `timeout`/`optional` from the primitive's content, never from its JSON type.
 */
data class ParsedMaestroCommand(val name: String, val args: JsonElement)

/**
 * Structural read of a [MaestroTrailblazeTool]'s YAML payload, used by the driver's own
 * interpreter below.
 *
 * Reuses the tool's own serializer as the parser — `serialize()` already normalizes the YAML text
 * into a `commands:` list of single-key JSON maps (bare scalars like `- back` become
 * `{"back": {}}`) — so this cannot drift from what the tool actually holds.
 */
object MaestroCommandYaml {
  private val json = Json

  /**
   * @throws IllegalArgumentException when the payload is a well-formed document but not a Maestro
   * commands list. Unreadable YAML raises the underlying reader's own exception type instead, so
   * callers guard against [Exception] rather than this one alone.
   */
  fun parse(tool: MaestroTrailblazeTool): List<ParsedMaestroCommand> {
    val encoded = json.encodeToJsonElement(MaestroTrailblazeTool.serializer(), tool).jsonObject
    val commands = encoded["commands"]?.jsonArray ?: return emptyList()
    return commands.map { item ->
      val obj = item.jsonObject
      require(obj.size == 1) {
        "Expected each Maestro command to be a single-key map, got keys ${obj.keys}"
      }
      val (name, args) = obj.entries.first()
      ParsedMaestroCommand(name, args)
    }
  }

  /** [parse], or null when the payload is malformed — for claim decisions that must not throw. */
  fun parseOrNull(tool: MaestroTrailblazeTool): List<ParsedMaestroCommand>? =
    runCatching { parse(tool) }.getOrNull()
}

/**
 * Interprets a recorded `mobile_maestro` tool onto this driver's own backends, command by command.
 *
 * The Square estate's scripted launch tools (and the recorded trails around them) fall back to raw
 * Maestro commands on every driver that is not the accessibility one — but the command set they
 * emit is small and closed: visibility waits, text taps, typing, and animation settles, all of
 * which this driver expresses natively through the same selector resolver every `androidTest_*`
 * tool uses.
 * Interpreting that vocabulary here is what lets a trail recorded against the Maestro-era drivers
 * replay in-process without the trail — or the scripted tools it calls — changing.
 *
 * Selector semantics ride the resolver's Maestro estate bridge ([DriverNodeMatch.AndroidMaestro]):
 * a Maestro `text` is an anchored, case-insensitive regex there, exactly as Maestro itself matches
 * it, so this interpreter never re-implements matching.
 *
 * A command OUTSIDE the vocabulary fails loudly naming the command, same policy as
 * [CanonicalToolAdapters]: degrading a recorded behavior is worse than refusing it. `launchApp` of
 * the app under test is in the vocabulary — it relaunches the launcher entry point through
 * [AppUnderTestLauncher] — but launching any OTHER package refuses, as does any launch option
 * (clearState and friends) whose real meaning would kill the instrumented process.
 *
 * Maestro's `optional` is honored on every command: a step the screen refuses is skipped and the
 * flow carries on, which is what Orchestra does with it. A step this interpreter refuses is NOT
 * skipped — see [run].
 */
internal object MaestroCommandAdapters {

  suspend fun run(
    tool: MaestroTrailblazeTool,
    target: AndroidTestTarget,
    context: TrailblazeToolExecutionContext,
  ): TrailblazeToolResult {
    val commands = try {
      MaestroCommandYaml.parse(tool)
    } catch (e: Exception) {
      // Whatever the parse throws, not only the malformed-shape `IllegalArgumentException`: the
      // YAML reader underneath raises its own type on unreadable text, and letting that escape
      // would trade this message for a stack trace naming a serializer.
      return TrailblazeToolResult.Error.ExceptionThrown(
        errorMessage = "mobile_maestro payload failed to parse: ${e.message}",
        command = tool,
      )
    }
    val messages = mutableListOf<String>()
    for ((index, command) in commands.withIndex()) {
      // A flow may use the same command twice, so the command name alone does not say where a run
      // stopped — which is the one fact a failed CI run needs from this message.
      val step = "step ${index + 1} of ${commands.size}"
      val optional: Boolean
      val result: TrailblazeToolResult
      try {
        // Read inside the same guard as the dispatch: an unreadable `optional` is itself a
        // refusal, and it has to be settled BEFORE the step runs rather than consulted after.
        optional = command.isOptional(tool)
        result = dispatchOne(command, tool, target, context)
      } catch (refusal: RefusedCommand) {
        // Outside the interpreted vocabulary, so nothing was attempted. `optional` says what to do
        // with a step the SCREEN refused; it cannot speak for a step this driver never ran, and
        // swallowing one here would be exactly the silent degradation this interpreter exists to
        // prevent.
        return TrailblazeToolResult.Error.ExceptionThrown(
          errorMessage = "Maestro $step ('${command.name}'): ${refusal.reason}",
          command = refusal.tool,
        )
      }
      when {
        result is TrailblazeToolResult.Success -> result.message?.let { messages.add(it) }
        // Maestro's `optional`: the command ran and the screen said no. Orchestra turns that into
        // a warning and carries on with the rest of the flow, so this interpreter does too.
        result is TrailblazeToolResult.Error && optional -> messages.add(
          "Optional '${command.name}' ($step) did not succeed and was skipped: ${result.errorMessage}",
        )
        // Sequential like a Maestro flow: the first failed command fails the tool.
        else -> return result
      }
    }
    return TrailblazeToolResult.Success(
      message = messages.joinToString(" ").ifBlank { "Ran ${commands.size} Maestro command(s)." },
    )
  }

  private suspend fun dispatchOne(
    command: ParsedMaestroCommand,
    tool: MaestroTrailblazeTool,
    target: AndroidTestTarget,
    context: TrailblazeToolExecutionContext,
  ): TrailblazeToolResult = when (command.name) {
    "tapOn" -> {
      val selector = selectorFrom(command.args, tool = tool)
      AndroidTestTapTool(nodeSelector = selector).executeWithAndroidTest(target, context)
    }

    "assertVisible" -> {
      val timeoutMs = (command.args as? JsonObject)?.get("timeout").longOrRefuse("timeout", tool)
      val selector = selectorFrom(command.args, allowedExtraKeys = setOf("timeout"), tool = tool)
      AndroidTestAssertVisibleTool(nodeSelector = selector, timeoutMs = timeoutMs)
        .executeWithAndroidTest(target, context)
    }

    // The negative of `assertVisible`, and the same shape: recorded trails reach for it to prove a
    // state was LEFT (an add-on removed, a badge cleared), where the positive assert cannot say
    // anything. Its `timeout` is how long the element has to leave, which is what the native
    // not-visible tool already waits out.
    "assertNotVisible" -> {
      val timeoutMs = (command.args as? JsonObject)?.get("timeout").longOrRefuse("timeout", tool)
      val selector = selectorFrom(command.args, allowedExtraKeys = setOf("timeout"), tool = tool)
      AndroidTestAssertNotVisibleTool(nodeSelector = selector, timeoutMs = timeoutMs)
        .executeWithAndroidTest(target, context)
    }

    "extendedWaitUntil" -> {
      val args = command.args as? JsonObject
        ?: unsupported("extendedWaitUntil without a map body", tool)
      val unknownKeys = args.keys - setOf("visible", "notVisible", "timeout") - COSMETIC_KEYS
      if (unknownKeys.isNotEmpty()) {
        unsupported("extendedWaitUntil with $unknownKeys", tool)
      }
      val timeoutMs = args["timeout"].longOrRefuse("timeout", tool)
      val visible = args["visible"]
      val notVisible = args["notVisible"]
      when {
        visible != null && notVisible == null ->
          AndroidTestAssertVisibleTool(
            nodeSelector = selectorFrom(visible, tool = tool),
            timeoutMs = timeoutMs,
          ).executeWithAndroidTest(target, context)
        notVisible != null && visible == null ->
          AndroidTestAssertNotVisibleTool(
            nodeSelector = selectorFrom(notVisible, tool = tool),
            timeoutMs = timeoutMs,
          ).executeWithAndroidTest(target, context)
        else -> unsupported("extendedWaitUntil needs exactly one of visible/notVisible", tool)
      }
    }

    // Maestro's scroll-until-visible, onto the loop the driver's own scroll tool already runs.
    // Every option is honored or refused BY MEANING, never ignored:
    //  - `element` is an ordinary element selector, read through the same estate bridge as `tapOn`.
    //  - `direction`, `visibilityPercentage` and `centerElement` are decided by
    //    [ScrollOptionSupport], the same answer the canonical scroll tool gets.
    //  - `timeout` bounds the loop, alongside the tool's own scroll cap.
    //  - `speed` and `scrollDuration` describe the kinematics of a fling. This driver scrolls the
    //    container programmatically, so there is no fling to slow down and nothing they could
    //    change about where the list ends up — accepted, and nothing acts on them.
    "scrollUntilVisible" -> {
      val args = command.args as? JsonObject
        ?: unsupported("scrollUntilVisible without a map body", tool)
      val unknownKeys = args.keys - setOf(
        "element", "direction", "timeout", "speed", "scrollDuration", "visibilityPercentage",
        "centerElement",
      ) - COSMETIC_KEYS
      if (unknownKeys.isNotEmpty()) {
        unsupported("scrollUntilVisible with $unknownKeys", tool)
      }
      val direction = args["direction"].scrollDirectionOrRefuse(tool)
      ScrollOptionSupport.unsupportedDirection(direction)?.let { unsupported("scrollUntilVisible $it", tool) }
      args["visibilityPercentage"].longOrRefuse("visibilityPercentage", tool)?.let { percentage ->
        ScrollOptionSupport.unsupportedVisibilityPercentage(percentage.toInt())
          ?.let { unsupported("scrollUntilVisible $it", tool) }
      }
      args["centerElement"].booleanOrRefuse("centerElement", tool)?.let { centerElement ->
        ScrollOptionSupport.unsupportedCenterElement(centerElement)
          ?.let { unsupported("scrollUntilVisible $it", tool) }
      }
      val element = args["element"] ?: unsupported("scrollUntilVisible without an element", tool)
      AndroidTestScrollUntilVisibleTool(
        nodeSelector = selectorFrom(element, tool = tool),
        timeoutMs = args["timeout"].longOrRefuse("timeout", tool)
          ?: ScrollOptionSupport.DEFAULT_TIMEOUT_MS,
      ).executeWithAndroidTest(target, context)
    }

    // The in-process equivalent of "animations are done" is the synchronization every native tool
    // already performs: Espresso idle + the Compose clock. The command's own timeout is an upper
    // bound on waiting, not a sleep, so no explicit delay is added on top — it is threaded through
    // as the wait's ceiling so a short bound stops short on a never-idle screen.
    "waitForAnimationToEnd" -> {
      target.waitForIdle(ceilingMs = (command.args as? JsonObject)?.get("timeout").longOrRefuse("timeout", tool))
      TrailblazeToolResult.Success(message = "Waited for idle (waitForAnimationToEnd).")
    }

    // Key-event injection into the focused window — the same semantics the canonical `inputText`
    // arm in [CanonicalToolAdapters] gives, and the shape the estate's 2FA branch emits on
    // non-accessibility drivers. Unlike the canonical tool there is no hide-keyboard half:
    // Maestro's own inputText types and leaves the IME as it stands.
    "inputText" -> {
      val text = (command.args as? JsonPrimitive)?.content
        ?: unsupported("inputText with a non-string body", tool)
      InstrumentationRegistry.getInstrumentation().sendStringSync(text)
      target.waitForIdle()
      TrailblazeToolResult.Success(message = "Typed '$text' (inputText).")
    }

    // "Cold-start to the entry point", which in-process means relaunching the app under test's
    // launcher Activity with CLEAR_TASK. The process (and its DI graph) survives where a real cold
    // start's would not — the same shared-process reality every trail in an in-process lane lives
    // with. The command's options are each honored or refused BY MEANING, never ignored:
    //  - `appId` must be the app under test — launching another package can't happen from inside
    //    this process, and force-stopping this one would kill the test.
    //  - `stopApp` picks the launch's task semantics: the default (true) is the CLEAR_TASK
    //    restart-from-entry-point — this driver cannot force-stop its own process, so that is the
    //    stand-in for "stopped first" — while an explicit `stopApp: false` is a warm resume that
    //    brings the existing task forward without recreating it.
    //  - `clearState: true` refuses — it is `pm clear` semantics; the app's own scripted reset
    //    chain forks on `ctx.device.driverType` in its trailmap and composes an in-process reset
    //    (e.g. a sign-out broadcast) on this driver instead.
    //  - `permissions` are granted for real through the same tolerant `pm grant` path the
    //    dual-mode shell tools use, matching Maestro's own grant-then-launch order. Only
    //    fully-qualified `allow` entries map; Maestro's shorthands (`all`, `notifications`) and
    //    deny/unset (a revoke, which `pm grant` cannot express) refuse loudly.
    "launchApp" -> {
      val args = command.args
      val obj = args as? JsonObject
      val appId = when (args) {
        is JsonPrimitive -> args.content
        is JsonObject -> {
          val unknownKeys =
            args.keys - setOf("appId", "clearState", "stopApp", "permissions") - COSMETIC_KEYS
          if (unknownKeys.isNotEmpty()) {
            unsupported("launchApp with $unknownKeys (an in-process relaunch cannot honor them)", tool)
          }
          args["appId"].stringContent()
        }
        else -> null
      }
      val self = InstrumentationRegistry.getInstrumentation().targetContext.packageName
      if (appId != null && appId != self) {
        refuse(
          "Maestro launchApp targets '$appId', but in-process this driver can only relaunch the " +
            "app under test ('$self').",
          tool,
        )
      }
      if (obj?.get("clearState").booleanOrRefuse("clearState", tool) == true) {
        unsupported(
          "launchApp with clearState: true (`pm clear` would kill the instrumented process; fork " +
            "the scripted tool that authors it on ctx.device.driverType in its trailmap and " +
            "compose an app-specific in-process reset there instead)",
          tool,
        )
      }
      val permissions = obj?.get("permissions") as? JsonObject
      if (!permissions.isNullOrEmpty()) {
        val executor = context.androidDeviceCommandExecutor
          ?: unsupported("launchApp permissions without an AndroidDeviceCommandExecutor", tool)
        for ((permission, state) in permissions) {
          val stateValue = state.stringContent()
          if (stateValue != "allow") {
            unsupported(
              "launchApp permission '$permission: $stateValue' (only \"allow\" maps onto pm grant)",
              tool,
            )
          }
          if (!permission.contains('.')) {
            unsupported(
              "launchApp permission shorthand '$permission' (only fully-qualified permission " +
                "names map onto pm grant)",
              tool,
            )
          }
          executor.grantRuntimePermission(self, permission)
        }
      }
      // Maestro's stopIfRunning defaults true, so only an explicit `stopApp: false` is a warm
      // resume; absent or true is the restart-from-entry-point.
      val clearTask = obj?.get("stopApp").booleanOrRefuse("stopApp", tool) != false
      AppUnderTestLauncher.launchAppUnderTest(clearTask = clearTask)
      TrailblazeToolResult.Success(
        message = (
          if (clearTask) {
            "Relaunched '$self' at its entry point (in-process stand-in for Maestro launchApp; " +
              "the process is not restarted)."
          } else {
            "Brought '$self' to the foreground without clearing its task (launchApp stopApp: false)."
          }
          ) +
          if (permissions.isNullOrEmpty()) "" else " Granted ${permissions.size} permission(s) first.",
      )
    }

    else -> unsupported("Maestro command '${command.name}'", tool)
  }

  /**
   * Whether Maestro's `optional` is set on this command — on its own map, or on the element
   * selector nested inside it.
   *
   * Orchestra reads both halves (`command.optional` and `elementSelector()?.optional`) before
   * deciding whether a failure is fatal, so a recording that marks either one means the same thing
   * on this driver as on a Maestro-backed one.
   */
  private fun ParsedMaestroCommand.isOptional(tool: MaestroTrailblazeTool): Boolean {
    val obj = args as? JsonObject ?: return false
    if (obj["optional"].booleanOrRefuse("optional", tool) == true) return true
    return NESTED_SELECTOR_KEYS.any {
      (obj[it] as? JsonObject)?.get("optional").booleanOrRefuse("optional", tool) == true
    }
  }

  /**
   * Builds the resolver selector from a Maestro element-selector node: the bare-string shorthand
   * (`tapOn: "Sign in"`) or the map form's `text`/`id`/`index` fields. Any OTHER selector field
   * (`point`, `containsChild`, …) refuses loudly — matching on fewer constraints than the
   * recording asked for could act on the wrong element.
   *
   * `optional` is always accepted here and never narrows the match: it says what happens when the
   * element is NOT found, which [run] applies to the command as a whole. `label` is accepted for
   * the same reason and is even less: it renames the step in a report and says nothing about which
   * element to match, so refusing a labelled step would refuse the recording over its prose.
   */
  private fun selectorFrom(
    args: JsonElement,
    allowedExtraKeys: Set<String> = emptySet(),
    tool: MaestroTrailblazeTool,
  ): TrailblazeNodeSelector {
    if (args is JsonPrimitive) {
      return TrailblazeNodeSelector.withMatch(DriverNodeMatch.AndroidMaestro(textRegex = args.content))
    }
    val obj = args as? JsonObject ?: unsupported("selector node ${args::class.simpleName}", tool)
    val unknownKeys = obj.keys - SELECTOR_KEYS - COSMETIC_KEYS - allowedExtraKeys
    if (unknownKeys.isNotEmpty()) {
      unsupported("selector field(s) $unknownKeys", tool)
    }
    val text = obj["text"].stringContent()
    val id = obj["id"].stringContent()
    if (text == null && id == null) {
      unsupported("selector with neither text nor id", tool)
    }
    return TrailblazeNodeSelector.withMatch(
      DriverNodeMatch.AndroidMaestro(textRegex = text, resourceIdRegex = id),
      // Both indices are 0-based, so an explicit `index: 0` is a real disambiguator — drop only
      // a negative (malformed) value.
      index = obj["index"].longOrRefuse("index", tool)?.toInt()?.takeIf { it >= 0 },
    )
  }

  /**
   * The scalar's text, or null when the key is absent.
   *
   * `null` in the YAML is a WRITTEN value, not an absent one, and it arrives here as a primitive
   * whose content is the literal "null" — which would otherwise sail past every "was this key
   * given?" check below and, for a selector, match the four-letter string.
   */
  private fun JsonElement?.stringContent(): String? =
    (this as? JsonPrimitive)?.content?.takeUnless { this is JsonNull }

  /**
   * Maestro's scroll direction, defaulting to its own default when the key is absent. A spelling
   * outside the enum refuses rather than reading as the default, which would scroll a recording
   * that asked for something else.
   */
  private fun JsonElement?.scrollDirectionOrRefuse(tool: MaestroTrailblazeTool): ScrollDirection {
    val raw = stringContent() ?: return ScrollOptionSupport.SUPPORTED_DIRECTION
    return ScrollDirection.entries.firstOrNull { it.name == raw.uppercase() }
      ?: unsupported("direction: '$raw' (not a Maestro scroll direction)", tool)
  }

  /**
   * A whole number, or null when [key] is absent.
   *
   * A value that is present but unreadable (`timeout: 20s`) refuses rather than reading as absent:
   * dropping a recorded timeout silently changes what the trail waits for, and so what it passes
   * or fails on.
   */
  private fun JsonElement?.longOrRefuse(key: String, tool: MaestroTrailblazeTool): Long? {
    val raw = stringContent() ?: return null
    return raw.toLongOrNull() ?: unsupported("$key: '$raw' (not a whole number)", tool)
  }

  /**
   * A boolean, or null when [key] is absent.
   *
   * Only Maestro's own `true`/`false` spellings read; anything else present refuses. Silently
   * reading an unrecognised spelling as absent is the worst outcome available here — it would turn
   * `optional` off, or drop a `clearState`, without saying anything.
   */
  private fun JsonElement?.booleanOrRefuse(key: String, tool: MaestroTrailblazeTool): Boolean? {
    val raw = stringContent() ?: return null
    return raw.lowercase().toBooleanStrictOrNull()
      ?: unsupported("$key: '$raw' (expected true or false)", tool)
  }

  /**
   * A command the interpreter declined to run, carrying the reason [run] reports for it.
   *
   * Thrown rather than returned so a refusal cannot be confused with a command that ran and
   * failed — the two get opposite treatment under `optional`, and every arm below produces them
   * from the same nested places.
   */
  private class RefusedCommand(
    val reason: String,
    val tool: MaestroTrailblazeTool,
  ) : Exception(reason)

  /** Refuses this command with [message] verbatim. */
  private fun refuse(message: String, tool: MaestroTrailblazeTool): Nothing =
    throw RefusedCommand(message, tool)

  private fun unsupported(what: String, tool: MaestroTrailblazeTool): Nothing =
    refuse(
      "$what is not supported by the in-process ANDROID_TEST driver's Maestro interpreter. The " +
        "interpreted vocabulary is the set measured across recorded trails (tapOn, assertVisible, " +
        "assertNotVisible, extendedWaitUntil visible/notVisible, scrollUntilVisible, " +
        "waitForAnimationToEnd, inputText, launchApp of the app under test) — failing loudly instead of degrading the " +
        "recorded behavior. Replay this trail on a Maestro-backed driver, or grow the vocabulary " +
        "in MaestroCommandAdapters.",
      tool,
    )

  /** Command keys whose value is itself an element selector, and so can carry `optional`. */
  private val NESTED_SELECTOR_KEYS = setOf("visible", "notVisible", "element")

  /** The element-selector fields this interpreter can match on. */
  private val SELECTOR_KEYS = setOf("text", "id", "index")

  /**
   * Keys Maestro allows on any command that say nothing about what it does.
   *
   * `label` renames the step in a report; `optional` is answered by [run] for the whole command.
   * Neither narrows a match, so both are accepted everywhere rather than refused as unknown —
   * a recording is not unreplayable because its author named a step.
   */
  private val COSMETIC_KEYS = setOf("label", "optional")
}
