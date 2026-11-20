package xyz.block.trailblaze.ios.tools

import ai.koog.agents.core.tools.annotations.LLMDescription
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
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
 * The iOS simulator keychain primitive: sets, gets, deletes and lists passwords in any installed
 * app's keychain group, always pinned to that group. simctl has no such command, so this is the one
 * thing `ios_simctl` can't do; see [IosSimctl.keychain] for the signed helper behind it.
 *
 * No value is ever returned, logged or put in an error. `get` stores the value under [memoryKey] as
 * sensitive memory, as `rememberSecretValue` does, so later steps use `{{memoryKey}}` and logs show
 * only that it was found.
 */
@Serializable
@TrailblazeToolClass(
  name = "ios_keychain",
  surfaceToLlm = false,
  isRecordable = false,
  resultType = IosKeychainResult::class,
)
@LLMDescription(
  "Sets, gets, deletes or lists passwords in an installed app's keychain group on the session's iOS " +
    "simulator. set stores a generic password (service + account), updating any existing one, and reads " +
    "it back; get reads one into the sensitive memory key memoryKey and never returns it; delete needs " +
    "a service and optionally an account; list names the generic and internet passwords in the group, " +
    "filtered by service and account when given. Returns action, found, memoryKey (get) and items " +
    "(list): names only, never a value. Values are never logged. Simulators only.",
)
data class IosKeychainTrailblazeTool(
  @param:LLMDescription("set, get, delete or list.")
  val action: IosKeychainAction,
  @param:LLMDescription("Bundle id of the installed app whose keychain group to use.")
  val appId: String,
  @param:LLMDescription("kSecAttrService. Required for set, get and delete; a filter for list.")
  val service: String? = null,
  @param:LLMDescription("kSecAttrAccount. Required for set and get; a filter for delete and list.")
  val account: String? = null,
  @param:LLMDescription("set only: the password to store. Never logged.")
  val value: String? = null,
  @param:LLMDescription("get only: the memory key to store the value in, marked sensitive. Use it later as {{key}}.")
  val memoryKey: String? = null,
  @param:LLMDescription("set only: kSecAttrAccessible. Defaults to WHEN_UNLOCKED.")
  val accessible: IosKeychainAccessibility = IosKeychainAccessibility.WHEN_UNLOCKED,
  @param:LLMDescription("Keychain access group. Defaults to the app's application-identifier entitlement.")
  val accessGroup: String? = null,
  @param:LLMDescription("Kill each step after this many seconds. Defaults to 10; at most 300. The first helper build per host gets 180s.")
  val timeoutSeconds: Int? = null,
) : ExecutableTrailblazeTool, SensitiveArgsTrailblazeTool, SensitiveValuesTrailblazeTool {

  override val sensitiveArgNames: Set<String> get() = setOf("value")

  override val sensitiveValues: Collection<String> get() = listOfNotNull(value?.takeIf { it.isNotEmpty() })

  /** The data-class default would print [value]. */
  override fun toString(): String =
    "IosKeychainTrailblazeTool(action=$action, appId=$appId, service=$service, account=$account, " +
      "value=${if (value == null) "null" else "***"}, memoryKey=$memoryKey, accessible=$accessible, " +
      "accessGroup=$accessGroup, timeoutSeconds=$timeoutSeconds)"

  override suspend fun execute(toolExecutionContext: TrailblazeToolExecutionContext): TrailblazeToolResult {
    val deviceInfo = toolExecutionContext.trailblazeDeviceInfo
    if (deviceInfo.platform != TrailblazeDevicePlatform.IOS) {
      return failure("ios_keychain is only supported on iOS simulators (got platform: ${deviceInfo.platform}).")
    }
    if (!isMacOs()) return failure("ios_keychain needs a macOS host with Xcode.")
    return execute(IosSimctl(udid = deviceInfo.trailblazeDeviceId.instanceId)) { key, secret ->
      toolExecutionContext.memory.rememberSensitive(key, secret)
    }
  }

  /** The seam the unit tests use; [rememberSensitive] stores `get`'s value. */
  internal suspend fun execute(
    simctl: IosSimctl,
    rememberSensitive: (key: String, value: String) -> Unit,
  ): TrailblazeToolResult = try {
    val timeout = (timeoutSeconds ?: IosSimctlTrailblazeTool.DEFAULT_TIMEOUT_SECONDS).toLong()
    require(timeout in 1..IosSimctlTrailblazeTool.MAX_TIMEOUT_SECONDS) {
      "timeoutSeconds must be 1..${IosSimctlTrailblazeTool.MAX_TIMEOUT_SECONDS} (got $timeout)."
    }
    if (action == IosKeychainAction.GET) {
      require(memoryKey != null && MEMORY_KEY.matches(memoryKey)) {
        "keychain get needs memoryKey, the memory key to store the value in (${MEMORY_KEY.pattern})."
      }
    } else {
      require(memoryKey == null) { "memoryKey is only for keychain get." }
    }
    val outcome = simctl.keychain(action, appId, service, account, value, accessible, accessGroup, timeout)
    val stored = outcome.value?.takeIf { action == IosKeychainAction.GET && outcome.found }
    if (stored != null) rememberSensitive(memoryKey!!, stored)
    val result = IosKeychainResult(
      action = action,
      found = outcome.found,
      memoryKey = memoryKey.takeIf { stored != null },
      items = outcome.items.takeIf { action == IosKeychainAction.LIST },
    )
    val where = "in $appId's group"
    val message = when (action) {
      IosKeychainAction.SET -> "Stored $service / $account $where."
      IosKeychainAction.GET ->
        if (stored != null) "Found $service / $account $where; its value is in sensitive memory as {{$memoryKey}}."
        else "No $service / $account $where."
      IosKeychainAction.DELETE -> if (outcome.found) "Deleted ${outcome.items.size} item(s) $where." else "Nothing to delete $where."
      IosKeychainAction.LIST ->
        "Found ${outcome.items.size} item(s) $where" +
          outcome.items.joinToString("") { "\n- ${it.itemClass}: ${it.service} / ${it.account}" }
    }
    TrailblazeToolResult.Success(
      message = message,
      structuredContent = TrailblazeJsonInstance.encodeToJsonElement(IosKeychainResult.serializer(), result),
    )
  } catch (e: CancellationException) {
    throw e
  } catch (e: Exception) {
    failure(redactSensitiveValuesIn("ios_keychain ${action.name.lowercase()} failed: ${e.message}", sensitiveValues))
  }

  private fun failure(message: String) = TrailblazeToolResult.Error.ExceptionThrown(errorMessage = message, command = this)

  companion object {
    private val MEMORY_KEY = Regex("^[A-Za-z_][A-Za-z0-9_.-]{0,127}$")
  }
}
