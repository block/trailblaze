package xyz.block.trailblaze.ios.tools

import ai.koog.agents.core.tools.annotations.LLMDescription
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.logs.client.TrailblazeJsonInstance
import xyz.block.trailblaze.toolcalls.ExecutableTrailblazeTool
import xyz.block.trailblaze.toolcalls.SensitiveArgsTrailblazeTool
import xyz.block.trailblaze.toolcalls.SensitiveValuesTrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolClass
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.toolcalls.redactSensitiveValuesIn
import xyz.block.trailblaze.util.isMacOs

/**
 * The one way tools and CLI callers control an iOS simulator: the counterpart of `android_adbShell`.
 * [argv] is plain `simctl` arguments, but only an allowlist of subcommands runs, each checked by
 * [IosSimctlArgv] before anything starts. `simctl spawn <program>` runs anything as the Mac user with
 * host file access, so `spawn` runs only `defaults`, `notifyutil` and `log show` (deprecated in
 * favor of `ctx.events`; kept until its remaining callers move off it).
 *
 * The device is always the session's simulator. Every call is killed after [timeoutSeconds] (10s by
 * default, 30s for `log show`). The result is how simctl exited: a non-zero exit is a successful
 * tool call carrying that exit code, and only a refused argv or a failed setup step is a tool error.
 *
 * The single-purpose `ios_terminate`, `ios_resetKeychain`, `ios_openUrl`, `ios_openApp` and
 * `ios_setPermissions` stay for now; `ios_openApp` also waits for the screen to be ready.
 */
@Serializable
@TrailblazeToolClass(
  name = "ios_simctl",
  surfaceToLlm = false,
  isRecordable = false,
  resultType = IosSimctlResult::class,
)
@LLMDescription(
  "Runs `xcrun simctl` on the session's iOS simulator, from an allowlist: terminate, launch " +
    "(only --terminate-running-process; app arguments allowed), openurl, keychain <device> reset, " +
    "location (list, clear, set, run, start), privacy, get_app_container, ui (appearance, content_size, " +
    "increase_contrast), status_bar (list, clear, override), push <device> <bundle id> and pbcopy " +
    "<device> (input in stdin), pbpaste, listapps, appinfo, and spawn <device> with defaults (read, " +
    "read-type, write, delete with a key), notifyutil -p/-s or log show (deprecated: " +
    "read app signals with ctx.events instead; needs --predicate and --last of at most 600s). `booted` or an omitted device means this session's simulator; other UDIDs are " +
    "refused. A defaults domain that is a bundle id is rewritten to that app's container plist " +
    "(Library/Preferences/<bundle id>), the only way to reach a sandboxed app's preferences; write an " +
    "app's preferences only while it is terminated (terminate, write, launch), since a running app's " +
    "cfprefsd can overwrite the write. An absolute path domain must be inside this simulator's app " +
    "containers; -g is the global domain. Returns exitCode, stdout, stderr and timedOut: a non-zero " +
    "exit is not a tool error. Simulators only.",
)
data class IosSimctlTrailblazeTool(
  @param:LLMDescription(
    "simctl arguments, without `xcrun simctl`, e.g. [\"terminate\", \"booted\", \"com.example.app\"] or " +
      "[\"spawn\", \"booted\", \"defaults\", \"write\", \"com.example.app\", \"Flag\", \"-bool\", \"YES\"].",
  )
  val argv: List<String>,
  @param:LLMDescription(
    "Not supported yet: must be empty. A launch environment can load code into the app, so any " +
      "value is refused until an allowlist is added for a real caller.",
  )
  val launchEnvironment: List<IosSimctlEnvironmentVariable> = emptyList(),
  @param:LLMDescription("Kill the command after this many seconds. Defaults to 10 (30 for log show); at most 300.")
  val timeoutSeconds: Int? = null,
  @param:LLMDescription(
    "Input for push (the JSON payload, at most 4096 bytes, with an \"aps\" key) or pbcopy (the text). " +
      "Never logged. Refused for any other subcommand.",
  )
  val stdin: String? = null,
) : ExecutableTrailblazeTool, SensitiveArgsTrailblazeTool, SensitiveValuesTrailblazeTool {

  override val sensitiveArgNames: Set<String> get() = setOf("launchEnvironment", "stdin")

  // Every value, however short: launchEnvironment is refused, so masking only touches a failed call's log.
  // stdin may be pasteboard text a trail treats as secret.
  override val sensitiveValues: Collection<String>
    get() = (launchEnvironment.map { it.value } + listOfNotNull(stdin)).filter { it.isNotEmpty() }

  /** The data-class default would print [stdin]. */
  override fun toString(): String =
    "IosSimctlTrailblazeTool(argv=$argv, launchEnvironment=$launchEnvironment, timeoutSeconds=$timeoutSeconds, " +
      "stdin=${if (stdin == null) "null" else "***"})"

  override suspend fun execute(toolExecutionContext: TrailblazeToolExecutionContext): TrailblazeToolResult {
    val deviceInfo = toolExecutionContext.trailblazeDeviceInfo
    if (deviceInfo.platform != TrailblazeDevicePlatform.IOS) {
      return failure("ios_simctl is only supported on iOS simulators (got platform: ${deviceInfo.platform}). On Android, use android_adbShell.")
    }
    if (!isMacOs()) return failure("ios_simctl needs a macOS host with Xcode.")
    return execute(IosSimctl(udid = deviceInfo.trailblazeDeviceId.instanceId))
  }

  /** Runs [argv] on [simctl]; the seam the unit tests use. */
  internal suspend fun execute(simctl: IosSimctl): TrailblazeToolResult = try {
    val names = launchEnvironment.map { it.name }
    require(names.size == names.toSet().size) { "launchEnvironment names a variable twice." }
    // From the parsed command, so `log show` as a launch app argument doesn't get the log default.
    val isLogShow = IosSimctlArgv.parse(argv, simctl.udid, launchEnvironment.associate { it.name to it.value }, stdin) is
      IosSimctlArgv.Command.LogShow
    val timeout = (timeoutSeconds ?: if (isLogShow) LOG_TIMEOUT_SECONDS else DEFAULT_TIMEOUT_SECONDS).toLong()
    require(timeout in 1..MAX_TIMEOUT_SECONDS) { "timeoutSeconds must be 1..$MAX_TIMEOUT_SECONDS (got $timeout)." }
    val result = simctl.run(argv, launchEnvironment.associate { it.name to it.value }, timeout, stdin)
    val sub = argv.firstOrNull { it != "xcrun" && it != "simctl" }.orEmpty()
    val message = when {
      result.timedOut -> "ios_simctl $sub timed out after ${timeout}s."
      result.exitCode != 0 -> "ios_simctl $sub exited ${result.exitCode}: ${result.stderr.trim().ifEmpty { result.stdout.trim() }}"
      else -> result.stdout.trimEnd('\n').ifEmpty { "ios_simctl $sub done." }
    }
    TrailblazeToolResult.Success(
      message = redactSensitiveValuesIn(message, sensitiveValues),
      structuredContent = TrailblazeJsonInstance.encodeToJsonElement(IosSimctlResult.serializer(), result),
    )
  } catch (e: CancellationException) {
    throw e
  } catch (e: IllegalArgumentException) {
    failure(redactSensitiveValuesIn("ios_simctl: ${e.message}", sensitiveValues))
  } catch (e: Exception) {
    failure(redactSensitiveValuesIn("ios_simctl failed: ${e.message}", sensitiveValues))
  }

  private fun failure(message: String) = TrailblazeToolResult.Error.ExceptionThrown(errorMessage = message, command = this)

  internal companion object {
    const val DEFAULT_TIMEOUT_SECONDS = 10
    const val LOG_TIMEOUT_SECONDS = 30
    const val MAX_TIMEOUT_SECONDS = 300L
  }
}

/** One `launch` environment variable; the app sees [name]=[value]. */
@Serializable
data class IosSimctlEnvironmentVariable(
  @param:LLMDescription("Variable name, e.g. `MY_FLAG`.")
  val name: String,
  @param:LLMDescription("Its value. Never logged.")
  val value: String,
) {
  /** The data-class default would print [value], e.g. when a failed call's result logs its command. */
  override fun toString(): String = "IosSimctlEnvironmentVariable(name=$name, value=***)"
}

/**
 * How an `ios_simctl` call exited. A non-zero [exitCode] is not a tool error; callers that need one
 * use the TypeScript SDK's `simctlOrThrow`.
 */
@Serializable
data class IosSimctlResult(
  /** simctl's exit code; -1 when it was killed for [timedOut]. */
  val exitCode: Int,
  /** `<bundle id>: <pid>` for launch, the path for get_app_container, the value for defaults read, the lines for log show. */
  val stdout: String,
  val stderr: String,
  /** True when the command was killed for running past its timeout. */
  val timedOut: Boolean,
)

/**
 * Accepts an enum by its name in any case, with or without underscores (`AFTER_FIRST_UNLOCK`,
 * `afterFirstUnlock`), so a CLI caller can write either; serializes the canonical name.
 */
abstract class LenientEnumSerializer<T : Enum<T>>(serialName: String, private val values: List<T>) : KSerializer<T> {
  override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor(serialName, PrimitiveKind.STRING)

  override fun serialize(encoder: Encoder, value: T) = encoder.encodeString(value.name)

  override fun deserialize(decoder: Decoder): T {
    val raw = decoder.decodeString()
    val normalized = raw.trim().replace("_", "").uppercase()
    return values.firstOrNull { it.name.replace("_", "") == normalized }
      ?: throw SerializationException("Unknown value '$raw'. Expected one of: ${values.joinToString { it.name }}")
  }
}
