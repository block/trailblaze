package xyz.block.trailblaze.ios.tools

import ai.koog.agents.core.tools.annotations.LLMDescription
import com.charleskorn.kaml.YamlInput
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import xyz.block.trailblaze.config.JsonObjectYamlSerializer
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.logs.client.temp.YamlJsonBridge
import xyz.block.trailblaze.toolcalls.ExecutableTrailblazeTool
import xyz.block.trailblaze.toolcalls.REDACTED_TOOL_ARG_PLACEHOLDER
import xyz.block.trailblaze.toolcalls.SensitiveValuesTrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolClass
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.toolcalls.commands.awaitIosScreenReady
import xyz.block.trailblaze.toolcalls.isMemoryReference
import xyz.block.trailblaze.util.IosHostSimctlUtils
import xyz.block.trailblaze.util.TrailblazeDispatchers
import java.util.concurrent.ConcurrentHashMap

/**
 * `xcrun simctl launch <udid> <appId> [arguments…]`: opens an app on an iOS simulator and returns
 * once it is on screen. `openApp` runs this on iOS; call it directly for what only iOS has, launch
 * arguments.
 *
 * [arguments] are passed the way Maestro's `launchApp.arguments` passes them (see
 * [IosLaunchArguments]). They reach only a launch that starts the process, so the tool fails rather
 * than drop them when the app is already running: the caller runs `ios_terminate` first. In trail
 * YAML a `true`/`false` value is always a boolean, quoted or not, because the YAML parser does not
 * keep quoting; other values decode as strings.
 *
 * `simctl launch` has no drawn signal, so the screen is captured before the launch and polled after
 * it until it has changed and then held still, for up to a few seconds (see [awaitIosScreenReady]).
 * That wait is bounded and does not fail the tool.
 */
@Serializable
@TrailblazeToolClass(
  name = "ios_openApp",
  surfaceToLlm = false,
)
@LLMDescription(
  "Opens an iOS simulator app (`simctl launch`), or brings it to the front if running, and waits " +
    "for it to appear. Launch arguments need a cold start: `ios_terminate` first.",
)
data class IosOpenAppTrailblazeTool(
  @param:LLMDescription("The iOS bundle id of the app to open (e.g. `com.example.app`).")
  val appId: String,
  @param:LLMDescription(
    "Launch arguments for an app that is not running, e.g. {\"AppleLanguages\": \"(es-US)\"}.",
  )
  @Serializable(with = LaunchArgumentsSerializer::class)
  val arguments: JsonObject? = null,
) : ExecutableTrailblazeTool, SensitiveValuesTrailblazeTool {

  /**
   * Launch arguments carry credentials (seeded session tokens), so every string value is masked in
   * logs, as Maestro's launch arguments are, including strings inside a value the launch will
   * reject. Numbers and booleans are flags and ports. A value that is only a memory reference
   * (`{{token}}`) names the secret rather than holding it, so it stays readable and recordable.
   */
  override val sensitiveValues: Collection<String>
    get() = buildList {
      fun collect(value: JsonElement) {
        when (value) {
          is JsonObject -> value.values.forEach(::collect)
          is JsonArray -> value.forEach(::collect)
          is JsonPrimitive -> if (value.isSensitive()) add(value.content)
        }
      }
      arguments?.values?.forEach(::collect)
    }

  /**
   * The generated `toString` would print every argument value into whatever logs this tool. Masks
   * the values before rendering them: rendered JSON escapes a quote, backslash or newline, so a
   * secret holding one would no longer match its own text.
   */
  override fun toString(): String = "IosOpenAppTrailblazeTool(appId=$appId, arguments=${arguments?.masked()})"

  private fun JsonPrimitive.isSensitive() = isString && !isMemoryReference(content)

  private fun JsonElement.masked(): JsonElement = when (this) {
    is JsonObject -> JsonObject(mapValues { (_, value) -> value.masked() })
    is JsonArray -> JsonArray(map { it.masked() })
    is JsonPrimitive -> if (isSensitive()) JsonPrimitive(REDACTED_TOOL_ARG_PLACEHOLDER) else this
  }

  override suspend fun execute(
    toolExecutionContext: TrailblazeToolExecutionContext,
  ): TrailblazeToolResult {
    val platform = toolExecutionContext.trailblazeDeviceInfo.platform
    if (platform != TrailblazeDevicePlatform.IOS) {
      return TrailblazeToolResult.Error.ExceptionThrown(
        errorMessage = "ios_openApp is only supported on iOS simulators (got platform: $platform). " +
          "Use openApp elsewhere.",
        command = this,
      )
    }
    return try {
      // simctl round trips and polling sleeps block, so none of it may run on the caller's dispatcher.
      withContext(TrailblazeDispatchers.IO) { open(toolExecutionContext) }
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      TrailblazeToolResult.Error.ExceptionThrown(
        errorMessage = "Failed to open '$appId': ${e.message}",
        command = this,
        stackTrace = e.stackTraceToString(),
      )
    }
  }

  private suspend fun open(context: TrailblazeToolExecutionContext): TrailblazeToolResult {
    val deviceId = context.trailblazeDeviceInfo.trailblazeDeviceId.instanceId
    if (deviceId !in IosHostSimctlUtils.listBootedDeviceIds()) {
      return TrailblazeToolResult.Error.ExceptionThrown(
        errorMessage = "$deviceId is not a booted iOS simulator, so '$appId' cannot be opened " +
          "there. Only simulators are supported on iOS.",
        command = this,
      )
    }
    val launchArguments = arguments ?: JsonObject(emptyMap())
    if (launchArguments.isNotEmpty()) {
      IosLaunchArguments.violation(launchArguments)?.let { violation ->
        return TrailblazeToolResult.Error.ExceptionThrown(errorMessage = violation, command = this)
      }
    }
    val capture = context.screenStateProvider?.let { provider -> { provider().viewHierarchy } }
    val baseline = capture?.invoke()
    // Another launch starting the app between the check and this launch would drop these arguments.
    launchLocks.getOrPut("$deviceId/$appId") { Mutex() }.withLock {
      if (launchArguments.isNotEmpty() && IosHostSimctlUtils.isAppRunning(deviceId = deviceId, appId = appId)) {
        return TrailblazeToolResult.Error.ExceptionThrown(
          errorMessage = "'$appId' is already running, and launch arguments only reach a launch " +
            "that starts it. Terminate it first (ios_terminate).",
          command = this,
        )
      }
      IosHostSimctlUtils.launchApp(
        deviceId = deviceId,
        appId = appId,
        arguments = IosLaunchArguments.argv(launchArguments),
        secrets = sensitiveValues,
      )
    }
    val opened = if (launchArguments.isEmpty()) "Opened $appId" else {
      "Opened $appId with launch arguments ${launchArguments.keys.joinToString()}"
    }
    if (capture == null) {
      return TrailblazeToolResult.Success(
        message = "$opened. This session has no screen capture, so readiness was not checked.",
      )
    }
    val readiness = awaitIosScreenReady(baseline = baseline, capture = capture)
    return TrailblazeToolResult.Success(message = "$opened: ${readiness.description}")
  }
}

/** One running-check-and-launch at a time per simulator and app. */
private val launchLocks = ConcurrentHashMap<String, Mutex>()

/**
 * `ios_openApp`'s launch arguments, turned into `simctl launch` argv exactly as Maestro's iOS driver
 * turns `launchApp.arguments` into it, so a launch moved off Maestro hands the app the same argv.
 */
internal object IosLaunchArguments {

  /** Why [arguments] can't be launch arguments, or null when every value is a scalar. */
  fun violation(arguments: JsonObject): String? {
    val invalid = arguments.filterValues { it !is JsonPrimitive || it is JsonNull }.keys
    if (invalid.isEmpty()) return null
    return "ios_openApp launch arguments must be strings, numbers or booleans; " +
      "${invalid.joinToString()} ${if (invalid.size == 1) "is" else "are"} not."
  }

  /**
   * One flag and one value per entry. A flag is the name with a leading `-`, the form the app's
   * `UserDefaults` reads `-Name value` pairs from, unless it already has one — except for a
   * boolean, whose name Maestro passes bare (`skipPermissionsSetup true`), so this does too.
   * Numbers pass as their decimal text. Two names that land on the same flag (`Name` and `-Name`) keep the later value.
   */
  fun argv(arguments: JsonObject): List<String> {
    val valueByFlag = LinkedHashMap<String, String>()
    for ((name, value) in arguments) {
      val primitive = value as JsonPrimitive
      val isBoolean = !primitive.isString && primitive.booleanOrNull != null
      val flag = if (isBoolean || name.startsWith("-")) name else "-$name"
      valueByFlag[flag] = primitive.content
    }
    return valueByFlag.flatMap { (flag, value) -> listOf(flag, value) }
  }
}

/**
 * [JsonObjectYamlSerializer], except that trail YAML scalars other than `true`/`false` decode as
 * strings: the YAML parser drops quoting, so `"123123"` and `123123` look the same, and decoding a
 * quoted token as a number would take it out of [IosOpenAppTrailblazeTool.sensitiveValues]. A
 * number's flag and value are the same either way.
 */
internal object LaunchArgumentsSerializer : KSerializer<JsonObject> {
  override val descriptor = JsonObjectYamlSerializer.descriptor

  override fun serialize(encoder: Encoder, value: JsonObject) = JsonObjectYamlSerializer.serialize(encoder, value)

  override fun deserialize(decoder: Decoder): JsonObject = if (decoder is YamlInput) {
    YamlJsonBridge.yamlNodeToJsonElement(decoder.node, coerceNumbers = false) as? JsonObject
      ?: error("ios_openApp arguments must be a YAML map")
  } else {
    JsonObjectYamlSerializer.deserialize(decoder)
  }
}
