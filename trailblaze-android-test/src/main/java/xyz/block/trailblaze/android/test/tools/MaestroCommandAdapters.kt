package xyz.block.trailblaze.android.test.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import androidx.test.platform.app.InstrumentationRegistry
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

  /** @throws IllegalArgumentException when the YAML is not a well-formed Maestro commands list. */
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
 */
internal object MaestroCommandAdapters {

  suspend fun run(
    tool: MaestroTrailblazeTool,
    target: AndroidTestTarget,
    context: TrailblazeToolExecutionContext,
  ): TrailblazeToolResult {
    val commands = try {
      MaestroCommandYaml.parse(tool)
    } catch (e: IllegalArgumentException) {
      return TrailblazeToolResult.Error.ExceptionThrown(
        errorMessage = "mobile_maestro payload failed to parse: ${e.message}",
        command = tool,
      )
    }
    val messages = mutableListOf<String>()
    for (command in commands) {
      when (val result = dispatchOne(command, tool, target, context)) {
        is TrailblazeToolResult.Success -> result.message?.let { messages.add(it) }
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
      selectorFrom(command.args, allowedExtraKeys = emptySet(), tool = tool)
        .fold(
          onSelector = { AndroidTestTapTool(nodeSelector = it).executeWithAndroidTest(target, context) },
          onError = { it },
        )
    }

    "assertVisible" -> {
      val optional = (command.args as? JsonObject)?.get("optional").booleanContent() ?: false
      val timeoutMs = (command.args as? JsonObject)?.get("timeout").longContent()
      selectorFrom(command.args, allowedExtraKeys = setOf("optional", "timeout"), tool = tool)
        .fold(
          onSelector = { selector ->
            val result = AndroidTestAssertVisibleTool(nodeSelector = selector, timeoutMs = timeoutMs)
              .executeWithAndroidTest(target, context)
            if (result !is TrailblazeToolResult.Success && optional) {
              TrailblazeToolResult.Success(
                message = "Optional assertVisible did not match (${selector.description()}) — skipped.",
              )
            } else {
              result
            }
          },
          onError = { it },
        )
    }

    "extendedWaitUntil" -> {
      val args = command.args as? JsonObject
        ?: return unsupported("extendedWaitUntil without a map body", tool)
      val unknownKeys = args.keys - setOf("visible", "notVisible", "timeout")
      if (unknownKeys.isNotEmpty()) {
        return unsupported("extendedWaitUntil with $unknownKeys", tool)
      }
      val timeoutMs = args["timeout"].longContent()
      val visible = args["visible"]
      val notVisible = args["notVisible"]
      when {
        visible != null && notVisible == null ->
          selectorFrom(visible, allowedExtraKeys = emptySet(), tool = tool).fold(
            onSelector = {
              AndroidTestAssertVisibleTool(nodeSelector = it, timeoutMs = timeoutMs)
                .executeWithAndroidTest(target, context)
            },
            onError = { it },
          )
        notVisible != null && visible == null ->
          selectorFrom(notVisible, allowedExtraKeys = emptySet(), tool = tool).fold(
            onSelector = {
              AndroidTestAssertNotVisibleTool(nodeSelector = it, timeoutMs = timeoutMs)
                .executeWithAndroidTest(target, context)
            },
            onError = { it },
          )
        else -> unsupported("extendedWaitUntil needs exactly one of visible/notVisible", tool)
      }
    }

    // The in-process equivalent of "animations are done" is the synchronization every native tool
    // already performs: Espresso idle + the Compose clock. The command's own timeout is an upper
    // bound on waiting, not a sleep, so no explicit delay is added on top — it is threaded through
    // as the wait's ceiling so a short bound stops short on a never-idle screen.
    "waitForAnimationToEnd" -> {
      target.waitForIdle(ceilingMs = (command.args as? JsonObject)?.get("timeout").longContent())
      TrailblazeToolResult.Success(message = "Waited for idle (waitForAnimationToEnd).")
    }

    // Key-event injection into the focused window — the same semantics the canonical `inputText`
    // arm in [CanonicalToolAdapters] gives, and the shape the estate's 2FA branch emits on
    // non-accessibility drivers. Unlike the canonical tool there is no hide-keyboard half:
    // Maestro's own inputText types and leaves the IME as it stands.
    "inputText" -> {
      val text = (command.args as? JsonPrimitive)?.content
        ?: return unsupported("inputText with a non-string body", tool)
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
          val unknownKeys = args.keys - setOf("appId", "clearState", "stopApp", "permissions")
          if (unknownKeys.isNotEmpty()) {
            return unsupported("launchApp with $unknownKeys (an in-process relaunch cannot honor them)", tool)
          }
          args["appId"].stringContent()
        }
        else -> null
      }
      val self = InstrumentationRegistry.getInstrumentation().targetContext.packageName
      if (appId != null && appId != self) {
        return TrailblazeToolResult.Error.ExceptionThrown(
          errorMessage = "Maestro launchApp targets '$appId', but in-process this driver can only " +
            "relaunch the app under test ('$self').",
          command = tool,
        )
      }
      if (obj?.get("clearState").booleanContent() == true) {
        return unsupported(
          "launchApp with clearState: true (`pm clear` would kill the instrumented process; fork " +
            "the scripted tool that authors it on ctx.device.driverType in its trailmap and " +
            "compose an app-specific in-process reset there instead)",
          tool,
        )
      }
      val permissions = obj?.get("permissions") as? JsonObject
      if (!permissions.isNullOrEmpty()) {
        val executor = context.androidDeviceCommandExecutor
          ?: return unsupported("launchApp permissions without an AndroidDeviceCommandExecutor", tool)
        for ((permission, state) in permissions) {
          val stateValue = state.stringContent()
          if (stateValue != "allow") {
            return unsupported(
              "launchApp permission '$permission: $stateValue' (only \"allow\" maps onto pm grant)",
              tool,
            )
          }
          if (!permission.contains('.')) {
            return unsupported(
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
      val clearTask = obj?.get("stopApp").booleanContent() != false
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

  /** A parsed selector or the loud error explaining why the shape isn't interpretable. */
  private sealed interface SelectorOrError {
    data class Selector(val selector: TrailblazeNodeSelector) : SelectorOrError
    data class Failure(val error: TrailblazeToolResult) : SelectorOrError
  }

  private suspend fun SelectorOrError.fold(
    onSelector: suspend (TrailblazeNodeSelector) -> TrailblazeToolResult,
    onError: (TrailblazeToolResult) -> TrailblazeToolResult,
  ): TrailblazeToolResult = when (this) {
    is SelectorOrError.Selector -> onSelector(selector)
    is SelectorOrError.Failure -> onError(error)
  }

  /**
   * Builds the resolver selector from a Maestro element-selector node: the bare-string shorthand
   * (`tapOn: "Sign in"`) or the map form's `text`/`id`/`index` fields. Any OTHER selector field
   * (`point`, `containsChild`, …) refuses loudly — matching on fewer constraints than the
   * recording asked for could act on the wrong element.
   */
  private fun selectorFrom(
    args: JsonElement,
    allowedExtraKeys: Set<String>,
    tool: MaestroTrailblazeTool,
  ): SelectorOrError {
    if (args is JsonPrimitive) {
      return SelectorOrError.Selector(
        TrailblazeNodeSelector.withMatch(DriverNodeMatch.AndroidMaestro(textRegex = args.content)),
      )
    }
    val obj = args as? JsonObject
      ?: return SelectorOrError.Failure(unsupported("selector node ${args::class.simpleName}", tool))
    val unknownKeys = obj.keys - setOf("text", "id", "index") - allowedExtraKeys
    if (unknownKeys.isNotEmpty()) {
      return SelectorOrError.Failure(unsupported("selector field(s) $unknownKeys", tool))
    }
    val text = obj["text"].stringContent()
    val id = obj["id"].stringContent()
    if (text == null && id == null) {
      return SelectorOrError.Failure(unsupported("selector with neither text nor id", tool))
    }
    return SelectorOrError.Selector(
      TrailblazeNodeSelector.withMatch(
        DriverNodeMatch.AndroidMaestro(textRegex = text, resourceIdRegex = id),
        // Both indices are 0-based, so an explicit `index: 0` is a real disambiguator — drop only
        // a negative (malformed) value.
        index = obj["index"].longContent()?.toInt()?.takeIf { it >= 0 },
      ),
    )
  }

  private fun JsonElement?.stringContent(): String? = (this as? JsonPrimitive)?.content

  private fun JsonElement?.longContent(): Long? = stringContent()?.toLongOrNull()

  private fun JsonElement?.booleanContent(): Boolean? =
    stringContent()?.lowercase()?.toBooleanStrictOrNull()

  private fun unsupported(what: String, tool: MaestroTrailblazeTool): TrailblazeToolResult =
    TrailblazeToolResult.Error.ExceptionThrown(
      errorMessage = "$what is not supported by the in-process ANDROID_TEST driver's Maestro " +
        "interpreter. The interpreted vocabulary is the estate's measured set (tapOn, " +
        "assertVisible, extendedWaitUntil visible/notVisible, waitForAnimationToEnd, inputText, " +
        "launchApp of the app under test) — failing loudly instead of degrading the recorded " +
        "behavior. Replay this trail on a Maestro-backed driver, or grow the vocabulary in " +
        "MaestroCommandAdapters.",
      command = tool,
    )
}
