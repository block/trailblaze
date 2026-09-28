package xyz.block.trailblaze.ios.tools

import ai.koog.agents.core.tools.annotations.LLMDescription
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.toolcalls.ExecutableTrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolClass
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.util.IosHostSimctlUtils

/**
 * Grants iOS simulator privacy services to an app via `xcrun simctl privacy <udid> grant <service>
 * <appId>`, one call per service, in list order — the iOS counterpart of `android_grantPermissions`.
 *
 * Only what is listed is granted. That is the point of this tool: the Maestro-backed `launchApp`
 * grants every service on every launch (Maestro's `all: allow` default), so a trail cannot tell which
 * permissions it depends on. A trailhead lists them here instead.
 *
 * simctl terminates a running app when a grant changes, so call this before `openApp`. Simulator
 * only; notification permission has no simctl service and is not covered.
 */
@Serializable
@TrailblazeToolClass(
  name = "ios_grantPrivacy",
  surfaceToLlm = false,
)
@LLMDescription(
  "Grants iOS simulator privacy services (`simctl privacy grant`) to an app, so their permission " +
    "dialogs never appear. Call BEFORE `openApp` — simctl terminates a running app when a grant changes.",
)
data class IosGrantPrivacyTrailblazeTool(
  @param:LLMDescription("The iOS bundle id of the target app (e.g. `com.example.app`).")
  val appId: String,
  @param:LLMDescription(
    "The simctl privacy services to grant, e.g. `location`, `photos`, `contacts`, `microphone`, " +
      "`calendar` (`xcrun simctl privacy` lists them). `all` grants every service simctl supports. " +
      "An empty list is a no-op.",
  )
  val services: List<String>,
) : ExecutableTrailblazeTool {
  override suspend fun execute(
    toolExecutionContext: TrailblazeToolExecutionContext,
  ): TrailblazeToolResult {
    val deviceInfo = toolExecutionContext.trailblazeDeviceInfo
    if (deviceInfo.platform != TrailblazeDevicePlatform.IOS) {
      return TrailblazeToolResult.Error.ExceptionThrown(
        errorMessage = "ios_grantPrivacy is only supported on iOS simulators (got platform: " +
          "${deviceInfo.platform}). On Android, use android_grantPermissions.",
        command = this,
      )
    }
    return try {
      withContext(Dispatchers.IO) {
        services.forEach { service ->
          IosHostSimctlUtils.grantPrivacy(
            deviceId = deviceInfo.trailblazeDeviceId.instanceId,
            appId = appId,
            service = service,
          )
        }
      }
      TrailblazeToolResult.Success(
        message = if (services.isEmpty()) {
          "No privacy services to grant for '$appId' (empty list)."
        } else {
          "Granted ${services.joinToString(", ")} to '$appId'."
        },
      )
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      TrailblazeToolResult.Error.ExceptionThrown(
        errorMessage = "Failed to grant privacy services to '$appId': ${e.message}",
        command = this,
        stackTrace = e.stackTraceToString(),
      )
    }
  }
}
