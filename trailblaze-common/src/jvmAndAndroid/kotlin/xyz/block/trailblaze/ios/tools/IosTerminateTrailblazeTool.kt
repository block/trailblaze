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
 * `xcrun simctl terminate <udid> <appId>`: kills the app on an iOS simulator, leaving its data and
 * permissions as they are. An app that was not running is reported, not failed — it is already in
 * the state the caller asked for.
 *
 * A trailhead building block, not an agent tool — see [xyz.block.trailblaze.mobile.tools.AndroidForceStopTrailblazeTool]
 * for the Android counterpart and why these are composed rather than hidden behind a launch mode.
 */
@Serializable
@TrailblazeToolClass(
  name = "ios_terminate",
  surfaceToLlm = false,
)
@LLMDescription(
  "Terminates an iOS simulator app (`simctl terminate`), leaving its data and permissions as they " +
    "are. Follow with `openApp` for a cold start.",
)
data class IosTerminateTrailblazeTool(
  @param:LLMDescription("The iOS bundle id of the app to terminate (e.g. `com.example.app`).")
  val appId: String,
) : ExecutableTrailblazeTool {
  override suspend fun execute(
    toolExecutionContext: TrailblazeToolExecutionContext,
  ): TrailblazeToolResult {
    val deviceInfo = toolExecutionContext.trailblazeDeviceInfo
    if (deviceInfo.platform != TrailblazeDevicePlatform.IOS) {
      return TrailblazeToolResult.Error.ExceptionThrown(
        errorMessage = "ios_terminate is only supported on iOS simulators (got platform: " +
          "${deviceInfo.platform}). On Android, use android_forceStop.",
        command = this,
      )
    }
    return try {
      val wasRunning = withContext(Dispatchers.IO) {
        IosHostSimctlUtils.terminateApp(deviceId = deviceInfo.trailblazeDeviceId.instanceId, appId = appId)
      }
      TrailblazeToolResult.Success(
        message = if (wasRunning) "Terminated '$appId'." else "'$appId' was not running; nothing to terminate.",
      )
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      TrailblazeToolResult.Error.ExceptionThrown(
        errorMessage = "Failed to terminate '$appId': ${e.message}",
        command = this,
        stackTrace = e.stackTraceToString(),
      )
    }
  }
}
