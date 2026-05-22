package xyz.block.trailblaze.mobile.tools

import ai.koog.agents.core.tools.annotations.LLMDescription
import kotlinx.serialization.Serializable
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.toolcalls.ExecutableTrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolClass
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.util.IosHostSimctlUtils

/**
 * Clears all data for an app, resetting it to a fresh-install state.
 *
 * Supports both Android and iOS:
 * - **Android**: delegates to [xyz.block.trailblaze.device.AndroidDeviceCommandExecutor.clearAppData]
 *   (`pm clear` on-device or `adb shell pm clear` from host).
 * - **iOS**: uses `xcrun simctl` to locate and delete the app's data container.
 */
@Serializable
@TrailblazeToolClass(
  name = "mobile_clearAppData",
  surfaceToLlm = false,
)
@LLMDescription("Clears all data for the specified app, resetting it to a fresh state.")
data class ClearAppDataTrailblazeTool(
  @param:LLMDescription("The app id to clear (Android package id or iOS bundle id, e.g. 'com.android.deskclock').")
  val appId: String,
) : ExecutableTrailblazeTool {
  override suspend fun execute(toolExecutionContext: TrailblazeToolExecutionContext): TrailblazeToolResult {
    val deviceInfo = toolExecutionContext.trailblazeDeviceInfo
    return try {
      when (deviceInfo.platform) {
        TrailblazeDevicePlatform.ANDROID -> {
          val executor = toolExecutionContext.androidDeviceCommandExecutor
            ?: return TrailblazeToolResult.Error.ExceptionThrown(
              errorMessage = "AndroidDeviceCommandExecutor is not provided",
            )
          executor.clearAppData(appId)
        }

        TrailblazeDevicePlatform.IOS -> {
          IosHostSimctlUtils.clearAppDataContainer(
            deviceId = deviceInfo.trailblazeDeviceId.instanceId,
            appId = appId,
          )
        }

        TrailblazeDevicePlatform.WEB ->
          return TrailblazeToolResult.Error.ExceptionThrown(
            errorMessage = "mobile_clearAppData is not supported for web devices.",
          )

        TrailblazeDevicePlatform.DESKTOP ->
          return TrailblazeToolResult.Error.ExceptionThrown(
            errorMessage = "mobile_clearAppData is not supported for the Compose desktop driver.",
          )
      }
      TrailblazeToolResult.Success(
        message = "Cleared app data for '$appId'.",
      )
    } catch (e: Exception) {
      TrailblazeToolResult.Error.ExceptionThrown(
        errorMessage = "Failed to clear app data for '$appId': ${e.message}",
        command = this@ClearAppDataTrailblazeTool,
        stackTrace = e.stackTraceToString(),
      )
    }
  }
}
